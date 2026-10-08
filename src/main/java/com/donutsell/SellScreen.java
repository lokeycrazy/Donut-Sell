package com.donutsell;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The DonutSell settings GUI: tabs for Stacks, Singles, Flip, Safety and Help.
 * Every entry has a short instruction line and a hover tooltip.
 */
public class SellScreen extends Screen {

    private static final int PW = 340;   // panel width
    private static final int PH = 236;   // panel height
    private static final String[] TABS = {"Stacks", "Singles", "Flip", "Safety", "Help"};
    private static int tab = 0;

    private int x0;
    private int y0;

    public SellScreen() {
        super(Component.literal("DonutSell"));
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        x0 = (this.width - PW) / 2;
        y0 = Math.max(2, (this.height - PH) / 2);

        // Background panel (a disabled button never takes clicks, it only draws the box).
        Button panel = Button.builder(Component.empty(), b -> { }).bounds(x0, y0, PW, PH).build();
        panel.active = false;
        this.addRenderableWidget(panel);

        this.addRenderableWidget(center(
                Component.literal("DonutSell v" + DonutSellClient.VERSION).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                y0 + 6));

        // Tab bar
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            Component label = (i == tab)
                    ? Component.literal(TABS[i]).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                    : Component.literal(TABS[i]);
            Button t = Button.builder(label, b -> {
                tab = idx;
                rebuild();
            }).bounds(x0 + 7 + i * 66, y0 + 20, 62, 18).build();
            t.setTooltip(Tooltip.create(Component.literal("Open the " + TABS[i] + " page.")));
            this.addRenderableWidget(t);
        }

        int y = y0 + 46;
        switch (tab) {
            case 0 -> tabStacks(y);
            case 1 -> tabSingles(y);
            case 2 -> tabFlip(y);
            case 3 -> tabSafety(y);
            default -> tabHelp(y);
        }

        // Bottom buttons (always visible)
        boolean running = DonutSellClient.isRunning();
        int by = y0 + PH - 28;

        Button sell = Button.builder(
                running ? red("STOP") : green("Begin selling"), b -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (DonutSellClient.isRunning()) {
                        DonutSellClient.stop(mc, true);
                        DonutSellClient.closeScreen();
                    } else if (DonutSellClient.start(mc)) {
                        DonutSellClient.closeScreen();
                    }
                }).bounds(x0 + 7, by, 104, 20).build();
        sell.setTooltip(Tooltip.create(Component.literal(
                "Sells everything in your hotbar and inventory with /ah sell, using the active mode (Stacks or Singles).")));
        this.addRenderableWidget(sell);

        Button flip = Button.builder(
                running ? red("STOP") : green("Begin order flipping"), b -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (DonutSellClient.isRunning()) {
                        DonutSellClient.stop(mc, true);
                        DonutSellClient.closeScreen();
                    } else if (DonutSellClient.startFlip(mc)) {
                        DonutSellClient.closeScreen();
                    }
                }).bounds(x0 + 117, by, 104, 20).build();
        flip.setTooltip(Tooltip.create(Component.literal(
                "Runs the full loop: /orders, collect items, sell everything, repeat. Press J or L to stop.")));
        this.addRenderableWidget(flip);

        Button close = Button.builder(Component.literal("Close"), b -> DonutSellClient.closeScreen())
                .bounds(x0 + 227, by, 106, 20).build();
        close.setTooltip(Tooltip.create(Component.literal("Close this window. Your settings are saved automatically.")));
        this.addRenderableWidget(close);
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    // ------------------------------------------------------------------ tabs

    private void tabStacks(int y) {
        this.addRenderableWidget(left("STACK LISTING", y, ChatFormatting.GOLD, true));
        this.addRenderableWidget(left("Lists each stack at ONE price per stack.", y + 12, ChatFormatting.GRAY, false));

        this.addRenderableWidget(left("Price per stack:", y + 30, ChatFormatting.AQUA, false));
        this.addRenderableWidget(box(x0 + 10, y + 42, 200, "e.g. 5000", DonutSellClient.price, DonutSellClient::setPrice, 12,
                "Price used for every stack. Example: 5000 sends /ah sell 5000 for each stack."));
        this.addRenderableWidget(left("Example: 5000 runs /ah sell 5000 for each stack.", y + 66, ChatFormatting.GRAY, false));

        boolean active = !DonutSellClient.singlesMode;
        Button b = Button.builder(active ? green("STACK MODE: ACTIVE") : yellow("Click to use STACK mode"), btn -> {
            DonutSellClient.setSinglesMode(false);
            rebuild();
        }).bounds(x0 + 10, y + 88, 200, 20).build();
        b.setTooltip(Tooltip.create(Component.literal(
                "Turns Stack mode on. Order items are shift-clicked into your inventory as whole stacks.")));
        this.addRenderableWidget(b);

        this.addRenderableWidget(left("Only ONE mode is active at a time.", y + 116, ChatFormatting.GRAY, false));
        this.addRenderableWidget(left("The active mode is used by both Begin buttons.", y + 128, ChatFormatting.GRAY, false));
    }

    private void tabSingles(int y) {
        this.addRenderableWidget(left("SINGLE LISTING", y, ChatFormatting.GOLD, true));
        this.addRenderableWidget(left("Splits stacks into single items, lists each one.", y + 12, ChatFormatting.GRAY, false));

        this.addRenderableWidget(left("Price per single item:", y + 30, ChatFormatting.AQUA, false));
        this.addRenderableWidget(box(x0 + 10, y + 42, 200, "e.g. 400", DonutSellClient.singlePrice, DonutSellClient::setSinglePrice, 12,
                "Price for ONE item. Every 1-item stack is listed at this price."));
        this.addRenderableWidget(left("Stacks bigger than 1 use the Stack price.", y + 66, ChatFormatting.GRAY, false));

        boolean active = DonutSellClient.singlesMode;
        Button b = Button.builder(active ? green("SINGLE MODE: ACTIVE") : yellow("Click to use SINGLE mode"), btn -> {
            DonutSellClient.setSinglesMode(true);
            rebuild();
        }).bounds(x0 + 10, y + 88, 200, 20).build();
        b.setTooltip(Tooltip.create(Component.literal(
                "Turns Single mode on. Collected stacks are split one item per inventory slot, then listed one by one.")));
        this.addRenderableWidget(b);

        this.addRenderableWidget(left("Splitting is slower on purpose, for reliability.", y + 116, ChatFormatting.GRAY, false));
        this.addRenderableWidget(left("Singles create MANY listings: watch your AH limit.", y + 128, ChatFormatting.RED, false));
    }

    private void tabFlip(int y) {
        this.addRenderableWidget(left("ORDER FLIPPING", y, ChatFormatting.GOLD, true));
        this.addRenderableWidget(left("Order slot to flip (1-45):", y + 16, ChatFormatting.AQUA, false));
        this.addRenderableWidget(box(x0 + 10, y + 28, 70, "1", String.valueOf(DonutSellClient.orderSlot),
                v -> DonutSellClient.setSetting("orderSlot", v), 2,
                "Which order to open on your 'my orders' page. 1 = top-left, counted left to right, row by row."));
        this.addRenderableWidget(left("Slot 1 = top-left, then left to right, row by row.", y + 54, ChatFormatting.GRAY, false));
        this.addRenderableWidget(left("Gray/red glass = empty or locked: it stops and warns.", y + 66, ChatFormatting.GRAY, false));

        this.addRenderableWidget(left("The loop:", y + 86, ChatFormatting.YELLOW, false));
        this.addRenderableWidget(left("1. /orders > chest icon > your order slot", y + 98, ChatFormatting.GRAY, false));
        this.addRenderableWidget(left("2. chest in the middle > Collect Items", y + 110, ChatFormatting.GRAY, false));
        this.addRenderableWidget(left("3. collect until your inventory is full", y + 122, ChatFormatting.GRAY, false));
        this.addRenderableWidget(left("4. sell everything with /ah sell, then repeat", y + 134, ChatFormatting.GRAY, false));
    }

    private void tabSafety(int y) {
        this.addRenderableWidget(left("SAFETY & TIMING", y, ChatFormatting.GOLD, true));
        int lx = x0 + 10;
        int rx = x0 + 175;
        int w = 150;

        setting(lx, y + 14, w, "Listing delay (ms)", "listingDelay", DonutSellClient.listingDelayMs, 5,
                "Wait between /ah sell commands. Minimum 250. Higher = safer.");
        setting(rx, y + 14, w, "Menu wait (ms)", "menuWait", DonutSellClient.menuWaitMs, 5,
                "Wait after a menu opens before clicking in it. Raise this if the server lags.");
        setting(lx, y + 52, w, "Collect click delay (ms)", "clickDelay", DonutSellClient.clickDelayMs, 5,
                "Wait between shift-clicks while collecting items (Stack mode).");
        setting(rx, y + 52, w, "Split click delay (ms)", "splitDelay", DonutSellClient.splitDelayMs, 5,
                "Wait between clicks while splitting into single items (Single mode). Higher = more reliable.");
        setting(lx, y + 90, w, "AH-full retry (seconds)", "ahRetry", DonutSellClient.ahRetrySec, 4,
                "When your AH is full the mod keeps going and retries the listing every this many seconds.");
        setting(rx, y + 90, w, "Stuck timeout (seconds)", "stuck", DonutSellClient.stuckSec, 4,
                "If nothing useful happens for this long, the mod resets itself and tries again.");

        this.addRenderableWidget(left("Defaults are the safest. Raise delays if the server lags.", y + 130, ChatFormatting.GRAY, false));
        this.addRenderableWidget(left("Auto-recovery tries 3 times, then stops and warns you.", y + 142, ChatFormatting.GRAY, false));
    }

    private void tabHelp(int y) {
        this.addRenderableWidget(left("HOW TO USE", y, ChatFormatting.GOLD, true));
        String[] lines = {
                "K opens this window. J = start/stop selling,",
                "L = start/stop order flipping (change in Controls).",
                "1. Stacks / Singles tab: enter a price, click USE.",
                "2. Flip tab: choose which order slot to flip.",
                "3. Click Begin selling or Begin order flipping.",
                "4. Press J or L any time to stop. It is always safe.",
                "AH full? You get a red warning, it retries by itself.",
                "Problems? It re-tries 3 times, then stops with a popup.",
                "Safety tab: raise delays if the server lags.",
                "Stay near your base and keep the game window open.",
                "Log file: config/donutsell.log"
        };
        for (int i = 0; i < lines.length; i++) {
            this.addRenderableWidget(left(lines[i], y + 14 + i * 11, ChatFormatting.GRAY, false));
        }
        this.addRenderableWidget(center(Component.literal("Made by Exhale").withStyle(ChatFormatting.DARK_GRAY), y0 + PH - 42));
    }

    // ------------------------------------------------------------------ widget helpers

    private void setting(int x, int y, int w, String label, String key, int value, int maxLen, String tip) {
        this.addRenderableWidget(left(label, x, y, ChatFormatting.AQUA, false, true));
        this.addRenderableWidget(box(x, y + 11, w, "", String.valueOf(value), v -> DonutSellClient.setSetting(key, v), maxLen, tip));
    }

    private StringWidget left(String text, int y, ChatFormatting color, boolean bold) {
        return left(text, x0 + 10, y, color, bold, true);
    }

    private StringWidget left(String text, int x, int y, ChatFormatting color, boolean bold, boolean absoluteX) {
        Component c = bold ? Component.literal(text).withStyle(color, ChatFormatting.BOLD)
                : Component.literal(text).withStyle(color);
        return new StringWidget(x, y, this.font.width(c), 10, c, this.font);
    }

    private StringWidget center(Component c, int y) {
        int w = this.font.width(c);
        return new StringWidget(x0 + (PW - w) / 2, y, w, 10, c, this.font);
    }

    private static Component green(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
    }

    private static Component red(String s) {
        return Component.literal(s).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
    }

    private static Component yellow(String s) {
        return Component.literal(s).withStyle(ChatFormatting.YELLOW);
    }

    private EditBox box(int x, int y, int w, String hint, String value, Consumer<String> onChange,
                        int maxLen, String tip) {
        EditBox box = new EditBox(this.font, x, y, w, 18, Component.literal(hint));
        box.setMaxLength(maxLen);
        if (!hint.isEmpty()) box.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        box.setValue(value == null ? "" : value);
        box.setResponder(text -> {
            String digits = text.replaceAll("\\D", "");   // numbers only
            if (!digits.equals(text)) {
                box.setValue(digits);
                return;
            }
            onChange.accept(digits);
        });
        box.setTooltip(Tooltip.create(Component.literal(tip)));
        return box;
    }
}
