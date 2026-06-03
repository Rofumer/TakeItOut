package net.maxbel.takeitout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorldContainerDumps {
    private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/world-dumps");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DUMPS_PATH = FabricLoader.getInstance().getConfigDir().resolve("takeitout-dump-containers.json");
    private static final String CONTEXTS_KEY = "contexts";

    private static final Map<BlockPos, Boolean> DUMPS = new LinkedHashMap<>();
    private static String currentContextKey;

    private WorldContainerDumps() {
    }

    public static boolean toggle(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || client.level == null || pos == null) {
            return false;
        }

        updateContext(client);

        BlockPos immutable = pos.immutable();
        if (!WorldContainerSources.isSupportedContainer(client.level, immutable)) {
            return false;
        }

        return setEnabled(client, immutable, !DUMPS.getOrDefault(immutable, false));
    }

    public static boolean setEnabled(Minecraft client, BlockPos pos, boolean enabled) {
        if (client == null || client.player == null || pos == null) {
            return false;
        }

        updateContext(client);

        BlockPos immutable = pos.immutable();
        if (!DUMPS.containsKey(immutable) && !enabled) {
            return false;
        }

        DUMPS.put(immutable, enabled);

        client.player.sendOverlayMessage(
                net.minecraft.network.chat.Component.literal(
                        "TakeItOut dump " + (enabled ? "marked" : "unmarked") + " (" + dumpCountSnapshot() + ")"
                )
        );
        LOGGER.info("Dump container {}: pos={}, total={}", enabled ? "marked" : "unmarked", immutable, dumpCountSnapshot());
        saveCurrentContext();
        return true;
    }

    public static boolean delete(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || pos == null) {
            return false;
        }

        updateContext(client);

        boolean deleted = DUMPS.remove(pos.immutable()) != null;
        if (deleted) {
            client.player.sendOverlayMessage(
                    net.minecraft.network.chat.Component.literal("TakeItOut dump deleted (" + dumpCountSnapshot() + ")")
            );
            LOGGER.info("Dump container deleted: pos={}, total={}", pos, dumpCountSnapshot());
            saveCurrentContext();
        }

        return deleted;
    }

    public static boolean deleteAll(Minecraft client) {
        if (client == null || client.player == null || currentContextKey == null) {
            return false;
        }

        updateContext(client);

        if (DUMPS.isEmpty()) {
            return false;
        }

        DUMPS.clear();
        client.player.sendOverlayMessage(
                net.minecraft.network.chat.Component.literal("TakeItOut: all dump containers deleted")
        );
        LOGGER.info("All dump containers deleted: context={}", currentContextKey);
        saveCurrentContext();
        return true;
    }

    public record DumpEntry(BlockPos pos, boolean enabled) {}

    public static List<BlockPos> getDumpSnapshot() {
        List<BlockPos> result = new ArrayList<>();
        for (Map.Entry<BlockPos, Boolean> entry : DUMPS.entrySet()) {
            if (entry.getValue()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public static List<DumpEntry> getAllDumpsSnapshot() {
        List<DumpEntry> result = new ArrayList<>();
        for (Map.Entry<BlockPos, Boolean> entry : DUMPS.entrySet()) {
            result.add(new DumpEntry(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public static List<Takeitout.WorldContainerSource> getDumpReferencesSnapshot() {
        String dimension = getCurrentDimensionId();
        List<Takeitout.WorldContainerSource> result = new ArrayList<>();
        for (Map.Entry<BlockPos, Boolean> entry : DUMPS.entrySet()) {
            if (entry.getValue()) {
                result.add(new Takeitout.WorldContainerSource(dimension, entry.getKey().asLong()));
            }
        }
        return result;
    }

    public static int dumpCountSnapshot() {
        int count = 0;
        for (boolean enabled : DUMPS.values()) {
            if (enabled) count++;
        }
        return count;
    }

    public static void updateContext(Minecraft client) {
        String nextContextKey = getContextKey(client);
        if (Objects.equals(currentContextKey, nextContextKey)) {
            return;
        }

        DUMPS.clear();
        currentContextKey = nextContextKey;

        if (currentContextKey != null) {
            loadCurrentContext();
        }
    }

    public static void clear() {
        DUMPS.clear();
        currentContextKey = null;
    }

    private static String getContextKey(Minecraft client) {
        if (client == null || client.level == null) {
            return null;
        }

        String dimension = client.level.dimension().identifier().toString();
        String worldKey;

        if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
            worldKey = "singleplayer:" + client.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            ServerData serverData = client.getCurrentServer();
            if (serverData != null) {
                String name = serverData.ip != null && !serverData.ip.isBlank() ? serverData.ip : serverData.name;
                worldKey = "server:" + name;
            } else {
                worldKey = "multiplayer:unknown";
            }
        }

        return worldKey + "|" + dimension;
    }

    private static String getCurrentDimensionId() {
        if (currentContextKey == null) {
            return "minecraft:overworld";
        }
        int sep = currentContextKey.lastIndexOf('|');
        return sep == -1 ? "minecraft:overworld" : currentContextKey.substring(sep + 1);
    }

    private static void loadCurrentContext() {
        JsonObject root = readDumpsFile();
        if (!root.has(CONTEXTS_KEY) || !root.get(CONTEXTS_KEY).isJsonObject()) {
            return;
        }

        JsonObject contexts = root.getAsJsonObject(CONTEXTS_KEY);
        if (!contexts.has(currentContextKey) || !contexts.get(currentContextKey).isJsonArray()) {
            return;
        }

        JsonArray dumps = contexts.getAsJsonArray(currentContextKey);
        for (JsonElement element : dumps) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has("x") || !obj.has("y") || !obj.has("z")) {
                continue;
            }
            try {
                boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
                DUMPS.put(
                        new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt()),
                        enabled
                );
            } catch (Exception ignored) {
            }
        }

        LOGGER.info("Dump containers loaded: context={}, total={}", currentContextKey, DUMPS.size());
    }

    private static void saveCurrentContext() {
        if (currentContextKey == null) {
            return;
        }

        try {
            Files.createDirectories(DUMPS_PATH.getParent());
            JsonObject root = readDumpsFile();

            JsonObject contexts;
            if (root.has(CONTEXTS_KEY) && root.get(CONTEXTS_KEY).isJsonObject()) {
                contexts = root.getAsJsonObject(CONTEXTS_KEY);
            } else {
                contexts = new JsonObject();
                root.add(CONTEXTS_KEY, contexts);
            }

            JsonArray dumps = new JsonArray();
            for (Map.Entry<BlockPos, Boolean> entry : DUMPS.entrySet()) {
                BlockPos pos = entry.getKey();
                JsonObject obj = new JsonObject();
                obj.addProperty("x", pos.getX());
                obj.addProperty("y", pos.getY());
                obj.addProperty("z", pos.getZ());
                obj.addProperty("enabled", entry.getValue());
                dumps.add(obj);
            }

            contexts.add(currentContextKey, dumps);
            Files.writeString(DUMPS_PATH, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Failed to save dump containers", e);
        }
    }

    private static JsonObject readDumpsFile() {
        if (!Files.exists(DUMPS_PATH)) {
            return new JsonObject();
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(DUMPS_PATH), JsonObject.class);
            return root == null ? new JsonObject() : root;
        } catch (Exception e) {
            LOGGER.warn("Failed to read dump containers", e);
            return new JsonObject();
        }
    }
}
