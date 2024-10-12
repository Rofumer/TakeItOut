package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import me.aleksilassila.litematica.printer.v1_21.Printer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.maxbel.takeitout.client.TakeitoutClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(Printer.class)
public abstract class PrinterV1_21Mixin {

    @Shadow
    protected abstract List<BlockPos> getReachablePositions();

    @Shadow
    @Final
    public ClientPlayerEntity player;


    @Inject(method = "me.aleksilassila.litematica.printer.v1_21.Printer.onGameTick", at = @At("TAIL"), remap = false)
    public void method1_21Hook(CallbackInfoReturnable<Boolean> cir) {

        //System.out.println("PrinterMixin");

        if (TakeitoutClient.AUTOTAKEOUT) {


            //System.out.println("PrinterMixin");

            ItemStack itemStack;
            int slot;

            WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
            List<BlockPos> positions = this.getReachablePositions();
            for (BlockPos position : positions) {
                SchematicBlockState state = new SchematicBlockState(this.player.getWorld(), worldSchematic, position);
                if (state.targetState.equals(state.currentState) || state.targetState.isAir()) {
                    continue;
                }
                itemStack = new ItemStack(state.targetState.getBlock().asItem());
                int shulker = getShulkerWithStack(player.getInventory(), itemStack);

                if (shulker != -1) {
                    slot = getSlotWithStack((Inventory) (getInventoryFromShulker((ItemStack) player.getInventory().getStack(shulker))), itemStack);
                    if (slot != -1) {
                        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker));
                        break;
                    }
                }
            }

        }

    }
}
