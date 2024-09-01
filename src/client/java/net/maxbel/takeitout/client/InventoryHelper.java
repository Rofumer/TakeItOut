package net.maxbel.takeitout.client;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

public class InventoryHelper {
    public static boolean insertStack(Inventory fromInventory, int fromSlot, Inventory toInventory, int ignoreSlot) {
        if (InventoryHelper.attemptCombine(fromInventory, fromSlot, toInventory, ignoreSlot)) {
            return true;
        }
        for (int i = 0; i < InventoryHelper.getSize(toInventory); ++i) {
            ItemStack invStack;
            if (i == ignoreSlot || !(invStack = toInventory.getStack(i)).isEmpty()) continue;
            toInventory.setStack(i, fromInventory.removeStack(fromSlot));
            return true;
        }
        return false;
    }


    private static boolean isStackFull(ItemStack stack) {
        return stack.getCount() >= stack.getMaxCount();
    }

    private static boolean attemptCombine(Inventory fromInventory, int fromSlot, Inventory toInventory, int ignoreSlot) {
        ItemStack insertStack = fromInventory.getStack(fromSlot);
        if (!InventoryHelper.isStackFull(insertStack)) {
            for (int i = 0; i < InventoryHelper.getSize(toInventory); ++i) {
                ItemStack invStack;
                if (i == ignoreSlot || InventoryHelper.isStackFull(invStack = toInventory.getStack(i)) || !Util.areItemsEqual(invStack, insertStack)) continue;
                InventoryHelper.combineStacks(invStack, insertStack);
                fromInventory.setStack(fromSlot, insertStack);
                toInventory.setStack(i, invStack);
                if (insertStack.getCount() != 0) continue;
                return true;
            }
        }
        return false;
    }

    public static int getSize(Inventory inventory) {
        if (inventory instanceof PlayerInventory) {
            return Math.min(36, inventory.size());
        }
        return inventory.size();
    }

    private static int combineStacks(ItemStack stack, ItemStack stack2) {
        int maxInsertAmount = Math.min(stack.getMaxCount() - stack.getCount(), stack2.getCount());
        stack.increment(maxInsertAmount);
        stack2.increment(-maxInsertAmount);
        return maxInsertAmount;
    }
}
