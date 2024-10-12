package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (button == 0 && action == 1) { // action == 1 - GLFW_PRESS
            //System.out.println("Left Button");
        }
        if (button == 1 && action == 1) {
            try {
                Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            } catch (ClassNotFoundException e) {
                return;
            }
            WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
            if (world != null) {
                WorldUtils.doSchematicWorldPickBlock(false, client);
                //System.out.println("Right Button");
            }
        }
    }
}
