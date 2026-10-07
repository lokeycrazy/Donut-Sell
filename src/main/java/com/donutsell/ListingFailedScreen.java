package com.donutsell;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Popup that stays on screen until you click OK (or press Esc). */
public class ListingFailedScreen extends Screen {

    public ListingFailedScreen() {
        super(Component.literal("DonutSell stopped"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        this.addRenderableWidget(label("DonutSell STOPPED", cy - 40));
        this.addRenderableWidget(label("Your auction house listings look full (/ah sell did not list the item).", cy - 24));
        this.addRenderableWidget(label("Clear some listings, then press your start/stop key to continue.", cy - 12));

        this.addRenderableWidget(Button.builder(Component.literal("OK"),
                b -> DonutSellClient.closeScreen()).bounds(cx - 50, cy + 10, 100, 20).build());
    }

    private StringWidget label(String text, int y) {
        Component c = Component.literal(text);
        int w = this.font.width(c);
        return new StringWidget(this.width / 2 - w / 2, y, w, 12, c, this.font);
    }
}
