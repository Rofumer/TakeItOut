package net.maxbel.takeitout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TakeitoutClient {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SETTINGS_PATH = FMLPaths.CONFIGDIR.get().resolve("takeitout-client.json");
    private static final String CATEGORY = "key.categories.takeitout";
    public static final int DEFAULT_CONTAINER_SOURCE_OUTLINE_COLOR = 0xFF22C55E;

    private static KeyBinding openSettingsKeyBinding;
    public static boolean AUTOTAKEOUT;
    public static boolean TAKE_SINGLE_ITEM_MODE;
    public static boolean RENDER_CONTAINER_SOURCES;
    public static int SERVER_SCAN_LIMIT = -1;
    public static ItemSortMode ITEM_SORT_MODE;
    public static int CONTAINER_SOURCE_OUTLINE_COLOR;
    public static ItemStack awaitingStack;
    public static final List<Takeitout.WorldContainerItemCount> WORLD_CONTAINER_ITEMS = new ArrayList<>();
    public static final Map<String, List<Takeitout.WorldContainerItemCount>> WORLD_CONTAINER_ITEMS_BY_SOURCE = new LinkedHashMap<>();
    private static int awaitingStackTicks;
    private static ClientWorld lastSourceWorld;

    public static void init(IEventBus modEventBus) {
        AUTOTAKEOUT = false;
        TAKE_SINGLE_ITEM_MODE = false;
        RENDER_CONTAINER_SOURCES = true;
        ITEM_SORT_MODE = ItemSortMode.NAME;
        CONTAINER_SOURCE_OUTLINE_COLOR = DEFAULT_CONTAINER_SOURCE_OUTLINE_COLOR;
        awaitingStack = ItemStack.EMPTY;
        awaitingStackTicks = 0;
        loadSettings();
        TakeItOutConfigs.CONTAINER_SOURCE_OUTLINE_COLOR.setIntegerValue(CONTAINER_SOURCE_OUTLINE_COLOR);
        TakeItOutConfigs.initCallbacks();
        TakeItOutConfigHandler.INSTANCE.load();
        TakeItOutHotkeys.initCallbacks();
        ConfigManager.getInstance().registerConfigHandler("takeitout", TakeItOutConfigHandler.INSTANCE);
        InputEventHandler.getKeybindManager().registerKeybindProvider(TakeItOutInputHandler.getInstance());

        modEventBus.addListener(TakeitoutClient::registerKeyMappings);

        WorldContainerSourceRenderer.register();

        MinecraftForge.EVENT_BUS.register(TakeitoutClient.class);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        openSettingsKeyBinding = new KeyBinding(
                "key.takeitout.open_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY
        );
        event.register(openSettingsKeyBinding);
    }

    public static void handleWorldContainerStackResponse(Takeitout.WorldContainerStackResponsePayload payload) {
        MinecraftClient client = MinecraftClient.getInstance();
        WorldContainerSources.recordResponse(payload.stack(), payload.success());
        if (!payload.success()
                && !awaitingStack.isEmpty()
                && awaitingStack.isOf(payload.stack().getItem())) {
            awaitingStack = ItemStack.EMPTY;
            awaitingStackTicks = 0;
            if (client.player != null) {
                client.player.sendMessage(
                        Text.translatable("message.takeitout.item_not_found", payload.stack().getName()),
                        true
                );
            }
        }
    }

    public static void handleWorldContainerItems(Takeitout.WorldContainerItemsPayload payload) {
        WORLD_CONTAINER_ITEMS.clear();
        WORLD_CONTAINER_ITEMS_BY_SOURCE.clear();
        WORLD_CONTAINER_ITEMS.addAll(payload.items());
        for (Takeitout.WorldContainerContents container : payload.containers()) {
            WORLD_CONTAINER_ITEMS_BY_SOURCE.put(WorldContainerSources.sourceKey(container.source()), new ArrayList<>(container.items()));
        }
        if (ModList.get().isLoaded("litematica")) {
            WorldContainerMaterialListCache.handleItemsPayload(payload);
        }
    }

    public static void handleServerConfigSync(Takeitout.ServerConfigSyncPayload payload) {
        SERVER_SCAN_LIMIT = payload.linkedContainerScanLimit();
    }

    public static void handleSharedGroupsList(Takeitout.SharedGroupsListPayload payload) {
        SharedGroupsClient.SHARED_GROUPS.clear();
        SharedGroupsClient.SHARED_GROUPS.addAll(payload.groups());
        SharedGroupsClient.serverSupportsSharedGroups = true;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world != lastSourceWorld) {
            WorldContainerSources.updateContext(client);
            WorldContainerDumps.updateContext(client);
            lastSourceWorld = client.world;
            TakeItOutHotkeys.clearBoxSelection();
            if (client.world == null) {
                SharedGroupsClient.clear();
            }
        }

        if (client.player != null && !awaitingStack.isEmpty()) {
            if (getSlotWithItem(client.player, awaitingStack.getItem()) != -1) {
                awaitingStack = ItemStack.EMPTY;
                awaitingStackTicks = 0;
            } else if (++awaitingStackTicks > 70) {
                awaitingStack = ItemStack.EMPTY;
                awaitingStackTicks = 0;
            }
        } else {
            awaitingStackTicks = 0;
        }

        while (openSettingsKeyBinding.wasPressed()) {
            if (client.currentScreen == null) {
                client.setScreen(new TakeItOutSettingsScreen(null));
            }
        }
    }

    public static void sendToServer(Takeitout.Payload payload) {
        Takeitout.CHANNEL.sendToServer(payload);
    }

    public static void toggleAutoTakeout(MinecraftClient client) {
        AUTOTAKEOUT = !AUTOTAKEOUT;
        awaitingStack = ItemStack.EMPTY;
        saveSettings();

        if (client.player != null) {
            client.player.sendMessage(
                    Text.translatable(AUTOTAKEOUT ? "message.takeitout.on" : "message.takeitout.off"),
                    false
            );
        }
    }

    public static void toggleSingleItemMode(MinecraftClient client) {
        TAKE_SINGLE_ITEM_MODE = !TAKE_SINGLE_ITEM_MODE;
        saveSettings();

        if (client.player != null) {
            client.player.sendMessage(
                    Text.translatable(
                            TAKE_SINGLE_ITEM_MODE
                                    ? "message.takeitout.single_item_mode.on"
                                    : "message.takeitout.single_item_mode.off"
                    ),
                    false
            );
        }
    }

    public static void toggleContainerSourceRender(MinecraftClient client) {
        RENDER_CONTAINER_SOURCES = !RENDER_CONTAINER_SOURCES;
        saveSettings();

        if (client.player != null) {
            client.player.sendMessage(
                    Text.translatable(
                            RENDER_CONTAINER_SOURCES
                                    ? "message.takeitout.container_source_render.on"
                                    : "message.takeitout.container_source_render.off"
                    ),
                    false
            );
        }
    }

    public static void dumpNow(MinecraftClient client) {
        if (client.player == null) {
            return;
        }

        List<Takeitout.WorldContainerSource> dumps = WorldContainerDumps.getDumpReferencesSnapshot();
        if (dumps.isEmpty()) {
            client.player.sendMessage(Text.literal("TakeItOut: no dump containers marked"), true);
            return;
        }

        sendToServer(new Takeitout.DumpInventoryPayload(dumps));
    }

    public static void setContainerSourceOutlineColor(int color) {
        applyContainerSourceOutlineColor(color);
        saveSettings();
    }

    public static void cycleItemSortMode() {
        ITEM_SORT_MODE = ITEM_SORT_MODE == ItemSortMode.NAME ? ItemSortMode.COUNT : ItemSortMode.NAME;
        saveSettings();
    }

    static void applyContainerSourceOutlineColor(int color) {
        CONTAINER_SOURCE_OUTLINE_COLOR = 0xFF000000 | (color & 0x00FFFFFF);
    }

    public static int getSlotWithItem(ClientPlayerEntity player, Item item) {
        PlayerInventory inventory = player.getInventory();

        for (int i = 0; i < inventory.size(); ++i) {
            if (inventory.getStack(i).isOf(item)) return i;
            if (!inventory.getStack(i).isEmpty() && ItemStack.areItemsEqual(inventory.getStack(i), item.getDefaultStack())) {
                return i;
            }
        }

        return -1;
    }

    public static boolean onGameTick() {

        if (AUTOTAKEOUT && awaitingStack.isEmpty()) {

            if (!ModList.get().isLoaded("litematica")) {
                return false;
            }

            if (ModList.get().isLoaded("forgematica_printer")) {
                return false;
            }

            WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
            if (worldSchematic == null) return false;
            MinecraftClient mc = MinecraftClient.getInstance();
            PlayerAbilities abilities = mc.player.getAbilities();
            if (!abilities.allowModifyWorld)
                return false;
            BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 3, true, true);
            if (result != null) {
                if (result.getBlockPos() != null) {
                    SchematicBlockState state = new SchematicBlockState(mc.player.getEntityWorld(), worldSchematic, result.getBlockPos());
                    if (state.currentState != null && state.targetState.equals(state.currentState)) {
                        return false;
                    }
                    if (!state.targetState.isAir()
                            && (state.currentState == null || state.currentState.isReplaceable())) {
                        if (getSlotWithItem(mc.player, state.targetState.getBlock().asItem()) == -1) {
                            WorldUtils.doSchematicWorldPickBlock(true, mc);
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        return false;
    }

    private static void loadSettings() {
        if (!Files.exists(SETTINGS_PATH)) {
            return;
        }

        try {
            JsonObject obj = GSON.fromJson(Files.readString(SETTINGS_PATH), JsonObject.class);
            if (obj == null) {
                return;
            }

            if (obj.has("autotakeout")) {
                AUTOTAKEOUT = obj.get("autotakeout").getAsBoolean();
            }
            if (obj.has("single_item_mode")) {
                TAKE_SINGLE_ITEM_MODE = obj.get("single_item_mode").getAsBoolean();
            }
            if (obj.has("render_container_sources")) {
                RENDER_CONTAINER_SOURCES = obj.get("render_container_sources").getAsBoolean();
            }
            if (obj.has("item_sort_mode")) {
                ITEM_SORT_MODE = ItemSortMode.fromString(obj.get("item_sort_mode").getAsString());
            }
            if (obj.has("container_source_outline_color")) {
                CONTAINER_SOURCE_OUTLINE_COLOR = parseColor(obj.get("container_source_outline_color").getAsString());
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveSettings() {
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("autotakeout", AUTOTAKEOUT);
            obj.addProperty("single_item_mode", TAKE_SINGLE_ITEM_MODE);
            obj.addProperty("render_container_sources", RENDER_CONTAINER_SOURCES);
            obj.addProperty("item_sort_mode", ITEM_SORT_MODE.id);
            obj.addProperty("container_source_outline_color", formatColor(CONTAINER_SOURCE_OUTLINE_COLOR));
            Files.writeString(SETTINGS_PATH, GSON.toJson(obj));
        } catch (IOException ignored) {
        }
    }

    private static int parseColor(String raw) {
        if (raw == null) {
            return DEFAULT_CONTAINER_SOURCE_OUTLINE_COLOR;
        }

        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if (value.length() == 6) {
            value = "FF" + value;
        }

        try {
            return (int) Long.parseLong(value, 16);
        } catch (NumberFormatException ignored) {
            return DEFAULT_CONTAINER_SOURCE_OUTLINE_COLOR;
        }
    }

    private static String formatColor(int color) {
        return String.format("#%06X", color & 0x00FFFFFF);
    }

    public enum ItemSortMode {
        NAME("name", "Name"),
        COUNT("count", "Count");

        private final String id;
        private final String label;

        ItemSortMode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static ItemSortMode fromString(String value) {
            if (value != null) {
                for (ItemSortMode mode : values()) {
                    if (mode.id.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                        return mode;
                    }
                }
            }

            return NAME;
        }
    }
}
