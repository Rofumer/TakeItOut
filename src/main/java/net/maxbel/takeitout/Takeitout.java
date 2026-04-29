package net.maxbel.takeitout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Takeitout implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/server");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SERVER_CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("takeitout-server.json");
    private static final String LINKED_CONTAINER_EXCHANGE_MODE_KEY = "linked_container_exchange_mode";
    private static final String ALLOWED_EXCHANGE_DIMENSIONS_KEY = "allowed_exchange_dimensions";
    private static final String LINKED_CONTAINER_SCAN_LIMIT_KEY = "linked_container_scan_limit";
    private static final int DEFAULT_LINKED_CONTAINER_SCAN_LIMIT = 64;
    private static final Set<String> ALLOWED_EXCHANGE_DIMENSIONS = new HashSet<>();
    private static LinkedContainerExchangeMode linkedContainerExchangeMode = LinkedContainerExchangeMode.CROSS_DIMENSION;
    private static int linkedContainerScanLimit = DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;

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

    public record WorldContainerSource(String dimension, long position) {
        public static final PacketCodec<RegistryByteBuf, WorldContainerSource> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.STRING,
                        WorldContainerSource::dimension,
                        PacketCodecs.LONG,
                        WorldContainerSource::position,
                        WorldContainerSource::new
                );
    }

    private enum LinkedContainerExchangeMode {
        DISABLED("disabled"),
        SAME_DIMENSION("same_dimension"),
        CROSS_DIMENSION("cross_dimension");

        private final String id;

        LinkedContainerExchangeMode(String id) {
            this.id = id;
        }

        private static LinkedContainerExchangeMode fromString(String value) {
            if (value != null) {
                for (LinkedContainerExchangeMode mode : values()) {
                    if (mode.id.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                        return mode;
                    }
                }
            }

            LOGGER.warn("Invalid linked container exchange mode '{}', using {}", value, CROSS_DIMENSION.id);
            return CROSS_DIMENSION;
        }
    }

    public record GetWorldContainerStackPayload(List<WorldContainerSource> sources, ItemStack stack, boolean singleItemMode) implements CustomPayload {
        public static final CustomPayload.Id<GetWorldContainerStackPayload> ID =
                new CustomPayload.Id<>(Identifier.of("takeitout", "get_world_container_stack"));

        public static final PacketCodec<RegistryByteBuf, GetWorldContainerStackPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        GetWorldContainerStackPayload::sources,
                        ItemStack.PACKET_CODEC,
                        GetWorldContainerStackPayload::stack,
                        PacketCodecs.BOOLEAN,
                        GetWorldContainerStackPayload::singleItemMode,
                        GetWorldContainerStackPayload::new
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record WorldContainerStackResponsePayload(ItemStack stack, boolean success) implements CustomPayload {
        public static final CustomPayload.Id<WorldContainerStackResponsePayload> ID =
                new CustomPayload.Id<>(Identifier.of("takeitout", "world_container_stack_response"));

        public static final PacketCodec<RegistryByteBuf, WorldContainerStackResponsePayload> CODEC =
                PacketCodec.tuple(
                        ItemStack.PACKET_CODEC,
                        WorldContainerStackResponsePayload::stack,
                        PacketCodecs.BOOLEAN,
                        WorldContainerStackResponsePayload::success,
                        WorldContainerStackResponsePayload::new
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record WorldContainerItemCount(ItemStack stack, int count) {
        public static final PacketCodec<RegistryByteBuf, WorldContainerItemCount> CODEC =
                PacketCodec.tuple(
                        ItemStack.PACKET_CODEC,
                        WorldContainerItemCount::stack,
                        PacketCodecs.INTEGER,
                        WorldContainerItemCount::count,
                        WorldContainerItemCount::new
                );
    }

    public record WorldContainerContents(WorldContainerSource source, List<WorldContainerItemCount> items) {
        public static final PacketCodec<RegistryByteBuf, WorldContainerContents> CODEC =
                PacketCodec.tuple(
                        WorldContainerSource.CODEC,
                        WorldContainerContents::source,
                        PacketCodecs.collection(ArrayList::new, WorldContainerItemCount.CODEC),
                        WorldContainerContents::items,
                        WorldContainerContents::new
                );
    }

    public record GetWorldContainerItemsPayload(List<WorldContainerSource> sources) implements CustomPayload {
        public static final CustomPayload.Id<GetWorldContainerItemsPayload> ID =
                new CustomPayload.Id<>(Identifier.of("takeitout", "get_world_container_items"));

        public static final PacketCodec<RegistryByteBuf, GetWorldContainerItemsPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        GetWorldContainerItemsPayload::sources,
                        GetWorldContainerItemsPayload::new
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record WorldContainerItemsPayload(
            List<WorldContainerItemCount> items,
            List<WorldContainerContents> containers
    ) implements CustomPayload {
        public static final CustomPayload.Id<WorldContainerItemsPayload> ID =
                new CustomPayload.Id<>(Identifier.of("takeitout", "world_container_items"));

        public static final PacketCodec<RegistryByteBuf, WorldContainerItemsPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.collection(ArrayList::new, WorldContainerItemCount.CODEC),
                        WorldContainerItemsPayload::items,
                        PacketCodecs.collection(ArrayList::new, WorldContainerContents.CODEC),
                        WorldContainerItemsPayload::containers,
                        WorldContainerItemsPayload::new
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    @Override
    public void onInitialize() {
        loadServerConfig();

        PayloadTypeRegistry.playC2S().register(GetShulkerStackPayload.ID, GetShulkerStackPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GetWorldContainerStackPayload.ID, GetWorldContainerStackPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GetWorldContainerItemsPayload.ID, GetWorldContainerItemsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WorldContainerStackResponsePayload.ID, WorldContainerStackResponsePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WorldContainerItemsPayload.ID, WorldContainerItemsPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GetShulkerStackPayload.ID, (payload, context) -> {
            context.server().execute(() -> handleGetShulkerStack(context.player(), payload));
        });
        ServerPlayNetworking.registerGlobalReceiver(GetWorldContainerStackPayload.ID, (payload, context) -> {
            context.server().execute(() -> handleGetWorldContainerStack(context.player(), payload));
        });
        ServerPlayNetworking.registerGlobalReceiver(GetWorldContainerItemsPayload.ID, (payload, context) -> {
            context.server().execute(() -> handleGetWorldContainerItems(context.player(), payload));
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

    private static void handleGetWorldContainerStack(ServerPlayerEntity player, GetWorldContainerStackPayload payload) {
        ItemStack requested = payload.stack();
        if (requested == null || requested.isEmpty() || payload.sources() == null) {
            return;
        }

        int checked = 0;
        int invalidSourceCount = 0;
        int emptySourceCount = 0;
        int failedExtractCount = 0;
        int scanLimit = linkedContainerScanLimit;
        for (WorldContainerSource source : payload.sources()) {
            if (checked >= scanLimit) {
                break;
            }
            checked++;

            BlockPos pos = BlockPos.fromLong(source.position());
            Inventory inventory = getWorldContainerInventory(player, source);
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
                Math.min(payload.sources().size(), scanLimit),
                invalidSourceCount,
                emptySourceCount,
                failedExtractCount
        );
    }

    private static void handleGetWorldContainerItems(ServerPlayerEntity player, GetWorldContainerItemsPayload payload) {
        List<WorldContainerItemCount> items = new ArrayList<>();
        List<WorldContainerContents> containers = new ArrayList<>();
        if (payload.sources() == null) {
            ServerPlayNetworking.send(player, new WorldContainerItemsPayload(items, containers));
            return;
        }

        int checked = 0;
        int scanLimit = linkedContainerScanLimit;
        for (WorldContainerSource source : payload.sources()) {
            if (checked >= scanLimit) {
                break;
            }
            checked++;

            Inventory inventory = getWorldContainerInventory(player, source);
            List<WorldContainerItemCount> containerItems = new ArrayList<>();
            if (inventory == null) {
                containers.add(new WorldContainerContents(source, containerItems));
                continue;
            }

            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }

                addItemCount(items, stack);
                addItemCount(containerItems, stack);
            }

            containers.add(new WorldContainerContents(source, containerItems));
        }

        ServerPlayNetworking.send(player, new WorldContainerItemsPayload(items, containers));
    }

    private static void addItemCount(List<WorldContainerItemCount> items, ItemStack stack) {
        ItemStack keyStack = stack.copyWithCount(1);

        for (int i = 0; i < items.size(); i++) {
            WorldContainerItemCount existing = items.get(i);
            if (ItemStack.areItemsAndComponentsEqual(existing.stack(), keyStack)) {
                items.set(i, new WorldContainerItemCount(existing.stack(), existing.count() + stack.getCount()));
                return;
            }
        }

        items.add(new WorldContainerItemCount(keyStack, stack.getCount()));
    }

    private static boolean extractFromWorldContainer(ServerPlayerEntity player, Inventory inventory, BlockPos pos, int slot, boolean singleItemMode) {
        if (slot < 0 || slot >= inventory.size()) {
            return false;
        }

        ItemStack stackInContainer = inventory.getStack(slot);
        if (stackInContainer == null || stackInContainer.isEmpty()) {
            return false;
        }

        ItemStack extracted = stackInContainer.copy();
        if (singleItemMode) {
            extracted.setCount(1);
        }

        ItemStack remainingInContainer = stackInContainer.copy();
        remainingInContainer.decrement(extracted.getCount());
        ItemStack currentMainHand = player.getStackInHand(Hand.MAIN_HAND).copy();

        if (currentMainHand.isEmpty()) {
            inventory.setStack(slot, remainingInContainer.isEmpty() ? ItemStack.EMPTY : remainingInContainer);
            syncWorldContainer(player, inventory);
            player.setStackInHand(Hand.MAIN_HAND, extracted);
            syncPlayerInventory(player);
            return true;
        }

        int freeSlot = player.getInventory().getEmptySlot();
        if (freeSlot != -1) {
            inventory.setStack(slot, remainingInContainer.isEmpty() ? ItemStack.EMPTY : remainingInContainer);
            syncWorldContainer(player, inventory);
            player.getInventory().setStack(freeSlot, currentMainHand);
            player.setStackInHand(Hand.MAIN_HAND, extracted);
            syncPlayerInventory(player);
            return true;
        }

        inventory.setStack(slot, remainingInContainer.isEmpty() ? ItemStack.EMPTY : remainingInContainer);
        if (canInsertIntoInventory(inventory, currentMainHand)) {
            ItemStack leftover = insertIntoInventory(inventory, currentMainHand);
            if (!leftover.isEmpty()) {
                inventory.setStack(slot, stackInContainer);
                return false;
            }

            syncWorldContainer(player, inventory);
            player.setStackInHand(Hand.MAIN_HAND, extracted);
            syncPlayerInventory(player);
            return true;
        }

        inventory.setStack(slot, stackInContainer);

        if (remainingInContainer.isEmpty()) {
            for (int i = Math.min(36, player.getInventory().size()) - 1; i >= 0; --i) {
                ItemStack item = player.getInventory().getStack(i);
                if (!canReplaceInventoryItem(item) || !inventory.isValid(slot, item)) {
                    continue;
                }

                inventory.setStack(slot, item.copy());
                syncWorldContainer(player, inventory);
                player.getInventory().setStack(i, currentMainHand);
                player.setStackInHand(Hand.MAIN_HAND, extracted);
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

    private static boolean canInsertIntoInventory(Inventory inventory, ItemStack toInsert) {
        int remaining = toInsert.getCount();

        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing == null || existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, toInsert)) {
                continue;
            }

            int max = Math.min(existing.getMaxCount(), inventory.getMaxCount(existing));
            remaining -= Math.max(0, max - existing.getCount());
        }

        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing != null && !existing.isEmpty()) {
                continue;
            }

            if (!inventory.isValid(i, toInsert)) {
                continue;
            }

            remaining -= Math.min(toInsert.getMaxCount(), inventory.getMaxCount(toInsert));
        }

        return remaining <= 0;
    }

    private static ItemStack insertIntoInventory(Inventory inventory, ItemStack toInsert) {
        ItemStack remaining = toInsert.copy();

        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing == null || existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.areItemsAndComponentsEqual(existing, remaining)) {
                continue;
            }

            int max = Math.min(existing.getMaxCount(), inventory.getMaxCount(existing));
            int canMove = Math.min(max - existing.getCount(), remaining.getCount());
            if (canMove <= 0) {
                continue;
            }

            existing.increment(canMove);
            remaining.decrement(canMove);
        }

        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing != null && !existing.isEmpty()) {
                continue;
            }

            if (!inventory.isValid(i, remaining)) {
                continue;
            }

            int move = Math.min(remaining.getCount(), Math.min(remaining.getMaxCount(), inventory.getMaxCount(remaining)));
            ItemStack moved = remaining.copy();
            moved.setCount(move);
            inventory.setStack(i, moved);
            remaining.decrement(move);
        }

        return remaining;
    }

    private static int getSlotWithStack(Inventory inventory, ItemStack stackReference) {
        for (int i = 0; i < inventory.size(); ++i) {
            ItemStack stack = inventory.getStack(i);
            if (stack != null && !stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, stackReference)) {
                return i;
            }
        }
        return -1;
    }

    private static Inventory getWorldContainerInventory(ServerPlayerEntity player, WorldContainerSource source) {
        ServerWorld world = getSourceWorld(player, source);
        if (world == null) {
            return null;
        }

        BlockPos pos = BlockPos.fromLong(source.position());
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        Inventory inventory = null;

        if (block instanceof ChestBlock chestBlock) {
            inventory = ChestBlock.getInventory(chestBlock, state, world, pos, true);
        } else if (block instanceof ShulkerBoxBlock || block instanceof BarrelBlock) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof Inventory blockInventory) {
                inventory = blockInventory;
            }
        }

        if (inventory == null) {
            return null;
        }

        return inventory;
    }

    private static ServerWorld getSourceWorld(ServerPlayerEntity player, WorldContainerSource source) {
        if (source == null || source.dimension() == null || source.dimension().isBlank()) {
            return null;
        }

        String playerDimension = player.getEntityWorld().getRegistryKey().getValue().toString();
        if (linkedContainerExchangeMode == LinkedContainerExchangeMode.DISABLED) {
            LOGGER.warn(
                    "World container source blocked by server config: player={}, reason=disabled, playerDimension={}, sourceDimension={}, pos={}",
                    player.getName().getString(),
                    playerDimension,
                    source.dimension(),
                    BlockPos.fromLong(source.position())
            );
            return null;
        }

        if (linkedContainerExchangeMode == LinkedContainerExchangeMode.SAME_DIMENSION && !playerDimension.equals(source.dimension())) {
            LOGGER.warn(
                    "World container source blocked by server config: player={}, reason=same_dimension_only, playerDimension={}, sourceDimension={}, pos={}",
                    player.getName().getString(),
                    playerDimension,
                    source.dimension(),
                    BlockPos.fromLong(source.position())
            );
            return null;
        }

        if (!isDimensionAllowedForExchange(playerDimension) || !isDimensionAllowedForExchange(source.dimension())) {
            LOGGER.warn(
                    "World container source blocked by server config: player={}, playerDimension={}, sourceDimension={}, pos={}",
                    player.getName().getString(),
                    playerDimension,
                    source.dimension(),
                    BlockPos.fromLong(source.position())
            );
            return null;
        }

        Identifier dimensionId;
        try {
            dimensionId = Identifier.of(source.dimension());
        } catch (Exception ignored) {
            return null;
        }

        RegistryKey<net.minecraft.world.World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
        if (!(player.getEntityWorld() instanceof ServerWorld playerWorld)) {
            return null;
        }

        return playerWorld.getServer().getWorld(worldKey);
    }

    private static boolean isDimensionAllowedForExchange(String dimension) {
        return ALLOWED_EXCHANGE_DIMENSIONS.isEmpty() || ALLOWED_EXCHANGE_DIMENSIONS.contains(dimension);
    }

    private static void loadServerConfig() {
        ALLOWED_EXCHANGE_DIMENSIONS.clear();
        linkedContainerExchangeMode = LinkedContainerExchangeMode.CROSS_DIMENSION;
        linkedContainerScanLimit = DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;

        if (!Files.exists(SERVER_CONFIG_PATH)) {
            saveDefaultServerConfig();
            LOGGER.info("Server config created: {}", SERVER_CONFIG_PATH);
            return;
        }

        try {
            JsonObject root = GSON.fromJson(Files.readString(SERVER_CONFIG_PATH), JsonObject.class);
            if (root == null || !root.has(ALLOWED_EXCHANGE_DIMENSIONS_KEY) || !root.get(ALLOWED_EXCHANGE_DIMENSIONS_KEY).isJsonArray()) {
                saveDefaultServerConfig();
                return;
            }

            if (root.has(LINKED_CONTAINER_EXCHANGE_MODE_KEY)) {
                linkedContainerExchangeMode = LinkedContainerExchangeMode.fromString(root.get(LINKED_CONTAINER_EXCHANGE_MODE_KEY).getAsString());
            }

            if (root.has(LINKED_CONTAINER_SCAN_LIMIT_KEY)) {
                linkedContainerScanLimit = parseLinkedContainerScanLimit(root.get(LINKED_CONTAINER_SCAN_LIMIT_KEY));
            } else {
                root.addProperty(LINKED_CONTAINER_SCAN_LIMIT_KEY, linkedContainerScanLimit);
                saveServerConfig(root);
            }

            JsonArray allowedDimensions = root.getAsJsonArray(ALLOWED_EXCHANGE_DIMENSIONS_KEY);
            for (JsonElement element : allowedDimensions) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }

                String dimension = element.getAsString();
                if (dimension == null || dimension.isBlank()) {
                    continue;
                }

                try {
                    Identifier.of(dimension);
                    ALLOWED_EXCHANGE_DIMENSIONS.add(dimension);
                } catch (Exception e) {
                    LOGGER.warn("Ignoring invalid TakeItOut exchange dimension in server config: {}", dimension);
                }
            }

            LOGGER.info(
                    "Server config loaded: linkedContainerExchangeMode={}, linkedContainerScanLimit={}, allowedExchangeDimensions={}",
                    linkedContainerExchangeMode.id,
                    linkedContainerScanLimit,
                    ALLOWED_EXCHANGE_DIMENSIONS.isEmpty() ? "all" : ALLOWED_EXCHANGE_DIMENSIONS
            );
        } catch (Exception e) {
            LOGGER.warn("Failed to load TakeItOut server config, using defaults", e);
        }
    }

    private static int parseLinkedContainerScanLimit(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            LOGGER.warn(
                    "Invalid TakeItOut linked container scan limit '{}', using {}",
                    element,
                    DEFAULT_LINKED_CONTAINER_SCAN_LIMIT
            );
            return DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        }

        int limit;
        try {
            limit = element.getAsInt();
        } catch (Exception e) {
            LOGGER.warn(
                    "Invalid TakeItOut linked container scan limit '{}', using {}",
                    element,
                    DEFAULT_LINKED_CONTAINER_SCAN_LIMIT
            );
            return DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        }

        if (limit < 1) {
            LOGGER.warn(
                    "Invalid TakeItOut linked container scan limit '{}', using {}",
                    limit,
                    DEFAULT_LINKED_CONTAINER_SCAN_LIMIT
            );
            return DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        }

        return limit;
    }

    private static void saveDefaultServerConfig() {
        JsonObject root = new JsonObject();
        root.addProperty(LINKED_CONTAINER_EXCHANGE_MODE_KEY, linkedContainerExchangeMode.id);
        root.addProperty(LINKED_CONTAINER_SCAN_LIMIT_KEY, linkedContainerScanLimit);
        root.add(ALLOWED_EXCHANGE_DIMENSIONS_KEY, new JsonArray());

        saveServerConfig(root);
    }

    private static void saveServerConfig(JsonObject root) {
        try {
            Files.createDirectories(SERVER_CONFIG_PATH.getParent());
            Files.writeString(SERVER_CONFIG_PATH, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.warn("Failed to write TakeItOut server config", e);
        }
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

    private static void syncWorldContainer(ServerPlayerEntity player, Inventory inventory) {
        inventory.markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }
}
