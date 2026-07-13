package net.maxbel.takeitout.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.NonNullList;

public class ItemStackInventory
extends SimpleContainer {
    protected final ItemStack itemStack;
    protected final int SIZE;

    public ItemStackInventory(ItemStack stack, int SIZE) {
        super(ItemStackInventory.getStacks(stack).toArray(new ItemStack[SIZE]));
        this.itemStack = stack;
        this.SIZE = SIZE;
    }

    public static NonNullList<ItemStack> getStacks(ItemStack usedStack) {
        NonNullList<ItemStack> itemStacks = NonNullList.create();
        itemStacks.addAll(usedStack.get(DataComponents.CONTAINER).stream().toList());
        return itemStacks;
    }

    public static ItemStackInventory getInventoryFromShulker(ItemStack stack) {

        return new ItemStackInventory(stack, 27);
    }

}
