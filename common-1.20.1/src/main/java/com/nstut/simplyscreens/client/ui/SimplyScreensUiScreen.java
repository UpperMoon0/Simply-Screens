package com.nstut.simplyscreens.client.ui;

import com.nstut.openui.api.ButtonWidget;
import com.nstut.openui.api.Ui;
import com.nstut.openui.minecraft.UiScreen;
import com.nstut.openui.state.Signal;
import com.nstut.openui.state.Signals;
import net.minecraft.network.chat.Component;

public abstract class SimplyScreensUiScreen extends UiScreen {
    protected final Signal<UiThemeMode> themeMode = Signals.of(SimplyScreensUiPreferences.getThemeMode());

    protected SimplyScreensUiScreen(Component title) {
        super(title);
    }

    @Override
    protected void init() {
        super.init();
        uiRuntime().theme(themeMode.get().toOpenUiTheme());
    }

    protected ButtonWidget buildThemeToggle() {
        return Ui.button(
                        () -> Component.translatable(themeMode.get() == UiThemeMode.DARK
                                ? "gui.simplyscreens.theme.light"
                                : "gui.simplyscreens.theme.dark"),
                        this::toggleTheme)
                .ghost().small();
    }

    private void toggleTheme() {
        UiThemeMode next = themeMode.get().next();
        themeMode.set(next);
        SimplyScreensUiPreferences.setThemeMode(next);
        if (uiRuntime() != null) uiRuntime().theme(next.toOpenUiTheme());
    }
}
