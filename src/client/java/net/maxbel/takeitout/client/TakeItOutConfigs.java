package net.maxbel.takeitout.client;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigColor;

import java.util.ArrayList;
import java.util.List;

public final class TakeItOutConfigs {
    public static final ConfigColor CONTAINER_SOURCE_OUTLINE_COLOR = new ConfigColor(
            "containerSourceOutlineColor",
            "#FF22C55E"
    );

    public static final List<ConfigColor> GENERIC_LIST = List.of(
            CONTAINER_SOURCE_OUTLINE_COLOR
    );

    public static final List<IConfigBase> SETTINGS_LIST;

    static {
        CONTAINER_SOURCE_OUTLINE_COLOR.setPrettyName("Container Source Outline Color");
        CONTAINER_SOURCE_OUTLINE_COLOR.setTranslatedName("Container Source Outline Color");
        CONTAINER_SOURCE_OUTLINE_COLOR.setComment("Color of the outline rendered around linked world containers.");

        List<IConfigBase> settings = new ArrayList<>();
        settings.addAll(GENERIC_LIST);
        settings.addAll(TakeItOutHotkeys.HOTKEY_LIST);
        SETTINGS_LIST = List.copyOf(settings);
    }

    private TakeItOutConfigs() {
    }

    public static void initCallbacks() {
        CONTAINER_SOURCE_OUTLINE_COLOR.setValueChangeCallback(config ->
                TakeitoutClient.setContainerSourceOutlineColor(config.getIntegerValue())
        );
    }
}
