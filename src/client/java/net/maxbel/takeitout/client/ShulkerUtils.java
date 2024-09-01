/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.block.Block
 *  net.minecraft.block.ShulkerBoxBlock
 *  net.minecraft.entity.player.PlayerEntity
 *  net.minecraft.inventory.Inventory
 *  net.minecraft.inventory.SimpleInventory
 *  net.minecraft.item.BlockItem
 *  net.minecraft.item.Item
 *  net.minecraft.item.ItemStack
 */
package net.maxbel.takeitout.client;

import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public class ShulkerUtils {
    public static boolean isShulkerItem(ItemStack item) {
        return Block.getBlockFromItem((Item)item.getItem()) instanceof ShulkerBoxBlock;
    }

    public static boolean shulkerContainsAny(Inventory shulkerInv, ItemStack stack) {
        for (int i = 0; i < shulkerInv.size(); ++i) {
            if (!shulkerInv.getStack(i).getItem().equals(stack.getItem())) continue;
            return true;
        }
        return false;
    }

    public static ItemStack insertIntoShulker(SimpleInventory shulkerInv, ItemStack stack, PlayerEntity player) {
        if (ShulkerUtils.isShulkerItem(stack) || !shulkerInv.canInsert(stack)) {
            return stack;
        }
        ItemStack output = shulkerInv.addStack(stack);
        shulkerInv.onClose(player);
        return output;
    }

    public static ItemStackInventory getInventoryFromShulker(ItemStack stack) {

        return new ItemStackInventory(stack, 27);
    }
}

