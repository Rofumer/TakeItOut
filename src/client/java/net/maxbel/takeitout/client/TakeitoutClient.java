package net.maxbel.takeitout.client;

import fi.dy.masa.litematica.util.RayTraceUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import org.lwjgl.glfw.GLFW;

public class TakeitoutClient implements ClientModInitializer {

    private static KeyBinding keyBinding;
    public static boolean AUTOTAKEOUT;

    @Override
    public void onInitializeClient() {

        AUTOTAKEOUT = false;

        keyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Toggle auto take out", // The translation key of the keybinding's name
                InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
                GLFW.GLFW_KEY_R, // The keycode of the key
                "TakeItOut" // The translation key of the keybinding's category.
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.wasPressed()) {
                if (AUTOTAKEOUT) {
                    client.player.sendMessage(Text.literal("Auto Take Out is OFF"), false);
                    AUTOTAKEOUT = false;
                } else {
                    client.player.sendMessage(Text.literal("Auto Take Out is ON"), false);
                    AUTOTAKEOUT = true;
                }
            }
        });
    }

    protected static int getSlotWithItem(ClientPlayerEntity player, Item item) {
        PlayerInventory inventory = player.getInventory();

        for (int i = 0; i < inventory.main.size(); ++i) {
            if (inventory.main.get(i).isOf(item)) return i;
            if (!inventory.main.get(i).isEmpty() && ItemStack.areItemsEqual(inventory.main.get(i), item.getDefaultStack())) {
                return i;
            }
        }

        return -1;
    }

    public static boolean onGameTick() {

        if (AUTOTAKEOUT) {

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
                    SchematicBlockState state = new SchematicBlockState(mc.player.getWorld(), worldSchematic, result.getBlockPos());
                    if (!state.targetState.isAir()) {
                        if (getSlotWithItem(mc.player, state.targetState.getBlock().asItem()) == -1) {
                            WorldUtils.doSchematicWorldPickBlock(false, mc);
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        return false;
    }
}