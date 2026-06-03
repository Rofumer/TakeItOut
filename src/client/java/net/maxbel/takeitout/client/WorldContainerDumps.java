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
    private static final String ACTIVE_GROUP_KEY = "activeGroup";
    private static final String GROUPS_KEY = "groups";

    private static final Map<BlockPos, Boolean> DUMPS = new LinkedHashMap<>();
    private static String currentContextKey;
    private static String currentWorldKey;
    private static String currentGroupName = WorldContainerSources.DEFAULT_GROUP;

    private WorldContainerDumps() {
    }

    public static boolean toggle(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || client.level == null || pos == null) return false;
        updateContext(client);
        BlockPos immutable = pos.immutable();
        if (!WorldContainerSources.isSupportedContainer(client.level, immutable)) return false;
        return setEnabled(client, immutable, !DUMPS.getOrDefault(immutable, false));
    }

    public static boolean setEnabled(Minecraft client, BlockPos pos, boolean enabled) {
        if (client == null || client.player == null || pos == null) return false;
        updateContext(client);
        BlockPos immutable = pos.immutable();
        if (!DUMPS.containsKey(immutable) && !enabled) return false;
        DUMPS.put(immutable, enabled);
        client.player.sendOverlayMessage(
                net.minecraft.network.chat.Component.literal("TakeItOut dump " + (enabled ? "marked" : "unmarked") + " (" + dumpCountSnapshot() + ")")
        );
        LOGGER.info("Dump container {}: pos={}, total={}", enabled ? "marked" : "unmarked", immutable, dumpCountSnapshot());
        saveCurrentContext();
        return true;
    }

    public static boolean delete(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || pos == null) return false;
        updateContext(client);
        boolean deleted = DUMPS.remove(pos.immutable()) != null;
        if (deleted) {
            client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("TakeItOut dump deleted (" + dumpCountSnapshot() + ")"));
            LOGGER.info("Dump container deleted: pos={}, total={}", pos, dumpCountSnapshot());
            saveCurrentContext();
        }
        return deleted;
    }

    public static boolean deleteAll(Minecraft client) {
        if (client == null || client.player == null || currentWorldKey == null) return false;
        updateContext(client);
        if (DUMPS.isEmpty()) return false;
        DUMPS.clear();
        client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("TakeItOut: all dump containers deleted"));
        LOGGER.info("All dump containers deleted: world={}, group={}", currentWorldKey, currentGroupName);
        saveCurrentContext();
        return true;
    }

    public record DumpEntry(BlockPos pos, boolean enabled) {
    }

    public static List<BlockPos> getDumpSnapshot() {
        List<BlockPos> result = new ArrayList<>();
        for (Map.Entry<BlockPos, Boolean> entry : DUMPS.entrySet()) {
            if (entry.getValue()) result.add(entry.getKey());
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
            if (entry.getValue()) result.add(new Takeitout.WorldContainerSource(dimension, entry.getKey().asLong()));
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
        String nextWorldKey = nextContextKey != null ? extractWorldKey(nextContextKey) : null;
        if (Objects.equals(currentContextKey, nextContextKey)) return;

        boolean worldChanged = !Objects.equals(currentWorldKey, nextWorldKey);
        DUMPS.clear();
        currentContextKey = nextContextKey;

        if (worldChanged) {
            currentWorldKey = nextWorldKey;
            // Sync group name with Sources (Sources.updateContext already ran)
            currentGroupName = WorldContainerSources.getCurrentGroupName();
        }

        if (currentContextKey != null) loadCurrentContext();
    }

    // Called by WorldContainerSources when switching groups
    static void switchGroup(String name) {
        if (Objects.equals(name, currentGroupName)) return;
        saveCurrentContext();
        DUMPS.clear();
        currentGroupName = name;
        loadCurrentContext();
    }

    // Called by WorldContainerSources for group file operations
    static void createGroup(String worldKey, String name) {
        modifyGroupsFile(worldKey, groups -> {
            if (!groups.has(name)) groups.add(name, new JsonObject());
        });
    }

    static void renameGroup(String worldKey, String oldName, String newName) {
        modifyGroupsFile(worldKey, groups -> {
            if (!groups.has(oldName)) return;
            JsonElement data = groups.get(oldName);
            groups.remove(oldName);
            groups.add(newName, data);
        });
    }

    static void deleteGroup(String worldKey, String name) {
        modifyGroupsFile(worldKey, groups -> groups.remove(name));
    }

    public static void clear() {
        DUMPS.clear();
        currentContextKey = null;
        currentWorldKey = null;
        currentGroupName = WorldContainerSources.DEFAULT_GROUP;
    }

    private static String getContextKey(Minecraft client) {
        if (client == null || client.level == null) return null;
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
        if (currentContextKey == null) return "minecraft:overworld";
        int sep = currentContextKey.lastIndexOf('|');
        return sep == -1 ? "minecraft:overworld" : currentContextKey.substring(sep + 1);
    }

    private static String extractWorldKey(String contextKey) {
        if (contextKey == null) return "unknown";
        int sep = contextKey.lastIndexOf('|');
        return sep == -1 ? contextKey : contextKey.substring(0, sep);
    }

    private static void loadCurrentContext() {
        JsonObject root = readDumpsFile();
        migrateIfNeeded(root);
        JsonObject worldCtx = getWorldContext(root, currentWorldKey);
        JsonObject groups = worldCtx.has(GROUPS_KEY) && worldCtx.get(GROUPS_KEY).isJsonObject()
                ? worldCtx.getAsJsonObject(GROUPS_KEY) : new JsonObject();
        if (!groups.has(currentGroupName) || !groups.get(currentGroupName).isJsonObject()) return;
        JsonObject group = groups.getAsJsonObject(currentGroupName);
        String dimension = getCurrentDimensionId();
        if (!group.has(dimension) || !group.get(dimension).isJsonArray()) return;
        for (JsonElement element : group.getAsJsonArray(dimension)) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has("x") || !obj.has("y") || !obj.has("z")) continue;
            try {
                boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
                DUMPS.put(new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt()), enabled);
            } catch (Exception ignored) {
            }
        }
        LOGGER.info("Dump containers loaded: world={}, group={}, dimension={}, total={}", currentWorldKey, currentGroupName, dimension, DUMPS.size());
    }

    private static void saveCurrentContext() {
        if (currentWorldKey == null) return;
        try {
            Files.createDirectories(DUMPS_PATH.getParent());
            JsonObject root = readDumpsFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, currentWorldKey);
            worldCtx.addProperty(ACTIVE_GROUP_KEY, currentGroupName);
            JsonObject groups = getOrCreateObject(worldCtx, GROUPS_KEY);
            JsonObject group = getOrCreateObject(groups, currentGroupName);
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
            group.add(getCurrentDimensionId(), dumps);
            Files.writeString(DUMPS_PATH, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Failed to save dump containers", e);
        }
    }

    private static void migrateIfNeeded(JsonObject root) {
        if (currentWorldKey == null) return;
        if (!root.has(CONTEXTS_KEY) || !root.get(CONTEXTS_KEY).isJsonObject()) return;
        JsonObject contexts = root.getAsJsonObject(CONTEXTS_KEY);
        if (contexts.has(currentWorldKey) && contexts.get(currentWorldKey).isJsonObject()) return;

        boolean needsMigration = false;
        for (String key : contexts.keySet()) {
            if (key.startsWith(currentWorldKey + "|") && contexts.get(key).isJsonArray()) {
                needsMigration = true;
                break;
            }
        }
        if (!needsMigration) return;

        JsonObject defaultGroup = new JsonObject();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : contexts.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(currentWorldKey + "|") && entry.getValue().isJsonArray()) {
                String dimension = key.substring(currentWorldKey.length() + 1);
                defaultGroup.add(dimension, entry.getValue());
                toRemove.add(key);
            }
        }
        toRemove.forEach(contexts::remove);

        JsonObject worldCtx = new JsonObject();
        worldCtx.addProperty(ACTIVE_GROUP_KEY, WorldContainerSources.DEFAULT_GROUP);
        JsonObject groups = new JsonObject();
        groups.add(WorldContainerSources.DEFAULT_GROUP, defaultGroup);
        worldCtx.add(GROUPS_KEY, groups);
        contexts.add(currentWorldKey, worldCtx);

        try {
            Files.createDirectories(DUMPS_PATH.getParent());
            Files.writeString(DUMPS_PATH, GSON.toJson(root));
            LOGGER.info("Migrated dump containers to group format: world={}", currentWorldKey);
        } catch (IOException e) {
            LOGGER.warn("Failed to save migrated dump containers", e);
        }
    }

    @FunctionalInterface
    private interface GroupsModifier {
        void modify(JsonObject groups);
    }

    private static void modifyGroupsFile(String worldKey, GroupsModifier modifier) {
        try {
            Files.createDirectories(DUMPS_PATH.getParent());
            JsonObject root = readDumpsFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, worldKey);
            JsonObject groups = getOrCreateObject(worldCtx, GROUPS_KEY);
            modifier.modify(groups);
            Files.writeString(DUMPS_PATH, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Failed to modify dump groups for world '{}'", worldKey, e);
        }
    }

    private static JsonObject readDumpsFile() {
        if (!Files.exists(DUMPS_PATH)) return new JsonObject();
        try {
            JsonObject root = GSON.fromJson(Files.readString(DUMPS_PATH), JsonObject.class);
            return root == null ? new JsonObject() : root;
        } catch (Exception e) {
            LOGGER.warn("Failed to read dump containers", e);
            return new JsonObject();
        }
    }

    private static JsonObject getWorldContext(JsonObject root, String worldKey) {
        if (worldKey == null || !root.has(CONTEXTS_KEY) || !root.get(CONTEXTS_KEY).isJsonObject()) return new JsonObject();
        JsonObject contexts = root.getAsJsonObject(CONTEXTS_KEY);
        if (!contexts.has(worldKey) || !contexts.get(worldKey).isJsonObject()) return new JsonObject();
        return contexts.getAsJsonObject(worldKey);
    }

    private static JsonObject getOrCreateWorldContext(JsonObject root, String worldKey) {
        JsonObject contexts;
        if (root.has(CONTEXTS_KEY) && root.get(CONTEXTS_KEY).isJsonObject()) {
            contexts = root.getAsJsonObject(CONTEXTS_KEY);
        } else {
            contexts = new JsonObject();
            root.add(CONTEXTS_KEY, contexts);
        }
        if (contexts.has(worldKey) && contexts.get(worldKey).isJsonObject()) return contexts.getAsJsonObject(worldKey);
        JsonObject worldCtx = new JsonObject();
        contexts.add(worldKey, worldCtx);
        return worldCtx;
    }

    private static JsonObject getOrCreateObject(JsonObject parent, String key) {
        if (parent.has(key) && parent.get(key).isJsonObject()) return parent.getAsJsonObject(key);
        JsonObject obj = new JsonObject();
        parent.add(key, obj);
        return obj;
    }
}
