package net.maxbel.takeitout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
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
import net.minecraft.world.level.Level;
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

    public record GetShulkerStackPayload(int slot, int shulker, boolean singleItemMode) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetShulkerStackPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "getstack"));

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

    public record WorldContainerSource(String dimension, long position) {
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerSource> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        WorldContainerSource::dimension,
                        ByteBufCodecs.VAR_LONG,
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

    public record GetWorldContainerStackPayload(List<WorldContainerSource> sources, ItemStack stack, boolean singleItemMode, boolean fromUi, List<WorldContainerSource> dumps) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetWorldContainerStackPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "get_world_container_stack"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GetWorldContainerStackPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        GetWorldContainerStackPayload::sources,
                        ItemStack.STREAM_CODEC,
                        GetWorldContainerStackPayload::stack,
                        ByteBufCodecs.BOOL,
                        GetWorldContainerStackPayload::singleItemMode,
                        ByteBufCodecs.BOOL,
                        GetWorldContainerStackPayload::fromUi,
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        GetWorldContainerStackPayload::dumps,
                        GetWorldContainerStackPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record WorldContainerStackResponsePayload(ItemStack stack, boolean success) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<WorldContainerStackResponsePayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "world_container_stack_response"));

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

    public record WorldContainerContents(WorldContainerSource source, List<WorldContainerItemCount> items) {
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContainerContents> CODEC =
                StreamCodec.composite(
                        WorldContainerSource.CODEC,
                        WorldContainerContents::source,
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerItemCount.CODEC),
                        WorldContainerContents::items,
                        WorldContainerContents::new
                );
    }

    public record GetWorldContainerItemsPayload(List<WorldContainerSource> sources) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GetWorldContainerItemsPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "get_world_container_items"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GetWorldContainerItemsPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        GetWorldContainerItemsPayload::sources,
                        GetWorldContainerItemsPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record WorldContainerItemsPayload(
            List<WorldContainerItemCount> items,
            List<WorldContainerContents> containers
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<WorldContainerItemsPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "world_container_items"));

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

    public record DumpInventoryPayload(List<WorldContainerSource> dumps) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DumpInventoryPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "dump_inventory"));

        public static final StreamCodec<RegistryFriendlyByteBuf, DumpInventoryPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, WorldContainerSource.CODEC),
                        DumpInventoryPayload::dumps,
                        DumpInventoryPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record ServerConfigSyncPayload(int linkedContainerScanLimit) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ServerConfigSyncPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "server_config_sync"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigSyncPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        ServerConfigSyncPayload::linkedContainerScanLimit,
                        ServerConfigSyncPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record SharedSourceEntry(long position, boolean linked) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SharedSourceEntry> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG,
                        SharedSourceEntry::position,
                        ByteBufCodecs.BOOL,
                        SharedSourceEntry::linked,
                        SharedSourceEntry::new
                );
    }

    public record SharedGroupDimension(String dimension, List<SharedSourceEntry> sources) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SharedGroupDimension> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupDimension::dimension,
                        ByteBufCodecs.collection(ArrayList::new, SharedSourceEntry.CODEC),
                        SharedGroupDimension::sources,
                        SharedGroupDimension::new
                );
    }

    public record SharedGroupEntry(String id, String name, String authorName, String authorId, List<SharedGroupDimension> dimensions) {
        public static final StreamCodec<RegistryFriendlyByteBuf, SharedGroupEntry> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupEntry::id,
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupEntry::name,
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupEntry::authorName,
                        ByteBufCodecs.STRING_UTF8,
                        SharedGroupEntry::authorId,
                        ByteBufCodecs.collection(ArrayList::new, SharedGroupDimension.CODEC),
                        SharedGroupEntry::dimensions,
                        SharedGroupEntry::new
                );
    }

    public record PublishGroupPayload(String name, List<SharedGroupDimension> dimensions) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PublishGroupPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "publish_group"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PublishGroupPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        PublishGroupPayload::name,
                        ByteBufCodecs.collection(ArrayList::new, SharedGroupDimension.CODEC),
                        PublishGroupPayload::dimensions,
                        PublishGroupPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record UnpublishGroupPayload(String groupId) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UnpublishGroupPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "unpublish_group"));

        public static final StreamCodec<RegistryFriendlyByteBuf, UnpublishGroupPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8,
                        UnpublishGroupPayload::groupId,
                        UnpublishGroupPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public record SharedGroupsListPayload(List<SharedGroupEntry> groups) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SharedGroupsListPayload> ID =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("takeitout", "shared_groups_list"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SharedGroupsListPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.collection(ArrayList::new, SharedGroupEntry.CODEC),
                        SharedGroupsListPayload::groups,
                        SharedGroupsListPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    public static void init(IEventBus modEventBus) {
        loadServerConfig();
        modEventBus.addListener(Takeitout::registerPayloads);
        NeoForge.EVENT_BUS.addListener(Takeitout::onPlayerJoin);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(GetShulkerStackPayload.ID, GetShulkerStackPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleGetShulkerStack((ServerPlayer) context.player(), payload))
        );
        registrar.playToServer(GetWorldContainerStackPayload.ID, GetWorldContainerStackPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleGetWorldContainerStack((ServerPlayer) context.player(), payload))
        );
        registrar.playToServer(GetWorldContainerItemsPayload.ID, GetWorldContainerItemsPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleGetWorldContainerItems((ServerPlayer) context.player(), payload))
        );
        registrar.playToServer(DumpInventoryPayload.ID, DumpInventoryPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleDumpInventoryPayload((ServerPlayer) context.player(), payload))
        );
        registrar.playToServer(PublishGroupPayload.ID, PublishGroupPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> handlePublishGroupPayload((ServerPlayer) context.player(), payload))
        );
        registrar.playToServer(UnpublishGroupPayload.ID, UnpublishGroupPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> handleUnpublishGroupPayload((ServerPlayer) context.player(), payload))
        );

        if (FMLEnvironment.dist.isClient()) {
            registrar.playToClient(WorldContainerStackResponsePayload.ID, WorldContainerStackResponsePayload.CODEC, (payload, context) ->
                    context.enqueueWork(() -> net.maxbel.takeitout.client.TakeitoutClient.handleWorldContainerStackResponse(payload)));
            registrar.playToClient(WorldContainerItemsPayload.ID, WorldContainerItemsPayload.CODEC, (payload, context) ->
                    context.enqueueWork(() -> net.maxbel.takeitout.client.TakeitoutClient.handleWorldContainerItems(payload)));
            registrar.playToClient(ServerConfigSyncPayload.ID, ServerConfigSyncPayload.CODEC, (payload, context) ->
                    context.enqueueWork(() -> net.maxbel.takeitout.client.TakeitoutClient.handleServerConfigSync(payload)));
            registrar.playToClient(SharedGroupsListPayload.ID, SharedGroupsListPayload.CODEC, (payload, context) ->
                    context.enqueueWork(() -> net.maxbel.takeitout.client.TakeitoutClient.handleSharedGroupsList(payload)));
        } else {
            registrar.playToClient(WorldContainerStackResponsePayload.ID, WorldContainerStackResponsePayload.CODEC, (payload, context) -> {});
            registrar.playToClient(WorldContainerItemsPayload.ID, WorldContainerItemsPayload.CODEC, (payload, context) -> {});
            registrar.playToClient(ServerConfigSyncPayload.ID, ServerConfigSyncPayload.CODEC, (payload, context) -> {});
            registrar.playToClient(SharedGroupsListPayload.ID, SharedGroupsListPayload.CODEC, (payload, context) -> {});
        }
    }

    @SubscribeEvent
    private static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new ServerConfigSyncPayload(linkedContainerScanLimit));
            PacketDistributor.sendToPlayer(player, new SharedGroupsListPayload(loadSharedGroups()));
        }
    }

    private static void handlePublishGroupPayload(ServerPlayer player, PublishGroupPayload payload) {
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
            player.sendSystemMessage(Component.literal("TakeItOut: shared group limit reached (" + MAX_GROUPS_PER_PLAYER + ")"));
            return;
        }

        String groupId = UUID.randomUUID().toString();
        groups.add(new SharedGroupEntry(groupId, name, playerName, playerId, payload.dimensions()));
        saveSharedGroups(groups);
        broadcastSharedGroups(getServerFromPlayer(player), groups);
        player.sendSystemMessage(Component.literal("TakeItOut: group \"" + name + "\" shared on server"));
    }

    private static void handleUnpublishGroupPayload(ServerPlayer player, UnpublishGroupPayload payload) {
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
            player.sendSystemMessage(Component.literal("TakeItOut: group removed from server"));
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

    private static net.minecraft.server.MinecraftServer getServerFromPlayer(ServerPlayer player) {
        if (player.level() instanceof ServerLevel serverLevel) {
            return serverLevel.getServer();
        }
        return null;
    }

    private static void broadcastSharedGroups(net.minecraft.server.MinecraftServer server, List<SharedGroupEntry> groups) {
        if (server == null) {
            return;
        }
        SharedGroupsListPayload packet = new SharedGroupsListPayload(groups);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, packet);
        }
    }

    private static void handleGetShulkerStack(ServerPlayer player, GetShulkerStackPayload payload) {
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

        ItemStack shulker = player.getInventory().getItem(shulkerSlot);
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
        remainingInShulker.shrink(extracted.getCount());

        ItemStack currentMainHand = player.getItemInHand(InteractionHand.MAIN_HAND).copy();

        LOGGER.debug(
                "GetShulkerStack request: player={}, shulkerSlot={}, shulkerInnerSlot={}, selectedSlot={}, singleItemMode={}, currentHand={}, extracted={}",
                player.getName().getString(),
                shulkerSlot,
                slotInShulker,
                player.getInventory().selected,
                singleItemMode,
                currentMainHand,
                extracted
        );

        // 1. Если рука пустая — просто кладём extracted в руку
        if (currentMainHand.isEmpty()) {
            itemStacks.set(slotInShulker, remainingInShulker.isEmpty() ? ItemStack.EMPTY : remainingInShulker);
            setShulkerContents(shulker, itemStacks);

            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
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
        int freeSlot = player.getInventory().getFreeSlot();
        if (freeSlot != -1) {
            itemStacks.set(slotInShulker, remainingInShulker.isEmpty() ? ItemStack.EMPTY : remainingInShulker);
            setShulkerContents(shulker, itemStacks);

            player.getInventory().setItem(freeSlot, currentMainHand);
            player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
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

                player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
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
            for (int i = Math.min(36, player.getInventory().getContainerSize()) - 1; i >= 0; --i) {
                ItemStack item = player.getInventory().getItem(i);

                if (!canReplaceInventoryItem(item)) {
                    continue;
                }

                itemStacks.set(slotInShulker, item.copy());
                setShulkerContents(shulker, itemStacks);

                player.getInventory().setItem(i, currentMainHand);
                player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
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

    private static void handleGetWorldContainerStack(ServerPlayer player, GetWorldContainerStackPayload payload) {
        ItemStack requested = payload.stack();
        if (requested == null || requested.isEmpty() || payload.sources() == null) {
            return;
        }

        if (payload.fromUi() && !allowAllItemsTake) {
            player.sendSystemMessage(Component.literal("TakeItOut: taking items via All Items tab is disabled on this server"));
            PacketDistributor.sendToPlayer(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), false));
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

            BlockPos pos = BlockPos.of(source.position());
            Container inventory = getWorldContainerInventory(player, source);
            if (inventory == null) {
                invalidSourceCount++;
                continue;
            }

            int slot = getSlotWithStack(inventory, requested);
            if (slot != -1 && extractFromWorldContainer(player, inventory, pos, slot, payload.singleItemMode(), payload.dumps())) {
                PacketDistributor.sendToPlayer(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), true));
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
            Container bestShulkerInventory = null;
            BlockPos bestShulkerPos = null;

            int scanned = 0;
            for (WorldContainerSource source : payload.sources()) {
                if (scanned >= scanLimit) {
                    break;
                }
                scanned++;

                Container inventory = getWorldContainerInventory(player, source);
                if (inventory == null) {
                    continue;
                }

                BlockPos pos = BlockPos.of(source.position());
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (stack == null || stack.isEmpty() || !isShulkerItem(stack)) {
                        continue;
                    }

                    int count = 0;
                    for (ItemStack shulkerItem : copyContainerContents(stack)) {
                        if (!shulkerItem.isEmpty() && shulkerItem.is(requested.getItem())) {
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
                PacketDistributor.sendToPlayer(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), true));
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

        PacketDistributor.sendToPlayer(player, new WorldContainerStackResponsePayload(requested.copyWithCount(1), false));
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

    private static void handleGetWorldContainerItems(ServerPlayer player, GetWorldContainerItemsPayload payload) {
        List<WorldContainerItemCount> items = new ArrayList<>();
        List<WorldContainerContents> containers = new ArrayList<>();
        if (payload.sources() == null) {
            PacketDistributor.sendToPlayer(player, new WorldContainerItemsPayload(items, containers));
            return;
        }

        int checked = 0;
        int scanLimit = linkedContainerScanLimit;
        for (WorldContainerSource source : payload.sources()) {
            if (checked >= scanLimit) {
                break;
            }
            checked++;

            Container inventory = getWorldContainerInventory(player, source);
            List<WorldContainerItemCount> containerItems = new ArrayList<>();
            if (inventory == null) {
                containers.add(new WorldContainerContents(source, containerItems));
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

            containers.add(new WorldContainerContents(source, containerItems));
        }

        PacketDistributor.sendToPlayer(player, new WorldContainerItemsPayload(items, containers));
    }

    private static void handleDumpInventoryPayload(ServerPlayer player, DumpInventoryPayload payload) {
        if (payload.dumps() == null || payload.dumps().isEmpty()) {
            return;
        }

        for (int i = 0; i < Math.min(36, player.getInventory().getContainerSize()); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!canReplaceInventoryItem(stack)) {
                continue;
            }

            ItemStack remaining = stack.copy();
            for (WorldContainerSource dump : payload.dumps()) {
                if (remaining.isEmpty()) {
                    break;
                }
                Container dumpInventory = getWorldContainerInventory(player, dump);
                if (dumpInventory == null) {
                    continue;
                }
                remaining = insertIntoInventory(dumpInventory, remaining);
                syncWorldContainer(player, dumpInventory);
            }

            if (remaining.getCount() != stack.getCount()) {
                player.getInventory().setItem(i, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
            }
        }

        syncPlayerInventory(player);
        LOGGER.debug("DumpInventory: player={}", player.getName().getString());
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
            boolean singleItemMode,
            List<WorldContainerSource> dumps
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

        if (ItemStack.isSameItemSameComponents(currentMainHand, extracted)
                && currentMainHand.getCount() < currentMainHand.getMaxStackSize()) {
            int canAdd = Math.min(currentMainHand.getMaxStackSize() - currentMainHand.getCount(), extracted.getCount());
            ItemStack actualRemaining = stackInContainer.copy();
            actualRemaining.shrink(canAdd);
            inventory.setItem(slot, actualRemaining.isEmpty() ? ItemStack.EMPTY : actualRemaining);
            syncWorldContainer(player, inventory);
            currentMainHand.grow(canAdd);
            player.setItemInHand(InteractionHand.MAIN_HAND, currentMainHand);
            syncPlayerInventory(player);
            return true;
        }

        for (int i = 0; i < Math.min(36, player.getInventory().getContainerSize()); i++) {
            ItemStack invStack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(invStack, extracted)
                    && invStack.getCount() < invStack.getMaxStackSize()) {
                int canAdd = Math.min(invStack.getMaxStackSize() - invStack.getCount(), extracted.getCount());
                ItemStack actualRemaining = stackInContainer.copy();
                actualRemaining.shrink(canAdd);
                inventory.setItem(slot, actualRemaining.isEmpty() ? ItemStack.EMPTY : actualRemaining);
                syncWorldContainer(player, inventory);
                invStack.grow(canAdd);
                player.getInventory().setItem(i, invStack);
                syncPlayerInventory(player);
                return true;
            }
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
        if (canReplaceInventoryItem(currentMainHand)) {
            Container insertTarget = null;
            for (WorldContainerSource dumpSource : dumps) {
                Container dumpInv = getWorldContainerInventory(player, dumpSource);
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
                    inventory.setItem(slot, stackInContainer);
                    return false;
                }

                syncWorldContainer(player, inventory);
                if (insertTarget != inventory) {
                    syncWorldContainer(player, insertTarget);
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
                syncPlayerInventory(player);
                return true;
            }
        }

        inventory.setItem(slot, stackInContainer);

        if (remainingInContainer.isEmpty()) {
            for (int i = Math.min(36, player.getInventory().getContainerSize()) - 1; i >= 0; --i) {
                ItemStack item = player.getInventory().getItem(i);
                if (!canReplaceInventoryItem(item)) {
                    continue;
                }

                boolean dumped = false;
                for (WorldContainerSource dumpSource : dumps) {
                    Container dumpInv = getWorldContainerInventory(player, dumpSource);
                    if (dumpInv != null && canInsertIntoInventory(dumpInv, item)) {
                        insertIntoInventory(dumpInv, item);
                        syncWorldContainer(player, dumpInv);
                        inventory.setItem(slot, ItemStack.EMPTY);
                        syncWorldContainer(player, inventory);
                        player.getInventory().setItem(i, currentMainHand);
                        player.setItemInHand(InteractionHand.MAIN_HAND, extracted);
                        syncPlayerInventory(player);
                        dumped = true;
                        break;
                    }
                }
                if (dumped) {
                    return true;
                }

                if (!inventory.canPlaceItem(slot, item)) {
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

        // Сначала пытаемся достакать в уже существующие стаки
        for (int i = 0; i < itemStacks.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = itemStacks.get(i);

            if (existing == null || existing.isEmpty()) {
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

        // Потом ищем пустые слоты
        for (int i = 0; i < itemStacks.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = itemStacks.get(i);

            if (existing != null && !existing.isEmpty()) {
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

    private static boolean canInsertIntoInventory(Container inventory, ItemStack toInsert) {
        int remaining = toInsert.getCount();

        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing == null || existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(existing, toInsert)) {
                continue;
            }

            int max = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize(existing));
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

            remaining -= Math.min(toInsert.getMaxStackSize(), inventory.getMaxStackSize(toInsert));
        }

        return remaining <= 0;
    }

    private static ItemStack insertIntoInventory(Container inventory, ItemStack toInsert) {
        ItemStack remaining = toInsert.copy();

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing == null || existing.isEmpty()) {
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }

            int max = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize(existing));
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

            int move = Math.min(remaining.getCount(), Math.min(remaining.getMaxStackSize(), inventory.getMaxStackSize(remaining)));
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

    private static Container getWorldContainerInventory(ServerPlayer player, WorldContainerSource source) {
        ServerLevel world = getSourceWorld(player, source);
        if (world == null) {
            return null;
        }

        BlockPos pos = BlockPos.of(source.position());
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        Container inventory = null;

        if (block instanceof ChestBlock chestBlock) {
            inventory = ChestBlock.getContainer(chestBlock, state, world, pos, true);
        } else if (block instanceof ShulkerBoxBlock || block instanceof BarrelBlock) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof Container blockInventory) {
                inventory = blockInventory;
            }
        }

        if (inventory == null) {
            return null;
        }

        return inventory;
    }

    private static ServerLevel getSourceWorld(ServerPlayer player, WorldContainerSource source) {
        if (source == null || source.dimension() == null || source.dimension().isBlank()) {
            return null;
        }

        String playerDimension = player.level().dimension().location().toString();
        if (linkedContainerExchangeMode == LinkedContainerExchangeMode.DISABLED) {
            LOGGER.warn(
                    "World container source blocked by server config: player={}, reason=disabled, playerDimension={}, sourceDimension={}, pos={}",
                    player.getName().getString(),
                    playerDimension,
                    source.dimension(),
                    BlockPos.of(source.position())
            );
            return null;
        }

        if (linkedContainerExchangeMode == LinkedContainerExchangeMode.SAME_DIMENSION && !playerDimension.equals(source.dimension())) {
            LOGGER.warn(
                    "World container source blocked by server config: player={}, reason=same_dimension_only, playerDimension={}, sourceDimension={}, pos={}",
                    player.getName().getString(),
                    playerDimension,
                    source.dimension(),
                    BlockPos.of(source.position())
            );
            return null;
        }

        if (!isDimensionAllowedForExchange(playerDimension) || !isDimensionAllowedForExchange(source.dimension())) {
            LOGGER.warn(
                    "World container source blocked by server config: player={}, playerDimension={}, sourceDimension={}, pos={}",
                    player.getName().getString(),
                    playerDimension,
                    source.dimension(),
                    BlockPos.of(source.position())
            );
            return null;
        }

        ResourceLocation dimensionId;
        try {
            dimensionId = ResourceLocation.parse(source.dimension());
        } catch (Exception ignored) {
            return null;
        }

        ResourceKey<Level> worldKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
        if (!(player.level() instanceof ServerLevel playerWorld)) {
            return null;
        }

        return playerWorld.getServer().getLevel(worldKey);
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
                    ResourceLocation.parse(dimension);
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

    private static boolean isValidInventorySlot(ServerPlayer player, int slot) {
        return slot >= 0 && slot < player.getInventory().getContainerSize();
    }

    private static List<ItemStack> copyContainerContents(ItemStack shulkerStack) {
        NonNullList<ItemStack> stacks = NonNullList.withSize(27, ItemStack.EMPTY);
        ItemContainerContents container = shulkerStack.get(DataComponents.CONTAINER);

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
        shulkerStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(itemStacks));
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
