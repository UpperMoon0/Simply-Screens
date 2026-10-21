package com.nstut.simplyscreens.client.ui;

import com.nstut.openui.theme.Theme;

public enum UiThemeMode {
    DARK,
    LIGHT;

    public Theme toOpenUiTheme() {
        return this == LIGHT ? Theme.light() : Theme.dark();
    }

    public UiThemeMode next() {
        return this == DARK ? LIGHT : DARK;
    }

    public String configValue() {
        return this == LIGHT ? "light" : "dark";
    }

    public static UiThemeMode fromConfigValue(String value) {
        return "light".equalsIgnoreCase(value) ? LIGHT : DARK;
    }
}
