package net.maxbel.takeitout;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Takeitout implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("TakeItOut");

    public record GetShulkerStackPayload(int slot, int shulker, boolean singleItemMode) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetShulkerStackPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "getstack"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GetShulkerStackPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.INT, GetShulkerStackPayload::slot,
                        ByteBufCodecs.INT, GetShulkerStackPayload::shulker,
                        ByteBufCodecs.BOOL, GetShulkerStackPayload::singleItemMode,
                        GetShulkerStackPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(GetShulkerStackPayload.ID, GetShulkerStackPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GetShulkerStackPayload.ID, (payload, context) ->
                context.server().execute(() -> handleGetShulkerStackPayload(context.player(), payload))
        );
    }

    private static void handleGetShulkerStackPayload(ServerPlayer player, GetShulkerStackPayload payload) {
        int slotInShulker = payload.slot();
        int shulkerSlot = payload.shulker();
        boolean singleItemMode = payload.singleItemMode();

        if (!isValidInventorySlot(player, shulkerSlot)) {
            return;
        }

        ItemStack shulker = player.getInventory().getItem(shulkerSlot);
        if (shulker.isEmpty() || !isShulkerItem(shulker)) {
            return;
        }

        ItemContainerContents contents = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        List<ItemStack> itemStacks = copyContainerContents(contents);

        if (slotInShulker < 0 || slotInShulker >= itemStacks.size()) {
            return;
        }

        ItemStack stackInShulker = itemStacks.get(slotInShulker);
        if (stackInShulker.isEmpty()) {
            return;
        }

        ItemStack extracted = stackInShulker.copy();
        if (singleItemMode) {
            extracted.setCount(1);
        }

        ItemStack remainingInShulker = stackInShulker.copy();
        remainingInShulker.shrink(extracted.getCount());
        ItemStack currentMainHand = player.getItemInHand(InteractionHand.MAIN_HAND).copy();

        if (currentMainHand.isEmpty()) {
            itemStacks.set(slotInShulker, remainingInShulker.isEmpty() ? ItemStack.EMPTY : remainingInShulker);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(itemStacks));

            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            return;
        }

        int freeSlot = player.getInventory().getFreeSlot();

        if (freeSlot != -1) {
            itemStacks.set(slotInShulker, remainingInShulker.isEmpty() ? ItemStack.EMPTY : remainingInShulker);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(itemStacks));

            if (!currentMainHand.isEmpty()) {
                player.getInventory().setItem(freeSlot, currentMainHand);
            }

            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            return;
        }

        if (!remainingInShulker.isEmpty()) {
            itemStacks.set(slotInShulker, remainingInShulker);
            ItemStack leftover = insertIntoShulker(itemStacks, currentMainHand);
            if (!leftover.isEmpty()) {
                return;
            }

            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(itemStacks));
            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            return;
        }

        for (int i = Math.min(36, player.getInventory().getContainerSize()) - 1; i >= 0; --i) {
            ItemStack item = player.getInventory().getItem(i);

            if (!canReplaceInventoryItem(item)) {
                continue;
            }

            if (!remainingInShulker.isEmpty()) {
                continue;
            }

            itemStacks.set(slotInShulker, item.copy());
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(itemStacks));

            player.getInventory().setItem(i, currentMainHand);
            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);

            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            return;
        }
    }

    private static ItemStack insertIntoShulker(List<ItemStack> itemStacks, ItemStack toInsert) {
        ItemStack remaining = toInsert.copy();

        for (int i = 0; i < itemStacks.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = itemStacks.get(i);
            if (existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }

            int max = existing.getMaxStackSize();
            int canMove = Math.min(max - existing.getCount(), remaining.getCount());
            if (canMove <= 0) {
                continue;
            }

            existing.grow(canMove);
            remaining.shrink(canMove);
        }

        for (int i = 0; i < itemStacks.size() && !remaining.isEmpty(); i++) {
            if (!itemStacks.get(i).isEmpty()) {
                continue;
            }

            int move = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            ItemStack moved = remaining.copy();
            moved.setCount(move);
            itemStacks.set(i, moved);
            remaining.shrink(move);
        }

        return remaining;
    }

    private static boolean isValidInventorySlot(ServerPlayer player, int slot) {
        return slot >= 0 && slot < player.getInventory().getContainerSize();
    }

    private static List<ItemStack> copyContainerContents(ItemContainerContents contents) {
        NonNullList<ItemStack> stacks = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.copyInto(stacks);
        return stacks;
    }

    private static boolean isShulkerItem(ItemStack item) {
        return !item.isEmpty()
                && item.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean canReplaceInventoryItem(ItemStack item) {
        if (item.isEmpty()) {
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
}
