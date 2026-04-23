package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.Printer;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.TakeitoutClient.awaitingStack;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(Printer.class)
public abstract class PrinterMixin {

    @Shadow
    protected abstract List<BlockPos> getReachablePositions();

    @Shadow
    @Final
    public LocalPlayer player;

    @Inject(method = "onGameTick", at = @At("TAIL"), remap = false)
    public void methodHookTail(CallbackInfoReturnable<Boolean> cir) {
        if (TakeitoutClient.AUTOTAKEOUT && awaitingStack.isEmpty()) {
            ItemStack itemStack;
            int slot;

            WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
            if (worldSchematic == null) {
                return;
            }

            List<BlockPos> positions = this.getReachablePositions();
            for (BlockPos position : positions) {
                SchematicBlockState state = new SchematicBlockState(this.player.level(), worldSchematic, position);

                if (state.targetState.equals(state.currentState) || state.targetState.isAir()) {
                    continue;
                }
                if (!state.targetState.equals(state.currentState) && !state.currentState.isAir()) {
                    continue;
                }

                itemStack = new ItemStack(state.targetState.getBlock().asItem());
                slot = player.getInventory().findSlotMatchingItem(itemStack);
                if (slot != -1) {
                    return;
                }

                int shulker = getShulkerWithStack(player.getInventory(), itemStack);
                if (shulker != -1) {
                    Container shInv = getInventoryFromShulker(player.getInventory().getItem(shulker));
                    slot = getSlotWithStack(shInv, itemStack);
                    if (slot != -1) {
                        awaitingStack = itemStack.copyWithCount(1);
                        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker, TakeitoutClient.SHULKER_SINGLE_ITEM_MODE));
                        break;
                    }
                }

                if (WorldContainerSources.requestStack(Minecraft.getInstance(), itemStack, TakeitoutClient.TAKE_SINGLE_ITEM_MODE)) {
                    break;
                }
            }
        }
    }

    @Inject(method = "onGameTick", at = @At("HEAD"), remap = false, cancellable = true)
    public void methodHookHead(CallbackInfoReturnable<Boolean> cir) {
        if (!awaitingStack.isEmpty()) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}
