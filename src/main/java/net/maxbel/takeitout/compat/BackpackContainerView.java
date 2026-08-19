package net.maxbel.takeitout.compat;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;

/**
 * Adapts a Sophisticated Backpacks {@link InventoryHandler} to vanilla's {@link Inventory}
 * so it can flow through the existing world-container request/insert logic unchanged.
 */
final class BackpackContainerView implements Inventory {
    private final InventoryHandler handler;

    BackpackContainerView(InventoryHandler handler) {
        this.handler = handler;
    }

    @Override
    public int size() {
        return handler.getSlots();
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        return handler.getStackInSlot(slot);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack current = handler.getStackInSlot(slot);
        if (current.isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = current.split(amount);
        handler.setStackInSlot(slot, current.isEmpty() ? ItemStack.EMPTY : current);
        return removed;
    }

    @Override
    public ItemStack removeStack(int slot) {
        ItemStack current = handler.getStackInSlot(slot);
        handler.setStackInSlot(slot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        handler.setStackInSlot(slot, stack);
    }

    @Override
    public void markDirty() {
        handler.saveInventory();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return true;
    }

    @Override
    public void clear() {
        for (int i = 0; i < handler.getSlots(); i++) {
            handler.setStackInSlot(i, ItemStack.EMPTY);
        }
    }
}
