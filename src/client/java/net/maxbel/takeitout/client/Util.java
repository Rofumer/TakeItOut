package net.maxbel.takeitout.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public class Util {

    public static int getShulkerWithStack(Inventory playerInventory, ItemStack stackReference) {
        Player player = playerInventory.player;

        for (int i = 0; i < getSize(playerInventory); ++i) {
            ItemStack item = playerInventory.getItem(i);

            if (!isShulkerItem(item)) {
                continue;
            }

            if (item.getCount() != 1) {
                player.sendSystemMessage(
                        Component.translatable("takeitout.msg.stacked_shulker_skipped", i)
                                .withStyle(ChatFormatting.YELLOW)
                );
                continue;
            }

            try {
                int innerSlot = getSlotWithStack(
                        ItemStackInventory.getInventoryFromShulker(item),
                        stackReference
                );

                if (innerSlot > -1) {
                    return i;
                }

            } catch (Throwable ignored) {
                player.sendSystemMessage(
                        Component.literal("takeitout.msg.shulker_read_fail")
                                .withStyle(ChatFormatting.RED)
                );
            }
        }

        return -1;
    }

    public static boolean isShulkerItem(ItemStack item) {
        return !item.isEmpty()
                && item.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    public static int getSlotWithNoShulker(Inventory inventory) {
        for (int i = getSize(inventory) - 1; i >= 0; --i) {
            ItemStack item = inventory.getItem(i);
            if (!item.isEmpty() && !isShulkerItem(item)) {
                return i;
            }
        }
        return -1;
    }

    public static int getSlotWithStack(Container inventory, ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack current = inventory.getItem(i);

            if (current.isEmpty()) {
                continue;
            }

            if (!areItemsEqual(stack, current)) {
                continue;
            }

            return i;
        }

        return -1;
    }

    public static boolean areItemsEqual(ItemStack stack1, ItemStack stack2) {
        if (stack1 == null || stack2 == null) {
            return false;
        }

        if (stack1.isEmpty() || stack2.isEmpty()) {
            return false;
        }

        return ItemStack.isSameItem(stack1, stack2);
    }

    public static int getSize(Container inventory) {
        if (inventory instanceof Inventory) {
            return Math.min(36, inventory.getContainerSize());
        }
        return inventory.getContainerSize();
    }
}