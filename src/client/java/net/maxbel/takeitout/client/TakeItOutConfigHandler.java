package net.maxbel.takeitout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigHandler;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TakeItOutConfigHandler implements IConfigHandler {
    public static final TakeItOutConfigHandler INSTANCE = new TakeItOutConfigHandler();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("takeitout.json");

    private TakeItOutConfigHandler() {
    }

    @Override
    public void load() {
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }

        try {
            JsonObject root = GSON.fromJson(Files.readString(CONFIG_PATH), JsonObject.class);
            if (root != null) {
                ConfigUtils.readConfigBase(root, "Generic", TakeItOutConfigs.GENERIC_LIST);
                ConfigUtils.readConfigBase(root, "GenericHotkeys", TakeItOutHotkeys.HOTKEY_LIST);
                TakeitoutClient.applyContainerSourceOutlineColor(TakeItOutConfigs.CONTAINER_SOURCE_OUTLINE_COLOR.getIntegerValue());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void save() {
        JsonObject root = new JsonObject();
        ConfigUtils.writeConfigBase(root, "Generic", TakeItOutConfigs.GENERIC_LIST);
        ConfigUtils.writeConfigBase(root, "GenericHotkeys", TakeItOutHotkeys.HOTKEY_LIST);

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(root));
        } catch (IOException ignored) {
        }
    }
}
