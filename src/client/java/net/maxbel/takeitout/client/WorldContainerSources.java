package net.maxbel.takeitout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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

public class WorldContainerSources {
    private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/world-sources");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SOURCES_PATH = FabricLoader.getInstance().getConfigDir().resolve("takeitout-world-sources.json");
    private static final long FAILURE_RETRY_DELAY_MS = 1500L;
    private static final String CONTEXTS_KEY = "contexts";
    private static final Map<BlockPos, Boolean> SOURCES = new LinkedHashMap<>();
    private static ItemStack lastFailedStack = ItemStack.EMPTY;
    private static long lastFailureTsMs = 0L;
    private static String currentContextKey;

    public static boolean toggle(MinecraftClient client, BlockPos pos) {
        if (client == null || client.player == null || client.world == null || pos == null) {
            return false;
        }

        updateContext(client);

        BlockPos immutable = pos.toImmutable();
        if (!isSupportedContainer(client.world, immutable)) {
            return false;
        }

        boolean linked = !SOURCES.getOrDefault(immutable, false);
        SOURCES.put(immutable, linked);

        client.player.sendMessage(
                Text.literal("TakeItOut source " + (linked ? "linked" : "unlinked") + " (" + size() + ")"),
                true
        );
        LOGGER.info("World container source {}: pos={}, linked={}, totalLinked={}", linked ? "linked" : "unlinked", immutable, linked, size());
        saveCurrentContext();
        return true;
    }

    public static boolean remove(MinecraftClient client, BlockPos pos) {
        return setLinked(client, pos, false);
    }

    public static boolean setLinked(MinecraftClient client, BlockPos pos, boolean linked) {
        if (client == null || client.player == null || pos == null) {
            return false;
        }

        updateContext(client);

        BlockPos immutable = pos.toImmutable();
        if (!SOURCES.containsKey(immutable) && !linked) {
            return false;
        }

        SOURCES.put(immutable, linked);
        client.player.sendMessage(
                Text.literal("TakeItOut source " + (linked ? "linked" : "unlinked") + " (" + size() + ")"),
                true
        );
        LOGGER.info("World container source {}: pos={}, totalLinked={}", linked ? "linked" : "unlinked", immutable, size());
        saveCurrentContext();
        return true;
    }

    public static boolean delete(MinecraftClient client, BlockPos pos) {
        if (client == null || client.player == null || pos == null) {
            return false;
        }

        updateContext(client);

        boolean deleted = SOURCES.remove(pos.toImmutable()) != null;
        if (deleted) {
            client.player.sendMessage(Text.literal("TakeItOut source deleted (" + size() + ")"), true);
            LOGGER.info("World container source deleted: pos={}, totalLinked={}", pos, size());
            saveCurrentContext();
        }

        return deleted;
    }

    public static boolean requestStack(MinecraftClient client, ItemStack required, boolean singleItemMode) {
        if (client == null || client.player == null || client.world == null || required == null || required.isEmpty()) {
            return false;
        }

        if (size() == 0) {
            LOGGER.warn("World container request skipped: required={}, reason=no_sources", required);
            return false;
        }

        if (isCoolingDownAfterFailure(required)) {
            return false;
        }

        LOGGER.debug("World container request: required={}, sources={}, singleItemMode={}", required, size(), singleItemMode);
        TakeitoutClient.awaitingStack = required.copyWithCount(1);
        ClientPlayNetworking.send(new Takeitout.GetWorldContainerStackPayload(
                getPackedSources(),
                required.copyWithCount(1),
                singleItemMode
        ));
        return true;
    }

    public static void clear() {
        SOURCES.clear();
        currentContextKey = null;
        lastFailedStack = ItemStack.EMPTY;
        lastFailureTsMs = 0L;
    }

    public static void updateContext(MinecraftClient client) {
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

    public static int size() {
        int count = 0;
        for (boolean linked : SOURCES.values()) {
            if (linked) {
                count++;
            }
        }
        return count;
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

    public static boolean isLinked(BlockPos pos) {
        return pos != null && SOURCES.getOrDefault(pos.toImmutable(), false);
    }

    public static String getCurrentContextLabel() {
        return currentContextKey == null ? "unknown" : currentContextKey;
    }

    public static long[] getPackedSourcesSnapshot() {
        return getPackedSources();
    }

    public static long[] getAllPackedSourcesSnapshot() {
        long[] packed = new long[SOURCES.size()];
        int i = 0;
        for (BlockPos source : SOURCES.keySet()) {
            packed[i] = source.asLong();
            i++;
        }
        return packed;
    }

    public static void recordResponse(ItemStack stack, boolean success) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        if (success) {
            if (ItemStack.areItemsAndComponentsEqual(lastFailedStack, stack)) {
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

        if (!ItemStack.areItemsAndComponentsEqual(lastFailedStack, stack)) {
            return false;
        }

        lastFailedStack = ItemStack.EMPTY;
        lastFailureTsMs = 0L;
        return true;
    }

    private static long[] getPackedSources() {
        List<BlockPos> linkedSources = getSourcesSnapshot();
        long[] packed = new long[linkedSources.size()];
        int i = 0;
        for (BlockPos source : linkedSources) {
            packed[i] = source.asLong();
            i++;
        }
        return packed;
    }

    public record SourceEntry(BlockPos pos, boolean linked) {
    }

    public static boolean isSupportedContainer(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        return block instanceof ShulkerBoxBlock
                || block instanceof ChestBlock
                || block instanceof BarrelBlock;
    }

    private static boolean isCoolingDownAfterFailure(ItemStack required) {
        return !lastFailedStack.isEmpty()
                && ItemStack.areItemsAndComponentsEqual(lastFailedStack, required)
                && System.currentTimeMillis() - lastFailureTsMs < FAILURE_RETRY_DELAY_MS;
    }

    private static String getContextKey(MinecraftClient client) {
        if (client == null || client.world == null) {
            return null;
        }

        String dimension = client.world.getRegistryKey().getValue().toString();
        String worldKey = "unknown";

        if (client.isInSingleplayer()) {
            if (client.getServer() != null && client.getServer().getSaveProperties() != null) {
                worldKey = "singleplayer:" + client.getServer().getSaveProperties().getLevelName();
            } else {
                worldKey = "singleplayer";
            }
        } else {
            ServerInfo serverInfo = client.getCurrentServerEntry();
            if (serverInfo != null) {
                String addressOrName = serverInfo.address != null && !serverInfo.address.isBlank()
                        ? serverInfo.address
                        : serverInfo.name;
                String type = serverInfo.isRealm() ? "realm" : serverInfo.isLocal() ? "local" : "server";
                worldKey = type + ":" + addressOrName;
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
                SOURCES.put(new BlockPos(
                        source.get("x").getAsInt(),
                        source.get("y").getAsInt(),
                        source.get("z").getAsInt()
                ), linked);
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
                JsonObject sourceObj = new JsonObject();
                sourceObj.addProperty("x", source.getX());
                sourceObj.addProperty("y", source.getY());
                sourceObj.addProperty("z", source.getZ());
                sourceObj.addProperty("linked", entry.getValue());
                sources.add(sourceObj);
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
}
