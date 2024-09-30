package net.maxbel.takeitout.client;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

public class Util {

    public static int getShulkerWithStack(PlayerInventory playerInventory, ItemStack stack) {
        for (int i = 0; i < getSize(playerInventory); ++i) {
            ItemStack item = playerInventory.getStack(i);
            if (isShulkerItem(item) && getSlotWithStack(ItemStackInventory.getInventoryFromShulker(item), stack) > -1) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isShulkerItem(ItemStack item) {
        return item.getItem() instanceof BlockItem && ((BlockItem)item.getItem()).getBlock() instanceof ShulkerBoxBlock;
    }

    public static int getSlotWithNoShulker(Inventory inventory) {
        for (int i = getSize(inventory)-1; i >= 0; --i) {
            if(isShulkerItem(inventory.getStack(i))) return i;
        }
        return -1;
    }

    public static int getSlotWithStack(Inventory inventory, ItemStack stack) {
        for (int i = 0; i < getSize(inventory); ++i) {
            if(inventory.getStack(i) == null) continue;
            if (inventory.getStack(i).isEmpty() || !Util.areItemsEqual(stack, inventory.getStack(i))) continue;
            return i;
        }
        return -1;
    }

    public static boolean areItemsEqual(ItemStack stack1, ItemStack stack2) {
        return stack1.getItem() == stack2.getItem() && ItemStack.areItemsEqual((ItemStack)stack1, (ItemStack)stack2);
    }

    public static int getSize(Inventory inventory) {
        if (inventory instanceof PlayerInventory) {
            return Math.min(36, inventory.size());
        }
        return inventory.size();
    }
}

