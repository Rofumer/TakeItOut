package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

import static fi.dy.masa.litematica.util.WorldUtils.getValidBlockRange;
import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("TakeItOut");
    @Unique
    private static final boolean VERBOSE_LOG = false;
    @Unique
    private static final Set<String> PLACE_STATE_IGNORED_PROPERTIES = Set.of("lit", "powered", "open");
    @Unique
    private static final Set<String> FENCE_WALL_IGNORED_PROPERTIES = Set.of("north", "south", "east", "west", "up");
    @Unique
    private static final Set<String> MUSHROOM_BLOCK_IGNORED_PROPERTIES = Set.of("north", "south", "east", "west", "up", "down");

    @Unique private static int waitTicks = 0;
    @Unique private static boolean waitingForItem = false;
    @Unique private static BlockState waitingState = null;
    @Unique private static int retryCount = 0;
    @Unique private static int expectedWaitTicks = 40;
    @Unique private static long requestTsMs = 0L;
    @Unique private static BlockPos lastEasyPlacePos = null;
    @Unique private static BlockState lastEasyPlaceTargetState = null;
    @Unique private static boolean autoPlaceRetryInProgress = false;
    @Unique private static boolean autoPlaceRetriedForCurrentWait = false;

    @Unique
    private static int computeExpectedWaitTicks(Minecraft mc) {
        int pingMs = 0;
        try {
            if (mc != null && mc.getConnection() != null && mc.player != null) {
                var entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (entry != null) {
                    pingMs = entry.getLatency();
                }
            }
        } catch (Throwable ignored) {
        }

        if (pingMs <= 0) {
            pingMs = 180;
        }

        int ms = (int) Math.round(pingMs * 2.5 + 200);
        int ticks = (ms + 49) / 50;
        ticks = Math.max(20, Math.min(ticks, 200));
        return ticks;
    }

    @Unique
    private static int jitter(int baseTicks, int percent) {
        int spread = Math.max(1, baseTicks * percent / 100);
        return baseTicks + (int) (System.nanoTime() % (2L * spread + 1)) - spread;
    }

    @Unique
    private static String slotToHotbarHuman(int slot) {
        if (slot >= 0 && slot <= 8) {
            return (slot + 1) + "/9";
        }
        return "non-hotbar(" + slot + ")";
    }

    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void interceptMissingItem(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir) {
        if (mc == null || mc.player == null) {
            return;
        }

        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 6, true, true);
        if (result == null) {
            return;
        }

        BlockPos pos = result.getBlockPos();
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) {
            return;
        }

        BlockState state = schematic.getBlockState(pos);
        BlockState worldState = mc.level.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state);
        ItemStack inHand = mc.player.getMainHandItem();

        if (arePlacementEquivalent(worldState, state)) {
            return;
        }

        if (waitingForItem && waitingState != null && !waitingState.equals(state)) {
            logVerbose(
                    "[RMB_FLOW] target changed while waiting: oldState={}, newState={}, oldPos={}, newPos={}. Resetting wait.",
                    waitingState,
                    state,
                    lastEasyPlacePos,
                    pos
            );
            waitingForItem = false;
            waitingState = null;
            waitTicks = 0;
            retryCount = 0;
            autoPlaceRetriedForCurrentWait = false;
        }

        // We verify success against the exact target selected at action start.
        lastEasyPlacePos = pos.immutable();
        lastEasyPlaceTargetState = state;

        if (!ItemStack.isSameItemSameComponents(inHand, required)) {
            logVerbose(
                    "[RMB_FLOW] missing required item: hologramPos={}, hologramState={}, worldState={}, required={}, inHand={}, selectedHotbarSlot={}",
                    pos,
                    state,
                    worldState,
                    required,
                    inHand,
                    slotToHotbarHuman(mc.player.getInventory().getSelectedSlot())
            );

            if (!waitingForItem) {
                logVerbose("[RMB_FLOW] requesting pick block for missing item");
                WorldUtils.doSchematicWorldPickBlock(true, mc);
                waitingForItem = true;
                waitingState = state;
                waitTicks = 0;
                retryCount = 0;
                expectedWaitTicks = computeExpectedWaitTicks(mc);
                requestTsMs = System.currentTimeMillis();
                autoPlaceRetriedForCurrentWait = false;
                logVerbose("[RMB_FLOW] waiting started, expectedWaitTicks={}", expectedWaitTicks);
            }

            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
        }
    }

    @ModifyArg(
            method = "easyPlaceOnUseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/WorldUtils;doEasyPlaceAction(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/world/InteractionResult;"
            ),
            remap = false
    )
    private static Minecraft checkItemAndTick(Minecraft client) {
        if (waitingForItem && client != null && client.player != null && waitingState != null) {
            ItemStack inHand = client.player.getMainHandItem();
            ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(waitingState);
            int inventorySlot = InventoryUtils.findSlotWithItem(client.player.containerMenu, required, true);

            if (!ItemStack.isSameItemSameComponents(inHand, required) && inventorySlot != -1) {
                logVerbose(
                        "[RMB_FLOW] required item arrived in inventory: slot={}, selectedHotbarSlot={}, stack={}",
                        inventorySlot,
                        slotToHotbarHuman(client.player.getInventory().getSelectedSlot()),
                        required
                );
                InventoryUtils.swapItemToMainHand(required, client);
                inHand = client.player.getMainHandItem();
            }

            if (WorldContainerSources.consumeFailedResponse(required.copyWithCount(1))) {
                LOGGER.warn(
                        "[RMB_FLOW] item request failed: required={}, selectedHotbarSlot={}, mainHand={}",
                        required,
                        slotToHotbarHuman(client.player.getInventory().getSelectedSlot()),
                        inHand
                );
                waitingForItem = false;
                waitingState = null;
                waitTicks = 0;
                retryCount = 0;
                autoPlaceRetriedForCurrentWait = false;
                return client;
            }

            if (ItemStack.isSameItemSameComponents(inHand, required)) {
                if (!autoPlaceRetriedForCurrentWait
                        && !autoPlaceRetryInProgress
                        && lastEasyPlacePos != null
                        && lastEasyPlaceTargetState != null
                        && !arePlacementEquivalent(client.level.getBlockState(lastEasyPlacePos), lastEasyPlaceTargetState)) {
                    autoPlaceRetriedForCurrentWait = true;
                    autoPlaceRetryInProgress = true;
                    try {
                        InteractionResult retryResult = invokeDoEasyPlaceActionReflective(client);
                        logVerbose("[RMB_FLOW] auto place retry on item arrival: result={}", retryResult);
                    } finally {
                        autoPlaceRetryInProgress = false;
                    }
                }

                logVerbose(
                        "[RMB_FLOW] required item arrived in hand: waitedTicks={}, retries={}, selectedHotbarSlot={}, mainHand={}",
                        waitTicks,
                        retryCount,
                        slotToHotbarHuman(client.player.getInventory().getSelectedSlot()),
                        inHand
                );
                waitingForItem = false;
                waitingState = null;
                waitTicks = 0;
                retryCount = 0;
                autoPlaceRetriedForCurrentWait = false;
            } else {
                waitTicks++;

                if (retryCount == 0 && waitTicks >= jitter(expectedWaitTicks / 2, 10)) {
                    logVerbose("[RMB_FLOW] retry pick block #1 at tick {}", waitTicks);
                    WorldUtils.doSchematicWorldPickBlock(true, client);
                    retryCount = 1;
                } else if (retryCount == 1 && waitTicks >= jitter(expectedWaitTicks, 10)) {
                    logVerbose("[RMB_FLOW] retry pick block #2 at tick {}", waitTicks);
                    WorldUtils.doSchematicWorldPickBlock(true, client);
                    retryCount = 2;
                }

                int hardTimeout = expectedWaitTicks + Math.max(10, expectedWaitTicks / 2);
                if (waitTicks >= hardTimeout) {
                    LOGGER.warn(
                            "[RMB_FLOW] timeout waiting item from shulker/world container: expectedWaitTicks={}, waitedTicks={}, retries={}, elapsedMs={}",
                            expectedWaitTicks,
                            waitTicks,
                            retryCount,
                            (System.currentTimeMillis() - requestTsMs)
                    );
                    waitingForItem = false;
                    waitingState = null;
                    waitTicks = 0;
                    retryCount = 0;
                    autoPlaceRetriedForCurrentWait = false;
                }
            }
        }

        return client;
    }

    @Unique
    private static InteractionResult invokeDoEasyPlaceActionReflective(Minecraft client) {
        try {
            var method = WorldUtils.class.getDeclaredMethod("doEasyPlaceAction", Minecraft.class);
            method.setAccessible(true);
            Object result = method.invoke(null, client);
            if (result instanceof InteractionResult interactionResult) {
                return interactionResult;
            }
        } catch (Throwable t) {
            LOGGER.warn("[RMB_FLOW] auto place retry reflective call failed", t);
        }

        return InteractionResult.PASS;
    }

    @Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private static void doSchematicWorldPickBlockHook(boolean closest, Minecraft mc,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (mc == null || mc.player == null) {
            return;
        }

        if (!isSelectedHotbarSlotAllowedByLitematica()) {
            logVerbose("[RMB_FLOW] pick blocked by PICK_BLOCKABLE_SLOTS, selected={}", slotToHotbarHuman(mc.player.getInventory().getSelectedSlot()));
            return;
        }

        final int range = (int) getValidBlockRange(mc);
        BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(mc.player, range, true, true);

        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            logVerbose("[RMB_FLOW] pick blocked: no valid schematic hit, hit={}", hit);
            return;
        }

        BlockPos pos = hit.getBlockPos();
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) {
            logVerbose("[RMB_FLOW] pick blocked: schematic world null");
            return;
        }

        BlockState state = world.getBlockState(pos);
        BlockState worldState = mc.level.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state, world, pos);

        logVerbose(
                "[RMB_FLOW] pick context: hologramPos={}, hologramState={}, worldState={}, requiredItem={}, mainHand={}, selectedHotbarSlot={}",
                pos,
                state,
                worldState,
                required,
                mc.player.getMainHandItem(),
                slotToHotbarHuman(mc.player.getInventory().getSelectedSlot())
        );

        if (!ItemStack.isSameItemSameComponents(mc.player.getMainHandItem(), required)) {
            int slot = InventoryUtils.findSlotWithItem(mc.player.containerMenu, required, true);
            logVerbose("[RMB_FLOW] required item not in hand. direct inventory slot={}", slot);

            if (slot != -1) {
                logVerbose("[RMB_FLOW] swapping item from slot {} to selected hotbar {}", slot, slotToHotbarHuman(mc.player.getInventory().getSelectedSlot()));
                InventoryUtils.swapItemToMainHand(required, mc);
            } else {
                int shulkerSlot = getShulkerWithStack(mc.player.getInventory(), required);
                logVerbose("[RMB_FLOW] searching shulker with required item. shulkerSlot={}", shulkerSlot);

                if (shulkerSlot != -1) {
                    Container shInv = getInventoryFromShulker(mc.player.getInventory().getItem(shulkerSlot));
                    int inner = getSlotWithStack(shInv, required);
                    logVerbose("[RMB_FLOW] shulker lookup result: shulkerSlot={}, innerSlot={}", shulkerSlot, inner);

                    if (inner != -1) {
                        boolean canSend = ClientPlayNetworking.canSend(Takeitout.GetShulkerStackPayload.ID);
                        logVerbose("[RMB_FLOW] shulker extract network available={}", canSend);

                        if (canSend) {
                            logVerbose(
                                    "[RMB_FLOW] requesting shulker extract: fromShulkerSlot={}, fromShulkerInnerSlot={}, expectedTargetHotbarSlot={}",
                                    shulkerSlot,
                                    inner,
                                    slotToHotbarHuman(mc.player.getInventory().getSelectedSlot())
                            );
                            TakeitoutClient.awaitingStack = required.copyWithCount(1);
                            ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(inner, shulkerSlot, TakeitoutClient.SHULKER_SINGLE_ITEM_MODE));
                        } else {
                            LOGGER.warn("[RMB_FLOW] cannot request shulker extract: payload channel unavailable");
                        }
                    }
                } else {
                    logVerbose(
                            "[RMB_FLOW] required item not found in inventory/shulkers, trying world containers: required={}, sources={}, selectedHotbarSlot={}",
                            required,
                            WorldContainerSources.size(),
                            slotToHotbarHuman(mc.player.getInventory().getSelectedSlot())
                    );
                    WorldContainerSources.requestStack(mc, required, TakeitoutClient.TAKE_SINGLE_ITEM_MODE);
                }
            }
        }

        fi.dy.masa.litematica.util.InventoryUtils.schematicWorldPickBlock(required, pos, world, mc);
        logVerbose(
            "[RMB_FLOW] schematicWorldPickBlock completed: selectedHotbarSlot={}, mainHandNow={}",
            slotToHotbarHuman(mc.player.getInventory().getSelectedSlot()),
            mc.player.getMainHandItem()
        );

        cir.setReturnValue(true);
        cir.cancel();
    }

    @Inject(method = "doEasyPlaceAction", at = @At("RETURN"), remap = false)
    private static void logPlaceResult(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir) {
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }

        if (lastEasyPlacePos == null || lastEasyPlaceTargetState == null) {
            return;
        }

        BlockPos pos = lastEasyPlacePos;
        BlockState hologramState = lastEasyPlaceTargetState;
        BlockState worldState = mc.level.getBlockState(lastEasyPlacePos);
        boolean placed = arePlacementEquivalent(worldState, hologramState);

        if (!placed || cir.getReturnValue() == InteractionResult.FAIL) {
            LOGGER.warn(
                    "[RMB_FLOW] place result: result={}, hologramPos={}, hologramState={}, worldState={}, placedMatchesHologram={}, selectedHotbarSlot={}, mainHand={}",
                    cir.getReturnValue(),
                    pos,
                    hologramState,
                    worldState,
                    placed,
                    slotToHotbarHuman(mc.player.getInventory().getSelectedSlot()),
                    mc.player.getMainHandItem()
            );
        } else {
            logVerbose(
                    "[RMB_FLOW] place result: result={}, hologramPos={}, hologramState={}, worldState={}, placedMatchesHologram={}, selectedHotbarSlot={}, mainHand={}",
                    cir.getReturnValue(),
                    pos,
                    hologramState,
                    worldState,
                    placed,
                    slotToHotbarHuman(mc.player.getInventory().getSelectedSlot()),
                    mc.player.getMainHandItem()
            );
        }

        lastEasyPlacePos = null;
        lastEasyPlaceTargetState = null;
    }

    @Unique
    private static boolean shouldIgnorePlacementProperty(BlockState targetState, Property<?> property) {
        String name = property.getName();

        if (PLACE_STATE_IGNORED_PROPERTIES.contains(name)) {
            return true;
        }

        return ((targetState.getBlock() instanceof FenceBlock || targetState.getBlock() instanceof WallBlock)
                && FENCE_WALL_IGNORED_PROPERTIES.contains(name))
                || (targetState.getBlock() instanceof MushroomBlock
                && MUSHROOM_BLOCK_IGNORED_PROPERTIES.contains(name));
    }

    @Unique
    private static boolean arePlacementEquivalent(BlockState worldState, BlockState targetState) {
        if (worldState.equals(targetState)) {
            return true;
        }

        if (!worldState.is(targetState.getBlock())) {
            return false;
        }

        for (Property<?> property : targetState.getProperties()) {
            if (shouldIgnorePlacementProperty(targetState, property)) {
                continue;
            }

            if (!worldState.hasProperty(property)) {
                return false;
            }

            if (!worldState.getValue(property).equals(targetState.getValue(property))) {
                return false;
            }
        }

        return true;
    }

    @Unique
    private static boolean isSelectedHotbarSlotAllowedByLitematica() {
        try {
            String raw = Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue();
            if (raw == null || raw.trim().isEmpty()) {
                return true;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                return true;
            }

            int selected = mc.player.getInventory().getSelectedSlot();

            for (String part : raw.split(",")) {
                part = part.trim();
                if (part.isEmpty()) {
                    continue;
                }

                try {
                    int oneBased = Integer.parseInt(part);
                    int zeroBased = oneBased - 1;
                    if (zeroBased == selected) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Unique
    private static void logVerbose(String message, Object... args) {
        if (VERBOSE_LOG) {
            LOGGER.info(message, args);
        }
    }
}
