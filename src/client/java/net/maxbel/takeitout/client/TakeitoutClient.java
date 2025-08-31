package net.maxbel.takeitout.client;

//import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
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
//import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
//import fi.dy.masa.litematica.world.SchematicWorldHandler;
//import fi.dy.masa.litematica.world.WorldSchematic;
import org.lwjgl.glfw.GLFW;

public class TakeitoutClient implements ClientModInitializer {

    private static KeyBinding keyBinding;
    public static boolean AUTOTAKEOUT;
    public static ItemStack awaitingStack;

    @Override
    public void onInitializeClient() {

        AUTOTAKEOUT = false;
        awaitingStack = ItemStack.EMPTY;
        keyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.takeitout.toggle", // The translation key of the keybinding's name
                InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
                GLFW.GLFW_KEY_R, // The keycode of the key
                "category.takeitout" // The translation key of the keybinding's category.
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.wasPressed()) {
                if (AUTOTAKEOUT) {
                    client.player.sendMessage(Text.translatable("message.takeitout.off"), false);
                    AUTOTAKEOUT = false;
                    awaitingStack = ItemStack.EMPTY;
                } else {
                    client.player.sendMessage(Text.translatable("message.takeitout.on"), false);
                    AUTOTAKEOUT = true;
                    awaitingStack = ItemStack.EMPTY;
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

        if (AUTOTAKEOUT && awaitingStack == ItemStack.EMPTY) {

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
}