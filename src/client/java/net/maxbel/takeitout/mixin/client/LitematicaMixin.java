package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(WorldUtils.class)
public class LitematicaMixin {

    @Unique private static int  waitTicks = 0;
    @Unique private static boolean waitingForItem = false;
    @Unique private static BlockState waitingState = null;
    @Unique private static int  retryCount = 0;
    @Unique private static int  expectedWaitTicks = 40;
    @Unique private static long requestTsMs = 0L;

    @Unique
    private static int computeExpectedWaitTicks(MinecraftClient mc) {
        int pingMs = 0;
        try {
            if (mc != null && mc.getNetworkHandler() != null && mc.player != null) {
                var e = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                if (e != null) pingMs = e.getLatency();
            }
        } catch (Throwable ignored) {}
        if (pingMs <= 0) pingMs = 180;
        int ms = (int)Math.round(pingMs * 2.5 + 200);
        int ticks = (ms + 49) / 50;
        return Math.max(20, Math.min(ticks, 200));
    }

    @Unique
    private static int jitter(int baseTicks, int percent) {
        int spread = Math.max(1, baseTicks * percent / 100);
        return baseTicks + (int)(System.nanoTime() % (2L * spread + 1)) - spread;
    }

    // перехватываем момент, когда Litematica вычислила нужный ItemStack
    @Inject(
            method = "doEasyPlaceAction",
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lfi/dy/masa/litematica/materials/MaterialCache;getRequiredBuildItemForState(Lnet/minecraft/block/BlockState;)Lnet/minecraft/item/ItemStack;",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private static void takeItOut$intercept(MinecraftClient mc, CallbackInfoReturnable<ActionResult> cir) {
        if (mc == null || mc.player == null) return;

        BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(mc.player, 6, true, true);
        if (hit == null) return;

        BlockPos pos = hit.getBlockPos();
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) return;

        BlockState state = world.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state);
        ItemStack inHand  = mc.player.getMainHandStack();

        if (!fi.dy.masa.malilib.util.InventoryUtils.areStacksEqual(inHand, required)) {
            if (!waitingForItem) {
                WorldUtils.doSchematicWorldPickBlock(true, mc);
                waitingForItem = true;
                waitingState = state;
                waitTicks = 0;
                retryCount = 0;
                expectedWaitTicks = computeExpectedWaitTicks(mc);
                requestTsMs = System.currentTimeMillis();
            }
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

    // тик-обвязка вокруг easy place
    @ModifyArg(
            method = "easyPlaceOnUseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/WorldUtils;doEasyPlaceAction(Lnet/minecraft/client/MinecraftClient;)Lnet/minecraft/util/ActionResult;"
            )
    )
    private static MinecraftClient takeItOut$tick(MinecraftClient client) {
        if (waitingForItem && client.player != null && waitingState != null) {
            ItemStack inHand = client.player.getMainHandStack();
            ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(waitingState);

            if (fi.dy.masa.malilib.util.InventoryUtils.areStacksEqual(inHand, required)) {
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
                    System.out.println("[TakeItOut] Timeout while waiting item from shulker. waited " +
                            waitTicks + "t, retries " + retryCount);
                    waitingForItem = false;
                    waitingState = null;
                    waitTicks = 0;
                    retryCount = 0;
                }
            }
        }
        return client;
    }

    // hook на pick block из схемо-мира, достаём вещь из шалкера при необходимости
    @Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), cancellable = true)
    private static void takeItOut$pickBlock(boolean closest, MinecraftClient mc,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (mc == null || mc.player == null) return;

        int range = 6;
        BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(mc.player, range, true, true);
        if (hit == null || hit.getType() != BlockHitResult.Type.BLOCK) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        BlockPos pos = hit.getBlockPos();
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) { cir.setReturnValue(false); cir.cancel(); return; }

        BlockState state = world.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state, world, pos);

        if (!fi.dy.masa.malilib.util.InventoryUtils.areStacksEqual(mc.player.getMainHandStack(), required)) {
            int slot = fi.dy.masa.malilib.util.InventoryUtils.findSlotWithItem(
                    mc.player.playerScreenHandler, required, true
            );
            if (slot != -1) {
                fi.dy.masa.malilib.util.InventoryUtils.swapItemToMainHand(required, mc);
            } else {
                int shulkerSlot = getShulkerWithStack(mc.player.getInventory(), required);
                if (shulkerSlot != -1) {
                    Inventory shInv = (Inventory) getInventoryFromShulker(
                            mc.player.getInventory().getStack(shulkerSlot));
                    int inner = getSlotWithStack(shInv, required);
                    if (inner != -1) {
                        PacketByteBuf buf = PacketByteBufs.create();
                        buf.writeVarInt(inner);
                        buf.writeVarInt(shulkerSlot);
                        ClientPlayNetworking.send(Takeitout.GETSTACK, buf);
                    }
                }
            }
        }

        // обязательно вызывать litematica-версию
        fi.dy.masa.litematica.util.InventoryUtils.schematicWorldPickBlock(required, pos, world, mc);

        cir.setReturnValue(true);
        cir.cancel();
    }
}
