package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.v1_20.Printer;
import me.aleksilassila.litematica.printer.v1_20.SchematicBlockState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
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

    @Shadow protected abstract List<BlockPos> getReachablePositions();

    @Shadow @Final public ClientPlayerEntity player;

    @Inject(method = "me.aleksilassila.litematica.printer.v1_20.Printer.onGameTick",
            at = @At("TAIL"), remap = false)
    private void takeItOut$tail(CallbackInfoReturnable<Boolean> cir) {

        if (!TakeitoutClient.AUTOTAKEOUT || awaitingStack != ItemStack.EMPTY) return;

        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        if (worldSchematic == null) return;

        List<BlockPos> positions = this.getReachablePositions();
        for (BlockPos pos : positions) {
            SchematicBlockState state =
                    new SchematicBlockState(this.player.getWorld(), worldSchematic, pos);

            if (state.targetState.equals(state.currentState) || state.targetState.isAir()) continue;
            if (!state.currentState.isAir()) continue;

            ItemStack need = new ItemStack(state.targetState.getBlock().asItem());
            if (player.getInventory().getSlotWithStack(need) != -1) return;

            int shulker = getShulkerWithStack(player.getInventory(), need);
            if (shulker != -1) {
                int inner = getSlotWithStack((Inventory) getInventoryFromShulker(
                        player.getInventory().getStack(shulker)), need);
                if (inner != -1) {
                    awaitingStack = need;

                    PacketByteBuf buf = PacketByteBufs.create();
                    buf.writeVarInt(inner);
                    buf.writeVarInt(shulker);
                    ClientPlayNetworking.send(Takeitout.GETSTACK, buf);
                    break;
                }
            }
        }
    }

    @Inject(method = "me.aleksilassila.litematica.printer.v1_20.Printer.onGameTick",
            at = @At("HEAD"), remap = false, cancellable = true)
    private void takeItOut$head(CallbackInfoReturnable<Boolean> cir) {
        if (awaitingStack != ItemStack.EMPTY) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}
