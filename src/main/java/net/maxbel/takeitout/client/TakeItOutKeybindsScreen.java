package net.maxbel.takeitout.client;

import fi.dy.masa.malilib.config.gui.GuiModConfigs;
import net.minecraft.client.gui.screens.Screen;

public final class TakeItOutKeybindsScreen {
    private TakeItOutKeybindsScreen() {
    }

    public static Screen create(Screen parent) {
        GuiModConfigs screen = new GuiModConfigs(
                "takeitout",
                TakeItOutConfigs.SETTINGS_LIST,
                "TakeItOut Settings"
        );
        screen.setParent(parent);
        return screen;
    }
}
