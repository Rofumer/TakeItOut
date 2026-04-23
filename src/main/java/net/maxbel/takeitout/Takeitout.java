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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class Takeitout implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/server");

    public record GetShulkerStackPayload(int slot, int shulker, boolean singleItemMode) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetShulkerStackPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "getstack"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GetShulkerStackPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.INT,
                        GetShulkerStackPayload::slot,
                        ByteBufCodecs.INT,
                        GetShulkerStackPayload::shulker,
                        ByteBufCodecs.BOOL,
                        GetShulkerStackPayload::singleItemMode,
                        GetShulkerStackPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record GetWorldContainerStackPayload(long[] sourcePositions, ItemStack stack, boolean singleItemMode)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetWorldContainerStackPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "get_world_container_stack"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GetWorldContainerStackPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.LONG_ARRAY,
                        GetWorldContainerStackPayload::sourcePositions,
                        ItemStack.STREAM_CODEC,
                        GetWorldContainerStackPayload::stack,
                        ByteBufCodecs.BOOL,
                        GetWorldContainerStackPayload::singleItemMode,
                        GetWorldContainerStackPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record WorldContainerStackResponsePayload(ItemStack stack, boolean success) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<WorldContainerStackResponsePayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "world_container_stack_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerStackResponsePayload> CODEC =
                StreamCodec.composite(
                        ItemStack.STREAM_CODEC,
                        WorldContainerStackResponsePayload::stack,
                        ByteBufCodecs.BOOL,
                        WorldContainerStackResponsePayload::success,
                        WorldContainerStackResponsePayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record WorldContainerItemCount(ItemStack stack, int count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerItemCount> CODEC =
                StreamCodec.composite(
                        ItemStack.STREAM_CODEC,
                        WorldContainerItemCount::stack,
                        ByteBufCodecs.INT,
                        WorldContainerItemCount::count,
                        WorldContainerItemCount::new
                );
    }

    public record WorldContainerContents(long sourcePosition, List<WorldContainerItemCount> items) {
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerContents> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.LONG,
                        WorldContainerContents::sourcePosition,
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerItemCount.CODEC),
                        WorldContainerContents::items,
                        WorldContainerContents::new
                );
    }

    public record GetWorldContainerItemsPayload(long[] sourcePositions) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetWorldContainerItemsPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "get_world_container_items"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GetWorldContainerItemsPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.LONG_ARRAY,
                        GetWorldContainerItemsPayload::sourcePositions,
                        GetWorldContainerItemsPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record WorldContainerItemsPayload(List<WorldContainerItemCount> items, List<WorldContainerContents> containers)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<WorldContainerItemsPayload> ID =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("takeitout", "world_container_items"));

        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerItemsPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerItemCount.CODEC),
                        WorldContainerItemsPayload::items,
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerContents.CODEC),
                        WorldContainerItemsPayload::containers,
                        WorldContainerItemsPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(GetShulkerStackPayload.ID, GetShulkerStackPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(GetWorldContainerStackPayload.ID, GetWorldContainerStackPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(GetWorldContainerItemsPayload.ID, GetWorldContainerItemsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorldContainerStackResponsePayload.ID, WorldContainerStackResponsePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorldContainerItemsPayload.ID, WorldContainerItemsPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GetShulkerStackPayload.ID, (payload, context) ->
                context.server().execute(() -> handleGetShulkerStackPayload(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(GetWorldContainerStackPayload.ID, (payload, context) ->
                context.server().execute(() -> handleGetWorldContainerStackPayload(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(GetWorldContainerItemsPayload.ID, (payload, context) ->
                context.server().execute(() -> handleGetWorldContainerItemsPayload(context.player(), payload))
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
            syncPlayerInventory(player);
            return;
        }

        int freeSlot = player.getInventory().getFreeSlot();

        if (freeSlot != -1) {
            itemStacks.set(slotInShulker, remainingInShulker.isEmpty() ? ItemStack.EMPTY : remainingInShulker);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(itemStacks));

            player.getInventory().setItem(freeSlot, currentMainHand);
            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
            syncPlayerInventory(player);
            return;
        }

        if (!remainingInShulker.isEmpty()) {
            itemStacks.set(slotInShulker, remainingInShulker);
            ItemStack leftover = insertIntoShulker(itemStacks, currentMainHand);
            if (leftover.isEmpty()) {
                shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(itemStacks));
                player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
                syncPlayerInventory(player);
                return;
            }
        }

        if (remainingInShulker.isEmpty()) {
            for (int i = Math.min(36, player.getInventory().getContainerSize()) - 1; i >= 0; --i) {
                ItemStack item = player.getInventory().getItem(i);

                if (!canReplaceInventoryItem(item)) {
                    continue;
                }

                itemStacks.set(slotInShulker, item.copy());
                shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(itemStacks));

                player.getInventory().setItem(i, currentMainHand);
                player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
                syncPlayerInventory(player);
                return;
            }
        }
    }

    private static void handleGetWorldContainerStackPayload(ServerPlayer player, GetWorldContainerStackPayload payload) {
        ItemStack requested = payload.stack();
        if (requested == null || requested.isEmpty() || payload.sourcePositions() == null) {
            return;
        }

        int checked = 0;
        int invalidSourceCount = 0;
        int emptySourceCount = 0;
        int failedExtractCount = 0;

        for (long packedPos : payload.sourcePositions()) {
            if (checked >= 64) {
                break;
            }
            checked++;

            BlockPos pos = BlockPos.of(packedPos);
            Container inventory = getWorldContainerInventory(player, pos);
            if (inventory == null) {
                invalidSourceCount++;
                continue;
            }

            int slot = getSlotWithStack(inventory, requested);
            if (slot != -1 && extractFromWorldContainer(player, inventory, pos, slot, payload.singleItemMode())) {
                ServerPlayNetworking.send(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), true));
                LOGGER.debug(
                        "GetWorldContainerStack success: player={}, requested={}, pos={}, slot={}, singleItemMode={}",
                        player.getName().getString(),
                        requested,
                        pos,
                        slot,
                        payload.singleItemMode()
                );
                return;
            }

            if (slot == -1) {
                emptySourceCount++;
            } else {
                failedExtractCount++;
            }
        }

        ServerPlayNetworking.send(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), false));
        LOGGER.warn(
                "GetWorldContainerStack miss: player={}, requested={}, sources={}, invalidSources={}, noMatchingStack={}, failedExtract={}",
                player.getName().getString(),
                requested,
                Math.min(payload.sourcePositions().length, 64),
                invalidSourceCount,
                emptySourceCount,
                failedExtractCount
        );
    }

    private static void handleGetWorldContainerItemsPayload(ServerPlayer player, GetWorldContainerItemsPayload payload) {
        List<WorldContainerItemCount> items = new ArrayList<>();
        List<WorldContainerContents> containers = new ArrayList<>();

        if (payload.sourcePositions() == null) {
            ServerPlayNetworking.send(player, new WorldContainerItemsPayload(items, containers));
            return;
        }

        int checked = 0;
        for (long packedPos : payload.sourcePositions()) {
            if (checked >= 64) {
                break;
            }
            checked++;

            Container inventory = getWorldContainerInventory(player, BlockPos.of(packedPos));
            List<WorldContainerItemCount> containerItems = new ArrayList<>();

            if (inventory == null) {
                containers.add(new WorldContainerContents(packedPos, containerItems));
                continue;
            }

            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }

                addItemCount(items, stack);
                addItemCount(containerItems, stack);
            }

            containers.add(new WorldContainerContents(packedPos, containerItems));
        }

        ServerPlayNetworking.send(player, new WorldContainerItemsPayload(items, containers));
    }

    private static void addItemCount(List<WorldContainerItemCount> items, ItemStack stack) {
        ItemStack keyStack = stack.copyWithCount(1);

        for (int i = 0; i < items.size(); i++) {
            WorldContainerItemCount existing = items.get(i);
            if (ItemStack.isSameItemSameComponents(existing.stack(), keyStack)) {
                items.set(i, new WorldContainerItemCount(existing.stack(), existing.count() + stack.getCount()));
                return;
            }
        }

        items.add(new WorldContainerItemCount(keyStack, stack.getCount()));
    }

    private static boolean extractFromWorldContainer(
            ServerPlayer player,
            Container inventory,
            BlockPos pos,
            int slot,
            boolean singleItemMode
    ) {
        if (slot < 0 || slot >= inventory.getContainerSize()) {
            return false;
        }

        ItemStack stackInContainer = inventory.getItem(slot);
        if (stackInContainer == null || stackInContainer.isEmpty()) {
            return false;
        }

        ItemStack extracted = stackInContainer.copy();
        if (singleItemMode) {
            extracted.setCount(1);
        }

        ItemStack remainingInContainer = stackInContainer.copy();
        remainingInContainer.shrink(extracted.getCount());
        ItemStack currentMainHand = player.getItemInHand(InteractionHand.MAIN_HAND).copy();

        if (currentMainHand.isEmpty()) {
            inventory.setItem(slot, remainingInContainer.isEmpty() ? ItemStack.EMPTY : remainingInContainer);
            syncWorldContainer(player, inventory);
            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
            syncPlayerInventory(player);
            return true;
        }

        int freeSlot = player.getInventory().getFreeSlot();
        if (freeSlot != -1) {
            inventory.setItem(slot, remainingInContainer.isEmpty() ? ItemStack.EMPTY : remainingInContainer);
            syncWorldContainer(player, inventory);
            player.getInventory().setItem(freeSlot, currentMainHand);
            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
            syncPlayerInventory(player);
            return true;
        }

        inventory.setItem(slot, remainingInContainer.isEmpty() ? ItemStack.EMPTY : remainingInContainer);
        if (canInsertIntoContainer(inventory, currentMainHand)) {
            ItemStack leftover = insertIntoContainer(inventory, currentMainHand);
            if (!leftover.isEmpty()) {
                inventory.setItem(slot, stackInContainer);
                return false;
            }

            syncWorldContainer(player, inventory);
            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
            syncPlayerInventory(player);
            return true;
        }

        inventory.setItem(slot, stackInContainer);

        if (remainingInContainer.isEmpty()) {
            for (int i = Math.min(36, player.getInventory().getContainerSize()) - 1; i >= 0; --i) {
                ItemStack item = player.getInventory().getItem(i);
                if (!canReplaceInventoryItem(item) || !inventory.canPlaceItem(slot, item)) {
                    continue;
                }

                inventory.setItem(slot, item.copy());
                syncWorldContainer(player, inventory);
                player.getInventory().setItem(i, currentMainHand);
                player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
                syncPlayerInventory(player);
                return true;
            }
        }

        LOGGER.debug(
                "GetWorldContainerStack aborted: player={}, reason=no_valid_destination, pos={}, slot={}, currentHand={}, extracted={}",
                player.getName().getString(),
                pos,
                slot,
                currentMainHand,
                extracted
        );
        return false;
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

    private static boolean canInsertIntoContainer(Container inventory, ItemStack toInsert) {
        int remaining = toInsert.getCount();

        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing == null || existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(existing, toInsert)) {
                continue;
            }

            int max = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize());
            remaining -= Math.max(0, max - existing.getCount());
        }

        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing != null && !existing.isEmpty()) {
                continue;
            }

            if (!inventory.canPlaceItem(i, toInsert)) {
                continue;
            }

            remaining -= Math.min(toInsert.getMaxStackSize(), inventory.getMaxStackSize());
        }

        return remaining <= 0;
    }

    private static ItemStack insertIntoContainer(Container inventory, ItemStack toInsert) {
        ItemStack remaining = toInsert.copy();

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing == null || existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }

            int max = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize());
            int canMove = Math.min(max - existing.getCount(), remaining.getCount());
            if (canMove <= 0) {
                continue;
            }

            existing.grow(canMove);
            remaining.shrink(canMove);
        }

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing != null && !existing.isEmpty()) {
                continue;
            }

            if (!inventory.canPlaceItem(i, remaining)) {
                continue;
            }

            int move = Math.min(remaining.getCount(), Math.min(remaining.getMaxStackSize(), inventory.getMaxStackSize()));
            ItemStack moved = remaining.copy();
            moved.setCount(move);
            inventory.setItem(i, moved);
            remaining.shrink(move);
        }

        return remaining;
    }

    private static int getSlotWithStack(Container inventory, ItemStack stackReference) {
        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, stackReference)) {
                return i;
            }
        }
        return -1;
    }

    private static Container getWorldContainerInventory(ServerPlayer player, BlockPos pos) {
        ServerLevel world = player.level();
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        Container inventory = null;

        if (block instanceof ChestBlock chestBlock) {
            inventory = ChestBlock.getContainer(chestBlock, state, world, pos, true);
        } else if (block instanceof ShulkerBoxBlock || block instanceof BarrelBlock) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                inventory = container;
            }
        }

        return inventory;
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

    private static void syncPlayerInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private static void syncWorldContainer(ServerPlayer player, Container inventory) {
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
    }
}
