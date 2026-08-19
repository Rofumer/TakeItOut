package net.maxbel.takeitout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.maxbel.takeitout.compat.CuriosCompat;
import net.maxbel.takeitout.compat.SophisticatedBackpacksCompat;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
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
import java.util.UUID;

public class Takeitout {
    private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/server");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SERVER_CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("takeitout-server.json");
    private static final Path SHARED_GROUPS_PATH = FMLPaths.CONFIGDIR.get().resolve("takeitout-shared-groups.json");
    private static final int MAX_GROUPS_PER_PLAYER = 10;
    private static final String LINKED_CONTAINER_EXCHANGE_MODE_KEY = "linked_container_exchange_mode";
    private static final String ALLOWED_EXCHANGE_DIMENSIONS_KEY = "allowed_exchange_dimensions";
    private static final String LINKED_CONTAINER_SCAN_LIMIT_KEY = "linked_container_scan_limit";
    private static final String ALLOW_ALL_ITEMS_TAKE_KEY = "allow_all_items_take";
    private static final int DEFAULT_LINKED_CONTAINER_SCAN_LIMIT = 64;
    private static final Set<String> ALLOWED_EXCHANGE_DIMENSIONS = new HashSet<>();
    private static LinkedContainerExchangeMode linkedContainerExchangeMode = LinkedContainerExchangeMode.CROSS_DIMENSION;
    private static int linkedContainerScanLimit = DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
    private static boolean allowAllItemsTake = true;

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new Identifier("takeitout", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public interface Payload {
        void write(PacketByteBuf buf);
    }

    public record GetShulkerStackPayload(int slot, int shulker, boolean singleItemMode) implements Payload {
        public void write(PacketByteBuf buf) {
            buf.writeVarInt(slot);
            buf.writeVarInt(shulker);
            buf.writeBoolean(singleItemMode);
        }

        public static GetShulkerStackPayload read(PacketByteBuf buf) {
            return new GetShulkerStackPayload(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }
    }

    public record WorldContainerSource(String dimension, long position) {
        public void write(PacketByteBuf buf) {
            buf.writeString(dimension);
            buf.writeVarLong(position);
        }

        public static WorldContainerSource read(PacketByteBuf buf) {
            return new WorldContainerSource(buf.readString(), buf.readVarLong());
        }
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

    public record GetWorldContainerStackPayload(List<WorldContainerSource> sources, ItemStack stack, boolean singleItemMode, boolean fromUi, List<WorldContainerSource> dumps) implements Payload {
        public void write(PacketByteBuf buf) {
            buf.writeCollection(sources, (b, v) -> v.write(b));
            buf.writeItemStack(stack);
            buf.writeBoolean(singleItemMode);
            buf.writeBoolean(fromUi);
            buf.writeCollection(dumps, (b, v) -> v.write(b));
        }

        public static GetWorldContainerStackPayload read(PacketByteBuf buf) {
            List<WorldContainerSource> sources = buf.readList(WorldContainerSource::read);
            ItemStack stack = buf.readItemStack();
            boolean singleItemMode = buf.readBoolean();
            boolean fromUi = buf.readBoolean();
            List<WorldContainerSource> dumps = buf.readList(WorldContainerSource::read);
            return new GetWorldContainerStackPayload(sources, stack, singleItemMode, fromUi, dumps);
        }
    }

    public record WorldContainerStackResponsePayload(ItemStack stack, boolean success) {
        public void write(PacketByteBuf buf) {
            buf.writeItemStack(stack);
            buf.writeBoolean(success);
        }

        public static WorldContainerStackResponsePayload read(PacketByteBuf buf) {
            return new WorldContainerStackResponsePayload(buf.readItemStack(), buf.readBoolean());
        }
    }

    public record WorldContainerItemCount(ItemStack stack, int count) {
        public void write(PacketByteBuf buf) {
            buf.writeItemStack(stack);
            buf.writeVarInt(count);
        }

        public static WorldContainerItemCount read(PacketByteBuf buf) {
            return new WorldContainerItemCount(buf.readItemStack(), buf.readVarInt());
        }
    }

    public record WorldContainerContents(WorldContainerSource source, List<WorldContainerItemCount> items) {
        public void write(PacketByteBuf buf) {
            source.write(buf);
            buf.writeCollection(items, (b, v) -> v.write(b));
        }

        public static WorldContainerContents read(PacketByteBuf buf) {
            WorldContainerSource source = WorldContainerSource.read(buf);
            List<WorldContainerItemCount> items = buf.readList(WorldContainerItemCount::read);
            return new WorldContainerContents(source, items);
        }
    }

    public record GetWorldContainerItemsPayload(List<WorldContainerSource> sources) implements Payload {
        public void write(PacketByteBuf buf) {
            buf.writeCollection(sources, (b, v) -> v.write(b));
        }

        public static GetWorldContainerItemsPayload read(PacketByteBuf buf) {
            return new GetWorldContainerItemsPayload(buf.readList(WorldContainerSource::read));
        }
    }

    public record WorldContainerItemsPayload(
            List<WorldContainerItemCount> items,
            List<WorldContainerContents> containers
    ) {
        public void write(PacketByteBuf buf) {
            buf.writeCollection(items, (b, v) -> v.write(b));
            buf.writeCollection(containers, (b, v) -> v.write(b));
        }

        public static WorldContainerItemsPayload read(PacketByteBuf buf) {
            List<WorldContainerItemCount> items = buf.readList(WorldContainerItemCount::read);
            List<WorldContainerContents> containers = buf.readList(WorldContainerContents::read);
            return new WorldContainerItemsPayload(items, containers);
        }
    }

    public record DumpInventoryPayload(List<WorldContainerSource> dumps) implements Payload {
        public void write(PacketByteBuf buf) {
            buf.writeCollection(dumps, (b, v) -> v.write(b));
        }

        public static DumpInventoryPayload read(PacketByteBuf buf) {
            return new DumpInventoryPayload(buf.readList(WorldContainerSource::read));
        }
    }

    public record ServerConfigSyncPayload(int linkedContainerScanLimit) {
        public void write(PacketByteBuf buf) {
            buf.writeVarInt(linkedContainerScanLimit);
        }

        public static ServerConfigSyncPayload read(PacketByteBuf buf) {
            return new ServerConfigSyncPayload(buf.readVarInt());
        }
    }

    public record SharedSourceEntry(long position, boolean linked) {
        public void write(PacketByteBuf buf) {
            buf.writeVarLong(position);
            buf.writeBoolean(linked);
        }

        public static SharedSourceEntry read(PacketByteBuf buf) {
            return new SharedSourceEntry(buf.readVarLong(), buf.readBoolean());
        }
    }

    public record SharedGroupDimension(String dimension, List<SharedSourceEntry> sources) {
        public void write(PacketByteBuf buf) {
            buf.writeString(dimension);
            buf.writeCollection(sources, (b, v) -> v.write(b));
        }

        public static SharedGroupDimension read(PacketByteBuf buf) {
            String dimension = buf.readString();
            List<SharedSourceEntry> sources = buf.readList(SharedSourceEntry::read);
            return new SharedGroupDimension(dimension, sources);
        }
    }

    public record SharedGroupEntry(String id, String name, String authorName, String authorId, List<SharedGroupDimension> dimensions) {
        public void write(PacketByteBuf buf) {
            buf.writeString(id);
            buf.writeString(name);
            buf.writeString(authorName);
            buf.writeString(authorId);
            buf.writeCollection(dimensions, (b, v) -> v.write(b));
        }

        public static SharedGroupEntry read(PacketByteBuf buf) {
            String id = buf.readString();
            String name = buf.readString();
            String authorName = buf.readString();
            String authorId = buf.readString();
            List<SharedGroupDimension> dimensions = buf.readList(SharedGroupDimension::read);
            return new SharedGroupEntry(id, name, authorName, authorId, dimensions);
        }
    }

    public record PublishGroupPayload(String name, List<SharedGroupDimension> dimensions) implements Payload {
        public void write(PacketByteBuf buf) {
            buf.writeString(name);
            buf.writeCollection(dimensions, (b, v) -> v.write(b));
        }

        public static PublishGroupPayload read(PacketByteBuf buf) {
            String name = buf.readString();
            List<SharedGroupDimension> dimensions = buf.readList(SharedGroupDimension::read);
            return new PublishGroupPayload(name, dimensions);
        }
    }

    public record UnpublishGroupPayload(String groupId) implements Payload {
        public void write(PacketByteBuf buf) {
            buf.writeString(groupId);
        }

        public static UnpublishGroupPayload read(PacketByteBuf buf) {
            return new UnpublishGroupPayload(buf.readString());
        }
    }

    public record SharedGroupsListPayload(List<SharedGroupEntry> groups) {
        public void write(PacketByteBuf buf) {
            buf.writeCollection(groups, (b, v) -> v.write(b));
        }

        public static SharedGroupsListPayload read(PacketByteBuf buf) {
            return new SharedGroupsListPayload(buf.readList(SharedGroupEntry::read));
        }
    }

    @Mod("takeitout")
    public static class TakeItOutForge {
        public TakeItOutForge() {
            net.minecraftforge.eventbus.api.IEventBus modEventBus =
                    net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
            modEventBus.addListener(Takeitout::commonSetup);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(Takeitout.class);

            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> net.maxbel.takeitout.client.TakeitoutClient.init(modEventBus)
            );
        }
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        loadServerConfig();
        registerNetworking();
    }

    private static void registerNetworking() {
        int id = 0;
        CHANNEL.registerMessage(id++, GetShulkerStackPayload.class,
                GetShulkerStackPayload::write, GetShulkerStackPayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> handleGetShulkerStack((ServerPlayerEntity) ctx.getSender(), payload));
                    ctx.setPacketHandled(true);
                });
        CHANNEL.registerMessage(id++, GetWorldContainerStackPayload.class,
                GetWorldContainerStackPayload::write, GetWorldContainerStackPayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> handleGetWorldContainerStack((ServerPlayerEntity) ctx.getSender(), payload));
                    ctx.setPacketHandled(true);
                });
        CHANNEL.registerMessage(id++, GetWorldContainerItemsPayload.class,
                GetWorldContainerItemsPayload::write, GetWorldContainerItemsPayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> handleGetWorldContainerItems((ServerPlayerEntity) ctx.getSender(), payload));
                    ctx.setPacketHandled(true);
                });
        CHANNEL.registerMessage(id++, DumpInventoryPayload.class,
                DumpInventoryPayload::write, DumpInventoryPayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> handleDumpInventoryPayload((ServerPlayerEntity) ctx.getSender(), payload));
                    ctx.setPacketHandled(true);
                });
        CHANNEL.registerMessage(id++, PublishGroupPayload.class,
                PublishGroupPayload::write, PublishGroupPayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> handlePublishGroupPayload((ServerPlayerEntity) ctx.getSender(), payload));
                    ctx.setPacketHandled(true);
                });
        CHANNEL.registerMessage(id++, UnpublishGroupPayload.class,
                UnpublishGroupPayload::write, UnpublishGroupPayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> handleUnpublishGroupPayload((ServerPlayerEntity) ctx.getSender(), payload));
                    ctx.setPacketHandled(true);
                });

        CHANNEL.registerMessage(id++, WorldContainerStackResponsePayload.class,
                WorldContainerStackResponsePayload::write, WorldContainerStackResponsePayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> net.maxbel.takeitout.client.TakeitoutClient.handleWorldContainerStackResponse(payload));
                    ctx.setPacketHandled(true);
                });
        CHANNEL.registerMessage(id++, WorldContainerItemsPayload.class,
                WorldContainerItemsPayload::write, WorldContainerItemsPayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> net.maxbel.takeitout.client.TakeitoutClient.handleWorldContainerItems(payload));
                    ctx.setPacketHandled(true);
                });
        CHANNEL.registerMessage(id++, ServerConfigSyncPayload.class,
                ServerConfigSyncPayload::write, ServerConfigSyncPayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> net.maxbel.takeitout.client.TakeitoutClient.handleServerConfigSync(payload));
                    ctx.setPacketHandled(true);
                });
        CHANNEL.registerMessage(id++, SharedGroupsListPayload.class,
                SharedGroupsListPayload::write, SharedGroupsListPayload::read,
                (payload, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> net.maxbel.takeitout.client.TakeitoutClient.handleSharedGroupsList(payload));
                    ctx.setPacketHandled(true);
                });
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayerEntity player) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ServerConfigSyncPayload(linkedContainerScanLimit));
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SharedGroupsListPayload(loadSharedGroups()));
        }
    }

    private static void sendToPlayer(ServerPlayerEntity player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    private static void handlePublishGroupPayload(ServerPlayerEntity player, PublishGroupPayload payload) {
        String name = payload.name();
        if (name == null || name.isBlank() || name.length() > 64) {
            return;
        }
        if (payload.dimensions() == null) {
            return;
        }

        String playerId = player.getGameProfile().getId().toString();
        String playerName = player.getGameProfile().getName();

        List<SharedGroupEntry> groups = loadSharedGroups();
        groups.removeIf(g -> g.authorId().equals(playerId) && g.name().equals(name));

        long playerGroupCount = groups.stream().filter(g -> g.authorId().equals(playerId)).count();
        if (playerGroupCount >= MAX_GROUPS_PER_PLAYER) {
            player.sendMessage(Text.literal("TakeItOut: shared group limit reached (" + MAX_GROUPS_PER_PLAYER + ")"), false);
            return;
        }

        String groupId = UUID.randomUUID().toString();
        groups.add(new SharedGroupEntry(groupId, name, playerName, playerId, payload.dimensions()));
        saveSharedGroups(groups);
        broadcastSharedGroups(getServerFromPlayer(player), groups);
        player.sendMessage(Text.literal("TakeItOut: group \"" + name + "\" shared on server"), false);
    }

    private static void handleUnpublishGroupPayload(ServerPlayerEntity player, UnpublishGroupPayload payload) {
        String groupId = payload.groupId();
        if (groupId == null || groupId.isBlank()) {
            return;
        }

        String playerId = player.getGameProfile().getId().toString();
        List<SharedGroupEntry> groups = loadSharedGroups();
        boolean removed = groups.removeIf(g -> g.id().equals(groupId) && g.authorId().equals(playerId));

        if (removed) {
            saveSharedGroups(groups);
            broadcastSharedGroups(getServerFromPlayer(player), groups);
            player.sendMessage(Text.literal("TakeItOut: group removed from server"), false);
        }
    }

    private static List<SharedGroupEntry> loadSharedGroups() {
        if (!Files.exists(SHARED_GROUPS_PATH)) {
            return new ArrayList<>();
        }

        try {
            JsonArray arr = GSON.fromJson(Files.readString(SHARED_GROUPS_PATH), JsonArray.class);
            if (arr == null) {
                return new ArrayList<>();
            }

            List<SharedGroupEntry> groups = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                try {
                    String id = obj.get("id").getAsString();
                    String name = obj.get("name").getAsString();
                    String authorName = obj.get("authorName").getAsString();
                    String authorId = obj.get("authorId").getAsString();
                    List<SharedGroupDimension> dimensions = new ArrayList<>();
                    if (obj.has("dimensions") && obj.get("dimensions").isJsonArray()) {
                        for (JsonElement dimEl : obj.getAsJsonArray("dimensions")) {
                            if (!dimEl.isJsonObject()) {
                                continue;
                            }
                            JsonObject dimObj = dimEl.getAsJsonObject();
                            String dimension = dimObj.get("dimension").getAsString();
                            List<SharedSourceEntry> sources = new ArrayList<>();
                            if (dimObj.has("sources") && dimObj.get("sources").isJsonArray()) {
                                for (JsonElement srcEl : dimObj.getAsJsonArray("sources")) {
                                    if (!srcEl.isJsonObject()) {
                                        continue;
                                    }
                                    JsonObject srcObj = srcEl.getAsJsonObject();
                                    sources.add(new SharedSourceEntry(srcObj.get("pos").getAsLong(), srcObj.get("linked").getAsBoolean()));
                                }
                            }
                            dimensions.add(new SharedGroupDimension(dimension, sources));
                        }
                    }
                    groups.add(new SharedGroupEntry(id, name, authorName, authorId, dimensions));
                } catch (Exception ignored) {
                }
            }
            return groups;
        } catch (Exception e) {
            LOGGER.warn("Failed to load shared groups", e);
            return new ArrayList<>();
        }
    }

    private static void saveSharedGroups(List<SharedGroupEntry> groups) {
        try {
            Files.createDirectories(SHARED_GROUPS_PATH.getParent());
            JsonArray arr = new JsonArray();
            for (SharedGroupEntry group : groups) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", group.id());
                obj.addProperty("name", group.name());
                obj.addProperty("authorName", group.authorName());
                obj.addProperty("authorId", group.authorId());
                JsonArray dims = new JsonArray();
                for (SharedGroupDimension dim : group.dimensions()) {
                    JsonObject dimObj = new JsonObject();
                    dimObj.addProperty("dimension", dim.dimension());
                    JsonArray sources = new JsonArray();
                    for (SharedSourceEntry src : dim.sources()) {
                        JsonObject srcObj = new JsonObject();
                        srcObj.addProperty("pos", src.position());
                        srcObj.addProperty("linked", src.linked());
                        sources.add(srcObj);
                    }
                    dimObj.add("sources", sources);
                    dims.add(dimObj);
                }
                obj.add("dimensions", dims);
                arr.add(obj);
            }
            Files.writeString(SHARED_GROUPS_PATH, GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.warn("Failed to save shared groups", e);
        }
    }

    private static net.minecraft.server.MinecraftServer getServerFromPlayer(ServerPlayerEntity player) {
        if (player.getEntityWorld() instanceof ServerWorld serverWorld) {
            return serverWorld.getServer();
        }
        return null;
    }

    private static void broadcastSharedGroups(net.minecraft.server.MinecraftServer server, List<SharedGroupEntry> groups) {
        if (server == null) {
            return;
        }
        SharedGroupsListPayload packet = new SharedGroupsListPayload(groups);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            sendToPlayer(p, packet);
        }
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
                player.getInventory().selectedSlot,
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

        if (payload.fromUi() && !allowAllItemsTake) {
            player.sendMessage(Text.literal("TakeItOut: taking items via All Items tab is disabled on this server"), false);
            sendToPlayer(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), false));
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
            if (slot != -1 && extractFromWorldContainer(player, inventory, pos, slot, payload.singleItemMode(), payload.dumps())) {
                sendToPlayer(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), true));
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

        // Second pass: item not found directly — find a shulker box containing the most of the requested item
        if (!isShulkerItem(requested)) {
            int bestShulkerSlot = -1;
            int bestShulkerCount = 0;
            Inventory bestShulkerInventory = null;
            BlockPos bestShulkerPos = null;

            int scanned = 0;
            for (WorldContainerSource source : payload.sources()) {
                if (scanned >= scanLimit) {
                    break;
                }
                scanned++;

                Inventory inventory = getWorldContainerInventory(player, source);
                if (inventory == null) {
                    continue;
                }

                BlockPos pos = BlockPos.fromLong(source.position());
                for (int i = 0; i < inventory.size(); i++) {
                    ItemStack stack = inventory.getStack(i);
                    if (stack == null || stack.isEmpty() || !isShulkerItem(stack)) {
                        continue;
                    }

                    int count = 0;
                    for (ItemStack shulkerItem : copyContainerContents(stack)) {
                        if (!shulkerItem.isEmpty() && shulkerItem.isOf(requested.getItem())) {
                            count += shulkerItem.getCount();
                        }
                    }

                    if (count > bestShulkerCount) {
                        bestShulkerCount = count;
                        bestShulkerSlot = i;
                        bestShulkerInventory = inventory;
                        bestShulkerPos = pos;
                    }
                }
            }

            if (bestShulkerSlot != -1 && extractFromWorldContainer(player, bestShulkerInventory, bestShulkerPos, bestShulkerSlot, true, payload.dumps())) {
                sendToPlayer(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), true));
                LOGGER.debug(
                        "GetWorldContainerStack shulker fallback success: player={}, requested={}, pos={}, slot={}, itemCount={}",
                        player.getName().getString(),
                        requested,
                        bestShulkerPos,
                        bestShulkerSlot,
                        bestShulkerCount
                );
                return;
            }
        }

        // Third pass: item not found in world sources or shulkers — check the player's own carried Sophisticated Backpacks
        // (main inventory, offhand, worn armor slots, and Curios slots if that mod is installed)
        if (SophisticatedBackpacksCompat.isLoaded() && !isShulkerItem(requested) && !SophisticatedBackpacksCompat.isBackpackItem(requested)) {
            List<ItemStack> candidates = new ArrayList<>();
            for (int i = 0; i < Math.min(36, player.getInventory().size()); i++) {
                candidates.add(player.getInventory().getStack(i));
            }
            candidates.add(player.getOffHandStack());
            for (EquipmentSlot equipmentSlot : EquipmentSlot.values()) {
                if (equipmentSlot.getType() == EquipmentSlot.Type.ARMOR) {
                    candidates.add(player.getEquippedStack(equipmentSlot));
                }
            }
            if (CuriosCompat.isLoaded()) {
                candidates.addAll(CuriosCompat.getEquippedStacks(player));
            }

            for (ItemStack invStack : candidates) {
                if (invStack.isEmpty() || !SophisticatedBackpacksCompat.isBackpackItem(invStack)) continue;

                Inventory backpackInventory = SophisticatedBackpacksCompat.asContainer(invStack);
                if (backpackInventory == null) continue;

                int slot = getSlotWithStack(backpackInventory, requested);
                if (slot != -1 && extractFromWorldContainer(player, backpackInventory, null, slot, payload.singleItemMode(), payload.dumps())) {
                    sendToPlayer(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), true));
                    LOGGER.debug(
                            "GetWorldContainerStack carried-backpack success: player={}, requested={}, innerSlot={}, singleItemMode={}",
                            player.getName().getString(),
                            requested,
                            slot,
                            payload.singleItemMode()
                    );
                    return;
                }
            }
        }

        sendToPlayer(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), false));
        LOGGER.debug(
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
            sendToPlayer(player, new WorldContainerItemsPayload(items, containers));
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

        sendToPlayer(player, new WorldContainerItemsPayload(items, containers));
    }

    private static void handleDumpInventoryPayload(ServerPlayerEntity player, DumpInventoryPayload payload) {
        if (payload.dumps() == null || payload.dumps().isEmpty()) {
            return;
        }

        for (int i = 0; i < Math.min(36, player.getInventory().size()); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!canReplaceInventoryItem(stack)) {
                continue;
            }

            ItemStack remaining = stack.copy();
            for (WorldContainerSource dump : payload.dumps()) {
                if (remaining.isEmpty()) {
                    break;
                }
                Inventory dumpInventory = getWorldContainerInventory(player, dump);
                if (dumpInventory == null) {
                    continue;
                }
                remaining = insertIntoInventory(dumpInventory, remaining);
                syncWorldContainer(player, dumpInventory);
            }

            if (remaining.getCount() != stack.getCount()) {
                player.getInventory().setStack(i, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
            }
        }

        syncPlayerInventory(player);
        LOGGER.debug("DumpInventory: player={}", player.getName().getString());
    }

    private static void addItemCount(List<WorldContainerItemCount> items, ItemStack stack) {
        ItemStack keyStack = stack.copyWithCount(1);

        for (int i = 0; i < items.size(); i++) {
            WorldContainerItemCount existing = items.get(i);
            if (ItemStack.canCombine(existing.stack(), keyStack)) {
                items.set(i, new WorldContainerItemCount(existing.stack(), existing.count() + stack.getCount()));
                return;
            }
        }

        items.add(new WorldContainerItemCount(keyStack, stack.getCount()));
    }

    private static boolean extractFromWorldContainer(
            ServerPlayerEntity player,
            Inventory inventory,
            BlockPos pos,
            int slot,
            boolean singleItemMode,
            List<WorldContainerSource> dumps
    ) {
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

        if (ItemStack.canCombine(currentMainHand, extracted)
                && currentMainHand.getCount() < currentMainHand.getMaxCount()) {
            int canAdd = Math.min(currentMainHand.getMaxCount() - currentMainHand.getCount(), extracted.getCount());
            ItemStack actualRemaining = stackInContainer.copy();
            actualRemaining.decrement(canAdd);
            inventory.setStack(slot, actualRemaining.isEmpty() ? ItemStack.EMPTY : actualRemaining);
            syncWorldContainer(player, inventory);
            currentMainHand.increment(canAdd);
            player.setStackInHand(Hand.MAIN_HAND, currentMainHand);
            syncPlayerInventory(player);
            return true;
        }

        for (int i = 0; i < Math.min(36, player.getInventory().size()); i++) {
            ItemStack invStack = player.getInventory().getStack(i);
            if (ItemStack.canCombine(invStack, extracted)
                    && invStack.getCount() < invStack.getMaxCount()) {
                int canAdd = Math.min(invStack.getMaxCount() - invStack.getCount(), extracted.getCount());
                ItemStack actualRemaining = stackInContainer.copy();
                actualRemaining.decrement(canAdd);
                inventory.setStack(slot, actualRemaining.isEmpty() ? ItemStack.EMPTY : actualRemaining);
                syncWorldContainer(player, inventory);
                invStack.increment(canAdd);
                player.getInventory().setStack(i, invStack);
                syncPlayerInventory(player);
                return true;
            }
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
        if (canReplaceInventoryItem(currentMainHand)) {
            Inventory insertTarget = null;
            for (WorldContainerSource dumpSource : dumps) {
                Inventory dumpInv = getWorldContainerInventory(player, dumpSource);
                if (dumpInv != null && canInsertIntoInventory(dumpInv, currentMainHand)) {
                    insertTarget = dumpInv;
                    break;
                }
            }
            if (insertTarget == null && canInsertIntoInventory(inventory, currentMainHand)) {
                insertTarget = inventory;
            }
            if (insertTarget != null) {
                ItemStack leftover = insertIntoInventory(insertTarget, currentMainHand);
                if (!leftover.isEmpty()) {
                    inventory.setStack(slot, stackInContainer);
                    return false;
                }

                syncWorldContainer(player, inventory);
                if (insertTarget != inventory) {
                    syncWorldContainer(player, insertTarget);
                }
                player.setStackInHand(Hand.MAIN_HAND, extracted);
                syncPlayerInventory(player);
                return true;
            }
        }

        inventory.setStack(slot, stackInContainer);

        if (remainingInContainer.isEmpty()) {
            for (int i = Math.min(36, player.getInventory().size()) - 1; i >= 0; --i) {
                ItemStack item = player.getInventory().getStack(i);
                if (!canReplaceInventoryItem(item)) {
                    continue;
                }

                boolean dumped = false;
                for (WorldContainerSource dumpSource : dumps) {
                    Inventory dumpInv = getWorldContainerInventory(player, dumpSource);
                    if (dumpInv != null && canInsertIntoInventory(dumpInv, item)) {
                        insertIntoInventory(dumpInv, item);
                        syncWorldContainer(player, dumpInv);
                        inventory.setStack(slot, ItemStack.EMPTY);
                        syncWorldContainer(player, inventory);
                        player.getInventory().setStack(i, currentMainHand);
                        player.setStackInHand(Hand.MAIN_HAND, extracted);
                        syncPlayerInventory(player);
                        dumped = true;
                        break;
                    }
                }
                if (dumped) {
                    return true;
                }

                if (!inventory.isValid(slot, item)) {
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

            if (!ItemStack.canCombine(existing, remaining)) {
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

            if (!ItemStack.canCombine(existing, toInsert)) {
                continue;
            }

            int max = Math.min(existing.getMaxCount(), inventory.getMaxCountPerStack());
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

            remaining -= Math.min(toInsert.getMaxCount(), inventory.getMaxCountPerStack());
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

            if (!ItemStack.canCombine(existing, remaining)) {
                continue;
            }

            int max = Math.min(existing.getMaxCount(), inventory.getMaxCountPerStack());
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

            int move = Math.min(remaining.getCount(), Math.min(remaining.getMaxCount(), inventory.getMaxCountPerStack()));
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
            if (stack != null && !stack.isEmpty() && ItemStack.canCombine(stack, stackReference)) {
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
        } else if (SophisticatedBackpacksCompat.isLoaded() && SophisticatedBackpacksCompat.isBackpackBlock(block)) {
            inventory = SophisticatedBackpacksCompat.asContainer(world.getBlockEntity(pos));
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
            dimensionId = new Identifier(source.dimension());
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
        allowAllItemsTake = true;

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

            if (root.has(ALLOW_ALL_ITEMS_TAKE_KEY)) {
                allowAllItemsTake = root.get(ALLOW_ALL_ITEMS_TAKE_KEY).getAsBoolean();
            } else {
                root.addProperty(ALLOW_ALL_ITEMS_TAKE_KEY, allowAllItemsTake);
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
                    new Identifier(dimension);
                    ALLOWED_EXCHANGE_DIMENSIONS.add(dimension);
                } catch (Exception e) {
                    LOGGER.warn("Ignoring invalid TakeItOut exchange dimension in server config: {}", dimension);
                }
            }

            LOGGER.info(
                    "Server config loaded: linkedContainerExchangeMode={}, linkedContainerScanLimit={}, allowAllItemsTake={}, allowedExchangeDimensions={}",
                    linkedContainerExchangeMode.id,
                    linkedContainerScanLimit,
                    allowAllItemsTake,
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
        root.addProperty(ALLOW_ALL_ITEMS_TAKE_KEY, allowAllItemsTake);
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
        NbtCompound blockEntityTag = BlockItem.getBlockEntityNbt(shulkerStack);

        if (blockEntityTag != null && blockEntityTag.contains("Items", NbtElement.LIST_TYPE)) {
            Inventories.readNbt(blockEntityTag, stacks);
        }

        return stacks;
    }

    private static void setShulkerContents(ItemStack shulkerStack, List<ItemStack> itemStacks) {
        NbtCompound blockEntityTag = BlockItem.getBlockEntityNbt(shulkerStack);
        if (blockEntityTag == null) {
            blockEntityTag = new NbtCompound();
        }

        Inventories.writeNbt(blockEntityTag, DefaultedList.copyOf(ItemStack.EMPTY, itemStacks.toArray(new ItemStack[0])));
        blockEntityTag.putString("id", "minecraft:shulker_box");

        shulkerStack.getOrCreateNbt().put("BlockEntityTag", blockEntityTag);
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
