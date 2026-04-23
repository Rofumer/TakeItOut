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
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
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
//import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
//import fi.dy.masa.litematica.world.SchematicWorldHandler;
//import fi.dy.masa.litematica.world.WorldSchematic;
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
    private static final Identifier CATEGORY_ID = Identifier.of("takeitout", "key_category");
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(CATEGORY_ID);
    public static final int DEFAULT_CONTAINER_SOURCE_OUTLINE_COLOR = 0xFF22C55E;

    private static KeyBinding openSettingsKeyBinding;
    public static boolean AUTOTAKEOUT;
    public static boolean TAKE_SINGLE_ITEM_MODE;
    public static boolean RENDER_CONTAINER_SOURCES;
    public static int CONTAINER_SOURCE_OUTLINE_COLOR;
    public static ItemStack awaitingStack;
    public static final List<Takeitout.WorldContainerItemCount> WORLD_CONTAINER_ITEMS = new ArrayList<>();
    public static final Map<Long, List<Takeitout.WorldContainerItemCount>> WORLD_CONTAINER_ITEMS_BY_SOURCE = new LinkedHashMap<>();
    private static int awaitingStackTicks;
    private static ClientWorld lastSourceWorld;

    // id категории

    // сама категория

    @Override
    public void onInitializeClient() {

        AUTOTAKEOUT = false;
        TAKE_SINGLE_ITEM_MODE = false;
        RENDER_CONTAINER_SOURCES = true;
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
        openSettingsKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.takeitout.open_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY
        ));
        WorldContainerSourceRenderer.register();
        ClientPlayNetworking.registerGlobalReceiver(Takeitout.WorldContainerStackResponsePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                WorldContainerSources.recordResponse(payload.stack(), payload.success());
                if (!payload.success()
                        && !awaitingStack.isEmpty()
                        && ItemStack.areItemsAndComponentsEqual(awaitingStack, payload.stack())) {
                    awaitingStack = ItemStack.EMPTY;
                    awaitingStackTicks = 0;
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Takeitout.WorldContainerItemsPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                WORLD_CONTAINER_ITEMS.clear();
                WORLD_CONTAINER_ITEMS_BY_SOURCE.clear();
                WORLD_CONTAINER_ITEMS.addAll(payload.items());
                for (Takeitout.WorldContainerContents container : payload.containers()) {
                    WORLD_CONTAINER_ITEMS_BY_SOURCE.put(container.sourcePosition(), new ArrayList<>(container.items()));
                }
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != lastSourceWorld) {
                WorldContainerSources.updateContext(client);
                lastSourceWorld = client.world;
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
        });
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

    public static void setContainerSourceOutlineColor(int color) {
        applyContainerSourceOutlineColor(color);
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

            try {
                Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            } catch (ClassNotFoundException e) {
                return false;
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
                    if (!state.targetState.isAir()) {
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
}
