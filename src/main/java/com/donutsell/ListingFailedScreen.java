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

        this.addRenderableWidget(new StringWidget(0, cy - 40, this.width, 12,
                Component.literal("DonutSell STOPPED"), this.font).alignCenter());
        this.addRenderableWidget(new StringWidget(0, cy - 24, this.width, 12,
                Component.literal("Your auction house listings look full (/ah sell did not list the item)."),
                this.font).alignCenter());
        this.addRenderableWidget(new StringWidget(0, cy - 12, this.width, 12,
                Component.literal("Clear some listings, then press your start/stop key to continue."),
                this.font).alignCenter());

        this.addRenderableWidget(Button.builder(Component.literal("OK"),
                b -> DonutSellClient.closeScreen()).bounds(cx - 50, cy + 10, 100, 20).build());
    }
}
