package com.donutsell;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.Properties;

public class DonutSellClient implements ClientModInitializer {

    public static final String MOD_ID = "donutsell";
    public static final String VERSION = "2.0.1";

    // ---- Fixed timing (safe values) ---------------------------------------------------
    private static final long SELECT_WAIT_MS = 150L;       // after changing hotbar slot, before the command
    private static final long INV_OPEN_WAIT_MS = 500L;     // pauses around opening/closing the inventory
    private static final long REFILL_STEP_MS = 150L;       // between inventory moves
    private static final long REFILL_AFTER_MS = 500L;      // after closing the inventory
    private static final long PAGE_SETTLE_MS = 800L;       // let a new collect page load
    private static final long MENU_TIMEOUT_MS = 6000L;     // a menu must open within this
    private static final long FIRST_CHEST_EXTRA_DELAY_MS = 1000L; // extra wait once "my orders" opens (laggy step)
    private static final long FIRST_CHEST_TIMEOUT_MS = 12000L;    // that step may take longer
    private static final long COLLECT_RECHECK_MS = 500L;   // before re-sweeping a page for leftovers
    private static final int COLLECT_MAX_SWEEPS = 3;
    private static final int EMPTY_PAGES_LIMIT = 3;        // pages in a row with no items => order empty
    private static final int MAX_RECOVERIES = 3;           // automatic recoveries before giving up
    private static final int MAX_FIX_ATTEMPTS = 24;        // single-item repair attempts per collect
    private static final int ORDER_SLOT_RECHECKS = 3;      // times to re-check an "empty" order slot
    // ---- Menu layout (slot numbers are 0-indexed) -------------------------------------
    private static final int CHEST_ICON_SLOT = 51;         // /orders page: chest icon (my orders)
    private static final int EDIT_CHEST_SLOT = 13;         // Edit Order page: chest in the middle
    private static final int COLLECT_ITEM_SLOTS = 45;      // Collect Items: slots 0-44 hold items
    private static final int COLLECT_NEXT_SLOT = 53;       // Collect Items: next-page arrow
    // title that should be showing after N navigation clicks (empty = unknown, not checked)
    private static final String[] NAV_TITLES = {"Orders", "Your Orders", "Edit Order", "Collect"};
    // -------------------------------------------------------------------------------------

    private enum State {
        IDLE, SELECT, SEND, CHECK, AH_WAIT, REFILL_OPEN, REFILL_MOVE, REFILL_CLOSE,
        FLIP_COMMAND, FLIP_WAIT_MENU, FLIP_CLICK_NAV, FLIP_COLLECT, FLIP_CLOSE
    }

    // ---- Saved settings ----------------------------------------------------------------
    public static String price = "";          // price for a whole stack
    public static String singlePrice = "";    // price for a single item
    public static boolean singlesMode = false;
    public static int orderSlot = 1;          // 1-45, slot of the order on the "my orders" page
    public static int listingDelayMs = 500;   // between /ah sell commands
    public static int menuWaitMs = 600;       // wait after a menu opens
    public static int clickDelayMs = 300;     // between shift-clicks while collecting
    public static int splitDelayMs = 150;     // between clicks while splitting into singles
    public static int ahRetrySec = 30;        // retry interval when the AH is full
    public static int stuckSec = 60;          // no-progress timeout before auto-recovery

    private static KeyMapping openGuiKey;
    private static KeyMapping toggleKey;
    private static KeyMapping flipKey;
    private static boolean wasOpenDown = false;
    private static boolean wasToggleDown = false;
    private static boolean wasFlipDown = false;
    private static Screen current = null;

    private static State state = State.IDLE;
    private static long nextAt = 0L;
    private static long cachedHandle = 0L;
    private static long lastOpenAt = 0L;
    private static long lastToggleAt = 0L;
    private static long lastFlipAt = 0L;
    private static boolean announced = false;

    // selling state
    private static int hotbarIndex = 0;
    private static int attempts = 0;
    private static boolean ahWaiting = false;
    private static long retryAt = 0L;

    // watchdog / stats
    private static long lastProgressAt = 0L;
    private static int recoveries = 0;
    private static long startMs = 0L;
    private static int listings = 0;
    private static int cycles = 0;

    // order-flip state
    private static boolean flipMode = false;
    private static int navIndex = 0;
    private static int prevContainerId = 0;
    private static Screen prevScreen = null;
    private static long deadline = 0L;
    private static int orderEmptyChecks = 0;
    private static boolean pageClicked = false;
    private static int emptyStreak = 0;
    private static int collectCursor = 0;
    private static int collectSweeps = 0;

    // single-item splitting state
    private static int splitSource = -1;
    private static int lastTarget = -1;
    private static int targetRetry = 0;
    private static boolean placing = false;
    private static int fixAttempts = 0;
    private static final BitSet splitUsed = new BitSet();   // slots already given a single this cycle

    @Override
    public void onInitializeClient() {
        loadConfig();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutsell.open_gui", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutsell.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_J, category));
        flipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutsell.flip", InputConstants.Type.KEYSYM, InputConstants.KEY_L, category));

        // Keys pressed while a screen (chest, inventory, ...) is open.
        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> {
            if (screen instanceof ChatScreen) return; // never react while typing in chat
            ScreenKeyboardEvents.beforeKeyPress(screen).register((s, keyEvent) -> {
                if (openGuiKey.matches(keyEvent)) triggerOpen(client, "screen event");
                if (toggleKey.matches(keyEvent)) triggerToggle(client, "screen event");
                if (flipKey.matches(keyEvent)) triggerFlip(client, "screen event");
            });
        });

        // Remember which screen is open so the fallback key check can ignore chat.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            current = screen;
            ScreenEvents.remove(screen).register(s -> {
                if (current == s) current = null;
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(DonutSellClient::tick);
    }

    // ===================================================================================
    //  Keys
    // ===================================================================================

    private static long windowHandle(Minecraft mc) {
        if (cachedHandle != 0L) return cachedHandle;
        Object window = mc.getWindow();
        for (String name : new String[] {"handle", "getWindow"}) {
            try {
                Object r = window.getClass().getMethod(name).invoke(window);
                if (r instanceof Long l && l != 0L) {
                    cachedHandle = l;
                    return l;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return 0L;
    }

    /** Finds the key currently bound to a KeyMapping (works even if the accessor is renamed). */
    private static InputConstants.Key keyOf(KeyMapping mapping) {
        try {
            for (Field f : KeyMapping.class.getDeclaredFields()) {
                if (f.getName().equals("key") && f.getType() == InputConstants.Key.class) {
                    f.setAccessible(true);
                    return (InputConstants.Key) f.get(mapping);
                }
            }
            for (Method m : KeyMapping.class.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == InputConstants.Key.class
                        && !m.getName().toLowerCase().contains("default")) {
                    return (InputConstants.Key) m.invoke(mapping);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static boolean isDown(Minecraft mc, KeyMapping mapping, int defaultCode) {
        long handle = windowHandle(mc);
        if (handle == 0L) return false;
        int code = defaultCode;
        InputConstants.Key key = keyOf(mapping);
        if (key != null) {
            if (key.getType() != InputConstants.Type.KEYSYM) return false;
            code = key.getValue();
        }
        if (code <= 0) return false;
        return GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
    }

    private static void handleKeys(Minecraft mc) {
        // 1) Normal in-world key presses (the standard Fabric way).
        while (openGuiKey.consumeClick()) triggerOpen(mc, "world key");
        while (toggleKey.consumeClick()) triggerToggle(mc, "world key");
        while (flipKey.consumeClick()) triggerFlip(mc, "world key");

        // 2) Direct keyboard check as a backup (covers chests/inventory). Duplicates are filtered out.
        boolean open = isDown(mc, openGuiKey, InputConstants.KEY_K);
        boolean toggle = isDown(mc, toggleKey, InputConstants.KEY_J);
        boolean flip = isDown(mc, flipKey, InputConstants.KEY_L);
        boolean allowed = current == null
                || current instanceof AbstractContainerScreen<?>
                || current instanceof SellScreen;
        if (allowed && open && !wasOpenDown) triggerOpen(mc, "key poll");
        if (allowed && toggle && !wasToggleDown) triggerToggle(mc, "key poll");
        if (allowed && flip && !wasFlipDown) triggerFlip(mc, "key poll");
        wasOpenDown = open;
        wasToggleDown = toggle;
        wasFlipDown = flip;
    }

    private static void triggerOpen(Minecraft mc, String source) {
        long now = System.currentTimeMillis();
        if (now - lastOpenAt < 600L) return; // same key press seen by two detectors
        lastOpenAt = now;
        if (mc.player == null || current instanceof SellScreen) return;
        setScreen(mc, new SellScreen());
    }

    private static void triggerToggle(Minecraft mc, String source) {
        long now = System.currentTimeMillis();
        if (now - lastToggleAt < 600L) return;
        lastToggleAt = now;
        if (mc.player == null) return;
        if (isRunning()) stop(mc, true);
        else start(mc);
    }

    private static void triggerFlip(Minecraft mc, String source) {
        long now = System.currentTimeMillis();
        if (now - lastFlipAt < 600L) return;
        lastFlipAt = now;
        if (mc.player == null) return;
        if (isRunning()) stop(mc, true);
        else startFlip(mc);
    }

    // ===================================================================================
    //  Start / stop / failsafes
    // ===================================================================================

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    public static boolean start(Minecraft mc) {
        return begin(mc, false);
    }

    public static boolean startFlip(Minecraft mc) {
        return begin(mc, true);
    }

    private static boolean begin(Minecraft mc, boolean flip) {
        LocalPlayer player = mc.player;
        if (player == null) return false;
        if (!isValidPrice(price) || (singlesMode && !isValidPrice(singlePrice))) {
            player.sendOverlayMessage(Component.literal(singlesMode
                    ? "DonutSell: enter the Single price AND the Stack price (Singles + Stacks tabs)"
                    : "DonutSell: enter a Stack price first (Stacks tab)").withStyle(ChatFormatting.RED));
            if (!(current instanceof SellScreen)) setScreen(mc, new SellScreen());
            return false;
        }
        hotbarIndex = 0;
        attempts = 0;
        ahWaiting = false;
        recoveries = 0;
        listings = 0;
        cycles = 0;
        startMs = System.currentTimeMillis();
        resetCollect();
        flipMode = flip;
        nextAt = startMs;
        lastProgressAt = startMs;
        String what = singlesMode ? (singlePrice + " per single") : (price + " per stack");
        if (flip) {
            state = State.FLIP_COMMAND;
            player.sendOverlayMessage(Component.literal("DonutSell: order flip ON (" + what + ", slot " + orderSlot + ")")
                    .withStyle(ChatFormatting.GREEN));
            log("START order flip | " + what + " | order slot " + orderSlot);
        } else {
            state = State.SELECT;
            player.sendOverlayMessage(Component.literal("DonutSell: ON (" + what + ")").withStyle(ChatFormatting.GREEN));
            log("START selling | " + what);
        }
        return true;
    }

    private static void resetCollect() {
        pageClicked = false;
        emptyStreak = 0;
        collectCursor = 0;
        collectSweeps = 0;
        splitSource = -1;
        lastTarget = -1;
        targetRetry = 0;
        placing = false;
        fixAttempts = 0;
        splitUsed.clear();
        orderEmptyChecks = 0;
    }

    public static void stop(Minecraft mc, boolean announce) {
        boolean was = isRunning();
        endRun(mc, "stopped by you", announce);
        if (was && announce && mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal("DonutSell: OFF").withStyle(ChatFormatting.GOLD));
        }
    }

    private static void endRun(Minecraft mc, String reason, boolean chat) {
        boolean was = isRunning();
        state = State.IDLE;
        flipMode = false;
        ahWaiting = false;
        if (was) {
            log("STOP: " + reason + " | " + summary());
            if (chat && mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("DonutSell stopped (" + reason + "): " + summary())
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    private static void finish(Minecraft mc) {
        endRun(mc, "nothing left to sell", true);
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal("DonutSell: finished, nothing left to sell")
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /** Hard stop with a popup you must close. */
    private static void failStop(Minecraft mc, String line1, String line2) {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        endRun(mc, line1, true);
        setScreen(mc, new ListingFailedScreen(line1, line2));
    }

    private static void touch() {
        lastProgressAt = System.currentTimeMillis();
    }

    /** Real progress (a listing went through, an item was collected): also clears the recovery counter. */
    private static void success() {
        lastProgressAt = System.currentTimeMillis();
        recoveries = 0;
    }

    /** Something went wrong: close everything and restart the current loop, a few times, then give up. */
    private static void recover(Minecraft mc, String reason) {
        recoveries++;
        log("RECOVER " + recoveries + "/" + MAX_RECOVERIES + ": " + reason);
        if (recoveries > MAX_RECOVERIES) {
            failStop(mc, "Stopped after repeated problems.", reason);
            return;
        }
        long now = System.currentTimeMillis();
        LocalPlayer p = mc.player;
        if (p != null) {
            if (p.containerMenu != p.inventoryMenu) p.closeContainer();
            p.sendOverlayMessage(Component.literal("DonutSell: problem, retrying (" + recoveries + "/" + MAX_RECOVERIES + ")")
                    .withStyle(ChatFormatting.RED));
        }
        resetCollect();
        lastProgressAt = now;
        if (flipMode) {
            state = State.FLIP_COMMAND;
            nextAt = now + 1500L;
        } else {
            hotbarIndex = 0;
            attempts = 0;
            state = State.SELECT;
            nextAt = now + 1000L;
        }
    }

    private static String summary() {
        long s = Math.max(0L, (System.currentTimeMillis() - startMs) / 1000L);
        return listings + " listings, " + cycles + " cycles, " + (s / 3600) + "h " + ((s % 3600) / 60) + "m " + (s % 60) + "s";
    }

    private static boolean isValidPrice(String p) {
        if (p == null || p.isEmpty() || !p.matches("\\d+")) return false;
        try {
            return Long.parseLong(p) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String priceFor(int count) {
        return (singlesMode && count == 1) ? singlePrice : price;
    }

    // ===================================================================================
    //  Main loop
    // ===================================================================================

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            if (state != State.IDLE) log("STOP: left the world | " + summary());
            state = State.IDLE; // left the world / disconnected
            flipMode = false;
            ahWaiting = false;
            return;
        }

        if (!announced) {
            announced = true;
            player.sendOverlayMessage(Component.literal("DonutSell loaded").withStyle(ChatFormatting.GOLD));
        }

        handleKeys(mc);
        if (state == State.IDLE) return;

        if (player.getHealth() <= 0.0f) {
            endRun(mc, "you died", true);
            return;
        }

        long now = System.currentTimeMillis();

        // Watchdog: nothing useful happened for too long -> reset and retry.
        if (state != State.AH_WAIT && now - lastProgressAt > stuckSec * 1000L) {
            recover(mc, "no progress for " + stuckSec + "s (in " + state + ")");
            return;
        }

        if (now < nextAt) return;

        switch (state) {

            // ---------------- selling ----------------
            case SELECT -> {
                int idx = nextHotbarWithItem(player, hotbarIndex);
                if (idx >= 0) {
                    hotbarIndex = idx;
                    attempts = 0;
                    player.getInventory().setSelectedSlot(idx);
                    state = State.SEND;
                    nextAt = now + SELECT_WAIT_MS;
                } else if (firstMainWithItem(player) >= 0) {
                    state = State.REFILL_OPEN;
                    nextAt = now;
                } else if (flipMode) {
                    cycles++;
                    log("CYCLE " + cycles + " finished | " + summary());
                    state = State.FLIP_COMMAND;                 // everything sold: get more from the order
                    nextAt = now + INV_OPEN_WAIT_MS;
                } else {
                    finish(mc);
                }
            }
            case SEND -> {
                var stack = player.getInventory().getItem(hotbarIndex);
                if (stack.isEmpty()) {                           // already gone
                    hotbarIndex++;
                    state = State.SELECT;
                    nextAt = now;
                    return;
                }
                player.connection.sendCommand("ah sell " + priceFor(stack.getCount()));
                state = State.CHECK;
                nextAt = now + Math.max(0L, listingDelayMs - SELECT_WAIT_MS);
            }
            case CHECK -> {
                if (!hasItem(player, hotbarIndex)) {
                    listings++;
                    success();
                    if (ahWaiting) {
                        ahWaiting = false;
                        log("AH has room again - resuming");
                        player.sendSystemMessage(Component.literal("DonutSell: AH has room again - resuming.")
                                .withStyle(ChatFormatting.GREEN));
                    }
                    hotbarIndex++;
                    state = State.SELECT;
                    nextAt = now;
                } else if (attempts < 1) {
                    attempts++;                                  // one retry in case the answer was slow
                    state = State.SEND;
                    nextAt = now;
                } else {
                    // Still in hand after two tries: the AH is full. Keep waiting and retrying.
                    if (!ahWaiting) {
                        ahWaiting = true;
                        log("AH FULL - waiting, retry every " + ahRetrySec + "s");
                        player.sendSystemMessage(Component.literal("DonutSell: your AH listings look full. "
                                + "Waiting and retrying every " + ahRetrySec + "s until one sells.")
                                .withStyle(ChatFormatting.RED));
                    }
                    retryAt = now + ahRetrySec * 1000L;
                    state = State.AH_WAIT;
                    nextAt = now;
                }
            }
            case AH_WAIT -> {
                touch();                                         // waiting for the AH is intentional, never "stuck"
                if (now >= retryAt) {
                    attempts = 0;
                    state = State.SELECT;                        // same slot is still first with an item
                    nextAt = now;
                } else {
                    long left = (retryAt - now + 999L) / 1000L;
                    player.sendOverlayMessage(Component.literal("DonutSell: AH FULL - retrying in " + left + "s")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                    nextAt = now + 1000L;
                }
            }

            // ---------------- hotbar refill (inventory opens visibly) ----------------
            case REFILL_OPEN -> {
                setScreen(mc, new InventoryScreen(player));
                state = State.REFILL_MOVE;
                nextAt = now + INV_OPEN_WAIT_MS;
            }
            case REFILL_MOVE -> {
                int hotbar = firstEmptyHotbar(player);
                int source = firstMainWithItem(player);
                if (hotbar >= 0 && source >= 0) {
                    // Swap an inventory slot (9-35) with hotbar slot 'hotbar' (button = 0-8).
                    mc.gameMode.handleContainerInput(
                            player.inventoryMenu.containerId, source, hotbar, ContainerInput.SWAP, player);
                    nextAt = now + REFILL_STEP_MS;
                } else {
                    state = State.REFILL_CLOSE;
                    nextAt = now + INV_OPEN_WAIT_MS;
                }
            }
            case REFILL_CLOSE -> {
                player.closeContainer();
                hotbarIndex = 0;
                state = State.SELECT;
                nextAt = now + REFILL_AFTER_MS;
            }

            // ---------------- order flipping ----------------
            case FLIP_COMMAND -> {
                prevContainerId = player.containerMenu.containerId;
                prevScreen = current;
                navIndex = 0;
                orderEmptyChecks = 0;
                player.connection.sendCommand("orders");
                state = State.FLIP_WAIT_MENU;
                deadline = now + MENU_TIMEOUT_MS;
            }
            case FLIP_WAIT_MENU -> {
                if (menuOpenedSince(player)) {
                    touch();
                    String title = (current != prevScreen) ? screenTitle().toLowerCase() : "";
                    String expect = NAV_TITLES[Math.min(navIndex, NAV_TITLES.length - 1)].toLowerCase();
                    if (!expect.isEmpty() && !title.isEmpty() && !title.contains(expect)) {
                        recover(mc, "unexpected menu '" + screenTitle() + "' (wanted '" + expect + "')");
                        return;
                    }
                    if (navIndex >= NAV_TITLES.length - 1) {     // arrived at Collect Items
                        resetCollect();
                        state = State.FLIP_COLLECT;
                    } else {
                        state = State.FLIP_CLICK_NAV;
                    }
                    nextAt = now + menuWaitMs;
                    if (navIndex == 1) nextAt += FIRST_CHEST_EXTRA_DELAY_MS; // "my orders" page just opened
                } else if (now > deadline) {
                    recover(mc, "menu did not open (step " + navIndex + ")");
                }
            }
            case FLIP_CLICK_NAV -> {
                var menu = player.containerMenu;
                if (menu == player.inventoryMenu) {
                    recover(mc, "menu closed before the next click");
                    return;
                }
                int slot;
                if (navIndex == 0) {
                    slot = CHEST_ICON_SLOT;
                } else if (navIndex == 1) {
                    slot = orderSlot - 1;
                    if (slot >= menu.slots.size() || isPlaceholder(menu.getSlot(slot).getItem())) {
                        orderEmptyChecks++;                       // may just still be loading: look again
                        if (orderEmptyChecks <= ORDER_SLOT_RECHECKS) {
                            nextAt = now + 1000L;
                            return;
                        }
                        failStop(mc, "Order slot " + orderSlot + " is empty.",
                                "Pick a different slot on the Flip tab, then start again.");
                        return;
                    }
                    orderEmptyChecks = 0;
                } else {
                    slot = EDIT_CHEST_SLOT;
                }
                prevContainerId = menu.containerId;
                prevScreen = current;
                mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, player);
                navIndex++;
                state = State.FLIP_WAIT_MENU;
                deadline = now + (navIndex == 1 ? FIRST_CHEST_TIMEOUT_MS : MENU_TIMEOUT_MS);
                touch();
            }
            case FLIP_COLLECT -> {
                var menu = player.containerMenu;
                if (menu == player.inventoryMenu || menu.slots.size() < 54) {
                    recover(mc, "collect menu closed unexpectedly");
                    return;
                }
                if (singlesMode) collectSingles(mc, player, menu, now);
                else collectStacks(mc, player, menu, now);
            }
            case FLIP_CLOSE -> {
                player.closeContainer();
                hotbarIndex = 0;
                attempts = 0;
                touch();
                state = State.SELECT;                             // hand over to the selling loop
                nextAt = now + menuWaitMs;
            }
            default -> { }
        }
    }

    // ---- collecting ---------------------------------------------------------------------

    /** Stacks mode: shift-click every filled slot once, left to right. */
    private static void collectStacks(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, long now) {
        if (firstEmptyInventorySlot(player) < 0) {
            state = State.FLIP_CLOSE;                           // inventory full: go sell
            nextAt = now + 300L;
            return;
        }
        int slot = nextMenuItem(menu, collectCursor);
        if (slot >= 0) {
            mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
            pageClicked = true;
            collectCursor = slot + 1;
            nextAt = now + clickDelayMs;
            success();
        } else {
            endOfPage(mc, player, menu, now);
        }
    }

    /**
     * Singles mode, built for reliability: pick a stack up, drop ONE item per free slot (never the same
     * slot twice), confirm each drop, then repair any slot that still ended up with 2+ items.
     */
    private static void collectSingles(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, long now) {
        var held = menu.getCarried();

        // 0) Did the item we just placed really land?
        if (lastTarget >= 0) {
            int t = lastTarget;
            lastTarget = -1;
            var placed = menu.getSlot(t).getItem();
            if (placed.isEmpty() && !held.isEmpty() && targetRetry < 1) {
                targetRetry++;                                    // looks like it did not land: try that slot again
                lastTarget = t;
                mc.gameMode.handleContainerInput(menu.containerId, t, 1, ContainerInput.PICKUP, player);
                nextAt = now + splitDelayMs;
                return;
            }
            targetRetry = 0;
            if (!placed.isEmpty()) success();
        }

        // 1) Cursor holds a stack: drop one item into the next free slot.
        if (!held.isEmpty()) {
            placing = true;
            int target = nextSplitTarget(menu);
            if (target >= 0) {
                splitUsed.set(target);
                lastTarget = target;
                mc.gameMode.handleContainerInput(menu.containerId, target, 1, ContainerInput.PICKUP, player);
                nextAt = now + splitDelayMs;
                touch();
            } else {
                // No room left: put the rest back where it came from and go sell.
                if (splitSource >= 0) {
                    mc.gameMode.handleContainerInput(menu.containerId, splitSource, 0, ContainerInput.PICKUP, player);
                }
                placing = false;
                state = State.FLIP_CLOSE;
                nextAt = now + menuWaitMs;
            }
            return;
        }

        // The stack was just finished: give the server a moment to settle before checking the result.
        if (placing) {
            placing = false;
            nextAt = now + splitDelayMs * 2L;
            return;
        }

        // 2) Cursor empty: repair any slot that ended up with 2+ items.
        if (fixAttempts < MAX_FIX_ATTEMPTS) {
            int bad = firstMultiUsedSlot(menu);
            if (bad >= 0 && nextSplitTarget(menu) >= 0) {
                fixAttempts++;
                log("FIX: slot " + bad + " held " + menu.getSlot(bad).getItem().getCount() + " items, re-splitting");
                splitUsed.clear(bad);
                splitSource = bad;
                mc.gameMode.handleContainerInput(menu.containerId, bad, 0, ContainerInput.PICKUP, player);
                nextAt = now + splitDelayMs;
                return;
            }
        }

        // 3) Normal progress: pick the next stack from the page.
        if (nextSplitTarget(menu) < 0) {
            state = State.FLIP_CLOSE;                             // inventory full: go sell
            nextAt = now + menuWaitMs;
            return;
        }
        int slot = nextMenuItem(menu, collectCursor);
        if (slot >= 0) {
            splitSource = slot;
            collectCursor = slot + 1;
            pageClicked = true;
            mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, player);
            nextAt = now + splitDelayMs;
            touch();
        } else {
            endOfPage(mc, player, menu, now);
        }
    }

    /** The cursor has passed the last item on this page. */
    private static void endOfPage(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, long now) {
        if (firstMenuItem(menu) >= 0) {
            // Something is still there.
            if (collectSweeps < COLLECT_MAX_SWEEPS) {
                collectSweeps++;                                // server may just be slow: sweep again
                collectCursor = 0;
                nextAt = now + COLLECT_RECHECK_MS;
            } else {
                state = State.FLIP_CLOSE;                       // items won't move: inventory is full, go sell
                nextAt = now + 300L;
            }
            return;
        }
        if (pageClicked) emptyStreak = 0; else emptyStreak++;
        if (emptyStreak >= EMPTY_PAGES_LIMIT) {
            failStop(mc, "This order looks empty (" + EMPTY_PAGES_LIMIT + " pages in a row had no items).",
                    "Anything already collected is still in your inventory - press J to sell it.");
            return;
        }
        mc.gameMode.handleContainerInput(menu.containerId, COLLECT_NEXT_SLOT, 0, ContainerInput.PICKUP, player);
        pageClicked = false;
        collectCursor = 0;
        collectSweeps = 0;
        nextAt = now + PAGE_SETTLE_MS;
        touch();
    }

    // ---- helpers -----------------------------------------------------------------------

    private static boolean menuOpenedSince(LocalPlayer p) {
        if (p.containerMenu == p.inventoryMenu) return false;
        if (p.containerMenu.containerId != prevContainerId) return true;
        return current != prevScreen && current instanceof AbstractContainerScreen<?>;
    }

    private static String screenTitle() {
        if (current instanceof AbstractContainerScreen<?> cs) {
            return cs.getTitle().getString();
        }
        return "";
    }

    /** Empty or locked order slots are drawn as gray/red glass panes, so a pane counts as "no order here". */
    private static boolean isPlaceholder(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return true;
        try {
            String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            return path.contains("glass_pane");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hasItem(LocalPlayer p, int slot) {
        return !p.getInventory().getItem(slot).isEmpty();
    }

    private static int nextHotbarWithItem(LocalPlayer p, int from) {
        for (int i = Math.max(0, from); i < 9; i++) if (hasItem(p, i)) return i;
        return -1;
    }

    private static int firstEmptyHotbar(LocalPlayer p) {
        for (int i = 0; i < 9; i++) if (!hasItem(p, i)) return i;
        return -1;
    }

    private static int firstMainWithItem(LocalPlayer p) {
        for (int i = 9; i < 36; i++) if (hasItem(p, i)) return i;
        return -1;
    }

    private static int firstEmptyInventorySlot(LocalPlayer p) {
        for (int i = 0; i < 36; i++) if (!hasItem(p, i)) return i;
        return -1;
    }

    private static int firstMenuItem(AbstractContainerMenu menu) {
        return nextMenuItem(menu, 0);
    }

    private static int nextMenuItem(AbstractContainerMenu menu, int from) {
        for (int i = Math.max(0, from); i < COLLECT_ITEM_SLOTS; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    /** Next empty, not-yet-used slot in the player-inventory part of an open chest menu (the last 36 slots). */
    private static int nextSplitTarget(AbstractContainerMenu menu) {
        for (int i = menu.slots.size() - 36; i < menu.slots.size(); i++) {
            if (!splitUsed.get(i) && menu.getSlot(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    /** A slot we gave a single to that now holds 2 or more items. */
    private static int firstMultiUsedSlot(AbstractContainerMenu menu) {
        for (int i = menu.slots.size() - 36; i < menu.slots.size(); i++) {
            if (splitUsed.get(i) && menu.getSlot(i).getItem().getCount() > 1) return i;
        }
        return -1;
    }

    // ===================================================================================
    //  Screens (setScreen moved around in 26.x, so find it at runtime)
    // ===================================================================================

    public static void closeScreen() {
        setScreen(Minecraft.getInstance(), null);
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        Object gui = null;
        try {
            gui = Minecraft.class.getField("gui").get(mc);
        } catch (ReflectiveOperationException e1) {
            try {
                Field f = Minecraft.class.getDeclaredField("gui");
                f.setAccessible(true);
                gui = f.get(mc);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        if (gui != null && invokeSetScreen(gui, screen)) return;
        if (invokeSetScreen(mc, screen)) return;
        log("ERROR: could not find a setScreen method");
        if (screen != null && mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal("DonutSell: could not open screen (see log)"));
        }
    }

    private static boolean invokeSetScreen(Object target, Screen screen) {
        Method[][] groups = { target.getClass().getMethods(), target.getClass().getDeclaredMethods() };
        for (Method[] group : groups) {
            for (Method m : group) {
                if (m.getName().equals("setScreen") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(Screen.class)) {
                    try {
                        m.setAccessible(true);
                        m.invoke(target, screen);
                        return true;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        log("setScreen failed: " + e + " / " + e.getCause());
                    }
                }
            }
        }
        return false;
    }

    // ===================================================================================
    //  Log file + saved settings
    // ===================================================================================

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutsell.properties");
    }

    /** Events are written to config/donutsell.log (and the game log) so problems can be diagnosed later. */
    private static void log(String msg) {
        System.out.println("[DonutSell] " + msg);
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("donutsell.log");
            if (Files.exists(p) && Files.size(p) > 1_000_000L) Files.delete(p);
            Files.writeString(p, java.time.LocalTime.now().withNano(0) + "  " + msg + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    public static void setPrice(String value) {
        price = value;
        saveConfig();
    }

    public static void setSinglePrice(String value) {
        singlePrice = value;
        saveConfig();
    }

    public static void setSinglesMode(boolean value) {
        singlesMode = value;
        saveConfig();
    }

    private static int clamp(int v, int min, int max, int def) {
        if (v < 0) return def;
        return Math.max(min, Math.min(max, v));
    }

    /** Numeric settings from the GUI. Empty box = default, values are kept inside safe limits. */
    public static void setSetting(String name, String digits) {
        int v;
        try {
            v = digits.isEmpty() ? -1 : (int) Math.min(Long.parseLong(digits), 1_000_000L);
        } catch (NumberFormatException e) {
            v = -1;
        }
        switch (name) {
            case "orderSlot" -> orderSlot = clamp(v, 1, 45, 1);
            case "listingDelay" -> listingDelayMs = clamp(v, 250, 10000, 500);
            case "menuWait" -> menuWaitMs = clamp(v, 200, 10000, 600);
            case "clickDelay" -> clickDelayMs = clamp(v, 100, 5000, 300);
            case "splitDelay" -> splitDelayMs = clamp(v, 50, 5000, 150);
            case "ahRetry" -> ahRetrySec = clamp(v, 5, 3600, 30);
            case "stuck" -> stuckSec = clamp(v, 20, 3600, 60);
            default -> { }
        }
        saveConfig();
    }

    private static int intProp(Properties props, String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void loadConfig() {
        Path p = configPath();
        if (!Files.exists(p)) return;
        try (InputStream in = Files.newInputStream(p)) {
            Properties props = new Properties();
            props.load(in);
            price = props.getProperty("price", "");
            singlePrice = props.getProperty("singlePrice", "");
            singlesMode = Boolean.parseBoolean(props.getProperty("singlesMode", "false"));
            orderSlot = Math.max(1, Math.min(45, intProp(props, "orderSlot", 1)));
            listingDelayMs = Math.max(250, intProp(props, "listingDelay", 500));
            menuWaitMs = Math.max(200, intProp(props, "menuWait", 600));
            clickDelayMs = Math.max(100, intProp(props, "clickDelay", 300));
            splitDelayMs = Math.max(50, intProp(props, "splitDelay", 150));
            ahRetrySec = Math.max(5, intProp(props, "ahRetry", 30));
            stuckSec = Math.max(20, intProp(props, "stuck", 60));
        } catch (IOException ignored) {
        }
    }

    private static void saveConfig() {
        try (OutputStream out = Files.newOutputStream(configPath())) {
            Properties props = new Properties();
            props.setProperty("price", price);
            props.setProperty("singlePrice", singlePrice);
            props.setProperty("singlesMode", String.valueOf(singlesMode));
            props.setProperty("orderSlot", String.valueOf(orderSlot));
            props.setProperty("listingDelay", String.valueOf(listingDelayMs));
            props.setProperty("menuWait", String.valueOf(menuWaitMs));
            props.setProperty("clickDelay", String.valueOf(clickDelayMs));
            props.setProperty("splitDelay", String.valueOf(splitDelayMs));
            props.setProperty("ahRetry", String.valueOf(ahRetrySec));
            props.setProperty("stuck", String.valueOf(stuckSec));
            props.store(out, "DonutSell");
        } catch (IOException ignored) {
        }
    }
}
