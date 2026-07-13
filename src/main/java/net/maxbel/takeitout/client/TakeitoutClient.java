package net.maxbel.takeitout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
//import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
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
    public static final int DEFAULT_CONTAINER_SOURCE_OUTLINE_COLOR = 0xFF22C55E;

    private static KeyMapping openSettingsKeyBinding;
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
    private static ClientLevel lastSourceWorld;

    // id категории

    // сама категория

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

        NeoForge.EVENT_BUS.addListener(TakeitoutClient::onClientTick);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("takeitout", "takeitout")
        );

        openSettingsKeyBinding = new KeyMapping(
                "key.takeitout.open_settings",
                com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                category
        );

        event.register(openSettingsKeyBinding);
    }

    public static void handleWorldContainerStackResponse(Takeitout.WorldContainerStackResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        WorldContainerSources.recordResponse(payload.stack(), payload.success());
        if (!payload.success()
                && !awaitingStack.isEmpty()
                && awaitingStack.is(payload.stack().getItem())) {
            awaitingStack = ItemStack.EMPTY;
            awaitingStackTicks = 0;
            if (mc.player != null) {
                mc.player.sendOverlayMessage(
                        Component.translatable("message.takeitout.item_not_found", payload.stack().getDisplayName())
                );
            }
        }
    }

    public static void handleWorldContainerItems(Takeitout.WorldContainerItemsPayload payload) {
        WORLD_CONTAINER_ITEMS.clear();
        WORLD_CONTAINER_ITEMS_BY_SOURCE.clear();
        WORLD_CONTAINER_ITEMS.addAll(payload.items());
        for (Takeitout.WorldContainerContents container : payload.containers()) {
            WORLD_CONTAINER_ITEMS_BY_SOURCE.put(
                    WorldContainerSources.sourceKey(container.source()),
                    new ArrayList<>(container.items())
            );
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

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();

        if (client.level != lastSourceWorld) {
            WorldContainerSources.updateContext(client);
            WorldContainerDumps.updateContext(client);
            lastSourceWorld = client.level;
            awaitingStack = ItemStack.EMPTY;
            awaitingStackTicks = 0;
            TakeItOutHotkeys.clearBoxSelection();
            if (client.level == null) {
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

        while (openSettingsKeyBinding.consumeClick()) {
            if (client.screen == null) {
                client.setScreen(new TakeItOutSettingsScreen(null));
            }
        }
    }

    public static void sendToServer(CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundCustomPayloadPacket(payload));
        }
    }

    public static void toggleAutoTakeout(Minecraft client) {
        AUTOTAKEOUT = !AUTOTAKEOUT;
        awaitingStack = ItemStack.EMPTY;
        saveSettings();

        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.translatable(AUTOTAKEOUT ? "message.takeitout.on" : "message.takeitout.off")
            );
        }
    }

    public static void toggleSingleItemMode(Minecraft client) {
        TAKE_SINGLE_ITEM_MODE = !TAKE_SINGLE_ITEM_MODE;
        saveSettings();

        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.translatable(
                            TAKE_SINGLE_ITEM_MODE
                                    ? "message.takeitout.single_item_mode.on"
                                    : "message.takeitout.single_item_mode.off"
                    )
            );
        }
    }

    public static void toggleContainerSourceRender(Minecraft client) {
        RENDER_CONTAINER_SOURCES = !RENDER_CONTAINER_SOURCES;
        saveSettings();

        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.translatable(
                            RENDER_CONTAINER_SOURCES
                                    ? "message.takeitout.container_source_render.on"
                                    : "message.takeitout.container_source_render.off"
                    )
            );
        }
    }

    public static void dumpNow(Minecraft client) {
        if (client.player == null) {
            return;
        }

        List<Takeitout.WorldContainerSource> dumps = WorldContainerDumps.getDumpReferencesSnapshot();
        if (dumps.isEmpty()) {
            client.player.displayClientMessage(Component.literal("TakeItOut: no dump containers marked"), true);
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

    public static int getSlotWithItem(LocalPlayer player, Item item) {
        Inventory inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            if (inventory.getItem(i).is(item)) return i;
            if (!inventory.getItem(i).isEmpty() && ItemStack.isSameItem(inventory.getItem(i), item.getDefaultInstance())) {
                return i;
            }
        }

        return -1;
    }

    public static boolean onGameTick() {

        if (!ModList.get().isLoaded("litematica")) {
            return false;
        }

        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        if (worldSchematic == null) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }

        Abilities abilities = mc.player.getAbilities();
        if (!abilities.mayBuild) {
            return false;
        }

        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 3, true, true);
        if (result == null) {
            return false;
        }

        SchematicBlockState state = new SchematicBlockState(
                mc.player.level(),
                worldSchematic,
                result.getBlockPos()
        );

        if (state.targetState != null
                && !state.targetState.isAir()
                && (state.currentState == null || state.currentState.canBeReplaced())
                && !state.targetState.equals(state.currentState)) {
            if (getSlotWithItem(mc.player, state.targetState.getBlock().asItem()) == -1) {
                WorldUtils.doSchematicWorldPickBlock(true, mc);
                return true;
            }

            try {
                Class.forName("me.aleksilassila.litematica.printer.Printer");
                return false;
            } catch (ClassNotFoundException e) {
                //return false;
            }

            //System.out.println("CommonMixin");

            WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
            if (worldSchematic == null) return false;
            Minecraft mc = Minecraft.getInstance();
            Abilities abilities = mc.player.getAbilities();
            if (!abilities.mayBuild)
                return false;
            BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 3, true, true);
            if (result != null) {
                if (result.getBlockPos() != null) {
                    SchematicBlockState state = new SchematicBlockState(mc.player.level(), worldSchematic, result.getBlockPos());
                    if (state.currentState != null && state.targetState.equals(state.currentState)) {
                        return false;
                    }
                    if (!state.targetState.isAir()
                            && (state.currentState == null || state.currentState.canBeReplaced())) {
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
