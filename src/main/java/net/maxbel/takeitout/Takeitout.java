package net.maxbel.takeitout;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Takeitout implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/server");

    public record GetShulkerStackPayload(int slot, int shulker, boolean singleItemMode) implements CustomPayload {
        public static final CustomPayload.Id<GetShulkerStackPayload> ID =
                new CustomPayload.Id<>(Identifier.of("takeitout", "getstack"));

        public static final PacketCodec<RegistryByteBuf, GetShulkerStackPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.INTEGER,
                        GetShulkerStackPayload::slot,
                        PacketCodecs.INTEGER,
                        GetShulkerStackPayload::shulker,
                        PacketCodecs.BOOLEAN,
                        GetShulkerStackPayload::singleItemMode,
                        GetShulkerStackPayload::new
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(GetShulkerStackPayload.ID, GetShulkerStackPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GetShulkerStackPayload.ID, (payload, context) -> {
            context.server().execute(() -> handleGetShulkerStack(context.player(), payload));
        });
    }

    private static void handleGetShulkerStack(ServerPlayerEntity player, GetShulkerStackPayload payload) {
        int slotInShulker = payload.slot();
        int shulkerSlot = payload.shulker();
        boolean singleItemMode = payload.singleItemMode();

        if (!isValidInventorySlot(player, shulkerSlot)) {
            LOGGER.warn(
                    "GetShulkerStack aborted: player={}, reason=invalid_shulker_slot, shulkerSlot={}",
                    player.getName().getString(),
                    shulkerSlot
            );
            return;
        }

        ItemStack shulker = player.getInventory().getStack(shulkerSlot);
        if (shulker.isEmpty() || !isShulkerItem(shulker)) {
            LOGGER.warn(
                    "GetShulkerStack aborted: player={}, reason=not_shulker, shulkerSlot={}, stack={}",
                    player.getName().getString(),
                    shulkerSlot,
                    shulker
            );
            return;
        }

        List<ItemStack> itemStacks = copyContainerContents(shulker);

        if (slotInShulker < 0 || slotInShulker >= itemStacks.size()) {
            LOGGER.warn(
                    "GetShulkerStack aborted: player={}, reason=invalid_inner_slot, shulkerSlot={}, shulkerInnerSlot={}",
                    player.getName().getString(),
                    shulkerSlot,
                    slotInShulker
            );
            return;
        }

        ItemStack stackInShulker = itemStacks.get(slotInShulker);
        if (stackInShulker == null || stackInShulker.isEmpty()) {
            LOGGER.warn(
                    "GetShulkerStack aborted: player={}, reason=empty_stack, shulkerSlot={}, shulkerInnerSlot={}",
                    player.getName().getString(),
                    shulkerSlot,
                    slotInShulker
            );
            return;
        }

        ItemStack extracted = stackInShulker.copy();
        if (singleItemMode) {
            extracted.setCount(1);
        }

        ItemStack remainingInShulker = stackInShulker.copy();
        remainingInShulker.decrement(extracted.getCount());

        ItemStack currentMainHand = player.getStackInHand(Hand.MAIN_HAND).copy();

        LOGGER.debug(
                "GetShulkerStack request: player={}, shulkerSlot={}, shulkerInnerSlot={}, selectedSlot={}, singleItemMode={}, currentHand={}, extracted={}",
                player.getName().getString(),
                shulkerSlot,
                slotInShulker,
                player.getInventory().getSelectedSlot(),
                singleItemMode,
                currentMainHand,
                extracted
        );

        // 1. Если рука пустая — просто кладём extracted в руку
        if (currentMainHand.isEmpty()) {
            itemStacks.set(slotInShulker, remainingInShulker.isEmpty() ? ItemStack.EMPTY : remainingInShulker);
            setShulkerContents(shulker, itemStacks);

            player.setStackInHand(Hand.MAIN_HAND, extracted);
            syncPlayerInventory(player);

            LOGGER.debug(
                    "GetShulkerStack success(empty-hand path): player={}, extracted={}, shulkerSlot={}, shulkerInnerSlot={}",
                    player.getName().getString(),
                    extracted,
                    shulkerSlot,
                    slotInShulker
            );
            return;
        }

        // 2. Если есть свободный слот — старый предмет из руки кладём туда
        int freeSlot = player.getInventory().getEmptySlot();
        if (freeSlot != -1) {
            itemStacks.set(slotInShulker, remainingInShulker.isEmpty() ? ItemStack.EMPTY : remainingInShulker);
            setShulkerContents(shulker, itemStacks);

            player.getInventory().setStack(freeSlot, currentMainHand);
            player.setStackInHand(Hand.MAIN_HAND, extracted);
            syncPlayerInventory(player);

            LOGGER.debug(
                    "GetShulkerStack success(free-slot path): player={}, extracted={}, shulkerSlot={}, shulkerInnerSlot={}, oldHandMovedToSlot={}, oldHand={}",
                    player.getName().getString(),
                    extracted,
                    shulkerSlot,
                    slotInShulker,
                    freeSlot,
                    currentMainHand
            );
            return;
        }

        // 3. Если свободного слота нет — пробуем положить старый предмет из руки в шалкер
        if (!remainingInShulker.isEmpty()) {
            itemStacks.set(slotInShulker, remainingInShulker);

            ItemStack leftover = insertIntoShulker(itemStacks, currentMainHand);
            if (leftover.isEmpty()) {
                setShulkerContents(shulker, itemStacks);

                player.setStackInHand(Hand.MAIN_HAND, extracted);
                syncPlayerInventory(player);

                LOGGER.debug(
                        "GetShulkerStack success(insert-into-shulker path): player={}, extracted={}, shulkerSlot={}, shulkerInnerSlot={}, oldHand={}",
                        player.getName().getString(),
                        extracted,
                        shulkerSlot,
                        slotInShulker,
                        currentMainHand
                );
                return;
            }
        }

        // 4. Если и это не удалось — ищем заменяемый слот в инвентаре
        //    Только если весь слот из шалкера забирается полностью
        if (remainingInShulker.isEmpty()) {
            for (int i = Math.min(36, player.getInventory().size()) - 1; i >= 0; --i) {
                ItemStack item = player.getInventory().getStack(i);

                if (!canReplaceInventoryItem(item)) {
                    continue;
                }

                itemStacks.set(slotInShulker, item.copy());
                setShulkerContents(shulker, itemStacks);

                player.getInventory().setStack(i, currentMainHand);
                player.setStackInHand(Hand.MAIN_HAND, extracted);
                syncPlayerInventory(player);

                LOGGER.debug(
                        "GetShulkerStack success(replace-slot path): player={}, extracted={}, shulkerSlot={}, shulkerInnerSlot={}, replacedInventorySlot={}, replacedItem={}, oldHand={}",
                        player.getName().getString(),
                        extracted,
                        shulkerSlot,
                        slotInShulker,
                        i,
                        item,
                        currentMainHand
                );
                return;
            }
        }

        LOGGER.debug(
                "GetShulkerStack aborted: player={}, reason=no_valid_destination, shulkerSlot={}, shulkerInnerSlot={}, currentHand={}, extracted={}",
                player.getName().getString(),
                shulkerSlot,
                slotInShulker,
                currentMainHand,
                extracted
        );
    }

    private static ItemStack insertIntoShulker(List<ItemStack> itemStacks, ItemStack toInsert) {
        ItemStack remaining = toInsert.copy();

        // Сначала пытаемся достакать в уже существующие стаки
        for (int i = 0; i < itemStacks.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = itemStacks.get(i);

            if (existing == null || existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }

            int max = existing.getMaxCount();
            int canMove = Math.min(max - existing.getCount(), remaining.getCount());

            if (canMove <= 0) {
                continue;
            }

            existing.increment(canMove);
            remaining.decrement(canMove);
        }

        // Потом ищем пустые слоты
        for (int i = 0; i < itemStacks.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = itemStacks.get(i);

            if (existing != null && !existing.isEmpty()) {
                continue;
            }

            int move = Math.min(remaining.getCount(), remaining.getMaxCount());
            ItemStack moved = remaining.copy();
            moved.setCount(move);

            itemStacks.set(i, moved);
            remaining.decrement(move);
        }

        return remaining;
    }

    private static boolean isValidInventorySlot(ServerPlayerEntity player, int slot) {
        return slot >= 0 && slot < player.getInventory().size();
    }

    private static List<ItemStack> copyContainerContents(ItemStack shulkerStack) {
        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(27, ItemStack.EMPTY);
        ContainerComponent container = shulkerStack.get(DataComponentTypes.CONTAINER);

        if (container == null) {
            return stacks;
        }

        int i = 0;
        for (ItemStack stack : container.stream().toList()) {
            if (i >= stacks.size()) {
                break;
            }
            stacks.set(i, stack == null ? ItemStack.EMPTY : stack.copy());
            i++;
        }

        return stacks;
    }

    private static void setShulkerContents(ItemStack shulkerStack, List<ItemStack> itemStacks) {
        shulkerStack.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(itemStacks));
    }

    private static boolean isShulkerItem(ItemStack item) {
        if (item.isEmpty()) {
            return false;
        }

        if (!(item.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        return blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean canReplaceInventoryItem(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }

        if (item.getItem() instanceof HoeItem
                || item.getItem() instanceof AxeItem
                || item.getItem() instanceof ShovelItem) {
            return false;
        }

        if (!(item.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        return !(blockItem.getBlock() instanceof ShulkerBoxBlock)
                && !(blockItem.getBlock() instanceof EnderChestBlock);
    }

    private static void syncPlayerInventory(ServerPlayerEntity player) {
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
        player.currentScreenHandler.sendContentUpdates();
    }
}