package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.util.RayTraceUtils;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;
import static net.maxbel.takeitout.client.TakeitoutClient.getSlotWithItem;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

import fi.dy.masa.litematica.config.Configs;

@Mixin(WorldUtils.class)
public class LitematicaMixin {



    @Unique
    private static int cancelCooldownTicks = 0;
    @Unique
    private static boolean delayActive = false;

    @Inject(method = "easyPlaceOnUseTick", at = @At("HEAD"), cancellable = true)
    /*@Inject(
            method = "doEasyPlaceAction",
            cancellable = true,
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lfi/dy/masa/litematica/materials/MaterialCache;getRequiredBuildItemForState(Lnet/minecraft/block/BlockState;)Lnet/minecraft/item/ItemStack;"
            )
    )*/
    private static void onEasyPlaceActionEnd(MinecraftClient mc, CallbackInfo ci) {
        if (mc.player != null && Configs.Generic.EASY_PLACE_HOLD_ENABLED.getBooleanValue() && Configs.Generic.EASY_PLACE_MODE.getBooleanValue() && Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld() && KeybindMulti.isKeyDown(KeybindMulti.getKeyCode(mc.options.useKey))) {

        if (delayActive) {
            cancelCooldownTicks--;
            if (cancelCooldownTicks <= 0) {
                delayActive = false;
            }
            ci.cancel(); // отменяем каждый тик, пока delayActive = true
            return;
        }

            BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 3, true, true);


            if (result != null) {
                if (result.getBlockPos() != null) {

                    BlockPos position = result.getBlockPos();

                    WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();


                    if (position != null) {

                        SchematicBlockState state = new SchematicBlockState(mc.player.getWorld(), worldSchematic, position);
                        if (state.targetState.equals(state.currentState) || state.targetState.isAir()) {
                            //ci.setReturnValue(ActionResult.FAIL);
                            ci.cancel();
                            return;
                        }
                        if (!state.targetState.equals(state.currentState) && !state.currentState.isAir()) {
                            //ci.setReturnValue(ActionResult.FAIL);
                            ci.cancel();
                            return;
                        }

                        Hand hand = EntityUtils.getUsedHandForItem(mc.player, state.targetState.getBlock().asItem().getDefaultStack());
                        if (hand == null) {
                           /*     ItemStack itemStack;
                                int slot;
                            itemStack = new ItemStack(state.targetState.getBlock().asItem());
                            slot = mc.player.getInventory().getSlotWithStack(itemStack);
                            if(slot != -1) return;
                            int shulker = getShulkerWithStack(mc.player.getInventory(), itemStack);

                            if (shulker != -1) {
                                slot = getSlotWithStack((Inventory) (getInventoryFromShulker((ItemStack) mc.player.getInventory().getStack(shulker))), itemStack);
                                if (slot != -1) {
                                    ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker));
                                    System.out.println(itemStack.toString()+ "Slot" +slot+ "Shulker"+ shulker);
                                    ci.cancel();
                                }
                            }
                            */

                            //System.out.println(state);

                            // запустить задержку
                            delayActive = true;
                            cancelCooldownTicks = 2;

                            WorldUtils.doSchematicWorldPickBlock(false, mc);
                            //cir.setReturnValue(ActionResult.FAIL);
                            ci.cancel();

                        }

                    }else{//cir.setReturnValue(ActionResult.FAIL);
                        ci.cancel();}
                }else{//cir.setReturnValue(ActionResult.FAIL);
                    ci.cancel();}
            }
            else{//cir.setReturnValue(ActionResult.FAIL);
                ci.cancel();}
        }else{//cir.setReturnValue(ActionResult.FAIL);
            ci.cancel();}
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
}
