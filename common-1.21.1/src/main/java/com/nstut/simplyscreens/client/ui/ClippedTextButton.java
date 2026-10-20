package com.nstut.simplyscreens.client.ui;

import com.nstut.openui.api.ButtonWidget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.function.Supplier;

/** Playlist names are user supplied and must never paint over neighboring controls. */
public final class ClippedTextButton extends ButtonWidget {
    private final Supplier<Component> label;
    private Component displayedLabel;
    private Supplier<Boolean> enabledCondition;
    public ClippedTextButton(Supplier<Component> label, Runnable action) {
        super(label); this.label = label; onPress(action);
    }
    public ClippedTextButton enabledWhen(Supplier<Boolean> condition) { enabledCondition=condition; return this; }
    @Override public Component getLabel() { return displayedLabel == null ? label.get() : displayedLabel; }
    @Override public int preferredWidth(Font font) {
        return Math.max(super.preferredWidth(font), font == null ? 60 : font.width(label.get()) + 16);
    }
    static String fit(String text, Font font, int width) {
        if (font.width(text) <= width) return text;
        String suffix = "\u2026";
        if (font.width(suffix) > width) return "";
        return font.plainSubstrByWidth(text, width - font.width(suffix)) + suffix;
    }
    @Override public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        if (enabledCondition != null) enabled(enabledCondition.get());
        Component fullLabel=label.get(); tooltip(fullLabel);
        displayedLabel=Component.literal(fit(fullLabel.getString(),font,Math.max(0,getWidth()-16)));
        try { super.render(graphics,font,mouseX,mouseY,partialTick); }
        finally { displayedLabel=null; }
    }
}
