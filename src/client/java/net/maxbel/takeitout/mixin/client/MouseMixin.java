package net.maxbel.takeitout.mixin.client;

//import fi.dy.masa.litematica.util.WorldUtils;
//import fi.dy.masa.litematica.world.SchematicWorldHandler;
//import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
//import me.aleksilassila.litematica.printer.SchematicBlockState;
import net.maxbel.takeitout.client.SchematicBlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
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

    /** Проверка, что предмет в руке можно ставить как блок */
    private static boolean isPlaceableBlock(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        // интересует только нажатие правой кнопки (GLFW_PRESS = 1)
        if (button != 1 || action != 1) return;

        if (client == null || client.player == null || client.world == null || client.currentScreen != null) return;

        // проверяем, что Litematica есть и есть мир схемы
        WorldSchematic schematicWorld;
        try {
            Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            schematicWorld = SchematicWorldHandler.getSchematicWorld();
        } catch (ClassNotFoundException e) {
            return;
        }
        if (schematicWorld == null) return;

        // если включён Easy Place — не вмешиваемся
        if (Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) return;

        BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(client.player, 5, true, true);
        if (hit == null || hit.getBlockPos() == null) return;

        // берём наше состояние: мир + схема
        SchematicBlockState st = new SchematicBlockState(client.world, schematicWorld, hit.getBlockPos());

        boolean schematicWantsBlockHere = !st.targetState.isAir();
        boolean mismatchHere = schematicWantsBlockHere && !st.targetState.equals(st.currentState);

        // 1) если игрок держит ставимый блок — не мешаем ванильному клику
        if (isPlaceableBlock(client.player.getMainHandStack())) {
            return;
        }

        // 2) если схема хочет другой блок — подбираем его, но не отменяем клик
        if (mismatchHere) {
            WorldUtils.doSchematicWorldPickBlock(true, client);
            return; // не cancel → ваниль поставит блок
        }

        // иначе — ничего не делаем, пусть ваниль обработает как обычно
    }
    /*@Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
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
                    if(!Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) {
                        WorldUtils.doSchematicWorldPickBlock(true, client);
                    }
            }
        }
    }*/

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
