package net.maxbel.takeitout.client;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

public class TakeitoutClient implements ClientModInitializer {

    private static KeyMapping keyBinding;
    public static boolean AUTOTAKEOUT;
    public static ItemStack awaitingStack;

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.INVENTORY;

    @Override
    public void onInitializeClient() {
        AUTOTAKEOUT = false;
        awaitingStack = ItemStack.EMPTY;

        keyBinding = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.takeitout.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyBinding.consumeClick()) {
                if (client.player == null) {
                    continue;
                }

                if (AUTOTAKEOUT) {
                    client.player.displayClientMessage(Component.translatable("message.takeitout.off"), false);
                    AUTOTAKEOUT = false;
                    awaitingStack = ItemStack.EMPTY;
                } else {
                    client.player.displayClientMessage(Component.translatable("message.takeitout.on"), false);
                    AUTOTAKEOUT = true;
                    awaitingStack = ItemStack.EMPTY;
                }
            }
        });
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

        if (!state.targetState.isAir()) {
            if (getSlotWithItem(mc.player, state.targetState.getBlock().asItem()) == -1) {
                WorldUtils.doSchematicWorldPickBlock(true, mc);
                return true;
            }
        }

        return false;
    }
}