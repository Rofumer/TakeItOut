package net.maxbel.takeitout.client;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;

import java.util.ArrayList;
import java.util.List;

public final class TakeItOutConfigs {
    public static final ConfigColor CONTAINER_SOURCE_OUTLINE_COLOR = new ConfigColor(
            "containerSourceOutlineColor",
            "#FF22C55E"
    );

    public static final ConfigBoolean BOX_SELECT_CREATES_NEW_GROUP = new ConfigBoolean(
            "boxSelectCreatesNewGroup",
            false
    );

    public static final ConfigBoolean RETURN_TO_CONTAINER_WHEN_FULL = new ConfigBoolean(
            "returnToContainerWhenFull",
            false
    );

    // Everything in this list is what TakeItOutConfigHandler reads and writes, so a config that is not
    // here silently loses its value on restart.
    public static final List<IConfigBase> GENERIC_LIST = List.of(
            CONTAINER_SOURCE_OUTLINE_COLOR,
            BOX_SELECT_CREATES_NEW_GROUP,
            RETURN_TO_CONTAINER_WHEN_FULL
    );

    public static final List<IConfigBase> SETTINGS_LIST;

    static {
        CONTAINER_SOURCE_OUTLINE_COLOR.setPrettyName("Container Source Outline Color");
        CONTAINER_SOURCE_OUTLINE_COLOR.setTranslatedName("Container Source Outline Color");
        CONTAINER_SOURCE_OUTLINE_COLOR.setComment("Color of the outline rendered around linked world containers.");

        BOX_SELECT_CREATES_NEW_GROUP.setPrettyName("Box Select Creates New Group");
        BOX_SELECT_CREATES_NEW_GROUP.setTranslatedName("Box Select Creates New Group");
        BOX_SELECT_CREATES_NEW_GROUP.setComment("When enabled, box-selecting a region creates a new group instead of adding to the current one.");

        RETURN_TO_CONTAINER_WHEN_FULL.setPrettyName("Return To Container When Full");
        RETURN_TO_CONTAINER_WHEN_FULL.setTranslatedName("Return To Container When Full");
        RETURN_TO_CONTAINER_WHEN_FULL.setComment("When the inventory is full, send the oldest previously taken material back to the container it came from to free a slot. Requires a server running the same TakeItOut version.");

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
