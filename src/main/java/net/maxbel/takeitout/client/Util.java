package net.maxbel.takeitout.client;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class Util {

    public static int getShulkerWithStack(PlayerInventory playerInventory, ItemStack stackReference) {
        for (int i = 0; i < getSize(playerInventory); ++i) {
            ItemStack item = playerInventory.getStack(i);

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
                playerInventory.player.sendMessage(
                        //Text.literal("Action prevented (Take It Out Mod): Stacked shulkers").formatted(Formatting.YELLOW),
                        Text.translatable("takeitout.msg.stacked_shulker_skipped", i).formatted(Formatting.YELLOW),
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
                playerInventory.player.sendMessage(
                        Text.literal("takeitout.msg.shulker_read_fail").formatted(Formatting.RED),
                        //Text.translatable("takeitout.msg.shulker_read_fail", i).formatted(Formatting.RED),
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
        ContainerComponent container = shulkerStack.get(DataComponentTypes.CONTAINER);
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

    public static int getSlotWithNoShulker(Inventory inventory) {
        for (int i = getSize(inventory)-1; i >= 0; --i) {
            if(isShulkerItem(inventory.getStack(i))) return i;
        }
        return -1;
    }

    public static int getSlotWithStack(Inventory inventory, ItemStack stack) {
        for (int i = 0; i < getSize(inventory); ++i) {
            if(inventory.getStack(i) == null) continue;
            if (inventory.getStack(i).isEmpty() || !Util.areItemsEqual(stack, inventory.getStack(i))) continue;
            return i;
        }
        return -1;
    }

    public static boolean areItemsEqual(ItemStack stack1, ItemStack stack2) {
        return stack1.getItem() == stack2.getItem() && ItemStack.areItemsEqual((ItemStack)stack1, (ItemStack)stack2);
    }

    public static int getSize(Inventory inventory) {
        if (inventory instanceof PlayerInventory) {
            return Math.min(36, inventory.size());
        }
        return inventory.size();
    }
}
