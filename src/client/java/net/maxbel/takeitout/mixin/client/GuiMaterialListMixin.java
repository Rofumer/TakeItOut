package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import net.maxbel.takeitout.client.WorldContainerMaterialListCache;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiMaterialList.class, remap = false)
public class GuiMaterialListMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void requestLinkedContainerCounts(MaterialListBase materialList, CallbackInfo ci) {
        WorldContainerMaterialListCache.requestRefresh(MinecraftClient.getInstance());
    }
}
