package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.maxbel.takeitout.client.WorldContainerMaterialListCache;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = MaterialListUtils.class, remap = false)
public class MaterialListUtilsMixin {
    @Inject(method = "getMaterialList", at = @At("RETURN"), remap = false)
    private static void addLinkedContainersToCreatedEntries(
            Object2IntOpenHashMap<BlockState> total,
            Object2IntOpenHashMap<BlockState> missing,
            Object2IntOpenHashMap<BlockState> mismatched,
            Player player,
            CallbackInfoReturnable<List<MaterialListEntry>> cir
    ) {
        WorldContainerMaterialListCache.addAvailableCounts(cir.getReturnValue());
        if (player != null) {
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
