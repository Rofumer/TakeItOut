package net.maxbel.takeitout.client;

import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;

public class ModMenuCompat implements ModMenuApi {

    @Override
    public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
        return parent -> new TakeItOutKeybindsScreen(parent, Minecraft.getInstance().options);
    }
}