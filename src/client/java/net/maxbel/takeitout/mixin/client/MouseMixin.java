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
import net.maxbel.takeitout.client.TakeitoutClient;
import net.maxbel.takeitout.client.WorldContainerSources;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import net.minecraft.item.Item;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.maxbel.takeitout.client.TakeitoutClient.getSlotWithItem;

@Mixin(Mouse.class)
public class MouseMixin {

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("takeitout/mouse");
    @Unique private static BlockPos pendingPlacementPos = null;
    @Unique private static Item pendingPlacementExpectedItem = null;
    @Unique private static int pendingPlacementTicks = 0;
    @Unique private static boolean pendingPlacementNeedsUseRetry = false;
    @Unique private static boolean pendingPlacementUseRetried = false;
    @Unique private static final int PLACEMENT_VERIFY_TIMEOUT_TICKS = 8;

    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, MouseInput input, int action, CallbackInfo ci) {
        // Интересует только нажатие правой кнопки (GLFW_PRESS = 1)
        if (input.button() != 1 || action != 1) {
            return;
        }

        if ((input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
            if (client != null && client.player != null && client.world != null && client.currentScreen == null
                    && client.crosshairTarget instanceof BlockHitResult blockHit
                    && WorldContainerSources.toggle(client, blockHit.getBlockPos())) {
                ci.cancel();
            }
            return;
        }

        // 🔹 НОВАЯ ПРОВЕРКА: если автоподбор выключен — ничего не делаем
        if (!TakeitoutClient.AUTOTAKEOUT) {
            return;
        }

        // Базовые проверки окружения
        if (client == null || client.player == null || client.world == null) {
            return;
        }

        // Если открыт какой-то экран — не вмешиваемся
        if (client.currentScreen != null) {
            return;
        }

        // Проверяем, что Litematica доступна и есть мир схемы
        try {
            Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
        } catch (ClassNotFoundException e) {
            return;
        }
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            return;
        }

        // Если включён Easy Place — не вмешиваемся
        if (Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) {
            return;
        }

        // 1) Рейкаст в мир схемы С УЧЁТОМ видимости слоёв/срезов
        BlockHitResult schematicHit = RayTraceUtils.traceToSchematicWorld(client.player, 5, true, true);
        if (schematicHit == null || schematicHit.getBlockPos() == null) {
            return;
        }

        // 2) Ванильный рейкаст по реальному миру — блокируем "прострел"
        final double reach = 5.0D;
        final float tickDelta = 1.0F;

        Vec3d start = client.player.getCameraPosVec(tickDelta);
        Vec3d look = client.player.getRotationVec(tickDelta);
        Vec3d end = start.add(look.x * reach, look.y * reach, look.z * reach);

        HitResult worldHit = client.world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER, // только коллизионные формы
                RaycastContext.FluidHandling.NONE, // жидкости игнорируем
                client.player
        ));

        if (worldHit != null && worldHit.getType() == HitResult.Type.BLOCK) {
            BlockPos worldPos = ((BlockHitResult) worldHit).getBlockPos();
            BlockPos schemPos = schematicHit.getBlockPos();

            // ★ КЛЮЧЕВАЯ ПРОВЕРКА:
            // Если реальный хит в том же самом блоке (напр. полублок/ступень внутри блока),
            // считаем голограмму закрытой и выходим.
            if (worldPos.equals(schemPos)) {
                return;
            }

            // Иначе — обычное сравнение расстояний (реальный блок ближе по лучу?)
            double worldDist = start.distanceTo(worldHit.getPos());
            double schematicDist = start.distanceTo(schematicHit.getPos());
            if (worldDist + 1.0e-6 < schematicDist) {
                return;
            }
        }

        // 3) Убедимся, что схема действительно "что-то" хочет в этой точке (не воздух)
        SchematicBlockState st = new SchematicBlockState(client.world, schematicWorld, schematicHit.getBlockPos());
        if (st.targetState == null || st.targetState.isAir()) {
            return;
        }

        // Срабатываем только на несовпадение со схемой (голограммный "миссматч")
        // Если реальный блок уже совпадает с целевым блоком схемы — не подбираем.
        if (st.currentState != null && st.targetState.equals(st.currentState)) {
            return;
        }

        int selectedSlot = client.player.getInventory().getSelectedSlot();
        ItemStack inHand = client.player.getStackInHand(Hand.MAIN_HAND);
        ItemStack wanted = new ItemStack(st.targetState.getBlock().asItem());
        ItemStack current = new ItemStack(st.currentState.getBlock().asItem());

        LOGGER.debug(
                "PKM start: pos={}, hologramTarget={}, worldCurrent={}, handItem={}, handSlot={}, matchInHand={}",
                schematicHit.getBlockPos(),
                wanted,
                current,
                inHand,
                selectedSlot,
                inHand.isOf(wanted.getItem())
        );

        // 4) Подбираем нужный блок из схемы; событие не отменяем — ваниль обработает клик дальше
        WorldUtils.doSchematicWorldPickBlock(true, client);

        ItemStack afterPickInHand = client.player.getStackInHand(Hand.MAIN_HAND);
        LOGGER.debug(
                "PKM after pick-block: pos={}, required={}, handNow={}, handSlot={}",
                schematicHit.getBlockPos(),
                wanted,
                afterPickInHand,
                selectedSlot
        );

        pendingPlacementPos = schematicHit.getBlockPos().toImmutable();
        pendingPlacementExpectedItem = wanted.getItem();
        pendingPlacementTicks = 0;
        pendingPlacementNeedsUseRetry = !afterPickInHand.isOf(wanted.getItem());
        pendingPlacementUseRetried = false;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void verifyPlacement(CallbackInfo ci) {
        if (pendingPlacementPos == null || pendingPlacementExpectedItem == null || client == null || client.world == null) {
            return;
        }

        pendingPlacementTicks++;

        if (pendingPlacementNeedsUseRetry && !pendingPlacementUseRetried && client.player != null && client.currentScreen == null) {
            ItemStack handNow = client.player.getStackInHand(Hand.MAIN_HAND);
            if (handNow.isOf(pendingPlacementExpectedItem)) {
                LOGGER.debug(
                        "PKM retry use after delayed pick-block: pos={}, expected={}, handNow={}, ticksWaited={}",
                        pendingPlacementPos,
                        pendingPlacementExpectedItem,
                        handNow,
                        pendingPlacementTicks
                );
                ((MinecraftClientAccessor) (Object) client).takeitout$invokeDoItemUse();
                pendingPlacementUseRetried = true;
                pendingPlacementNeedsUseRetry = false;
            }
        }

        ItemStack nowAtPos = new ItemStack(client.world.getBlockState(pendingPlacementPos).getBlock().asItem());
        boolean placed = nowAtPos.isOf(pendingPlacementExpectedItem);
        if (placed) {
            LOGGER.debug(
                    "PKM placement SUCCESS: pos={}, expected={}, actual={}, ticksWaited={}",
                    pendingPlacementPos,
                    pendingPlacementExpectedItem,
                    nowAtPos,
                    pendingPlacementTicks
            );
            pendingPlacementPos = null;
            pendingPlacementExpectedItem = null;
            pendingPlacementTicks = 0;
            pendingPlacementNeedsUseRetry = false;
            pendingPlacementUseRetried = false;
            return;
        }

        if (pendingPlacementTicks >= PLACEMENT_VERIFY_TIMEOUT_TICKS) {
            LOGGER.warn(
                    "PKM placement FAIL/TIMEOUT: pos={}, expected={}, actual={}, ticksWaited={}",
                    pendingPlacementPos,
                    pendingPlacementExpectedItem,
                    nowAtPos,
                    pendingPlacementTicks
            );
            pendingPlacementPos = null;
            pendingPlacementExpectedItem = null;
            pendingPlacementTicks = 0;
            pendingPlacementNeedsUseRetry = false;
            pendingPlacementUseRetried = false;
        }
    }


    /** Проверка, что предмет в руке можно ставить как блок */
    /*private static boolean isPlaceableBlock(ItemStack stack) {
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
    }*//*last07.10.2025*/

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
