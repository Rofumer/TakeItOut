package net.maxbel.takeitout.client;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;

import java.util.ArrayList;
import java.util.List;

public final class TakeItOutConfigs {
    public static final ConfigColor CONTAINER_SOURCE_OUTLINE_COLOR = new ConfigColor(
            "containerSourceOutlineColor",
            "#FF22C55E",
            "Color of the outline rendered around linked world containers."
    );

    public static final ConfigBoolean BOX_SELECT_CREATES_NEW_GROUP = new ConfigBoolean(
            "boxSelectCreatesNewGroup",
            false,
            "When enabled, box-selecting a region creates a new group instead of adding to the current one."
    );

    public static final List<ConfigColor> GENERIC_LIST = List.of(
            CONTAINER_SOURCE_OUTLINE_COLOR
    );

    public static final List<IConfigBase> SETTINGS_LIST;

    static {
        List<IConfigBase> settings = new ArrayList<>();
        settings.addAll(GENERIC_LIST);
        settings.add(BOX_SELECT_CREATES_NEW_GROUP);
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
