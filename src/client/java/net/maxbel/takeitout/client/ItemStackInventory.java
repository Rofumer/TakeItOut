package net.maxbel.takeitout.client;

import java.util.Arrays;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

public class ItemStackInventory extends SimpleContainer {
    protected final ItemStack itemStack;
    protected final int SIZE;

    public ItemStackInventory(ItemStack stack, int SIZE) {
        super(getStacks(stack, SIZE));
        this.itemStack = stack;
        this.SIZE = SIZE;
    }

    private static ItemStack[] getStacks(ItemStack usedStack, int size) {
        ItemStack[] stacks = new ItemStack[size];
        Arrays.fill(stacks, ItemStack.EMPTY);

        ItemContainerContents contents = usedStack.get(DataComponents.CONTAINER);
        if (contents != null) {
            int i = 0;
            for (ItemStack stack : contents.stream().toList()) {
                if (i >= size) {
                    break;
                }
                stacks[i++] = stack;
            }
        }

        return stacks;
    }

    public static NonNullList<ItemStack> getStacks(ItemStack usedStack) {
        NonNullList<ItemStack> itemStacks = NonNullList.create();

        ItemContainerContents contents = usedStack.get(DataComponents.CONTAINER);
        if (contents != null) {
            itemStacks.addAll(contents.stream().toList());
        }

        return itemStacks;
    }

    public static ItemStackInventory getInventoryFromShulker(ItemStack stack) {
        return new ItemStackInventory(stack, 27);
    }
}