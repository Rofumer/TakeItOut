package net.maxbel.takeitout.client;

import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.util.collection.DefaultedList;

public class ItemStackInventory
extends SimpleInventory {
    protected final ItemStack itemStack;
    protected final int SIZE;

    public ItemStackInventory(ItemStack stack, int SIZE) {
        super(getStacks(stack, SIZE).toArray(new ItemStack[0]));
        this.itemStack = stack;
        this.SIZE = SIZE;
    }

    public static DefaultedList<ItemStack> getStacks(ItemStack usedStack, int size) {
        DefaultedList<ItemStack> itemStacks = DefaultedList.ofSize(size, ItemStack.EMPTY);

        NbtCompound blockEntityTag = BlockItem.getBlockEntityNbt(usedStack);
        if (blockEntityTag != null && blockEntityTag.contains("Items", NbtElement.LIST_TYPE)) {
            Inventories.readNbt(blockEntityTag, itemStacks);
        }

        return itemStacks;
    }

    public static ItemStackInventory getInventoryFromShulker(ItemStack stack) {

        return new ItemStackInventory(stack, 27);
    }

}
