package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import net.maxbel.takeitout.client.WorldContainerMaterialListCache;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = MaterialListUtils.class, remap = false)
public class MaterialListUtilsMixin {
    // No target args captured: litematica 0.29.1 added entity-count maps to getMaterialList, and
    // leaving them out keeps this handler valid for both the old and the new signature.
    @Inject(method = "getMaterialList", at = @At("RETURN"), remap = false)
    private static void addLinkedContainersToCreatedEntries(CallbackInfoReturnable<List<MaterialListEntry>> cir) {
        WorldContainerMaterialListCache.addAvailableCounts(cir.getReturnValue());
        if (Minecraft.getInstance().player != null) {
            WorldContainerMaterialListCache.requestRefresh(Minecraft.getInstance());
        }
    }

    @Inject(method = "updateAvailableCounts", at = @At("TAIL"), remap = false)
    private static void addLinkedContainersToUpdatedEntries(
            List<MaterialListEntry> materialList,
            Player player,
            CallbackInfo ci
    ) {
        WorldContainerMaterialListCache.addAvailableCounts(materialList);
    }
}
