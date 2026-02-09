package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;

import static fi.dy.masa.litematica.util.WorldUtils.getValidBlockRange;
import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(WorldUtils.class)
public class LitematicaMixin {

    @Unique
    private static int waitTicks = 0;
    @Unique
    private static boolean waitingForItem = false;
    @Unique
    private static BlockState waitingState = null;
    @Unique
    private static int retryCount = 0;
    @Unique
    private static int expectedWaitTicks = 40; // пересчитается динамически
    @Unique
    private static long requestTsMs = 0L;

    /** Оценка нужного ожидания с учётом пинга (в тиках). */
    @Unique
    private static int computeExpectedWaitTicks(MinecraftClient mc) {
        int pingMs = 0;
        try {
            if (mc != null && mc.getNetworkHandler() != null && mc.player != null) {
                var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                if (entry != null) pingMs = entry.getLatency(); // ms
            }
        } catch (Throwable ignored) {}

        // Если пинг неизвестен — примем 180 мс как «типичную Европу»
        if (pingMs <= 0) pingMs = 180;

        // Нужно 2–3 round-trip: возьмём 2.5 RTT + небольшой запас
        int ms = (int) Math.round(pingMs * 2.5 + 200);

        // Перевод в тики: 1 тик = 50 мс
        int ticks = (ms + 49) / 50;

        // Ограничим разумными пределами
        ticks = Math.max(20, Math.min(ticks, 200)); // от 1 до 10 секунд
        return ticks;
    }

    /** Небольшой джиттер, чтобы не «бить» ровно в границу тиков сервера. */
    @Unique
    private static int jitter(int baseTicks, int percent) {
        int spread = Math.max(1, baseTicks * percent / 100);
        return baseTicks + (int) (System.nanoTime() % (2L * spread + 1)) - spread;
    }

    @Inject(
            method = "doEasyPlaceAction",
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lfi/dy/masa/litematica/materials/MaterialCache;getRequiredBuildItemForState(Lnet/minecraft/block/BlockState;)Lnet/minecraft/item/ItemStack;",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private static void interceptMissingItem(MinecraftClient mc, CallbackInfoReturnable<ActionResult> cir) {
        if (mc.player == null) return;

        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 6, true, true);
        if (result == null) return;

        BlockPos pos = result.getBlockPos();
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        BlockState state = schematic.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state);
        ItemStack inHand = mc.player.getMainHandStack();

        // Если нет нужного стака — инициируем процесс
        if (!InventoryUtils.areStacksEqual(inHand, required)) {
            if (!waitingForItem) {
                WorldUtils.doSchematicWorldPickBlock(true, mc);
                waitingForItem = true;
                waitingState = state;
                waitTicks = 0;
                retryCount = 0;
                expectedWaitTicks = computeExpectedWaitTicks(mc);
                requestTsMs = System.currentTimeMillis();
            }
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

    @ModifyArg(
            method = "easyPlaceOnUseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/WorldUtils;doEasyPlaceAction(Lnet/minecraft/client/MinecraftClient;)Lnet/minecraft/util/ActionResult;"
            )
    )
    private static MinecraftClient checkItemAndTick(MinecraftClient client) {
        if (waitingForItem && client.player != null && waitingState != null) {
            ItemStack inHand = client.player.getMainHandStack();
            ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(waitingState);

            if (InventoryUtils.areStacksEqual(inHand, required)) {
                // Успешно: сброс состояния
                waitingForItem = false;
                waitingState = null;
                waitTicks = 0;
                retryCount = 0;
            } else {
                waitTicks++;

                // Первая повторная попытка примерно на половине ожидаемого времени
                if (retryCount == 0 && waitTicks >= jitter(expectedWaitTicks / 2, 10)) {
                    WorldUtils.doSchematicWorldPickBlock(true, client);
                    retryCount = 1;
                }
                // Вторая повторная попытка ближе к ожидаемому времени
                else if (retryCount == 1 && waitTicks >= jitter(expectedWaitTicks, 10)) {
                    WorldUtils.doSchematicWorldPickBlock(true, client);
                    retryCount = 2;
                }

                // Окончательный таймаут: ожидаемое время + половина
                int hardTimeout = expectedWaitTicks + Math.max(10, expectedWaitTicks / 2);
                if (waitTicks >= hardTimeout) {
                    System.out.println("[TakeItOut] Timeout while waiting item from shulker. ping-based " +
                            expectedWaitTicks + "t, waited " + waitTicks + "t, retries " + retryCount);
                    waitingForItem = false;
                    waitingState = null;
                    waitTicks = 0;
                    retryCount = 0;
                }
            }
        }
        return client;
    }


    /*
    @Unique
    private static final int MAX_WAIT_TICKS = 40; // примерно 1 секунда
    @Unique
    private static int waitTicks = 0;
    @Unique
    private static boolean waitingForItem = false;
    @Unique
    private static BlockState waitingState = null;
    @Unique
    private static boolean retried = false;

    @Inject(
            method = "doEasyPlaceAction",
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lfi/dy/masa/litematica/materials/MaterialCache;getRequiredBuildItemForState(Lnet/minecraft/block/BlockState;)Lnet/minecraft/item/ItemStack;",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private static void interceptMissingItem(MinecraftClient mc, CallbackInfoReturnable<ActionResult> cir) {
        if (mc.player == null) return;

        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 6, true, true);
        if (result == null) return;

        BlockPos pos = result.getBlockPos();
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        BlockState state = schematic.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state);
        ItemStack inHand = mc.player.getMainHandStack();

        // Если нет нужного стака — инициируем процесс
        if (!InventoryUtils.areStacksEqual(inHand, required)) {
            if (!waitingForItem) {
                WorldUtils.doSchematicWorldPickBlock(true, mc);
                waitingForItem = true;
                waitingState = state;
            }
            cir.setReturnValue(ActionResult.FAIL);
        }
    }


    @ModifyArg(
            method = "easyPlaceOnUseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/WorldUtils;doEasyPlaceAction(Lnet/minecraft/client/MinecraftClient;)Lnet/minecraft/util/ActionResult;"
            )
    )
    private static MinecraftClient checkItemAndTick(MinecraftClient client) {
        if (waitingForItem && client.player != null && waitingState != null) {
            ItemStack inHand = client.player.getMainHandStack();
            ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(waitingState);

            if (InventoryUtils.areStacksEqual(inHand, required)) {
                // Предмет появился — продолжаем
                waitingForItem = false;
                waitingState = null;
                waitTicks = 0;
                retried = false;
            } else {
                waitTicks++;

                // Повторная попытка после 2 тиков, если не пробовали
                if (!retried && waitTicks == 20) {
                    WorldUtils.doSchematicWorldPickBlock(true, client);
                    retried = true;
                }

                // Таймаут ожидания
                if (waitTicks >= MAX_WAIT_TICKS) {
                    System.out.println("[TakeItOut] Failed to receive item from shulker within timeout.");
                    waitingForItem = false;
                    waitingState = null;
                    waitTicks = 0;
                    retried = false;
                }
            }
        }
        return client;
    }

    */

    /*@Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), remap = true, cancellable = true)
    private static void doSchematicWorldPickBlockHook(boolean closest, MinecraftClient mc, CallbackInfoReturnable<Boolean> cir) {

        // pickblock from shulker

        BlockPos pos;
        pos = RayTraceUtils.getSchematicWorldTraceIfClosest(mc.world, mc.player, 6);
        if (pos != null) {
            WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
            if (world != null) {
                BlockState state = world.getBlockState(pos);
                ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(state, world, pos);
                if (mc.player.getInventory().getSlotWithStack(stack) != -1) {
                    return;
                }
                int shulker = getShulkerWithStack(mc.player.getInventory(), stack);
                if (shulker != -1) {
                    int slot = getSlotWithStack((Inventory) (getInventoryFromShulker((ItemStack) mc.player.getInventory().getStack(shulker))), stack);
                    if (slot != -1) {
                        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(slot, shulker));
                    }
                }
                //cir.setReturnValue(true);
                //cir.cancel();
            }
        }
    }*/

    /*@Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), cancellable = true)
    private static void doSchematicWorldPickBlockHook(boolean closest, MinecraftClient mc,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (mc == null || mc.player == null) return;

        final int range = (int) getValidBlockRange(mc);
        BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(
                mc.player, range, true, true
        );
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        BlockPos pos = hit.getBlockPos();
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) { cir.setReturnValue(false); cir.cancel(); return; }

        BlockState state = world.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance()
                .getRequiredBuildItemForState(state, world, pos);

        // Если в руке уже нужный стак — ничего
        if (!InventoryUtils.areStacksAndNbtEqual(mc.player.getMainHandStack(), required)) {
            int slot = InventoryUtils.findSlotWithItem(mc.player.playerScreenHandler, required, true);
            if (slot != -1) {
                InventoryUtils.swapItemToMainHand(required, mc);
            } else {
                // Логика с шалкером
                int shulkerSlot = getShulkerWithStack(mc.player.getInventory(), required);
                if (shulkerSlot != -1) {
                    Inventory shInv = (Inventory) getInventoryFromShulker(mc.player.getInventory().getStack(shulkerSlot));
                    int inner = getSlotWithStack(shInv, required);
                    if (inner != -1) {
                        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(inner, shulkerSlot));
                    }
                }
            }
        }

        // ВСЕГДА вызывать litematica-версию pickblock для Easy Place
        fi.dy.masa.litematica.util.InventoryUtils.schematicWorldPickBlock(required, pos, world, mc);

        cir.setReturnValue(true);
        cir.cancel();
    }* /*last07.10.2025*/

    @Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), cancellable = true)
    private static void doSchematicWorldPickBlockHook(boolean closest, MinecraftClient mc,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (mc == null || mc.player == null) return;

        // Если текущий слот запрещён pickBlockableSlots — НЕ вмешиваемся вообще.
        // Важно: НЕ cancel, иначе Easy Place перестанет работать.
        if (!isSelectedHotbarSlotAllowedByLitematica()) {
            return; // пусть оригинальный doSchematicWorldPickBlock от Litematica отработает сам
        }

        // 1) наш хит в схемо-мир
        final int range = (int) getValidBlockRange(mc);
        BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(mc.player, range, true, true);

        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            // Мы начали перехват, но тут делать нечего — лучше НЕ ломать оригинал.
            // Просто не вмешиваемся.
            return;
        }

        BlockPos pos = hit.getBlockPos();
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) {
            return;
        }

        BlockState state = world.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance()
                .getRequiredBuildItemForState(state, world, pos);

        // 2) наша логика: если нет в руке — попробуем из инвентаря/шалкера
        if (!fi.dy.masa.malilib.util.InventoryUtils.areStacksAndNbtEqual(mc.player.getMainHandStack(), required)) {
            int slot = fi.dy.masa.malilib.util.InventoryUtils.findSlotWithItem(
                    mc.player.playerScreenHandler, required, true
            );

            if (slot != -1) {
                fi.dy.masa.malilib.util.InventoryUtils.swapItemToMainHand(required, mc);
            } else {
                int shulkerSlot = getShulkerWithStack(mc.player.getInventory(), required);
                if (shulkerSlot != -1) {
                    Inventory shInv = (Inventory) getInventoryFromShulker(mc.player.getInventory().getStack(shulkerSlot));
                    int inner = getSlotWithStack(shInv, required);
                    if (inner != -1) {
                        ClientPlayNetworking.send(new Takeitout.GetShulkerStackPayload(inner, shulkerSlot));
                    }
                }
            }
        }

        // 3) дальше выполняем pickblock лайтематики как и раньше
        fi.dy.masa.litematica.util.InventoryUtils.schematicWorldPickBlock(required, pos, world, mc);

        // Мы полностью обработали вызов -> отменяем оригинал
        cir.setReturnValue(true);
        cir.cancel();
    }
    /*last22.09.2025,15:00*/


    /*@Inject(method = "doEasyPlaceAction", at = @At("RETURN"))
    private static void onEasyPlaceActionEnd(MinecraftClient mc, CallbackInfoReturnable<ActionResult> cir) {
        // Этот код выполнится после `return ActionResult.SUCCESS`
        WorldUtils.doSchematicWorldPickBlock(true, mc);
        //System.out.println("Easy Place завершено");
    }*/

    @Unique
    private static boolean isSelectedHotbarSlotAllowedByLitematica() {
        try {
            String raw = Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue(); // например "1,2,3,4,5"

            System.out.println("slots:"+raw);

            if (raw == null || raw.trim().isEmpty()) {
                return true; // пусто = не ограничено
            }

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null) return true;

            int selected = mc.player.getInventory().getSelectedSlot(); // 0..8

            for (String part : raw.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;

                try {
                    int oneBased = Integer.parseInt(part);
                    int zeroBased = oneBased - 1;
                    if (zeroBased == selected) return true;
                } catch (NumberFormatException ignored) {}
            }

            return false;
        } catch (Throwable ignored) {
            // если Litematica/Configs недоступны или что-то пошло не так — не ломаем работу
            return true;
        }
    }


}
