package net.maxbel.takeitout.client;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

public final class TakeItOutHotkeys {
    public static final ConfigHotkey OPEN_CONFIG_GUI = hotkey(
            "openConfigGui",
            "X,T",
            "Open Config GUI",
            "Open the TakeItOut settings screen."
    );
    public static final ConfigHotkey AUTO_TAKE_OUT = hotkey(
            "autoTakeOut",
            "R",
            "Auto Take Out",
            "Toggle automatic block take out."
    );
    public static final ConfigHotkey SINGLE_ITEM_MODE = hotkey(
            "singleItemMode",
            "B",
            "Single-item Mode",
            "Toggle taking one item instead of a full stack."
    );
    public static final ConfigHotkey LINK_LOOKED_AT_CONTAINER = hotkey(
            "linkLookedAtContainer",
            "H",
            "Link Looked-at Container",
            "Link or unlink the supported container you are currently looking at."
    );
    public static final ConfigHotkey TOGGLE_CONTAINER_SOURCE_RENDER = hotkey(
            "toggleContainerSourceRender",
            "",
            "Container Source Render",
            "Toggle rendering outlines around linked world containers."
    );

    public static final List<ConfigHotkey> HOTKEY_LIST = List.of(
            OPEN_CONFIG_GUI,
            AUTO_TAKE_OUT,
            SINGLE_ITEM_MODE,
            LINK_LOOKED_AT_CONTAINER,
            TOGGLE_CONTAINER_SOURCE_RENDER
    );

    private TakeItOutHotkeys() {
    }

    public static void initCallbacks() {
        OPEN_CONFIG_GUI.getKeybind().setCallback((KeyAction action, IKeybind key) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.screen == null) {
                client.setScreen(new TakeItOutSettingsScreen(null));
            }
            return true;
        });

        AUTO_TAKE_OUT.getKeybind().setCallback((KeyAction action, IKeybind key) -> {
            TakeitoutClient.toggleAutoTakeout(Minecraft.getInstance());
            return true;
        });

        SINGLE_ITEM_MODE.getKeybind().setCallback((KeyAction action, IKeybind key) -> {
            TakeitoutClient.toggleSingleItemMode(Minecraft.getInstance());
            return true;
        });

        LINK_LOOKED_AT_CONTAINER.getKeybind().setCallback((KeyAction action, IKeybind key) -> {
            if (action != KeyAction.PRESS) {
                return false;
            }

            Minecraft client = Minecraft.getInstance();
            if (client.screen != null || client.player == null || client.level == null) {
                return false;
            }

            if (client.hitResult instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK
                    && WorldContainerSources.isSupportedContainer(client.level, hit.getBlockPos())) {
                return WorldContainerSources.toggle(client, hit.getBlockPos());
            }

            client.player.sendOverlayMessage(Component.literal("Look at a chest, barrel or shulker box"));
            return false;
        });

        TOGGLE_CONTAINER_SOURCE_RENDER.getKeybind().setCallback((KeyAction action, IKeybind key) -> {
            TakeitoutClient.toggleContainerSourceRender(Minecraft.getInstance());
            return true;
        });
    }

    private static ConfigHotkey hotkey(String name, String defaultKey, String displayName, String comment) {
        ConfigHotkey hotkey = new ConfigHotkey(name, defaultKey, KeybindSettings.DEFAULT);
        hotkey.setPrettyName(displayName);
        hotkey.setTranslatedName(displayName);
        hotkey.setComment(comment);
        return hotkey;
    }
}
