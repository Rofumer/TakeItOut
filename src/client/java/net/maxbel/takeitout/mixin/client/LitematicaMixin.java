package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import fi.dy.masa.malilib.util.InventoryUtils;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(WorldUtils.class)
public class LitematicaMixin {


    @Unique
    private static final int MAX_WAIT_TICKS = 40; // примерно 1 секунда
    @Unique
    private static int waitTicks = 0;
    @Unique
    private static boolean waitingForItem = false;
    @Unique
    private static BlockState waitingState = null;
    @Unique
    private static boolean retried = false;

    @Inject(
            method = "doEasyPlaceAction",
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lfi/dy/masa/litematica/materials/MaterialCache;getRequiredBuildItemForState(Lnet/minecraft/block/BlockState;)Lnet/minecraft/item/ItemStack;",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private static void interceptMissingItem(MinecraftClient mc, CallbackInfoReturnable<ActionResult> cir) {
        if (mc.player == null) return;

        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 3, true, true);
        if (result == null) return;

        BlockPos pos = result.getBlockPos();
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        BlockState state = schematic.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state);
        ItemStack inHand = mc.player.getMainHandStack();

        // Если нет нужного стака — инициируем процесс
        if (!InventoryUtils.areStacksEqual(inHand, required)) {
            if (!waitingForItem) {
                WorldUtils.doSchematicWorldPickBlock(false, mc);
                waitingForItem = true;
                waitingState = state;
            }
            cir.setReturnValue(ActionResult.FAIL);
        }
    }


    @ModifyArg(
            method = "easyPlaceOnUseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/WorldUtils;doEasyPlaceAction(Lnet/minecraft/client/MinecraftClient;)Lnet/minecraft/util/ActionResult;"
            )
    )
    private static MinecraftClient checkItemAndTick(MinecraftClient client) {
        if (waitingForItem && client.player != null && waitingState != null) {
            ItemStack inHand = client.player.getMainHandStack();
            ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(waitingState);

            if (InventoryUtils.areStacksEqual(inHand, required)) {
                // Предмет появился — продолжаем
                waitingForItem = false;
                waitingState = null;
                waitTicks = 0;
                retried = false;
            } else {
                waitTicks++;

                // Повторная попытка после 2 тиков, если не пробовали
                if (!retried && waitTicks == 20) {
                    WorldUtils.doSchematicWorldPickBlock(false, client);
                    retried = true;
                }

                // Таймаут ожидания
                if (waitTicks >= MAX_WAIT_TICKS) {
                    System.out.println("[TakeItOut] Failed to receive item from shulker within timeout.");
                    waitingForItem = false;
                    waitingState = null;
                    waitTicks = 0;
                    retried = false;
                }
            }
        }
        return client;
    }

    @Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), remap = true, cancellable = true)
    private static void doSchematicWorldPickBlockHook(boolean closest, MinecraftClient mc, CallbackInfoReturnable<Boolean> cir) {

        // pickblock from shulker

        BlockPos pos;
        pos = RayTraceUtils.getSchematicWorldTraceIfClosest(mc.world, mc.player, 6);
        if (pos != null) {
            WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
            if (world != null) {
                BlockState state = world.getBlockState(pos);
                ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(state, world, pos);
                if (mc.player.getInventory().getSlotWithStack(stack) != -1) {
                    return;
                }
                int shulker = getShulkerWithStack(mc.player.getInventory(), stack);
                if (shulker != -1) {
                    int slot = getSlotWithStack((Inventory) (getInventoryFromShulker((ItemStack) mc.player.getInventory().getStack(shulker))), stack);
                    if (slot != -1) {
                        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker));
                    }
                }
                //cir.setReturnValue(true);
                //cir.cancel();
            }
        }
    }

    /*@Inject(method = "doEasyPlaceAction", at = @At("RETURN"))
    private static void onEasyPlaceActionEnd(MinecraftClient mc, CallbackInfoReturnable<ActionResult> cir) {
        // Этот код выполнится после `return ActionResult.SUCCESS`
        WorldUtils.doSchematicWorldPickBlock(true, mc);
        //System.out.println("Easy Place завершено");
    }*/
}
