package net.maxbel.takeitout.client;

import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import net.minecraft.client.gui.screens.Screen;

public class ModMenuCompat implements ModMenuApi {

    @Override
    public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
        return TakeItOutSettingsScreen::new;
    }
}
