package net.maxbel.takeitout.client;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;

public class ItemStackInventory
extends SimpleInventory {
    protected final ItemStack itemStack;
    protected final int SIZE;

    public ItemStackInventory(ItemStack stack, int SIZE) {
        super((ItemStack[])ItemStackInventory.getStacks(stack).toArray((Object[])new ItemStack[SIZE]));
        this.itemStack = stack;
        this.SIZE = SIZE;
    }

    public static DefaultedList<Object> getStacks(ItemStack usedStack) {
        DefaultedList<Object> itemStacks = DefaultedList.of();
        itemStacks.addAll(usedStack.get(DataComponentTypes.CONTAINER).stream().toList());
        return itemStacks;
    }

    public static ItemStackInventory getInventoryFromShulker(ItemStack stack) {

        return new ItemStackInventory(stack, 27);
    }

}

