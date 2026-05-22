package net.maxbel.takeitout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
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

public class TakeitoutClient implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SETTINGS_PATH = FabricLoader.getInstance().getConfigDir().resolve("takeitout-client.json");
    public static final int DEFAULT_CONTAINER_SOURCE_OUTLINE_COLOR = 0xFF22C55E;

    private static KeyMapping openSettingsKeyBinding;
    public static boolean AUTOTAKEOUT;
    public static boolean TAKE_SINGLE_ITEM_MODE;
    public static boolean SHULKER_SINGLE_ITEM_MODE;
    public static boolean RENDER_CONTAINER_SOURCES;
    public static boolean AUTO_SELECT_HOTBAR_SLOT;
    public static ItemSortMode ITEM_SORT_MODE;
    public static int CONTAINER_SOURCE_OUTLINE_COLOR;
    public static ItemStack awaitingStack;
    public static final List<Takeitout.WorldContainerItemCount> WORLD_CONTAINER_ITEMS = new ArrayList<>();
    public static final Map<String, List<Takeitout.WorldContainerItemCount>> WORLD_CONTAINER_ITEMS_BY_SOURCE = new LinkedHashMap<>();

    private static int awaitingStackTicks;
    private static ClientLevel lastSourceWorld;

    @Override
    public void onInitializeClient() {
        AUTOTAKEOUT = false;
        TAKE_SINGLE_ITEM_MODE = false;
        SHULKER_SINGLE_ITEM_MODE = false;
        RENDER_CONTAINER_SOURCES = true;
        AUTO_SELECT_HOTBAR_SLOT = false;
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

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("takeitout", "key_category")
        );

        openSettingsKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.takeitout.open_settings",
                com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                category
        ));

        WorldContainerSourceRenderer.register();

        ClientPlayNetworking.registerGlobalReceiver(Takeitout.WorldContainerStackResponsePayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    WorldContainerSources.recordResponse(payload.stack(), payload.success());
                    if (!payload.success()
                            && !awaitingStack.isEmpty()
                            && ItemStack.isSameItemSameComponents(awaitingStack, payload.stack())) {
                        awaitingStack = ItemStack.EMPTY;
                        awaitingStackTicks = 0;
                    }
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(Takeitout.WorldContainerItemsPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    WORLD_CONTAINER_ITEMS.clear();
                    WORLD_CONTAINER_ITEMS_BY_SOURCE.clear();
                    WORLD_CONTAINER_ITEMS.addAll(payload.items());
                    for (Takeitout.WorldContainerContents container : payload.containers()) {
                        WORLD_CONTAINER_ITEMS_BY_SOURCE.put(
                                WorldContainerSources.sourceKey(container.source()),
                                new ArrayList<>(container.items())
                        );
                    }
                    if (FabricLoader.getInstance().isModLoaded("litematica")) {
                        WorldContainerMaterialListCache.handleItemsPayload(payload);
                    }
                })
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != lastSourceWorld) {
                WorldContainerSources.updateContext(client);
                lastSourceWorld = client.level;
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
        });
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
        SHULKER_SINGLE_ITEM_MODE = TAKE_SINGLE_ITEM_MODE;
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
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) {
                return i;
            }

            if (!stack.isEmpty() && ItemStack.isSameItem(stack, item.getDefaultInstance())) {
                return i;
            }
        }

        return -1;
    }

    public static boolean onGameTick() {
        if (!AUTOTAKEOUT || !awaitingStack.isEmpty()) {
            return false;
        }

        try {
            Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
        } catch (ClassNotFoundException e) {
            return false;
        }

        try {
            Class.forName("me.aleksilassila.litematica.printer.Printer");
            return false;
        } catch (ClassNotFoundException e) {
            // ignore
        }

        if (FabricLoader.getInstance().isModLoaded("litematica-printer")) {
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
                && (state.currentState == null || !state.targetState.equals(state.currentState))) {
            if (getSlotWithItem(mc.player, state.targetState.getBlock().asItem()) == -1) {
                WorldUtils.doSchematicWorldPickBlock(true, mc);
                return true;
            }
        }

        return false;
    }

    private static void loadSettings() {
        if (!Files.exists(SETTINGS_PATH)) {
            return;
        }

        try {
            JsonObject object = GSON.fromJson(Files.readString(SETTINGS_PATH), JsonObject.class);
            if (object == null) {
                return;
            }

            if (object.has("autotakeout")) {
                AUTOTAKEOUT = object.get("autotakeout").getAsBoolean();
            }
            if (object.has("single_item_mode")) {
                TAKE_SINGLE_ITEM_MODE = object.get("single_item_mode").getAsBoolean();
                SHULKER_SINGLE_ITEM_MODE = TAKE_SINGLE_ITEM_MODE;
            }
            if (object.has("render_container_sources")) {
                RENDER_CONTAINER_SOURCES = object.get("render_container_sources").getAsBoolean();
            }
            if (object.has("auto_select_hotbar_slot")) {
                AUTO_SELECT_HOTBAR_SLOT = object.get("auto_select_hotbar_slot").getAsBoolean();
            }
            if (object.has("item_sort_mode")) {
                ITEM_SORT_MODE = ItemSortMode.fromString(object.get("item_sort_mode").getAsString());
            }
            if (object.has("container_source_outline_color")) {
                CONTAINER_SOURCE_OUTLINE_COLOR = parseColor(object.get("container_source_outline_color").getAsString());
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveSettings() {
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("autotakeout", AUTOTAKEOUT);
            object.addProperty("single_item_mode", TAKE_SINGLE_ITEM_MODE);
            object.addProperty("render_container_sources", RENDER_CONTAINER_SOURCES);
            object.addProperty("auto_select_hotbar_slot", AUTO_SELECT_HOTBAR_SLOT);
            object.addProperty("item_sort_mode", ITEM_SORT_MODE.id);
            object.addProperty("container_source_outline_color", formatColor(CONTAINER_SOURCE_OUTLINE_COLOR));
            Files.writeString(SETTINGS_PATH, GSON.toJson(object));
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
