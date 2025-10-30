package net.maxbel.takeitout;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import java.util.List;

public class Takeitout implements ModInitializer {

    public static final Identifier GETSTACK = new Identifier("takeitout", "getstack");

    @Override
    public void onInitialize() {
        // Принимаем C2S-пакет: [slotInShulker:int, shulkerSlot:int]
        ServerPlayNetworking.registerGlobalReceiver(GETSTACK, (server, player, handler, buf, responseSender) -> {
            int slotInShulker = buf.readVarInt();
            int shulkerSlot   = buf.readVarInt();

            server.execute(() -> handleRequest(player, slotInShulker, shulkerSlot));
        });
    }

    private static void handleRequest(ServerPlayerEntity player, int slotInShulker, int shulkerSlot) {
        if (shulkerSlot < 0 || shulkerSlot >= player.getInventory().size()) return;

        ItemStack shulkerStack = player.getInventory().getStack(shulkerSlot);
        if (!(shulkerStack.getItem() instanceof BlockItem bi) ||
                !(bi.getBlock() instanceof ShulkerBoxBlock)) {
            return;
        }

        DefaultedList<ItemStack> contents = getShulkerContents(shulkerStack);
        if (slotInShulker < 0 || slotInShulker >= contents.size()) return;

        ItemStack stackInShulker = contents.get(slotInShulker);
        if (stackInShulker == null || stackInShulker.isEmpty()) return;

        // Вариант 1: есть пустой слот — переложим выбранный предмет в руку, а то, что в руке, в инвентарь
        if (player.getInventory().getEmptySlot() != -1) {
            // Сохраняем текущее содержимое руки в свободный слот
            player.getInventory().setStack(player.getInventory().getEmptySlot(), player.getStackInHand(Hand.MAIN_HAND));

            // Забираем предмет из шулькера в руку
            player.setStackInHand(Hand.MAIN_HAND, stackInShulker);

            // Освобождаем ячейку шулькера
            contents.set(slotInShulker, ItemStack.EMPTY);
            setShulkerContents(shulkerStack, contents);
            return;
        }

        // Вариант 2: нет пустых слотов — ищем подходящий предмет в инвентаре, чтобы свапнуть
        for (int i = Math.min(36, player.getInventory().size()) - 1; i >= 0; --i) {
            ItemStack inv = player.getInventory().getStack(i);

            boolean isTool = inv.getItem() instanceof HoeItem
                    || inv.getItem() instanceof AxeItem
                    || inv.getItem() instanceof ShovelItem;

            boolean isForbiddenBlock = (inv.getItem() instanceof BlockItem blockItem)
                    && (blockItem.getBlock() instanceof ShulkerBoxBlock
                    || blockItem.getBlock() instanceof EnderChestBlock);

            if (!isTool && inv.getItem() instanceof BlockItem && !isForbiddenBlock) {
                // Кладем предмет из инвентаря в шулькер
                contents.set(slotInShulker, inv);
                setShulkerContents(shulkerStack, contents);

                // Предмет из шулькера — в руку
                player.getInventory().setStack(i, player.getStackInHand(Hand.MAIN_HAND));
                player.setStackInHand(Hand.MAIN_HAND, stackInShulker);
                break;
            }
        }
    }

    // ==== Shulker NBT helpers for 1.20.1 ====

    private static DefaultedList<ItemStack> getShulkerContents(ItemStack shulkerStack) {
        DefaultedList<ItemStack> items = DefaultedList.ofSize(27, ItemStack.EMPTY);

        // В 1.20.1 содержимое шулькера лежит в NBT "BlockEntityTag" -> "Items"
        NbtCompound beTag = BlockItem.getBlockEntityNbt(shulkerStack);
        if (beTag != null && beTag.contains("Items", NbtElement.LIST_TYPE)) {
            Inventories.readNbt(beTag, items);
        }
        return items;
    }

    private static void setShulkerContents(ItemStack shulkerStack, List<ItemStack> items) {
        NbtCompound beTag = BlockItem.getBlockEntityNbt(shulkerStack);
        if (beTag == null) beTag = new NbtCompound();

        // Записываем список предметов обратно
        Inventories.writeNbt(beTag, DefaultedList.copyOf(ItemStack.EMPTY, items.toArray(new ItemStack[0])));

        // Гарантируем правильное имя блока для BE (не обязательно, но полезно)
        beTag.putString("id", "minecraft:shulker_box");

        // Сохраняем обратно в ItemStack как BlockEntityTag
        NbtCompound stackNbt = shulkerStack.getOrCreateNbt();
        stackNbt.put("BlockEntityTag", beTag);
    }

    // ==== Клиентская отправка пакета (пример для 1.20.1) ====
    // ClientPlayNetworking.send(Takeitout.GETSTACK, (PacketByteBufs.create()).writeVarInt(slotInShulker).writeVarInt(shulkerSlot));
}
