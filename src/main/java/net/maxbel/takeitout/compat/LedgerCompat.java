package net.maxbel.takeitout.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

public final class LedgerCompat {
    private static final Object CONTAINER_EVENT_LOGGER;
    private static final Method LOG_ITEM_REMOVE;
    private static final Method LOG_ITEM_INSERT;
    private static final Constructor<?> ACTOR_CONSTRUCTOR;

    static {
        Object logger = null;
        Method logRemove = null;
        Method logInsert = null;
        Constructor<?> actorCtor = null;

        try {
            Class<?> actorClass = Class.forName("com.github.quiltservertools.ledger.actions.ActionActor");
            actorCtor = actorClass.getConstructor(String.class, UUID.class);

            Class<?> loggerClass = Class.forName("com.github.quiltservertools.ledger.events.ContainerEventLogger");
            Method getInstance = loggerClass.getMethod("getInstance");
            logger = getInstance.invoke(null);
            logRemove = loggerClass.getMethod("logItemRemove", ItemStack.class, BlockPos.class, actorClass);
            logInsert = loggerClass.getMethod("logItemInsert", ItemStack.class, BlockPos.class, actorClass);
        } catch (ReflectiveOperationException ignored) {
        }

        CONTAINER_EVENT_LOGGER = logger;
        LOG_ITEM_REMOVE = logRemove;
        LOG_ITEM_INSERT = logInsert;
        ACTOR_CONSTRUCTOR = actorCtor;
    }

    private LedgerCompat() {}

    public static void logItemRemove(ServerPlayer player, BlockPos pos, ItemStack stack) {
        logItemAction(LOG_ITEM_REMOVE, player, pos, stack);
    }

    public static void logItemInsert(ServerPlayer player, BlockPos pos, ItemStack stack) {
        logItemAction(LOG_ITEM_INSERT, player, pos, stack);
    }

    private static void logItemAction(Method method, ServerPlayer player, BlockPos pos, ItemStack stack) {
        if (method == null || ACTOR_CONSTRUCTOR == null) return;
        try {
            Object actor = ACTOR_CONSTRUCTOR.newInstance(player.getName().getString(), player.getUUID());
            method.invoke(CONTAINER_EVENT_LOGGER, stack, pos, actor);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
