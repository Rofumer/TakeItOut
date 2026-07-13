package net.maxbel.takeitout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.neoforged.fml.loading.FMLPaths;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
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

public final class WorldContainerSources {
    private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/world-sources");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SOURCES_PATH = FMLPaths.CONFIGDIR.get().resolve("takeitout-world-sources.json");
    private static final long FAILURE_RETRY_DELAY_MS = 1500L;
    private static final String CONTEXTS_KEY = "contexts";
    private static final String ACTIVE_GROUP_KEY = "activeGroup";
    private static final String GROUPS_KEY = "groups";
    public static final String DEFAULT_GROUP = "default";

    private static final Map<BlockPos, Boolean> SOURCES = new LinkedHashMap<>();

    private static ItemStack lastFailedStack = ItemStack.EMPTY;
    private static long lastFailureTsMs = 0L;
    private static String currentContextKey; // worldKey|dimension
    private static String currentWorldKey;
    private static String currentGroupName = DEFAULT_GROUP;
    private static boolean isDirty = false;

    private WorldContainerSources() {
    }

    public static boolean toggle(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || client.level == null || pos == null) return false;
        updateContext(client);
        BlockPos immutable = pos.immutable();
        if (!isSupportedContainer(client.level, immutable)) return false;
        boolean linked = !SOURCES.getOrDefault(immutable, false);
        SOURCES.put(immutable, linked);
        isDirty = true;
        int linkedCount = linkedSourceCountSnapshot();
        int scanLimit = TakeitoutClient.SERVER_SCAN_LIMIT;
        String suffix = linked && scanLimit > 0 && linkedCount > scanLimit
                ? " §eWarning: linked containers (" + linkedCount + ") exceed server scan limit (" + scanLimit + ")" : "";
        client.player.sendOverlayMessage(Component.literal("TakeItOut source " + (linked ? "linked" : "unlinked") + " (" + linkedCount + ")" + suffix));
        LOGGER.info("World container source {}: pos={}, linked={}, totalLinked={}", linked ? "linked" : "unlinked", immutable, linked, linkedSourceCountSnapshot());
        saveCurrentContext();
        return true;
    }

    public static boolean setLinked(Minecraft client, BlockPos pos, boolean linked) {
        if (client == null || client.player == null || pos == null) return false;
        updateContext(client);
        BlockPos immutable = pos.immutable();
        if (!SOURCES.containsKey(immutable) && !linked) return false;
        SOURCES.put(immutable, linked);
        isDirty = true;
        int linkedCount = linkedSourceCountSnapshot();
        int scanLimit = TakeitoutClient.SERVER_SCAN_LIMIT;
        String suffix = linked && scanLimit > 0 && linkedCount > scanLimit
                ? " §eWarning: linked containers (" + linkedCount + ") exceed server scan limit (" + scanLimit + ")" : "";
        client.player.sendOverlayMessage(Component.literal("TakeItOut source " + (linked ? "linked" : "unlinked") + " (" + linkedCount + ")" + suffix));
        LOGGER.info("World container source {}: pos={}, totalLinked={}", linked ? "linked" : "unlinked", immutable, linkedCount);
        saveCurrentContext();
        return true;
    }

    public static boolean setLinked(Minecraft client, SourceEntry source, boolean linked) {
        if (source == null) return false;
        if (Objects.equals(source.dimension(), getCurrentDimensionId())) return setLinked(client, source.pos(), linked);
        if (client == null || client.player == null || source.pos() == null) return false;
        updateContext(client);
        boolean changed = updateStoredSource(source, linked, false);
        if (changed) {
            isDirty = true;
            int linkedCount = linkedSourceCountSnapshot();
            int scanLimit = TakeitoutClient.SERVER_SCAN_LIMIT;
            String suffix = linked && scanLimit > 0 && linkedCount > scanLimit
                    ? " §eWarning: linked containers (" + linkedCount + ") exceed server scan limit (" + scanLimit + ")" : "";
            client.player.sendOverlayMessage(Component.literal("TakeItOut source " + (linked ? "linked" : "unlinked") + " (" + linkedCount + ")" + suffix));
            LOGGER.info("World container source {}: dimension={}, pos={}, totalLinked={}", linked ? "linked" : "unlinked", source.dimension(), source.pos(), linkedCount);
        }
        return changed;
    }

    public static int linkAll(Minecraft client, BlockPos corner1, BlockPos corner2) {
        if (client == null || client.player == null || client.level == null) return 0;
        updateContext(client);

        int minX = Math.min(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isSupportedContainer(client.level, pos)) {
                        SOURCES.put(pos, true);
                        isDirty = true;
                        count++;
                    }
                }
            }
        }

        if (count > 0) {
            int linkedCount = linkedSourceCountSnapshot();
            int scanLimit = TakeitoutClient.SERVER_SCAN_LIMIT;
            String suffix = scanLimit > 0 && linkedCount > scanLimit
                    ? " §eWarning: linked (" + linkedCount + ") exceeds scan limit (" + scanLimit + ")" : "";
            client.player.sendOverlayMessage(Component.literal("Box select: " + count + " linked (" + linkedCount + " total)" + suffix));
            LOGGER.info("Box select linked {} containers in [{},{},{}]-[{},{},{}], totalLinked={}", count, minX, minY, minZ, maxX, maxY, maxZ, linkedCount);
            saveCurrentContext();
        } else {
            client.player.sendOverlayMessage(Component.literal("Box select: no containers found"));
        }
        return count;
    }

    public static int unlinkAll(Minecraft client, BlockPos corner1, BlockPos corner2) {
        if (client == null || client.player == null || client.level == null) return 0;
        updateContext(client);

        int minX = Math.min(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isSupportedContainer(client.level, pos) && SOURCES.getOrDefault(pos, false)) {
                        SOURCES.put(pos, false);
                        isDirty = true;
                        count++;
                    }
                }
            }
        }

        if (count > 0) {
            int linkedCount = linkedSourceCountSnapshot();
            client.player.sendOverlayMessage(Component.literal("Box select: " + count + " unlinked (" + linkedCount + " total)"));
            LOGGER.info("Box select unlinked {} containers in [{},{},{}]-[{},{},{}], totalLinked={}", count, minX, minY, minZ, maxX, maxY, maxZ, linkedCount);
            saveCurrentContext();
        } else {
            client.player.sendOverlayMessage(Component.literal("Box select: no linked containers found"));
        }
        return count;
    }

    public static boolean areAllLinked(Level level, BlockPos corner1, BlockPos corner2) {
        int minX = Math.min(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        boolean foundAny = false;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isSupportedContainer(level, pos)) {
                        foundAny = true;
                        if (!SOURCES.getOrDefault(pos, false)) return false;
                    }
                }
            }
        }
        return foundAny;
    }

    public static boolean delete(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || pos == null) return false;
        updateContext(client);
        boolean deleted = SOURCES.remove(pos.immutable()) != null;
        if (deleted) {
            isDirty = true;
            client.player.sendOverlayMessage(Component.literal("TakeItOut source deleted (" + linkedSourceCountSnapshot() + ")"));
            LOGGER.info("World container source deleted: pos={}, totalLinked={}", pos, linkedSourceCountSnapshot());
            saveCurrentContext();
        }
        return deleted;
    }

    public static boolean deleteAll(Minecraft client) {
        if (client == null || client.player == null || currentWorldKey == null) return false;
        updateContext(client);
        if (getAllSourcesSnapshot().isEmpty()) return false;
        SOURCES.clear();
        isDirty = true;
        try {
            Files.createDirectories(SOURCES_PATH.getParent());
            JsonObject root = readSourcesFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, currentWorldKey);
            JsonObject groups = getOrCreateObject(worldCtx, GROUPS_KEY);
            groups.add(currentGroupName, new JsonObject());
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Failed to delete all world container sources", e);
        }
        client.player.sendOverlayMessage(Component.literal("TakeItOut: all sources deleted"));
        LOGGER.info("All world container sources deleted: world={}, group={}", currentWorldKey, currentGroupName);
        return true;
    }

    public static boolean delete(Minecraft client, SourceEntry source) {
        if (source == null) return false;
        if (Objects.equals(source.dimension(), getCurrentDimensionId())) return delete(client, source.pos());
        if (client == null || client.player == null || source.pos() == null) return false;
        updateContext(client);
        boolean deleted = updateStoredSource(source, false, true);
        if (deleted) {
            isDirty = true;
            client.player.sendOverlayMessage(Component.literal("TakeItOut source deleted (" + linkedSourceCountSnapshot() + ")"));
            LOGGER.info("World container source deleted: dimension={}, pos={}, totalLinked={}", source.dimension(), source.pos(), linkedSourceCountSnapshot());
        }
        return deleted;
    }

    // --- Group management ---

    public static List<String> getGroupNames() {
        if (currentWorldKey == null) return List.of(currentGroupName);
        JsonObject root = readSourcesFile();
        JsonObject worldCtx = getWorldContext(root, currentWorldKey);
        if (!worldCtx.has(GROUPS_KEY) || !worldCtx.get(GROUPS_KEY).isJsonObject()) {
            return List.of(currentGroupName);
        }
        List<String> names = new ArrayList<>(worldCtx.getAsJsonObject(GROUPS_KEY).keySet());
        if (names.isEmpty()) names.add(currentGroupName);
        return names;
    }

    public static String getCurrentGroupName() {
        return currentGroupName;
    }

    public static boolean isGroupDirty() {
        return isDirty;
    }

    public static void createGroup(String name) {
        if (currentWorldKey == null || name == null || name.isBlank()) return;
        try {
            Files.createDirectories(SOURCES_PATH.getParent());
            JsonObject root = readSourcesFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, currentWorldKey);
            JsonObject groups = getOrCreateObject(worldCtx, GROUPS_KEY);
            if (!groups.has(name)) groups.add(name, new JsonObject());
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
            WorldContainerDumps.createGroup(currentWorldKey, name);
        } catch (IOException e) {
            LOGGER.warn("Failed to create group '{}'", name, e);
        }
    }

    public static boolean renameGroup(String oldName, String newName) {
        if (currentWorldKey == null || oldName == null || newName == null || newName.isBlank()) return false;
        if (Objects.equals(oldName, newName)) return false;
        try {
            Files.createDirectories(SOURCES_PATH.getParent());
            JsonObject root = readSourcesFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, currentWorldKey);
            JsonObject groups = getOrCreateObject(worldCtx, GROUPS_KEY);
            if (!groups.has(oldName) || groups.has(newName)) return false;
            JsonElement data = groups.get(oldName);
            groups.remove(oldName);
            groups.add(newName, data);
            if (Objects.equals(currentGroupName, oldName)) {
                currentGroupName = newName;
                worldCtx.addProperty(ACTIVE_GROUP_KEY, newName);
            }
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
            WorldContainerDumps.renameGroup(currentWorldKey, oldName, newName);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to rename group '{}' to '{}'", oldName, newName, e);
            return false;
        }
    }

    public static boolean deleteGroup(String name) {
        if (currentWorldKey == null || name == null || Objects.equals(name, currentGroupName)) return false;
        try {
            Files.createDirectories(SOURCES_PATH.getParent());
            JsonObject root = readSourcesFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, currentWorldKey);
            JsonObject groups = getOrCreateObject(worldCtx, GROUPS_KEY);
            if (!groups.has(name)) return false;
            groups.remove(name);
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
            WorldContainerDumps.deleteGroup(currentWorldKey, name);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to delete group '{}'", name, e);
            return false;
        }
    }

    public static List<Takeitout.SharedGroupDimension> getGroupDataForPublishing() {
        if (currentWorldKey == null) return List.of();
        saveCurrentContext();
        List<Takeitout.SharedGroupDimension> result = new ArrayList<>();
        JsonObject root = readSourcesFile();
        JsonObject worldCtx = getWorldContext(root, currentWorldKey);
        JsonObject groups = worldCtx.has(GROUPS_KEY) && worldCtx.get(GROUPS_KEY).isJsonObject()
                ? worldCtx.getAsJsonObject(GROUPS_KEY) : new JsonObject();
        if (!groups.has(currentGroupName) || !groups.get(currentGroupName).isJsonObject()) return result;
        JsonObject group = groups.getAsJsonObject(currentGroupName);
        for (Map.Entry<String, JsonElement> dimEntry : group.entrySet()) {
            if (!dimEntry.getValue().isJsonArray()) continue;
            List<Takeitout.SharedSourceEntry> sources = new ArrayList<>();
            for (JsonElement el : dimEntry.getValue().getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject src = el.getAsJsonObject();
                if (!src.has("x") || !src.has("y") || !src.has("z")) continue;
                try {
                    boolean linked = !src.has("linked") || src.get("linked").getAsBoolean();
                    long pos = BlockPos.asLong(src.get("x").getAsInt(), src.get("y").getAsInt(), src.get("z").getAsInt());
                    sources.add(new Takeitout.SharedSourceEntry(pos, linked));
                } catch (Exception ignored) {}
            }
            result.add(new Takeitout.SharedGroupDimension(dimEntry.getKey(), sources));
        }
        return result;
    }

    public static void importSharedGroup(String groupName, List<Takeitout.SharedGroupDimension> dimensions) {
        if (currentWorldKey == null || groupName == null || groupName.isBlank() || dimensions == null) return;
        try {
            Files.createDirectories(SOURCES_PATH.getParent());
            JsonObject root = readSourcesFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, currentWorldKey);
            JsonObject groups = getOrCreateObject(worldCtx, GROUPS_KEY);
            String name = groupName;
            int suffix = 1;
            while (groups.has(name)) {
                name = groupName + " (" + suffix++ + ")";
            }
            JsonObject group = new JsonObject();
            for (Takeitout.SharedGroupDimension dim : dimensions) {
                JsonArray sources = new JsonArray();
                for (Takeitout.SharedSourceEntry src : dim.sources()) {
                    BlockPos pos = BlockPos.of(src.position());
                    JsonObject srcObj = new JsonObject();
                    srcObj.addProperty("x", pos.getX());
                    srcObj.addProperty("y", pos.getY());
                    srcObj.addProperty("z", pos.getZ());
                    srcObj.addProperty("linked", src.linked());
                    sources.add(srcObj);
                }
                group.add(dim.dimension(), sources);
            }
            groups.add(name, group);
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
            WorldContainerDumps.createGroup(currentWorldKey, name);
            LOGGER.info("Imported shared group: world={}, name={}", currentWorldKey, name);
        } catch (IOException e) {
            LOGGER.warn("Failed to import shared group '{}'", groupName, e);
        }
    }

    public static void switchGroup(Minecraft client, String name) {
        if (currentWorldKey == null || name == null || Objects.equals(name, currentGroupName)) return;
        saveCurrentContext();
        SOURCES.clear();
        currentGroupName = name;
        isDirty = false;
        persistActiveGroup();
        loadCurrentContext();
        WorldContainerDumps.switchGroup(name);
        LOGGER.info("Switched group: world={}, group={}", currentWorldKey, currentGroupName);
    }

    // --- Queries ---

    public static boolean isLinked(BlockPos pos) {
        return pos != null && SOURCES.getOrDefault(pos.immutable(), false);
    }

    public static boolean isSupportedContainer(Level world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        return block instanceof ShulkerBoxBlock || block instanceof ChestBlock || block instanceof BarrelBlock;
    }

    public static List<BlockPos> getSourcesSnapshot() {
        List<BlockPos> linkedSources = new ArrayList<>();
        for (Map.Entry<BlockPos, Boolean> entry : SOURCES.entrySet()) {
            if (entry.getValue()) linkedSources.add(entry.getKey());
        }
        return linkedSources;
    }

    public static List<SourceEntry> getAllSourcesSnapshot() {
        if (currentWorldKey == null) return List.of();
        List<SourceEntry> entries = new ArrayList<>();
        addCurrentContextEntries(entries);
        JsonObject root = readSourcesFile();
        JsonObject worldCtx = getWorldContext(root, currentWorldKey);
        JsonObject groups = worldCtx.has(GROUPS_KEY) && worldCtx.get(GROUPS_KEY).isJsonObject()
                ? worldCtx.getAsJsonObject(GROUPS_KEY) : new JsonObject();
        if (!groups.has(currentGroupName) || !groups.get(currentGroupName).isJsonObject()) return entries;
        JsonObject group = groups.getAsJsonObject(currentGroupName);
        String currentDim = getCurrentDimensionId();
        for (Map.Entry<String, JsonElement> dimEntry : group.entrySet()) {
            if (Objects.equals(dimEntry.getKey(), currentDim) || !dimEntry.getValue().isJsonArray()) continue;
            addEntriesFromArray(entries, dimEntry.getKey(), dimEntry.getValue().getAsJsonArray());
        }
        return entries;
    }

    private static void addCurrentContextEntries(List<SourceEntry> entries) {
        String dimension = getCurrentDimensionId();
        for (Map.Entry<BlockPos, Boolean> entry : SOURCES.entrySet()) {
            entries.add(new SourceEntry(dimension, entry.getKey(), entry.getValue()));
        }
    }

    public static int linkedSourceCountSnapshot() {
        int count = 0;
        for (SourceEntry source : getAllSourcesSnapshot()) {
            if (source.linked()) count++;
        }
        return count;
    }

    public static List<Takeitout.WorldContainerSource> getLinkedSourceReferencesSnapshot() {
        List<Takeitout.WorldContainerSource> sources = new ArrayList<>();
        for (SourceEntry source : getAllSourcesSnapshot()) {
            if (source.linked()) sources.add(toNetworkSource(source));
        }
        return sources;
    }

    public static List<Takeitout.WorldContainerSource> getAllSourceReferencesSnapshot() {
        List<Takeitout.WorldContainerSource> sources = new ArrayList<>();
        for (SourceEntry source : getAllSourcesSnapshot()) sources.add(toNetworkSource(source));
        return sources;
    }

    public static Takeitout.WorldContainerSource toNetworkSource(SourceEntry source) {
        return new Takeitout.WorldContainerSource(source.dimension(), source.pos().asLong());
    }

    public static Takeitout.WorldContainerSource currentDimensionSource(BlockPos pos) {
        return new Takeitout.WorldContainerSource(getCurrentDimensionId(), pos.asLong());
    }

    public static String sourceKey(Takeitout.WorldContainerSource source) {
        return source.dimension() + "|" + source.position();
    }

    public static String sourceKey(SourceEntry source) {
        return source.dimension() + "|" + source.pos().asLong();
    }

    public static boolean requestStack(Minecraft client, ItemStack required, boolean singleItemMode) {
        return requestStack(client, required, singleItemMode, false);
    }

    public static boolean requestStack(Minecraft client, ItemStack required, boolean singleItemMode, boolean fromUi) {
        if (client == null || client.player == null || client.level == null || required == null || required.isEmpty()) return false;
        List<Takeitout.WorldContainerSource> sources = getLinkedSourceReferencesSnapshot();
        if (sources.isEmpty()) {
            LOGGER.warn("World container request skipped: required={}, reason=no_sources", required);
            return false;
        }
        if (isCoolingDownAfterFailure(required)) return false;
        LOGGER.debug("World container request: required={}, sources={}, singleItemMode={}", required, sources.size(), singleItemMode);
        TakeitoutClient.awaitingStack = required.copyWithCount(1);
        TakeitoutClient.sendToServer(new Takeitout.GetWorldContainerStackPayload(
                sources, required.copyWithCount(1), singleItemMode, fromUi, WorldContainerDumps.getDumpReferencesSnapshot()
        ));
        return true;
    }

    public static void recordResponse(ItemStack stack, boolean success) {
        if (stack == null || stack.isEmpty()) return;
        if (success) {
            if (ItemStack.isSameItemSameComponents(lastFailedStack, stack)) {
                lastFailedStack = ItemStack.EMPTY;
                lastFailureTsMs = 0L;
            }
            return;
        }
        lastFailedStack = stack.copyWithCount(1);
        lastFailureTsMs = System.currentTimeMillis();
    }

    public static boolean consumeFailedResponse(ItemStack stack) {
        if (stack == null || stack.isEmpty() || lastFailedStack.isEmpty()) return false;
        if (!lastFailedStack.is(stack.getItem())) return false;
        lastFailedStack = ItemStack.EMPTY;
        lastFailureTsMs = 0L;
        return true;
    }

    public static void updateContext(Minecraft client) {
        String nextContextKey = getContextKey(client);
        String nextWorldKey = nextContextKey != null ? extractWorldKey(nextContextKey) : null;
        if (Objects.equals(currentContextKey, nextContextKey)) return;

        boolean worldChanged = !Objects.equals(currentWorldKey, nextWorldKey);
        SOURCES.clear();
        currentContextKey = nextContextKey;
        lastFailedStack = ItemStack.EMPTY;
        lastFailureTsMs = 0L;

        if (worldChanged) {
            currentWorldKey = nextWorldKey;
            isDirty = false;
            if (currentWorldKey != null) {
                currentGroupName = loadActiveGroupName();
            }
        }

        if (currentContextKey != null) {
            loadCurrentContext();
        }
    }

    public static void clear() {
        SOURCES.clear();
        currentContextKey = null;
        currentWorldKey = null;
        currentGroupName = DEFAULT_GROUP;
        isDirty = false;
        lastFailedStack = ItemStack.EMPTY;
        lastFailureTsMs = 0L;
    }

    public static int size() {
        int count = 0;
        for (boolean linked : SOURCES.values()) {
            if (linked) count++;
        }
        return count;
    }

    public static String getCurrentContextLabel() {
        if (currentWorldKey == null) return "unknown";
        return currentWorldKey + " [" + currentGroupName + "]";
    }

    // --- Private helpers ---

    private static boolean isCoolingDownAfterFailure(ItemStack required) {
        return !lastFailedStack.isEmpty()
                && lastFailedStack.is(required.getItem())
                && System.currentTimeMillis() - lastFailureTsMs < FAILURE_RETRY_DELAY_MS;
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
        return getDimension(currentContextKey);
    }

    private static String extractWorldKey(String contextKey) {
        if (contextKey == null) return "unknown";
        int separator = contextKey.lastIndexOf('|');
        return separator == -1 ? contextKey : contextKey.substring(0, separator);
    }

    private static String getDimension(String contextKey) {
        if (contextKey == null) return "minecraft:overworld";
        int separator = contextKey.lastIndexOf('|');
        return separator == -1 ? "minecraft:overworld" : contextKey.substring(separator + 1);
    }

    private static String loadActiveGroupName() {
        JsonObject root = readSourcesFile();
        migrateIfNeeded(root);
        JsonObject worldCtx = getWorldContext(root, currentWorldKey);
        if (worldCtx.has(ACTIVE_GROUP_KEY)) {
            String name = worldCtx.get(ACTIVE_GROUP_KEY).getAsString();
            if (name != null && !name.isBlank()) return name;
        }
        return DEFAULT_GROUP;
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
        worldCtx.addProperty(ACTIVE_GROUP_KEY, DEFAULT_GROUP);
        JsonObject groups = new JsonObject();
        groups.add(DEFAULT_GROUP, defaultGroup);
        worldCtx.add(GROUPS_KEY, groups);
        contexts.add(currentWorldKey, worldCtx);

        try {
            Files.createDirectories(SOURCES_PATH.getParent());
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
            LOGGER.info("Migrated sources to group format: world={}", currentWorldKey);
        } catch (IOException e) {
            LOGGER.warn("Failed to save migrated sources", e);
        }
    }

    private static void loadCurrentContext() {
        JsonObject root = readSourcesFile();
        JsonObject worldCtx = getWorldContext(root, currentWorldKey);
        JsonObject groups = worldCtx.has(GROUPS_KEY) && worldCtx.get(GROUPS_KEY).isJsonObject()
                ? worldCtx.getAsJsonObject(GROUPS_KEY) : new JsonObject();
        if (!groups.has(currentGroupName) || !groups.get(currentGroupName).isJsonObject()) {
            isDirty = false;
            return;
        }
        JsonObject group = groups.getAsJsonObject(currentGroupName);
        String dimension = getCurrentDimensionId();
        if (group.has(dimension) && group.get(dimension).isJsonArray()) {
            for (JsonElement element : group.getAsJsonArray(dimension)) {
                if (!element.isJsonObject()) continue;
                JsonObject source = element.getAsJsonObject();
                if (!source.has("x") || !source.has("y") || !source.has("z")) continue;
                try {
                    boolean linked = !source.has("linked") || source.get("linked").getAsBoolean();
                    SOURCES.put(new BlockPos(source.get("x").getAsInt(), source.get("y").getAsInt(), source.get("z").getAsInt()), linked);
                } catch (Exception ignored) {
                }
            }
        }
        isDirty = false;
        LOGGER.info("World container sources loaded: world={}, group={}, dimension={}, total={}", currentWorldKey, currentGroupName, dimension, SOURCES.size());
    }

    private static void saveCurrentContext() {
        if (currentWorldKey == null) return;
        try {
            Files.createDirectories(SOURCES_PATH.getParent());
            JsonObject root = readSourcesFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, currentWorldKey);
            worldCtx.addProperty(ACTIVE_GROUP_KEY, currentGroupName);
            JsonObject groups = getOrCreateObject(worldCtx, GROUPS_KEY);
            JsonObject group = getOrCreateObject(groups, currentGroupName);
            JsonArray sources = new JsonArray();
            for (Map.Entry<BlockPos, Boolean> entry : SOURCES.entrySet()) {
                BlockPos source = entry.getKey();
                JsonObject sourceObject = new JsonObject();
                sourceObject.addProperty("x", source.getX());
                sourceObject.addProperty("y", source.getY());
                sourceObject.addProperty("z", source.getZ());
                sourceObject.addProperty("linked", entry.getValue());
                sources.add(sourceObject);
            }
            group.add(getCurrentDimensionId(), sources);
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Failed to save world container sources", e);
        }
    }

    private static void persistActiveGroup() {
        if (currentWorldKey == null) return;
        try {
            Files.createDirectories(SOURCES_PATH.getParent());
            JsonObject root = readSourcesFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, currentWorldKey);
            worldCtx.addProperty(ACTIVE_GROUP_KEY, currentGroupName);
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Failed to persist active group", e);
        }
    }

    private static boolean updateStoredSource(SourceEntry source, boolean linked, boolean delete) {
        if (currentWorldKey == null) return false;
        try {
            Files.createDirectories(SOURCES_PATH.getParent());
            JsonObject root = readSourcesFile();
            JsonObject worldCtx = getOrCreateWorldContext(root, currentWorldKey);
            JsonObject groups = getOrCreateObject(worldCtx, GROUPS_KEY);
            JsonObject group = getOrCreateObject(groups, currentGroupName);
            String dimension = source.dimension();
            JsonArray existing = group.has(dimension) && group.get(dimension).isJsonArray()
                    ? group.getAsJsonArray(dimension) : new JsonArray();
            JsonArray updated = new JsonArray();
            boolean found = false;

            for (JsonElement element : existing) {
                if (!element.isJsonObject()) continue;
                JsonObject sourceObject = element.getAsJsonObject();
                SourceEntry existingEntry = parseSourceEntry(dimension, sourceObject);
                if (existingEntry != null && existingEntry.pos().equals(source.pos())) {
                    found = true;
                    if (!delete) {
                        sourceObject.addProperty("linked", linked);
                        updated.add(sourceObject);
                    }
                } else {
                    updated.add(sourceObject);
                }
            }

            if (!found && !delete) {
                JsonObject sourceObject = new JsonObject();
                sourceObject.addProperty("x", source.pos().getX());
                sourceObject.addProperty("y", source.pos().getY());
                sourceObject.addProperty("z", source.pos().getZ());
                sourceObject.addProperty("linked", linked);
                updated.add(sourceObject);
            }

            group.add(dimension, updated);
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
            return found || !delete;
        } catch (IOException e) {
            LOGGER.warn("Failed to update world container source", e);
            return false;
        }
    }

    private static void addEntriesFromArray(List<SourceEntry> entries, String dimension, JsonArray sources) {
        for (JsonElement element : sources) {
            if (!element.isJsonObject()) continue;
            SourceEntry entry = parseSourceEntry(dimension, element.getAsJsonObject());
            if (entry != null) entries.add(entry);
        }
    }

    private static SourceEntry parseSourceEntry(String dimension, JsonObject source) {
        if (!source.has("x") || !source.has("y") || !source.has("z")) return null;
        try {
            boolean linked = !source.has("linked") || source.get("linked").getAsBoolean();
            return new SourceEntry(dimension, new BlockPos(source.get("x").getAsInt(), source.get("y").getAsInt(), source.get("z").getAsInt()), linked);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject readSourcesFile() {
        if (!Files.exists(SOURCES_PATH)) return new JsonObject();
        try {
            JsonObject root = GSON.fromJson(Files.readString(SOURCES_PATH), JsonObject.class);
            return root == null ? new JsonObject() : root;
        } catch (Exception e) {
            LOGGER.warn("Failed to read world container sources", e);
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

    public record SourceEntry(String dimension, BlockPos pos, boolean linked) {
        public SourceEntry(BlockPos pos, boolean linked) {
            this(getCurrentDimensionId(), pos, linked);
        }
    }
}
