package net.maxbel.takeitout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
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
    private static final Path SOURCES_PATH = FabricLoader.getInstance().getConfigDir().resolve("takeitout-world-sources.json");
    private static final long FAILURE_RETRY_DELAY_MS = 1500L;
    private static final String CONTEXTS_KEY = "contexts";

    private static final Map<BlockPos, Boolean> SOURCES = new LinkedHashMap<>();

    private static ItemStack lastFailedStack = ItemStack.EMPTY;
    private static long lastFailureTsMs = 0L;
    private static String currentContextKey;

    private WorldContainerSources() {
    }

    public static boolean toggle(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || client.level == null || pos == null) {
            return false;
        }

        updateContext(client);

        BlockPos immutable = pos.immutable();
        if (!isSupportedContainer(client.level, immutable)) {
            return false;
        }

        boolean linked = !SOURCES.getOrDefault(immutable, false);
        SOURCES.put(immutable, linked);

        client.player.sendOverlayMessage(
                Component.literal("TakeItOut source " + (linked ? "linked" : "unlinked") + " (" + size() + ")")
        );
        LOGGER.info(
                "World container source {}: pos={}, linked={}, totalLinked={}",
                linked ? "linked" : "unlinked",
                immutable,
                linked,
                size()
        );
        saveCurrentContext();
        return true;
    }

    public static boolean setLinked(Minecraft client, BlockPos pos, boolean linked) {
        if (client == null || client.player == null || pos == null) {
            return false;
        }

        updateContext(client);

        BlockPos immutable = pos.immutable();
        if (!SOURCES.containsKey(immutable) && !linked) {
            return false;
        }

        SOURCES.put(immutable, linked);
        client.player.sendOverlayMessage(
                Component.literal("TakeItOut source " + (linked ? "linked" : "unlinked") + " (" + size() + ")")
        );
        LOGGER.info(
                "World container source {}: pos={}, totalLinked={}",
                linked ? "linked" : "unlinked",
                immutable,
                size()
        );
        saveCurrentContext();
        return true;
    }

    public static boolean delete(Minecraft client, BlockPos pos) {
        if (client == null || client.player == null || pos == null) {
            return false;
        }

        updateContext(client);

        boolean deleted = SOURCES.remove(pos.immutable()) != null;
        if (deleted) {
            client.player.sendOverlayMessage(Component.literal("TakeItOut source deleted (" + size() + ")"));
            LOGGER.info("World container source deleted: pos={}, totalLinked={}", pos, size());
            saveCurrentContext();
        }

        return deleted;
    }

    public static boolean isLinked(BlockPos pos) {
        return pos != null && SOURCES.getOrDefault(pos.immutable(), false);
    }

    public static boolean isSupportedContainer(Level world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        return block instanceof ShulkerBoxBlock
                || block instanceof ChestBlock
                || block instanceof BarrelBlock;
    }

    public static List<BlockPos> getSourcesSnapshot() {
        List<BlockPos> linkedSources = new ArrayList<>();
        for (Map.Entry<BlockPos, Boolean> entry : SOURCES.entrySet()) {
            if (entry.getValue()) {
                linkedSources.add(entry.getKey());
            }
        }
        return linkedSources;
    }

    public static List<SourceEntry> getAllSourcesSnapshot() {
        List<SourceEntry> entries = new ArrayList<>();
        for (Map.Entry<BlockPos, Boolean> entry : SOURCES.entrySet()) {
            entries.add(new SourceEntry(entry.getKey(), entry.getValue()));
        }
        return entries;
    }

    public static long[] getPackedSourcesSnapshot() {
        List<BlockPos> linkedSources = getSourcesSnapshot();
        long[] packed = new long[linkedSources.size()];
        for (int i = 0; i < linkedSources.size(); i++) {
            packed[i] = linkedSources.get(i).asLong();
        }
        return packed;
    }

    public static long[] getAllPackedSourcesSnapshot() {
        long[] packed = new long[SOURCES.size()];
        int i = 0;
        for (BlockPos source : SOURCES.keySet()) {
            packed[i++] = source.asLong();
        }
        return packed;
    }

    public static boolean requestStack(Minecraft client, ItemStack required, boolean singleItemMode) {
        if (client == null || client.player == null || client.level == null || required == null || required.isEmpty()) {
            return false;
        }

        if (size() == 0) {
            LOGGER.warn("World container request skipped: required={}, reason=no_sources", required);
            return false;
        }

        if (isCoolingDownAfterFailure(required)) {
            return false;
        }

        LOGGER.debug(
                "World container request: required={}, sources={}, singleItemMode={}",
                required,
                size(),
                singleItemMode
        );
        TakeitoutClient.awaitingStack = required.copyWithCount(1);
        ClientPlayNetworking.send(new Takeitout.GetWorldContainerStackPayload(
                getPackedSourcesSnapshot(),
                required.copyWithCount(1),
                singleItemMode
        ));
        return true;
    }

    public static void recordResponse(ItemStack stack, boolean success) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

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
        if (stack == null || stack.isEmpty() || lastFailedStack.isEmpty()) {
            return false;
        }

        if (!ItemStack.isSameItemSameComponents(lastFailedStack, stack)) {
            return false;
        }

        lastFailedStack = ItemStack.EMPTY;
        lastFailureTsMs = 0L;
        return true;
    }

    public static void updateContext(Minecraft client) {
        String nextContextKey = getContextKey(client);
        if (Objects.equals(currentContextKey, nextContextKey)) {
            return;
        }

        SOURCES.clear();
        currentContextKey = nextContextKey;
        lastFailedStack = ItemStack.EMPTY;
        lastFailureTsMs = 0L;

        if (currentContextKey != null) {
            loadCurrentContext();
        }
    }

    public static void clear() {
        SOURCES.clear();
        currentContextKey = null;
        lastFailedStack = ItemStack.EMPTY;
        lastFailureTsMs = 0L;
    }

    public static int size() {
        int count = 0;
        for (boolean linked : SOURCES.values()) {
            if (linked) {
                count++;
            }
        }
        return count;
    }

    public static String getCurrentContextLabel() {
        return currentContextKey == null ? "unknown" : currentContextKey;
    }

    private static boolean isCoolingDownAfterFailure(ItemStack required) {
        return !lastFailedStack.isEmpty()
                && ItemStack.isSameItemSameComponents(lastFailedStack, required)
                && System.currentTimeMillis() - lastFailureTsMs < FAILURE_RETRY_DELAY_MS;
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

    private static void loadCurrentContext() {
        JsonObject root = readSourcesFile();
        if (root == null || !root.has(CONTEXTS_KEY) || !root.get(CONTEXTS_KEY).isJsonObject()) {
            return;
        }

        JsonObject contexts = root.getAsJsonObject(CONTEXTS_KEY);
        if (!contexts.has(currentContextKey) || !contexts.get(currentContextKey).isJsonArray()) {
            return;
        }

        JsonArray sources = contexts.getAsJsonArray(currentContextKey);
        for (JsonElement element : sources) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject source = element.getAsJsonObject();
            if (!source.has("x") || !source.has("y") || !source.has("z")) {
                continue;
            }

            try {
                boolean linked = !source.has("linked") || source.get("linked").getAsBoolean();
                SOURCES.put(
                        new BlockPos(
                                source.get("x").getAsInt(),
                                source.get("y").getAsInt(),
                                source.get("z").getAsInt()
                        ),
                        linked
                );
            } catch (Exception ignored) {
            }
        }

        LOGGER.info("World container sources loaded: context={}, total={}", currentContextKey, SOURCES.size());
    }

    private static void saveCurrentContext() {
        if (currentContextKey == null) {
            return;
        }

        try {
            Files.createDirectories(SOURCES_PATH.getParent());

            JsonObject root = readSourcesFile();
            if (root == null) {
                root = new JsonObject();
            }

            JsonObject contexts;
            if (root.has(CONTEXTS_KEY) && root.get(CONTEXTS_KEY).isJsonObject()) {
                contexts = root.getAsJsonObject(CONTEXTS_KEY);
            } else {
                contexts = new JsonObject();
                root.add(CONTEXTS_KEY, contexts);
            }

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

            contexts.add(currentContextKey, sources);
            Files.writeString(SOURCES_PATH, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Failed to save world container sources", e);
        }
    }

    private static JsonObject readSourcesFile() {
        if (!Files.exists(SOURCES_PATH)) {
            return new JsonObject();
        }

        try {
            JsonObject root = GSON.fromJson(Files.readString(SOURCES_PATH), JsonObject.class);
            return root == null ? new JsonObject() : root;
        } catch (Exception e) {
            LOGGER.warn("Failed to read world container sources", e);
            return new JsonObject();
        }
    }

    public record SourceEntry(BlockPos pos, boolean linked) {
    }
}
