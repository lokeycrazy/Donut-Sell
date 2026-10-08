package com.donutsell;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The settings GUI. */
public class SellScreen extends Screen {

    private EditBox stackBox;
    private EditBox singleBox;
    private EditBox delayBox;

    public SellScreen() {
        super(Component.literal("DonutSell"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        this.addRenderableWidget(label("DonutSell - Auto Auction House Seller", cy - 84));

        stackBox = digitsBox(cx - 100, cy - 66, "Stack price (whole stack), e.g. 5000",
                DonutSellClient.price, DonutSellClient::setPrice, 12);
        this.addRenderableWidget(stackBox);
        this.setInitialFocus(stackBox);

        singleBox = digitsBox(cx - 100, cy - 18, "Single price (one item), e.g. 400",
                DonutSellClient.singlePrice, DonutSellClient::setSinglePrice, 12);
        singleBox.setEditable(DonutSellClient.singlesMode);
        this.addRenderableWidget(singleBox);

        this.addRenderableWidget(Button.builder(modeLabel(), b -> {
            DonutSellClient.setSinglesMode(!DonutSellClient.singlesMode);
            b.setMessage(modeLabel());
            singleBox.setEditable(DonutSellClient.singlesMode);
        }).bounds(cx - 100, cy - 42, 200, 20).build());

        delayBox = digitsBox(cx - 100, cy + 6,
                "Listing delay in ms (min " + DonutSellClient.MIN_LIST_DELAY_MS + ")",
                DonutSellClient.delayText, DonutSellClient::setDelay, 5);
        this.addRenderableWidget(delayBox);

        boolean running = DonutSellClient.isRunning();

        this.addRenderableWidget(Button.builder(Component.literal(running ? "Stop" : "Begin selling"), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (DonutSellClient.isRunning()) {
                DonutSellClient.stop(mc, true);
                DonutSellClient.closeScreen();
            } else if (DonutSellClient.start(mc)) {
                DonutSellClient.closeScreen();
            }
        }).bounds(cx - 100, cy + 30, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal(running ? "Stop" : "Begin order flipping"), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (DonutSellClient.isRunning()) {
                DonutSellClient.stop(mc, true);
                DonutSellClient.closeScreen();
            } else if (DonutSellClient.startFlip(mc)) {
                DonutSellClient.closeScreen();
            }
        }).bounds(cx - 100, cy + 54, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"),
                b -> DonutSellClient.closeScreen()).bounds(cx - 100, cy + 78, 200, 20).build());
    }

    private Component modeLabel() {
        return Component.literal("Mode: " + (DonutSellClient.singlesMode ? "Singles" : "Stacks"));
    }

    private EditBox digitsBox(int x, int y, String hint, String value,
                              java.util.function.Consumer<String> onChange, int maxLen) {
        EditBox box = new EditBox(this.font, x, y, 200, 20, Component.literal(hint));
        box.setMaxLength(maxLen);
        box.setHint(Component.literal(hint));
        box.setValue(value == null ? "" : value);
        box.setResponder(text -> {
            String digits = text.replaceAll("\\D", "");   // numbers only
            if (!digits.equals(text)) {
                box.setValue(digits);
                return;
            }
            onChange.accept(digits);
        });
        return box;
    }

    private StringWidget label(String text, int y) {
        Component c = Component.literal(text);
        int w = this.font.width(c);
        return new StringWidget(this.width / 2 - w / 2, y, w, 12, c, this.font);
    }
}
