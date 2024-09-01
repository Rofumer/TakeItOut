package net.maxbel.takeitout.client;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

public class Util {
    public static int getShulkerWithStack(PlayerInventory playerInventory, ItemStack stack) {
        for (int i = 0; i < InventoryHelper.getSize(playerInventory); ++i) {
            ItemStack item = playerInventory.getStack(i);

            if (isShulkerItem(item) && getSlotWithStack(ShulkerUtils.getInventoryFromShulker(item), stack) > -1) {
                return i;
            }
        }
        return -1;
    }

    public static int getHotBarSlot(PlayerInventory inventory, int shulkerSlot) {
        int i;
        for (i = 0; i < 9; ++i) {
            if (!inventory.getStack(i).isEmpty()) continue;
            return i;
        }
        if (inventory.selectedSlot != shulkerSlot) {
            return inventory.selectedSlot;
        }
        for (i = 0; i < 9; ++i) {
            if (inventory.getStack(i).hasEnchantments() || Util.isShulkerItem(inventory.getStack(i))) continue;
            return i;
        }
        if (shulkerSlot == inventory.selectedSlot) {
            return inventory.selectedSlot == 8 ? 0 : inventory.selectedSlot + 1;
        }
        return inventory.selectedSlot;
    }

    public static boolean isShulkerItem(ItemStack item) {
        return item.getItem() instanceof BlockItem && ((BlockItem)item.getItem()).getBlock() instanceof ShulkerBoxBlock;
    }

    public static int getSlotWithStack(Inventory inventory, ItemStack stack) {

        for (int i = 0; i < InventoryHelper.getSize(inventory); ++i) {
            if(inventory.getStack(i) == null) continue;
            if (inventory.getStack(i).isEmpty() || !Util.areItemsEqual(stack, inventory.getStack(i))) continue;
            return i;
        }
        return -1;
    }

    public static boolean areItemsEqual(ItemStack stack1, ItemStack stack2) {
        return stack1.getItem() == stack2.getItem() && ItemStack.areItemsEqual((ItemStack)stack1, (ItemStack)stack2);
    }
}

