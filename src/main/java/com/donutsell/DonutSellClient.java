package com.donutsell;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
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
import net.minecraft.world.inventory.ContainerInput;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class DonutSellClient implements ClientModInitializer {

    public static final String MOD_ID = "donutsell";

    // ---- Timing ------------------------------------------------------------------------
    private static final long LIST_DELAY_MS = 500L;      // time between two /ah sell commands
    private static final long SELECT_WAIT_MS = 150L;     // let the hotbar-slot change reach the server first
    private static final long CHECK_WAIT_MS = LIST_DELAY_MS - SELECT_WAIT_MS;
    private static final long INV_OPEN_WAIT_MS = 500L;   // pause after the inventory opens
    private static final long REFILL_STEP_MS = 150L;     // time between inventory moves (so you can watch)
    // -------------------------------------------------------------------------------------

    private enum State { IDLE, SELECT, SEND, CHECK, REFILL_OPEN, REFILL_MOVE, REFILL_CLOSE }

    public static String price = "";

    private static KeyMapping openGuiKey;
    private static KeyMapping toggleKey;
    private static boolean wasOpenDown = false;
    private static boolean wasToggleDown = false;
    private static Screen current = null;

    private static State state = State.IDLE;
    private static long nextAt = 0L;
    private static int hotbarIndex = 0;
    private static int attempts = 0;
    private static long cachedHandle = 0L;

    @Override
    public void onInitializeClient() {
        loadConfig();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutsell.open_gui", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutsell.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_J, category));

        // Remember which screen is open so keys can be ignored while typing in chat.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            current = screen;
            ScreenEvents.remove(screen).register(s -> {
                if (current == s) current = null;
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(DonutSellClient::tick);
    }

    // ===================================================================================
    //  Keys: checked directly every tick, so they work in the world, in chests and in the
    //  inventory without ever needing the chat box.
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

    private static boolean isDown(Minecraft mc, KeyMapping mapping) {
        long handle = windowHandle(mc);
        if (handle == 0L) return false;
        InputConstants.Key key = mapping.getKey();
        if (key.getType() != InputConstants.Type.KEYSYM || key.getValue() <= 0) return false;
        return GLFW.glfwGetKey(handle, key.getValue()) == GLFW.GLFW_PRESS;
    }

    private static void handleKeys(Minecraft mc) {
        boolean open = isDown(mc, openGuiKey);
        boolean toggle = isDown(mc, toggleKey);

        boolean inSellGui = current instanceof SellScreen;
        boolean allowed = !(current instanceof ChatScreen)
                && (current == null || current instanceof AbstractContainerScreen<?> || inSellGui);

        if (allowed && open && !wasOpenDown && !inSellGui) {
            setScreen(mc, new SellScreen());
        }
        if (allowed && toggle && !wasToggleDown) {
            if (isRunning()) stop(mc, true);
            else start(mc);
        }
        wasOpenDown = open;
        wasToggleDown = toggle;
    }

    // ===================================================================================
    //  Start / stop
    // ===================================================================================

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    public static boolean start(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return false;
        if (!isValidPrice(price)) {
            player.sendOverlayMessage(Component.literal("DonutSell: enter a price first"));
            if (!(current instanceof SellScreen)) setScreen(mc, new SellScreen());
            return false;
        }
        hotbarIndex = 0;
        attempts = 0;
        state = State.SELECT;
        nextAt = System.currentTimeMillis();
        player.sendOverlayMessage(Component.literal("DonutSell: ON (" + price + " per stack)"));
        return true;
    }

    public static void stop(Minecraft mc, boolean announce) {
        boolean wasRunning = isRunning();
        state = State.IDLE;
        if (wasRunning && announce && mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal("DonutSell: OFF"));
        }
    }

    private static void finish(Minecraft mc) {
        state = State.IDLE;
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal("DonutSell: finished, nothing left to sell"));
        }
    }

    private static void failed(Minecraft mc) {
        state = State.IDLE;
        setScreen(mc, new ListingFailedScreen());
    }

    private static boolean isValidPrice(String p) {
        if (p == null || p.isEmpty() || !p.matches("\\d+")) return false;
        try {
            return Long.parseLong(p) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
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

        handleKeys(mc);
        if (state == State.IDLE) return;

        long now = System.currentTimeMillis();
        if (now < nextAt) return;

        switch (state) {
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
                } else {
                    finish(mc);
                }
            }
            case SEND -> {
                player.connection.sendCommand("ah sell " + price);
                state = State.CHECK;
                nextAt = now + CHECK_WAIT_MS;
            }
            case CHECK -> {
                if (!hasItem(player, hotbarIndex)) {
                    hotbarIndex++;
                    state = State.SELECT;
                    nextAt = now;
                } else if (attempts < 1) {
                    attempts++;           // one retry, in case the first answer was just slow
                    state = State.SEND;
                    nextAt = now;
                } else {
                    failed(mc);           // item still in hand: listings full (or listing refused)
                }
            }
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
                nextAt = now + INV_OPEN_WAIT_MS;
            }
            default -> { }
        }
    }

    // ===================================================================================
    //  Inventory helpers (hotbar = 0-8, main inventory = 9-35)
    // ===================================================================================

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
        } catch (ReflectiveOperationException ignored) {
        }
        if (gui != null && invokeSetScreen(gui, screen)) return;
        invokeSetScreen(mc, screen);
    }

    private static boolean invokeSetScreen(Object target, Screen screen) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals("setScreen") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(Screen.class)) {
                try {
                    m.invoke(target, screen);
                    return true;
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return false;
    }

    // ===================================================================================
    //  Saved price
    // ===================================================================================

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutsell.properties");
    }

    public static void setPrice(String value) {
        price = value;
        saveConfig();
    }

    private static void loadConfig() {
        Path p = configPath();
        if (!Files.exists(p)) return;
        try (InputStream in = Files.newInputStream(p)) {
            Properties props = new Properties();
            props.load(in);
            price = props.getProperty("price", "");
        } catch (IOException ignored) {
        }
    }

    private static void saveConfig() {
        try (OutputStream out = Files.newOutputStream(configPath())) {
            Properties props = new Properties();
            props.setProperty("price", price);
            props.store(out, "DonutSell");
        } catch (IOException ignored) {
        }
    }
}
