package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.InventoryUtils;
import me.fallenbreath.conditionalmixin.api.annotation.Condition;
import me.fallenbreath.conditionalmixin.api.annotation.Restriction;
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
import net.minecraft.world.level.block.RedStoneWireBlock;
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
import static net.maxbel.takeitout.client.TakeitoutClient.TAKE_SINGLE_ITEM_MODE;
import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Restriction(require = @Condition(type = Condition.Type.MOD, value = "litematica"))
@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaMixin {
    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/pickblock");
    @Unique private static final Set<String> PLACE_STATE_IGNORED_PROPERTIES = Set.of("lit", "powered", "open");
    @Unique private static final Set<String> FENCE_WALL_IGNORED_PROPERTIES = Set.of("north", "south", "east", "west", "up");
    @Unique private static final Set<String> MUSHROOM_BLOCK_IGNORED_PROPERTIES = Set.of("north", "south", "east", "west", "up", "down");
    @Unique private static final Set<String> REDSTONE_WIRE_IGNORED_PROPERTIES = Set.of("north", "south", "east", "west", "power");

    @Unique private static boolean waitingForShulkerResponse = false;
    @Unique private static ItemStack waitingShulkerStack = ItemStack.EMPTY;
    @Unique private static long waitingShulkerRequestTsMs = 0L;
    @Unique private static BlockPos lastEasyPlaceTargetPos = null;
    @Unique private static BlockState lastEasyPlaceTargetState = null;

    /**
     * Intercept Easy Place: if the wrong item is in hand, kick off a pick-block request and wait.
     * Done on HEAD so we don't depend on Litematica's internals (which change often between versions).
     */
    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void interceptMissingItem(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir) {
        if (mc == null || mc.player == null) {
            lastEasyPlaceTargetPos = null;
            lastEasyPlaceTargetState = null;
            return;
        }

        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 6, true, true);
        if (result == null || result.getType() != HitResult.Type.BLOCK) {
            lastEasyPlaceTargetPos = null;
            lastEasyPlaceTargetState = null;
            return;
        }

        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) {
            lastEasyPlaceTargetPos = null;
            lastEasyPlaceTargetState = null;
            return;
        }

        lastEasyPlaceTargetPos = result.getBlockPos().immutable();
        lastEasyPlaceTargetState = schematic.getBlockState(lastEasyPlaceTargetPos);

        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(lastEasyPlaceTargetState);
        ItemStack inHand = mc.player.getMainHandItem();

        if (waitingForShulkerResponse) {
            if (WorldContainerSources.consumeFailedResponse(waitingShulkerStack)) {
                LOGGER.debug(
                        "EasyPlace world-container wait failed: expected={}, inHand={}",
                        waitingShulkerStack,
                        inHand
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            } else
            if (!waitingShulkerStack.isEmpty() && inHand.is(waitingShulkerStack.getItem())) {
                LOGGER.debug(
                        "EasyPlace shulker wait resolved: expected={}, inHand={}",
                        waitingShulkerStack,
                        inHand
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            } else {
                long elapsedMs = System.currentTimeMillis() - waitingShulkerRequestTsMs;
                if (elapsedMs < 3500L) {
                    cir.setReturnValue(InteractionResult.FAIL);
                    cir.cancel();
                    return;
                }

                LOGGER.debug(
                        "EasyPlace shulker wait timeout in pre-check: expected={}, inHand={}, elapsedMs={}",
                        waitingShulkerStack,
                        inHand,
                        elapsedMs
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            }
        }

        if (inHand.is(required.getItem())) {
            return;
        }

        if (mc.level != null && !mc.level.getBlockState(lastEasyPlaceTargetPos).canBeReplaced()) {
            return;
        }

        WorldUtils.doSchematicWorldPickBlock(true, mc);

        if (!mc.player.getMainHandItem().is(required.getItem())) {
            cir.setReturnValue(InteractionResult.FAIL);
            cir.cancel();
        }
    }

    @Inject(method = "doEasyPlaceAction", at = @At("RETURN"), remap = false)
    private static void logEasyPlaceResult(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir) {
        if (mc == null || mc.level == null || lastEasyPlaceTargetPos == null || lastEasyPlaceTargetState == null) {
            return;
        }

        BlockState worldState = mc.level.getBlockState(lastEasyPlaceTargetPos);
        boolean stateMatches = arePlacementEquivalent(worldState, lastEasyPlaceTargetState);
        boolean actionSucceeded = cir.getReturnValue() != InteractionResult.FAIL;

        if (stateMatches) {
            LOGGER.debug(
                    "EasyPlace result: pos={}, actionResult={}, actionSucceeded={}, worldState={}, targetState={}, placedMatchesTarget={}",
                    lastEasyPlaceTargetPos,
                    cir.getReturnValue(),
                    actionSucceeded,
                    worldState,
                    lastEasyPlaceTargetState,
                    true
            );
        } else if (actionSucceeded) {
            LOGGER.debug(
                    "EasyPlace result: pos={}, actionResult={}, actionSucceeded={}, worldState={}, targetState={}, placedMatchesTarget={}",
                    lastEasyPlaceTargetPos,
                    cir.getReturnValue(),
                    true,
                    worldState,
                    lastEasyPlaceTargetState,
                    false
            );
        } else {
            LOGGER.debug(
                    "EasyPlace result: pos={}, actionResult={}, actionSucceeded={}, worldState={}, targetState={}, placedMatchesTarget={}",
                    lastEasyPlaceTargetPos,
                    cir.getReturnValue(),
                    false,
                    worldState,
                    lastEasyPlaceTargetState,
                    false
            );
        }

        if (actionSucceeded && stateMatches) {
            waitingForShulkerResponse = false;
            waitingShulkerStack = ItemStack.EMPTY;
            waitingShulkerRequestTsMs = 0L;
        }

        lastEasyPlaceTargetPos = null;
        lastEasyPlaceTargetState = null;
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
        return client;
    }

    @Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private static void doSchematicWorldPickBlockHook(boolean closest, Minecraft mc,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (mc == null || mc.player == null) return;

        final int range = (int) getValidBlockRange(mc);
        BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(mc.player, range, true, true);

        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos pos = hit.getBlockPos();
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) return;

        BlockState state = world.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state, world, pos);
        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        ItemStack handBefore = mc.player.getMainHandItem();
        boolean easyPlaceMode = Configs.Generic.EASY_PLACE_MODE.getBooleanValue();

        if (!easyPlaceMode && waitingForShulkerResponse) {
            waitingForShulkerResponse = false;
            waitingShulkerStack = ItemStack.EMPTY;
            waitingShulkerRequestTsMs = 0L;
        }

        if (easyPlaceMode && waitingForShulkerResponse && !waitingShulkerStack.isEmpty()) {
            if (!InventoryUtils.areStacksEqual(waitingShulkerStack, required)) {
                LOGGER.debug(
                        "PickBlock shulker response dropped (target changed): expected={}, newRequired={}, inHand={}",
                        waitingShulkerStack,
                        required,
                        mc.player.getMainHandItem()
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            }
        }

        if (easyPlaceMode && waitingForShulkerResponse && !waitingShulkerStack.isEmpty()) {
            if (WorldContainerSources.consumeFailedResponse(waitingShulkerStack)) {
                LOGGER.debug(
                        "PickBlock world-container response failed: expected={}, inHand={}",
                        waitingShulkerStack,
                        mc.player.getMainHandItem()
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            } else
            if (mc.player.getMainHandItem().is(waitingShulkerStack.getItem())) {
                LOGGER.debug(
                        "PickBlock shulker response received: expected={}, inHand={}, handSlot={}",
                        waitingShulkerStack,
                        mc.player.getMainHandItem(),
                        mc.player.getInventory().getSelectedSlot()
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            } else {
                int inventorySlot = InventoryUtils.findSlotWithItem(mc.player.containerMenu, waitingShulkerStack, true);
                if (inventorySlot != -1) {
                    LOGGER.debug(
                            "PickBlock shulker response received in inventory: expected={}, sourceSlot={}, handBefore={}, handSlot={}",
                            waitingShulkerStack,
                            inventorySlot,
                            mc.player.getMainHandItem(),
                            mc.player.getInventory().getSelectedSlot()
                    );
                    InventoryUtils.swapItemToMainHand(waitingShulkerStack, mc);
                    waitingForShulkerResponse = false;
                    waitingShulkerStack = ItemStack.EMPTY;
                    waitingShulkerRequestTsMs = 0L;
                } else {
                    long elapsedMs = System.currentTimeMillis() - waitingShulkerRequestTsMs;
                    if (elapsedMs < 3500L) {
                        LOGGER.debug(
                                "PickBlock waiting shulker response: expected={}, inHand={}, elapsedMs={}",
                                waitingShulkerStack,
                                mc.player.getMainHandItem(),
                                elapsedMs
                        );
                        cir.setReturnValue(true);
                        cir.cancel();
                        return;
                    }

                    LOGGER.debug(
                            "PickBlock shulker response timeout: expected={}, inHand={}, elapsedMs={}, retrying",
                            waitingShulkerStack,
                            mc.player.getMainHandItem(),
                            elapsedMs
                    );
                    waitingForShulkerResponse = false;
                    waitingShulkerStack = ItemStack.EMPTY;
                    waitingShulkerRequestTsMs = 0L;
                }
            }
        }

        LOGGER.debug(
                "PickBlock request: pos={}, required={}, inHand={}, handSlot={}",
                pos,
                required,
                handBefore,
                selectedSlot
        );

        // if not in hand — try inventory/shulker
        if (!mc.player.getMainHandItem().is(required.getItem())) {
            if (!awaitingStack.isEmpty() && awaitingStack.is(required.getItem())) {
                LOGGER.debug(
                        "PickBlock duplicate request skipped: required={}, awaiting={}, handSlot={}",
                        required,
                        awaitingStack,
                        selectedSlot
                );
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }

            int slot = InventoryUtils.findSlotWithItem(mc.player.containerMenu, required, true);

            if (slot == -1) {
                int shulkerSlot = getShulkerWithStack(mc.player.getInventory(), required);
                if (shulkerSlot != -1) {
                    Container shInv = getInventoryFromShulker(mc.player.getInventory().getItem(shulkerSlot));
                    int inner = getSlotWithStack(shInv, required);
                    if (inner != -1) {
                        LOGGER.debug(
                                "PickBlock source=shulker: required={}, shulkerSlot={}, shulkerInnerSlot={}, handSlot={}",
                                required,
                                shulkerSlot,
                                inner,
                                selectedSlot
                        );
                        awaitingStack = required.copyWithCount(1);
                        TakeitoutClient.sendToServer(new Takeitout.GetShulkerStackPayload(inner, shulkerSlot, TAKE_SINGLE_ITEM_MODE));
                        if (easyPlaceMode) {
                            waitingForShulkerResponse = true;
                            waitingShulkerStack = required.copyWithCount(1);
                            waitingShulkerRequestTsMs = System.currentTimeMillis();
                        }
                        cir.setReturnValue(true);
                        cir.cancel();
                        return;
                    } else {
                        LOGGER.debug(
                                "PickBlock shulker-miss: required={}, shulkerSlot={}, handSlot={}",
                                required,
                                shulkerSlot,
                                selectedSlot
                        );
                    }
                } else {
                    LOGGER.debug(
                            "PickBlock miss: required={} not found in inventory or shulkers, trying world containers, sources={}, handSlot={}",
                            required,
                            WorldContainerSources.size(),
                            selectedSlot
                        );
                    if (WorldContainerSources.requestStack(mc, required, TAKE_SINGLE_ITEM_MODE)) {
                        if (easyPlaceMode) {
                            waitingForShulkerResponse = true;
                            waitingShulkerStack = required.copyWithCount(1);
                            waitingShulkerRequestTsMs = System.currentTimeMillis();
                        }
                        cir.setReturnValue(true);
                        cir.cancel();
                        return;
                    }
                }
            }
        }

        // finally, run Litematica's own pick-block as before
        fi.dy.masa.litematica.util.InventoryUtils.schematicWorldPickBlock(required, pos, world, mc);

        LOGGER.debug(
                "PickBlock done: pos={}, required={}, handAfter={}, handSlot={}",
                pos,
                required,
                mc.player.getMainHandItem(),
                mc.player.getInventory().getSelectedSlot()
        );

        cir.setReturnValue(true);
        cir.cancel();
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
                && MUSHROOM_BLOCK_IGNORED_PROPERTIES.contains(name))
                || (targetState.getBlock() instanceof RedStoneWireBlock
                && REDSTONE_WIRE_IGNORED_PROPERTIES.contains(name));
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

}
