package net.maxbel.takeitout.mixin.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
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

import static fi.dy.masa.litematica.util.WorldUtils.getValidBlockRange;
import static net.maxbel.takeitout.client.ItemStackInventory.getInventoryFromShulker;
import static net.maxbel.takeitout.client.Util.getShulkerWithStack;
import static net.maxbel.takeitout.client.Util.getSlotWithStack;

@Mixin(value = WorldUtils.class, remap = false)
public class LitematicaMixin {

    @Unique private static int waitTicks = 0;
    @Unique private static boolean waitingForItem = false;
    @Unique private static BlockState waitingState = null;
    @Unique private static int retryCount = 0;
    @Unique private static int expectedWaitTicks = 40; // пересчитается динамически
    @Unique private static long requestTsMs = 0L;

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

    /**
     * Перехват Easy Place: если в руке не тот предмет — инициируем pick block и ждём.
     * Делается на HEAD, чтобы не зависеть от внутренних вызовов Litematica (они часто меняются между версиями).
     */
    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, remap = false)
    private static void interceptMissingItem(MinecraftClient mc, CallbackInfoReturnable<ActionResult> cir) {
        if (mc == null || mc.player == null) return;

        BlockHitResult result = RayTraceUtils.traceToSchematicWorld(mc.player, 6, true, true);
        if (result == null) return;

        BlockPos pos = result.getBlockPos();
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return;

        BlockState state = schematic.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state);
        ItemStack inHand = mc.player.getMainHandStack();

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
            cir.cancel();
        }
    }

    @ModifyArg(
            method = "easyPlaceOnUseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/util/WorldUtils;doEasyPlaceAction(Lnet/minecraft/client/MinecraftClient;)Lnet/minecraft/util/ActionResult;"
            ),
            remap = false
    )
    private static MinecraftClient checkItemAndTick(MinecraftClient client) {
        if (waitingForItem && client != null && client.player != null && waitingState != null) {
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

    @Inject(method = "doSchematicWorldPickBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private static void doSchematicWorldPickBlockHook(boolean closest, MinecraftClient mc,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (mc == null || mc.player == null) return;

        // Если текущий слот запрещён pickBlockableSlots — НЕ вмешиваемся вообще.
        // Важно: НЕ cancel, иначе Easy Place перестанет работать.
        if (!isSelectedHotbarSlotAllowedByLitematica()) {
            return; // пусть оригинальный doSchematicWorldPickBlock от Litematica отработает сам
        }

        final int range = (int) getValidBlockRange(mc);
        BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(mc.player, range, true, true);

        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos pos = hit.getBlockPos();
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        if (world == null) return;

        BlockState state = world.getBlockState(pos);
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state, world, pos);

        // 2) наша логика: если нет в руке — попробуем из инвентаря/шалкера
        if (!InventoryUtils.areStacksAndNbtEqual(mc.player.getMainHandStack(), required)) {
            int slot = InventoryUtils.findSlotWithItem(mc.player.playerScreenHandler, required, true);

            if (slot != -1) {
                InventoryUtils.swapItemToMainHand(required, mc);
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

        cir.setReturnValue(true);
        cir.cancel();
    }

    @Unique
    private static boolean isSelectedHotbarSlotAllowedByLitematica() {
        try {
            String raw = Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue(); // например "1,2,3,4,5"
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
