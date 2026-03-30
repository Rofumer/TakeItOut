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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static fi.dy.masa.litematica.util.WorldUtils.getValidBlockRange;
import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaMixin {

    @Unique private static int waitTicks = 0;
    @Unique private static boolean waitingForItem = false;
    @Unique private static BlockState waitingState = null;
    @Unique private static int retryCount = 0;
    @Unique private static int expectedWaitTicks = 40;
    @Unique private static long requestTsMs = 0L;

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

        if (pingMs <= 0) pingMs = 180;

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

    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void interceptMissingItem(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir) {
        if (mc == null || mc.player == null) return;

        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 6, true, true);
        if (result == null) return;

        BlockPos pos = result.getBlockPos();
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return;

        BlockState state = schematic.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state);
        ItemStack inHand = mc.player.getMainHandItem();

        if (!InventoryUtils.areStacksEqual(inHand, required)) {
            if (!waitingForItem) {
                WorldUtils.doSchematicWorldPickBlock(true, mc);
                waitingForItem = true;
                waitingState = state;
                waitTicks = 0;
                retryCount = 0;
                expectedWaitTicks = computeExpectedWaitTicks(mc);
                requestTsMs = System.currentTimeMillis();
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

            if (InventoryUtils.areStacksEqual(inHand, required)) {
                waitingForItem = false;
                waitingState = null;
                waitTicks = 0;
                retryCount = 0;
            } else {
                waitTicks++;

                if (retryCount == 0 && waitTicks >= jitter(expectedWaitTicks / 2, 10)) {
                    WorldUtils.doSchematicWorldPickBlock(true, client);
                    retryCount = 1;
                } else if (retryCount == 1 && waitTicks >= jitter(expectedWaitTicks, 10)) {
                    WorldUtils.doSchematicWorldPickBlock(true, client);
                    retryCount = 2;
                }

                int hardTimeout = expectedWaitTicks + Math.max(10, expectedWaitTicks / 2);
                if (waitTicks >= hardTimeout) {
                    System.out.println("[TakeItOut] Timeout while waiting item from shulker. ping-based " +
                            expectedWaitTicks + "t, waited " + waitTicks + "t, retries " + retryCount);
                    waitingForItem = false;
                    waitingState = null;
                    waitTicks = 0;
                    retryCount = 0;
                }
            }
        }
        return client;
    }

    @Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private static void doSchematicWorldPickBlockHook(boolean closest, Minecraft mc,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (mc == null || mc.player == null) return;

        if (!isSelectedHotbarSlotAllowedByLitematica()) {
            return;
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

        if (!InventoryUtils.areStacksAndNbtEqual(mc.player.getMainHandItem(), required)) {
            int slot = InventoryUtils.findSlotWithItem(mc.player.containerMenu, required, true);

            if (slot != -1) {
                InventoryUtils.swapItemToMainHand(required, mc);
            } else {
                int shulkerSlot = getShulkerWithStack(mc.player.getInventory(), required);
                if (shulkerSlot != -1) {
                    Container shInv = getInventoryFromShulker(mc.player.getInventory().getItem(shulkerSlot));
                    int inner = getSlotWithStack(shInv, required);
                    if (inner != -1) {
                        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(inner, shulkerSlot));
                    }
                }
            }
        }

        fi.dy.masa.litematica.util.InventoryUtils.schematicWorldPickBlock(required, pos, world, mc);

        cir.setReturnValue(true);
        cir.cancel();
    }

    @Unique
    private static boolean isSelectedHotbarSlotAllowedByLitematica() {
        try {
            String raw = Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue();
            if (raw == null || raw.trim().isEmpty()) {
                return true;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return true;

            int selected = mc.player.getInventory().getSelectedSlot();

            for (String part : raw.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;

                try {
                    int oneBased = Integer.parseInt(part);
                    int zeroBased = oneBased - 1;
                    if (zeroBased == selected) return true;
                } catch (NumberFormatException ignored) {
                }
            }

            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }
}