package net.maxbel.takeitout.client;

import java.util.Arrays;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

public class ItemStackInventory extends SimpleContainer {
    protected final ItemStack itemStack;
    protected final int size;

    public ItemStackInventory(ItemStack stack, int size) {
        super(getStacks(stack, size));
        this.itemStack = stack;
        this.size = size;
    }

    private static ItemStack[] getStacks(ItemStack usedStack, int size) {
        ItemStack[] stacks = new ItemStack[size];
        Arrays.fill(stacks, ItemStack.EMPTY);

        if (usedStack == null || usedStack.isEmpty()) {
            return stacks;
        }

        ItemContainerContents contents = usedStack.get(DataComponents.CONTAINER);
        if (contents != null) {
            NonNullList<ItemStack> temp = NonNullList.withSize(size, ItemStack.EMPTY);
            contents.copyInto(temp);

            for (int i = 0; i < temp.size() && i < size; i++) {
                stacks[i] = temp.get(i);
            }
        }

        return stacks;
    }

    public static NonNullList<ItemStack> getStacks(ItemStack usedStack) {
        NonNullList<ItemStack> itemStacks = NonNullList.withSize(27, ItemStack.EMPTY);

        if (usedStack == null || usedStack.isEmpty()) {
            return itemStacks;
        }

        ItemContainerContents contents = usedStack.get(DataComponents.CONTAINER);
        if (contents != null) {
            contents.copyInto(itemStacks);
        }

        return itemStacks;
    }

    public static ItemStackInventory getInventoryFromShulker(ItemStack stack) {
        return new ItemStackInventory(stack, 27);
    }
}