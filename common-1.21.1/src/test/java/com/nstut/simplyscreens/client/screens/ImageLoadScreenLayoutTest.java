package com.nstut.simplyscreens.client.screens;

import com.nstut.openui.api.UIComponent;
import com.nstut.openui.controls.ScrollView;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageLoadScreenLayoutTest {
    @TempDir
    static Path tempDir;

    @BeforeAll
    static void isolatePreferences() {
        System.setProperty("simplyscreens.ui.config.dir", tempDir.toString());
    }

    @AfterAll
    static void restorePreferences() {
        System.clearProperty("simplyscreens.ui.config.dir");
    }

    @Test
    void normalViewportLaysOutInsideBounds() {
        UIComponent root = new ImageLoadScreen(BlockPos.ZERO).buildUI();
        root.layoutTree(stubFont(), 0, 0, 320, 240);
        assertFalse(containsScroll(root), "A normal viewport must use the fixed panel, not the scrollable shell");
        assertTrue(maxBottom(root, 0) <= 240,
                "Every laid out component must stay inside the viewport at 320x240");
    }

    @Test
    void tinyViewportSwitchesToScrollableShell() {
        UIComponent root = new ImageLoadScreen(BlockPos.ZERO).buildUI();
        root.layoutTree(stubFont(), 0, 0, 320, 100);
        assertTrue(containsScroll(root),
                "Below the fixed-chrome height budget the panel must wrap in a scrollable shell");
    }

    private static boolean containsScroll(UIComponent component) {
        if (component instanceof ScrollView) return true;
        for (UIComponent child : component.children()) {
            if (containsScroll(child)) return true;
        }
        return false;
    }

    private static int maxBottom(UIComponent component, int best) {
        int current = best;
        if (component.getHeight() > 0) {
            current = Math.max(current, component.getY() + component.getHeight());
        }
        for (UIComponent child : component.children()) {
            current = maxBottom(child, current);
        }
        return current;
    }

    /** Measures without a real FontManager, mirroring OpenUI's own Toast tests. */
    private static Font stubFont() {
        return new Font(null, false) {
            @Override public int width(String text) { return text.length() * 6; }
            @Override public int width(FormattedText text) { return text.getString().length() * 6; }
            @Override public int width(FormattedCharSequence text) { return 40; }
            @Override public List<FormattedCharSequence> split(FormattedText text, int maxWidth) {
                int charsPerLine = Math.max(1, maxWidth / 6);
                String value = text.getString();
                List<FormattedCharSequence> lines = new ArrayList<>();
                for (int i = 0; i < value.length(); i += charsPerLine) {
                    lines.add(Component.literal(value.substring(i, Math.min(value.length(), i + charsPerLine))).getVisualOrderText());
                }
                if (lines.isEmpty()) lines.add(Component.literal("").getVisualOrderText());
                return lines;
            }
        };
    }
}
