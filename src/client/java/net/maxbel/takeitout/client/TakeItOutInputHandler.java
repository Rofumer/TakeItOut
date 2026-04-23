package net.maxbel.takeitout.client;

import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

public final class TakeItOutInputHandler implements IKeybindProvider {
    private static final TakeItOutInputHandler INSTANCE = new TakeItOutInputHandler();

    private TakeItOutInputHandler() {
    }

    public static TakeItOutInputHandler getInstance() {
        return INSTANCE;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (IHotkey hotkey : TakeItOutHotkeys.HOTKEY_LIST) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(
                "TakeItOut",
                "takeitout.hotkeys.category.generic_hotkeys",
                TakeItOutHotkeys.HOTKEY_LIST
        );
    }
}
