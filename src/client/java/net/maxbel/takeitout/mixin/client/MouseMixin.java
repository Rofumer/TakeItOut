package net.maxbel.takeitout.mixin.client;

//import fi.dy.masa.litematica.util.WorldUtils;
//import fi.dy.masa.litematica.world.SchematicWorldHandler;
//import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.SchematicBlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.maxbel.takeitout.client.TakeitoutClient.getSlotWithItem;

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

    /*@Inject(method = "tick", at = @At("HEAD"))
    private void onCursorMove(CallbackInfo ci) {
        long varWindow = MinecraftClient.getInstance().getWindow().getHandle();
        // Проверка удержания правой кнопки мыши
        if (GLFW.glfwGetMouseButton(varWindow, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS) {
            if (client != null && client.world != null && client.player != null && client.currentScreen == null) {
                try {
                    if (Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler") != null) {
                        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
                        if (world != null) {
                        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(MinecraftClient.getInstance().player, 3, true, true);
                        //System.out.println(result);
                        if (result != null) {
                            if (result.getBlockPos() != null) {
                                SchematicBlockState state = new SchematicBlockState(MinecraftClient.getInstance().player.getWorld(), world, result.getBlockPos());
                                //System.out.println(result);
                                System.out.println("---");
                                System.out.println(state.targetState.getBlock().asItem());
                                System.out.println(state.currentState.getBlock().asItem());
                                System.out.println(client.player.getStackInHand(Hand.MAIN_HAND).getItem());
                                System.out.println("---");
                                //if (!state.targetState.equals(state.currentState) && !state.currentState.isAir()) {
                                //if (!state.targetState.equals(state.currentState) && state.currentState.isAir()) {
                                    if (!(client.player.getStackInHand(Hand.MAIN_HAND).getItem() == state.targetState.getBlock().asItem() && state.currentState.isAir())) {
                                            WorldUtils.doSchematicWorldPickBlock(true, client);
                                        System.out.println("pick block");
                                       // }
                                    }
                                }
                            }
                        }

                    }
                } catch (ClassNotFoundException ignored) {}
            }
        }
    }*/

}
