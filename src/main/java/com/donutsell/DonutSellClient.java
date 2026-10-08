package com.donutsell;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
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
import java.util.Properties;

public class DonutSellClient implements ClientModInitializer {

    public static final String MOD_ID = "donutsell";

    // ---- Selling timing ----------------------------------------------------------------
    public static final int MIN_LIST_DELAY_MS = 250;      // server limit between commands
    private static final long SELECT_WAIT_MS = 100L;      // only when a slot must change right before a command
    private static final long PRESELECT_AFTER_MS = 100L;  // switch to the next hotbar slot this long after a command
    private static final long INV_OPEN_WAIT_MS = 200L;    // pause after the inventory opens / before it closes
    private static final long REFILL_STEP_MS = 50L;       // time between inventory moves (you can still watch)
    private static final long REFILL_AFTER_MS = 150L;     // pause after closing the inventory
    // ---- Order flipping (slot numbers are 0-indexed) -----------------------------------
    // /orders -> chest icon (slot 51) -> first order (slot 0) -> center chest (slot 13) -> Collect Items
    private static final int[] NAV_SLOTS = {51, 0, 13};
    private static final int COLLECT_ITEM_SLOTS = 45;      // slots 0-44 hold the items
    private static final int COLLECT_NEXT_SLOT = 53;       // next-page arrow
    private static final int EMPTY_PAGES_LIMIT = 3;        // pages in a row with no items => order empty
    private static final long COLLECT_DELAY_MS = 100L;     // time between shift-clicks
    private static final long SPLIT_DELAY_MS = 50L;        // time between clicks while splitting into singles
    private static final long COLLECT_RECHECK_MS = 400L;   // pause before re-sweeping a page for leftovers
    private static final int COLLECT_MAX_SWEEPS = 3;       // re-sweeps before treating the inventory as full
    private static final long MENU_SETTLE_MS = 300L;       // let a newly opened menu fill with items
    private static final long PAGE_SETTLE_MS = 500L;       // let a new page load
    private static final long MENU_TIMEOUT_MS = 5000L;     // give up if a menu never opens
    private static final long FIRST_CHEST_EXTRA_DELAY_MS = 1000L; // extra wait once "my orders" opens (laggy step)
    private static final long FIRST_CHEST_TIMEOUT_MS = 10000L;    // that step is allowed to take longer
    // -------------------------------------------------------------------------------------

    private enum State {
        IDLE, PICK, SEND, PRESELECT, REFILL_OPEN, REFILL_MOVE, REFILL_CLOSE,
        FLIP_COMMAND, FLIP_WAIT_MENU, FLIP_CLICK_NAV, FLIP_COLLECT, FLIP_CLOSE
    }

    // saved settings
    public static String price = "";          // price for a whole stack
    public static String singlePrice = "";    // price for a single item (Singles mode)
    public static boolean singlesMode = false;
    public static String delayText = String.valueOf(MIN_LIST_DELAY_MS);
    private static int listingDelayMs = MIN_LIST_DELAY_MS;

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
    private static int sellIndex = 0;
    private static int nextFrom = 0;
    private static int pendingSlot = -1;
    private static int pendingChecks = 0;
    private static long lastSendAt = 0L;

    // order-flip state
    private static boolean flipMode = false;
    private static int navIndex = 0;
    private static int prevContainerId = 0;
    private static Screen prevScreen = null;
    private static long deadline = 0L;
    private static boolean pageClicked = false;
    private static int emptyStreak = 0;
    private static int collectCursor = 0;
    private static int collectSweeps = 0;
    private static int splitSource = -1;
    private static final java.util.BitSet splitUsed = new java.util.BitSet(); // slots already given a single this cycle

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
        System.out.println("[DonutSell] open-GUI key (" + source + ")");
        setScreen(mc, new SellScreen());
    }

    private static void triggerToggle(Minecraft mc, String source) {
        long now = System.currentTimeMillis();
        if (now - lastToggleAt < 600L) return;
        lastToggleAt = now;
        if (mc.player == null) return;
        System.out.println("[DonutSell] start/stop key (" + source + ")");
        if (isRunning()) stop(mc, true);
        else start(mc);
    }

    private static void triggerFlip(Minecraft mc, String source) {
        long now = System.currentTimeMillis();
        if (now - lastFlipAt < 600L) return;
        lastFlipAt = now;
        if (mc.player == null) return;
        System.out.println("[DonutSell] order-flip key (" + source + ")");
        if (isRunning()) stop(mc, true);
        else startFlip(mc);
    }

    // ===================================================================================
    //  Start / stop
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
                    ? "DonutSell: enter both the stack price and the single price"
                    : "DonutSell: enter a price first"));
            if (!(current instanceof SellScreen)) setScreen(mc, new SellScreen());
            return false;
        }
        sellIndex = 0;
        nextFrom = 0;
        pendingSlot = -1;
        pendingChecks = 0;
        splitUsed.clear();
        flipMode = flip;
        nextAt = System.currentTimeMillis();
        String what = singlesMode ? (singlePrice + " per single") : (price + " per stack");
        if (flip) {
            state = State.FLIP_COMMAND;
            player.sendOverlayMessage(Component.literal("DonutSell: order flip ON (" + what + ")"));
        } else {
            state = State.PICK;
            player.sendOverlayMessage(Component.literal("DonutSell: ON (" + what + ")"));
        }
        return true;
    }

    public static void stop(Minecraft mc, boolean announce) {
        boolean wasRunning = isRunning();
        state = State.IDLE;
        flipMode = false;
        if (wasRunning && announce && mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal("DonutSell: OFF"));
        }
    }

    private static void finish(Minecraft mc) {
        state = State.IDLE;
        flipMode = false;
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal("DonutSell: finished, nothing left to sell"));
        }
    }

    private static void failed(Minecraft mc) {
        state = State.IDLE;
        flipMode = false;
        setScreen(mc, new ListingFailedScreen());
    }

    private static void flipStop(Minecraft mc, String line1, String line2) {
        state = State.IDLE;
        flipMode = false;
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        setScreen(mc, new ListingFailedScreen(line1, line2));
    }

    private static boolean isValidPrice(String p) {
        if (p == null || p.isEmpty() || !p.matches("\\d+")) return false;
        try {
            return Long.parseLong(p) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long listingDelay() {
        return Math.max(MIN_LIST_DELAY_MS, listingDelayMs);
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
            state = State.IDLE; // left the world
            return;
        }

        if (!announced) {
            announced = true;
            player.sendOverlayMessage(Component.literal("DonutSell loaded"));
        }

        handleKeys(mc);
        if (state == State.IDLE) return;

        long now = System.currentTimeMillis();
        if (now < nextAt) return;

        switch (state) {

            // ---------------- selling ----------------
            case PICK -> {
                // 1) Did the previous listing really leave the hotbar?
                if (pendingSlot >= 0) {
                    if (hasItem(player, pendingSlot)) {
                        pendingChecks++;
                        if (pendingChecks == 1 || pendingChecks == 3) {   // give the server a moment
                            nextAt = now + listingDelay();
                            return;
                        }
                        if (pendingChecks == 2) {                         // try that slot once more
                            sellIndex = pendingSlot;
                            player.getInventory().setSelectedSlot(sellIndex);
                            state = State.SEND;
                            nextAt = now + SELECT_WAIT_MS;
                            return;
                        }
                        failed(mc);                                       // still there: listings full
                        return;
                    }
                    pendingSlot = -1;
                    pendingChecks = 0;
                }
                // 2) Pick the next hotbar slot with an item.
                int idx = nextHotbarWithItem(player, nextFrom);
                if (idx >= 0) {
                    nextFrom = idx + 1;
                    sellIndex = idx;
                    if (player.getInventory().getSelectedSlot() != idx) {
                        player.getInventory().setSelectedSlot(idx);
                        nextAt = now + SELECT_WAIT_MS;
                    }
                    state = State.SEND;
                } else if (firstMainWithItem(player) >= 0) {
                    state = State.REFILL_OPEN;
                    nextAt = now;
                } else if (flipMode) {
                    state = State.FLIP_COMMAND;                // everything sold: get more from the order
                    nextAt = now + INV_OPEN_WAIT_MS;
                } else {
                    finish(mc);
                }
            }
            case SEND -> {
                var stack = player.getInventory().getItem(sellIndex);
                if (stack.isEmpty()) {                          // already gone (e.g. slow confirmation)
                    pendingSlot = -1;
                    pendingChecks = 0;
                    state = State.PICK;
                    nextAt = now;
                    return;
                }
                player.connection.sendCommand("ah sell " + priceFor(stack.getCount()));
                pendingSlot = sellIndex;
                lastSendAt = now;
                state = State.PRESELECT;
                nextAt = now + PRESELECT_AFTER_MS;
            }
            case PRESELECT -> {
                // Switch to the next slot a little after the command so the next one can follow right on time.
                int nxt = nextHotbarWithItem(player, nextFrom);
                if (nxt >= 0) player.getInventory().setSelectedSlot(nxt);
                state = State.PICK;
                nextAt = Math.max(now, lastSendAt + listingDelay());
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
                nextFrom = 0;
                pendingSlot = -1;
                pendingChecks = 0;
                state = State.PICK;
                nextAt = now + REFILL_AFTER_MS;
            }

            // ---------------- order flipping ----------------
            case FLIP_COMMAND -> {
                prevContainerId = player.containerMenu.containerId;
                prevScreen = current;
                navIndex = 0;
                player.connection.sendCommand("orders");
                state = State.FLIP_WAIT_MENU;
                deadline = now + MENU_TIMEOUT_MS;
            }
            case FLIP_WAIT_MENU -> {
                if (menuOpenedSince(player)) {
                    if (navIndex >= NAV_SLOTS.length) {
                        String title = screenTitle();
                        if (!title.isEmpty() && !title.contains("Collect")) {
                            flipStop(mc, "Expected the Collect Items menu but got:", title);
                            return;
                        }
                        pageClicked = false;
                        emptyStreak = 0;
                        collectCursor = 0;
                        collectSweeps = 0;
                        splitSource = -1;
                        splitUsed.clear();
                        state = State.FLIP_COLLECT;
                    } else {
                        state = State.FLIP_CLICK_NAV;
                    }
                    nextAt = now + MENU_SETTLE_MS;
                    if (navIndex == 1) nextAt += FIRST_CHEST_EXTRA_DELAY_MS; // "my orders" page just opened
                } else if (now > deadline) {
                    flipStop(mc, "A menu did not open (step " + navIndex + " of " + NAV_SLOTS.length + ").",
                            "Open title: " + screenTitle());
                }
            }
            case FLIP_CLICK_NAV -> {
                prevContainerId = player.containerMenu.containerId;
                prevScreen = current;
                mc.gameMode.handleContainerInput(player.containerMenu.containerId,
                        NAV_SLOTS[navIndex], 0, ContainerInput.PICKUP, player);
                navIndex++;
                state = State.FLIP_WAIT_MENU;
                deadline = now + (navIndex == 1 ? FIRST_CHEST_TIMEOUT_MS : MENU_TIMEOUT_MS);
            }
            case FLIP_COLLECT -> {
                var menu = player.containerMenu;
                if (menu == player.inventoryMenu || menu.slots.size() < 54) {
                    flipStop(mc, "The Collect Items menu closed unexpectedly.", "Order flip stopped.");
                    return;
                }
                if (singlesMode) collectSingles(mc, player, menu, now);
                else collectStacks(mc, player, menu, now);
            }
            case FLIP_CLOSE -> {
                player.closeContainer();
                nextFrom = 0;
                pendingSlot = -1;
                pendingChecks = 0;
                state = State.PICK;                          // hand over to the selling loop
                nextAt = now + MENU_SETTLE_MS;
            }
            default -> { }
        }
    }

    // ---- collecting ---------------------------------------------------------------------

    /** Stacks mode: shift-click every filled slot once, left to right. */
    private static void collectStacks(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, long now) {
        if (firstEmptyInventorySlot(player) < 0) {
            state = State.FLIP_CLOSE;                       // inventory full: go sell
            nextAt = now + 200L;
            return;
        }
        int slot = nextMenuItem(menu, collectCursor);
        if (slot >= 0) {
            mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
            pageClicked = true;
            collectCursor = slot + 1;
            nextAt = now + COLLECT_DELAY_MS;
        } else {
            endOfPage(mc, player, menu, now);
        }
    }

    /** Singles mode: pick a stack up, then right-click empty inventory slots to drop one item in each. */
    private static void collectSingles(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, long now) {
        var held = menu.getCarried();
        if (!held.isEmpty()) {
            int target = nextSplitTarget(menu);
            if (target >= 0) {
                splitUsed.set(target);   // never give the same slot a second item, even if the server is slow to confirm
                mc.gameMode.handleContainerInput(menu.containerId, target, 1, ContainerInput.PICKUP, player);
                nextAt = now + SPLIT_DELAY_MS;
            } else {
                // No room left for the rest: put it back where it came from and go sell.
                if (splitSource >= 0) {
                    mc.gameMode.handleContainerInput(menu.containerId, splitSource, 0, ContainerInput.PICKUP, player);
                }
                state = State.FLIP_CLOSE;
                nextAt = now + 200L;
            }
            return;
        }
        if (nextSplitTarget(menu) < 0) {
            state = State.FLIP_CLOSE;
            nextAt = now + 200L;
            return;
        }
        int slot = nextMenuItem(menu, collectCursor);
        if (slot >= 0) {
            splitSource = slot;
            collectCursor = slot + 1;
            pageClicked = true;
            mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, player);
            nextAt = now + SPLIT_DELAY_MS;
        } else {
            endOfPage(mc, player, menu, now);
        }
    }

    /** The cursor has passed the last item on this page. */
    private static void endOfPage(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, long now) {
        if (firstMenuItem(menu) >= 0) {
            // Something is still there.
            if (collectSweeps < COLLECT_MAX_SWEEPS) {
                collectSweeps++;                            // server may just be slow: sweep again
                collectCursor = 0;
                nextAt = now + COLLECT_RECHECK_MS;
            } else {
                state = State.FLIP_CLOSE;                   // items won't move: inventory is full, go sell
                nextAt = now + 200L;
            }
            return;
        }
        if (pageClicked) emptyStreak = 0; else emptyStreak++;
        if (emptyStreak >= EMPTY_PAGES_LIMIT) {
            flipStop(mc, "This order looks empty (" + EMPTY_PAGES_LIMIT + " pages in a row had no items).",
                    "Anything already collected is still in your inventory - press J to sell it.");
            return;
        }
        mc.gameMode.handleContainerInput(menu.containerId, COLLECT_NEXT_SLOT, 0, ContainerInput.PICKUP, player);
        pageClicked = false;
        collectCursor = 0;
        collectSweeps = 0;
        nextAt = now + PAGE_SETTLE_MS;
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
        System.out.println("[DonutSell] ERROR: could not find a setScreen method");
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
                        System.out.println("[DonutSell] setScreen failed: " + e + " / " + e.getCause());
                    }
                }
            }
        }
        return false;
    }

    // ===================================================================================
    //  Saved settings
    // ===================================================================================

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutsell.properties");
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

    public static void setDelay(String digits) {
        delayText = digits;
        try {
            listingDelayMs = digits.isEmpty() ? MIN_LIST_DELAY_MS : (int) Math.min(Long.parseLong(digits), 60000L);
        } catch (NumberFormatException e) {
            listingDelayMs = MIN_LIST_DELAY_MS;
        }
        saveConfig();
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
            delayText = props.getProperty("delay", String.valueOf(MIN_LIST_DELAY_MS));
            try {
                listingDelayMs = Integer.parseInt(delayText);
            } catch (NumberFormatException e) {
                listingDelayMs = MIN_LIST_DELAY_MS;
            }
        } catch (IOException ignored) {
        }
    }

    private static void saveConfig() {
        try (OutputStream out = Files.newOutputStream(configPath())) {
            Properties props = new Properties();
            props.setProperty("price", price);
            props.setProperty("singlePrice", singlePrice);
            props.setProperty("singlesMode", String.valueOf(singlesMode));
            props.setProperty("delay", delayText);
            props.store(out, "DonutSell");
        } catch (IOException ignored) {
        }
    }
}
