package com.donutsell;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Popup that stays on screen until you click OK (or press Esc). */
public class ListingFailedScreen extends Screen {

    private final String line1;
    private final String line2;

    public ListingFailedScreen() {
        this("Your auction house listings look full (/ah sell did not list the item).",
                "Clear some listings, then press your start/stop key to continue.");
    }

    public ListingFailedScreen(String line1, String line2) {
        super(Component.literal("DonutSell stopped"));
        this.line1 = line1;
        this.line2 = line2;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        this.addRenderableWidget(label("DonutSell STOPPED", cy - 40));
        this.addRenderableWidget(label(line1, cy - 24));
        this.addRenderableWidget(label(line2, cy - 12));

        this.addRenderableWidget(Button.builder(Component.literal("OK"),
                b -> DonutSellClient.closeScreen()).bounds(cx - 50, cy + 10, 100, 20).build());
    }

    private StringWidget label(String text, int y) {
        Component c = Component.literal(text);
        int w = this.font.width(c);
        return new StringWidget(this.width / 2 - w / 2, y, w, 12, c, this.font);
    }
}
