package net.maxbel.takeitout.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public class Util {

    public static int getShulkerWithStack(Inventory playerInventory, ItemStack stackReference) {
        for (int i = 0; i < getSize(playerInventory); ++i) {
            ItemStack item = playerInventory.getItem(i);

            // не шалкер — пропускаем
            if (!isShulkerItem(item)) {
                continue;
            }

            // стаканый шалкер (Carpet/Carpet Extra)
            // если такой шалкер пустой — не блокируем основной pipeline,
            // иначе пропускаем и сообщаем в action bar
            if (item.getCount() != 1) {
                if (isShulkerEmpty(item)) {
                    continue;
                }
                playerInventory.player.displayClientMessage(
                        //Component.literal("Action prevented (Take It Out Mod): Stacked shulkers").withStyle(ChatFormatting.YELLOW),
                        Component.translatable("takeitout.msg.stacked_shulker_skipped", i).withStyle(ChatFormatting.YELLOW),
                        true // action bar
                );
                continue;
            }

            // просто пробуем прочитать содержимое шалкера — без hasNbt() (API 1.21.x)
            try {
                int innerSlot = getSlotWithStack(ItemStackInventory.getInventoryFromShulker(item), stackReference);
                if (innerSlot > -1) {
                    return i;
                }
            } catch (Throwable t) {
                // лог при желании
                // LOGGER.debug("Failed to read shulker inventory at slot {}", i, t);

                // и сообщение игроку в action bar
                playerInventory.player.displayClientMessage(
                        Component.literal("takeitout.msg.shulker_read_fail").withStyle(ChatFormatting.RED),
                        //Component.translatable("takeitout.msg.shulker_read_fail", i).withStyle(ChatFormatting.RED),
                        true
                );
            }
        }
        return -1;
    }


    public static boolean isShulkerItem(ItemStack item) {
        return item.getItem() instanceof BlockItem && ((BlockItem)item.getItem()).getBlock() instanceof ShulkerBoxBlock;
    }

    public static boolean isShulkerEmpty(ItemStack shulkerStack) {
        ItemContainerContents container = shulkerStack.get(DataComponents.CONTAINER);
        if (container == null) {
            return true;
        }
        for (ItemStack innerStack : container.stream().toList()) {
            if (!innerStack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static int getSlotWithNoShulker(Container inventory) {
        for (int i = getSize(inventory)-1; i >= 0; --i) {
            if(isShulkerItem(inventory.getItem(i))) return i;
        }
        return -1;
    }

    public static int getSlotWithStack(Container inventory, ItemStack stack) {
        for (int i = 0; i < getSize(inventory); ++i) {
            if(inventory.getItem(i) == null) continue;
            if (inventory.getItem(i).isEmpty() || !Util.areItemsEqual(stack, inventory.getItem(i))) continue;
            return i;
        }
        return -1;
    }

    public static boolean areItemsEqual(ItemStack stack1, ItemStack stack2) {
        return stack1.getItem() == stack2.getItem() && ItemStack.isSameItem((ItemStack)stack1, (ItemStack)stack2);
    }

    public static int getSize(Container inventory) {
        if (inventory instanceof Inventory) {
            return Math.min(36, inventory.getContainerSize());
        }
        return inventory.getContainerSize();
    }
}
