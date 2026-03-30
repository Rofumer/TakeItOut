package net.maxbel.takeitout.client;

import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public class Util {

    public static int getShulkerWithStack(Inventory playerInventory, ItemStack stackReference) {
        Player player = playerInventory.player;

        for (int i = 0; i < getSize(playerInventory); ++i) {
            ItemStack item = playerInventory.getItem(i);

            // не шалкер — пропускаем
            if (!isShulkerItem(item)) {
                continue;
            }

            // стаканый шалкер
            if (item.getCount() != 1) {
                player.displayClientMessage(
                        Component.translatable("takeitout.msg.stacked_shulker_skipped", i)
                                .withStyle(ChatFormatting.YELLOW),
                        true
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

            } catch (Throwable t) {
                player.displayClientMessage(
                        Component.literal("takeitout.msg.shulker_read_fail")
                                .withStyle(ChatFormatting.RED),
                        true
                );
            }
        }
        return -1;
    }

    public static boolean isShulkerItem(ItemStack item) {
        return item.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    public static int getSlotWithNoShulker(Inventory inventory) {
        for (int i = getSize(inventory) - 1; i >= 0; --i) {
            if (isShulkerItem(inventory.getItem(i))) return i;
        }
        return -1;
    }

    public static int getSlotWithStack(net.minecraft.world.Container inventory, ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack current = inventory.getItem(i);

            if (current.isEmpty()) continue;
            if (!areItemsEqual(stack, current)) continue;

            return i;
        }
        return -1;
    }

    public static boolean areItemsEqual(ItemStack stack1, ItemStack stack2) {
        return stack1.is(stack2.getItem()) && ItemStack.isSameItem(stack1, stack2);
    }

    public static int getSize(net.minecraft.world.Container inventory) {
        if (inventory instanceof Inventory) {
            return Math.min(36, inventory.getContainerSize());
        }
        return inventory.getContainerSize();
    }
}