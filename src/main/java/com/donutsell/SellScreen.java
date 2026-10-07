package com.donutsell;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The settings GUI: one price box, a Begin/Stop button and a Close button. */
public class SellScreen extends Screen {

    private EditBox priceBox;

    public SellScreen() {
        super(Component.literal("DonutSell"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        this.addRenderableWidget(label("DonutSell - Auto Auction House Seller", cy - 54));

        priceBox = new EditBox(this.font, cx - 100, cy - 34, 200, 20, Component.literal("Price"));
        priceBox.setMaxLength(12);
        priceBox.setHint(Component.literal("Price for each stack, e.g. 5000"));
        priceBox.setValue(DonutSellClient.price);
        priceBox.setResponder(text -> {
            String digits = text.replaceAll("\\D", "");   // numbers only
            if (!digits.equals(text)) {
                priceBox.setValue(digits);
                return;
            }
            DonutSellClient.setPrice(digits);
        });
        this.addRenderableWidget(priceBox);
        this.setInitialFocus(priceBox);

        String label = DonutSellClient.isRunning() ? "Stop selling" : "Begin selling";
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            Minecraft mc = Minecraft.getInstance();
            if (DonutSellClient.isRunning()) {
                DonutSellClient.stop(mc, true);
                DonutSellClient.closeScreen();
            } else if (DonutSellClient.start(mc)) {
                DonutSellClient.closeScreen();
            }
        }).bounds(cx - 100, cy - 6, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"),
                b -> DonutSellClient.closeScreen()).bounds(cx - 100, cy + 18, 200, 20).build());
    }

    private StringWidget label(String text, int y) {
        Component c = Component.literal(text);
        int w = this.font.width(c);
        return new StringWidget(this.width / 2 - w / 2, y, w, 12, c, this.font);
    }
}
