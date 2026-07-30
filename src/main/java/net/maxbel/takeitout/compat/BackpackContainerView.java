package net.maxbel.takeitout.compat;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;

/**
 * Adapts a Sophisticated Backpacks {@link InventoryHandler} to vanilla's {@link Container}
 * so it can flow through the existing world-container request/insert logic unchanged.
 */
final class BackpackContainerView implements Container {
    private final InventoryHandler handler;

    BackpackContainerView(InventoryHandler handler) {
        this.handler = handler;
    }

    @Override
    public int getContainerSize() {
        return handler.size();
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < handler.size(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return handler.getStackInSlot(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack current = handler.getStackInSlot(slot);
        if (current.isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = current.split(amount);
        handler.setStackInSlot(slot, current.isEmpty() ? ItemStack.EMPTY : current);
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = handler.getStackInSlot(slot);
        handler.setStackInSlot(slot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        handler.setStackInSlot(slot, stack);
    }

    @Override
    public void setChanged() {
        handler.saveInventory();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < handler.size(); i++) {
            handler.setStackInSlot(i, ItemStack.EMPTY);
        }
    }
}
