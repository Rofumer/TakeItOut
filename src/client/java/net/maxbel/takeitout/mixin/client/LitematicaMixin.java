package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.MushroomBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.state.property.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static fi.dy.masa.litematica.util.WorldUtils.getValidBlockRange;
import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.TakeitoutClient.TAKE_SINGLE_ITEM_MODE;
import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaMixin {
    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/pickblock");
    @Unique private static final Set<String> PLACE_STATE_IGNORED_PROPERTIES = Set.of("lit", "powered", "open");
    @Unique private static final Set<String> FENCE_WALL_IGNORED_PROPERTIES = Set.of("north", "south", "east", "west", "up");
    @Unique private static final Set<String> MUSHROOM_BLOCK_IGNORED_PROPERTIES = Set.of("north", "south", "east", "west", "up", "down");

    @Unique private static boolean waitingForShulkerResponse = false;
    @Unique private static ItemStack waitingShulkerStack = ItemStack.EMPTY;
    @Unique private static long waitingShulkerRequestTsMs = 0L;
    @Unique private static BlockPos lastEasyPlaceTargetPos = null;
    @Unique private static BlockState lastEasyPlaceTargetState = null;

    /**
     * Перехват Easy Place: если в руке не тот предмет — инициируем pick block и ждём.
     * Делается на HEAD, чтобы не зависеть от внутренних вызовов Litematica (они часто меняются между версиями).
     */
    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void interceptMissingItem(MinecraftClient mc, CallbackInfoReturnable<ActionResult> cir) {
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

        lastEasyPlaceTargetPos = result.getBlockPos().toImmutable();
        lastEasyPlaceTargetState = schematic.getBlockState(lastEasyPlaceTargetPos);

        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(lastEasyPlaceTargetState);
        ItemStack inHand = mc.player.getMainHandStack();

        if (waitingForShulkerResponse) {
            if (WorldContainerSources.consumeFailedResponse(waitingShulkerStack)) {
                LOGGER.warn(
                        "EasyPlace world-container wait failed: expected={}, inHand={}",
                        waitingShulkerStack,
                        inHand
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            } else
            if (!waitingShulkerStack.isEmpty() && ItemStack.areItemsAndComponentsEqual(inHand, waitingShulkerStack)) {
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
                    cir.setReturnValue(ActionResult.FAIL);
                    cir.cancel();
                    return;
                }

                LOGGER.warn(
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

        if (ItemStack.areItemsAndComponentsEqual(inHand, required)) {
            return;
        }

        if (mc.world != null && arePlacementEquivalent(mc.world.getBlockState(lastEasyPlaceTargetPos), lastEasyPlaceTargetState)) {
            return;
        }

        WorldUtils.doSchematicWorldPickBlock(true, mc);

        if (!ItemStack.areItemsAndComponentsEqual(mc.player.getMainHandStack(), required)) {
            cir.setReturnValue(ActionResult.FAIL);
            cir.cancel();
        }
    }

    @Inject(method = "doEasyPlaceAction", at = @At("RETURN"), remap = false)
    private static void logEasyPlaceResult(MinecraftClient mc, CallbackInfoReturnable<ActionResult> cir) {
        if (mc == null || mc.world == null || lastEasyPlaceTargetPos == null || lastEasyPlaceTargetState == null) {
            return;
        }

        BlockState worldState = mc.world.getBlockState(lastEasyPlaceTargetPos);
        boolean stateMatches = arePlacementEquivalent(worldState, lastEasyPlaceTargetState);
        boolean actionSucceeded = cir.getReturnValue() != ActionResult.FAIL;

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
            LOGGER.warn(
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
                    target = "Lfi/dy/masa/litematica/util/WorldUtils;doEasyPlaceAction(Lnet/minecraft/client/MinecraftClient;)Lnet/minecraft/util/ActionResult;"
            ),
            remap = false
    )
    private static MinecraftClient checkItemAndTick(MinecraftClient client) {
        return client;
    }

    @Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private static void doSchematicWorldPickBlockHook(boolean closest, MinecraftClient mc,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (mc == null || mc.player == null) return;

        // Если текущий слот запрещён pickBlockableSlots — НЕ вмешиваемся вообще.
        // Важно: НЕ cancel, иначе Easy Place перестанет работать.
        if (!isSelectedHotbarSlotAllowedByLitematica()) {
            return; // пусть оригинальный doSchematicWorldPickBlock от Litematica отработает сам
        }

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
        ItemStack handBefore = mc.player.getMainHandStack();
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
                        mc.player.getMainHandStack()
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            }
        }

        if (easyPlaceMode && waitingForShulkerResponse && !waitingShulkerStack.isEmpty()) {
            if (WorldContainerSources.consumeFailedResponse(waitingShulkerStack)) {
                LOGGER.warn(
                        "PickBlock world-container response failed: expected={}, inHand={}",
                        waitingShulkerStack,
                        mc.player.getMainHandStack()
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            } else
            if (ItemStack.areItemsAndComponentsEqual(mc.player.getMainHandStack(), waitingShulkerStack)) {
                LOGGER.debug(
                        "PickBlock shulker response received: expected={}, inHand={}, handSlot={}",
                        waitingShulkerStack,
                        mc.player.getMainHandStack(),
                        mc.player.getInventory().getSelectedSlot()
                );
                waitingForShulkerResponse = false;
                waitingShulkerStack = ItemStack.EMPTY;
                waitingShulkerRequestTsMs = 0L;
            } else {
                int inventorySlot = InventoryUtils.findSlotWithItem(mc.player.playerScreenHandler, waitingShulkerStack, true);
                if (inventorySlot != -1) {
                    LOGGER.debug(
                            "PickBlock shulker response received in inventory: expected={}, sourceSlot={}, handBefore={}, handSlot={}",
                            waitingShulkerStack,
                            inventorySlot,
                            mc.player.getMainHandStack(),
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
                                mc.player.getMainHandStack(),
                                elapsedMs
                        );
                        cir.setReturnValue(true);
                        cir.cancel();
                        return;
                    }

                    LOGGER.warn(
                            "PickBlock shulker response timeout: expected={}, inHand={}, elapsedMs={}, retrying",
                            waitingShulkerStack,
                            mc.player.getMainHandStack(),
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

        // 2) наша логика: если нет в руке — попробуем из инвентаря/шалкера
        if (!ItemStack.areItemsAndComponentsEqual(mc.player.getMainHandStack(), required)) {
            if (!awaitingStack.isEmpty() && ItemStack.areItemsAndComponentsEqual(awaitingStack, required.copyWithCount(1))) {
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

            int slot = InventoryUtils.findSlotWithItem(mc.player.playerScreenHandler, required, true);

            if (slot != -1) {
                LOGGER.debug(
                        "PickBlock source=inventory: required={}, sourceSlot={}, handSlot={}",
                        required,
                        slot,
                        selectedSlot
                );
                InventoryUtils.swapItemToMainHand(required, mc);
            } else {
                int shulkerSlot = getShulkerWithStack(mc.player.getInventory(), required);
                if (shulkerSlot != -1) {
                    Inventory shInv = (Inventory) getInventoryFromShulker(mc.player.getInventory().getStack(shulkerSlot));
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
                        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(inner, shulkerSlot, TAKE_SINGLE_ITEM_MODE));
                        if (easyPlaceMode) {
                            waitingForShulkerResponse = true;
                            waitingShulkerStack = required.copyWithCount(1);
                            waitingShulkerRequestTsMs = System.currentTimeMillis();
                        }
                        cir.setReturnValue(true);
                        cir.cancel();
                        return;
                    } else {
                        LOGGER.warn(
                                "PickBlock shulker-miss: required={}, shulkerSlot={}, handSlot={}",
                                required,
                                shulkerSlot,
                                selectedSlot
                        );
                    }
                } else {
                    LOGGER.warn(
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

        // 3) дальше выполняем pickblock лайтематики как и раньше
        fi.dy.masa.litematica.util.InventoryUtils.schematicWorldPickBlock(required, pos, world, mc);

        LOGGER.debug(
                "PickBlock done: pos={}, required={}, handAfter={}, handSlot={}",
                pos,
                required,
                mc.player.getMainHandStack(),
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
                && MUSHROOM_BLOCK_IGNORED_PROPERTIES.contains(name));
    }

    @Unique
    private static boolean arePlacementEquivalent(BlockState worldState, BlockState targetState) {
        if (worldState.equals(targetState)) {
            return true;
        }

        if (!worldState.isOf(targetState.getBlock())) {
            return false;
        }

        for (Property<?> property : targetState.getProperties()) {
            if (shouldIgnorePlacementProperty(targetState, property)) {
                continue;
            }

            if (!worldState.contains(property)) {
                return false;
            }

            if (!worldState.get(property).equals(targetState.get(property))) {
                return false;
            }
        }

        return true;
    }

    @Unique
    private static boolean isSelectedHotbarSlotAllowedByLitematica() {
        try {
            String raw = Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue(); // например "1,2,3,4,5"
            if (raw == null || raw.trim().isEmpty()) {
                return true; // пусто = не ограничено
            }

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null) return true;

            int selected = mc.player.getInventory().getSelectedSlot(); // 0..8

            for (String part : raw.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;

                try {
                    int oneBased = Integer.parseInt(part);
                    int zeroBased = oneBased - 1;
                    if (zeroBased == selected) return true;
                } catch (NumberFormatException ignored) {}
            }

            return false;
        } catch (Throwable ignored) {
            // если Litematica/Configs недоступны или что-то пошло не так — не ломаем работу
            return true;
        }
    }
}
