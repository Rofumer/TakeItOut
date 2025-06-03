package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityUtils.class)
public abstract class EntityUtilsMixin {

    @Inject(
            method = "getUsedHandForItem",
            at = @At("RETURN"),
            cancellable = false
    )
    private static void jeb$onUsedHandReturned(PlayerEntity player, ItemStack stack, CallbackInfoReturnable<Hand> cir) {
        Hand result = cir.getReturnValue();

        if (result == null) {
            System.out.println("🧤 getUsedHandForItem вернул null — ни в одной руке нет " + stack);
            // Можешь вызывать здесь:
            WorldUtils.doSchematicWorldPickBlock(true, MinecraftClient.getInstance());
        } else {
            System.out.println("🧤 getUsedHandForItem вернул " + result.name());
        }
    }
}
