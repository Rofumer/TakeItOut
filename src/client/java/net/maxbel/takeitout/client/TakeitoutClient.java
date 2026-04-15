package net.maxbel.takeitout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
//import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
//import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.Identifier;
//import fi.dy.masa.litematica.world.SchematicWorldHandler;
//import fi.dy.masa.litematica.world.WorldSchematic;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class TakeitoutClient implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SETTINGS_PATH = FabricLoader.getInstance().getConfigDir().resolve("takeitout-client.json");

    private static KeyBinding autoTakeoutKeyBinding;
    private static KeyBinding singleItemModeKeyBinding;
    public static boolean AUTOTAKEOUT;
    public static boolean TAKE_SINGLE_ITEM_MODE;
    public static ItemStack awaitingStack;

    // id категории
    public static final Identifier CATEGORY_ID = Identifier.of("takeitout", "key_category");

    // сама категория
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(CATEGORY_ID);

    @Override
    public void onInitializeClient() {

        AUTOTAKEOUT = false;
        TAKE_SINGLE_ITEM_MODE = false;
        awaitingStack = ItemStack.EMPTY;
        loadSettings();
        autoTakeoutKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.takeitout.toggle", // The translation key of the keybinding's name
                InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
                GLFW.GLFW_KEY_R, // The keycode of the key
                CATEGORY // The translation key of the keybinding's category.
        ));
        singleItemModeKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.takeitout.single_item_toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                CATEGORY
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && !awaitingStack.isEmpty()) {
                if (getSlotWithItem(client.player, awaitingStack.getItem()) != -1) {
                    awaitingStack = ItemStack.EMPTY;
                }
            }

            while (autoTakeoutKeyBinding.wasPressed()) {
                if (AUTOTAKEOUT) {
                    //client.player.sendMessage(Text.literal("Auto Take Out is OFF"), false);
                    client.player.sendMessage(Text.translatable("message.takeitout.off"), false);
                    AUTOTAKEOUT = false;
                    awaitingStack = ItemStack.EMPTY;
                    saveSettings();
                } else {
                    //client.player.sendMessage(Text.literal("Auto Take Out is ON"), false);
                    client.player.sendMessage(Text.translatable("message.takeitout.on"), false);
                    AUTOTAKEOUT = true;
                    awaitingStack = ItemStack.EMPTY;
                    saveSettings();
                }
            }

            while (singleItemModeKeyBinding.wasPressed()) {
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
        });
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
        } catch (Exception ignored) {
        }
    }

    private static void saveSettings() {
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("autotakeout", AUTOTAKEOUT);
            obj.addProperty("single_item_mode", TAKE_SINGLE_ITEM_MODE);
            Files.writeString(SETTINGS_PATH, GSON.toJson(obj));
        } catch (IOException ignored) {
        }
    }
}
