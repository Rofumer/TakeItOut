package net.maxbel.takeitout.client;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.inventory.Inventories;

public class ItemStackInventory extends SimpleInventory {
    protected final ItemStack itemStack;
    protected final int SIZE;

    public ItemStackInventory(ItemStack stack, int size) {
        super(getStacks(stack, size).toArray(new ItemStack[0]));
        this.itemStack = stack;
        this.SIZE = size;
    }

    /**
     * Получает содержимое шулькера (или другого контейнера в ItemStack) из NBT.
     */
    public static DefaultedList<ItemStack> getStacks(ItemStack usedStack, int size) {
        DefaultedList<ItemStack> itemStacks = DefaultedList.ofSize(size, ItemStack.EMPTY);

        // Для шулькера содержимое хранится в BlockEntityTag -> Items
        NbtCompound beTag = BlockItem.getBlockEntityNbt(usedStack);
        if (beTag != null && beTag.contains("Items", NbtElement.LIST_TYPE)) {
            Inventories.readNbt(beTag, itemStacks);
        }

        return itemStacks;
    }

    /**
     * Упрощённая версия для вызова без указания размера (27 — стандартный для шулькеров)
     */
    public static DefaultedList<ItemStack> getStacks(ItemStack usedStack) {
        return getStacks(usedStack, 27);
    }

    public static ItemStackInventory getInventoryFromShulker(ItemStack stack) {
        return new ItemStackInventory(stack, 27);
    }
}
