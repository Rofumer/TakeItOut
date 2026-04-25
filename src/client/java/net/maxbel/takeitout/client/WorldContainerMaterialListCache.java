package net.maxbel.takeitout.client;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.malilib.util.ItemType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.util.List;

public final class WorldContainerMaterialListCache {
    private static final long REFRESH_THROTTLE_MS = 500L;
    private static final Object2IntOpenHashMap<ItemType> LINKED_CONTAINER_COUNTS = new Object2IntOpenHashMap<>();
    private static boolean refreshPending;
    private static long lastRefreshRequestMs;

    private WorldContainerMaterialListCache() {
    }

    public static void requestRefresh(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        if (refreshPending) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastRefreshRequestMs < REFRESH_THROTTLE_MS) {
            return;
        }
        lastRefreshRequestMs = now;

        WorldContainerSources.updateContext(client);
        List<Takeitout.WorldContainerSource> sources = WorldContainerSources.getLinkedSourceReferencesSnapshot();
        if (sources.isEmpty()) {
            refreshPending = false;
            LINKED_CONTAINER_COUNTS.clear();
            refreshCurrentMaterialList(client);
            return;
        }

        refreshPending = true;
        ClientPlayNetworking.send(new Takeitout.GetWorldContainerItemsPayload(sources));
    }

    public static void handleItemsPayload(Takeitout.WorldContainerItemsPayload payload) {
        if (!refreshPending) {
            return;
        }

        refreshPending = false;
        LINKED_CONTAINER_COUNTS.clear();

        for (Takeitout.WorldContainerItemCount item : payload.items()) {
            ItemStack stack = item.stack();
            if (stack == null || stack.isEmpty() || item.count() <= 0) {
                continue;
            }

            LINKED_CONTAINER_COUNTS.addTo(new ItemType(stack.copyWithCount(1), true, false), item.count());
        }

        refreshCurrentMaterialList(MinecraftClient.getInstance());
    }

    public static void addAvailableCounts(List<MaterialListEntry> entries) {
        if (entries == null || entries.isEmpty() || LINKED_CONTAINER_COUNTS.isEmpty()) {
            return;
        }

        for (MaterialListEntry entry : entries) {
            ItemStack stack = entry.getStack();
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            int linkedContainerCount = LINKED_CONTAINER_COUNTS.getInt(new ItemType(stack, true, false));
            if (linkedContainerCount > 0) {
                entry.setCountAvailable(entry.getCountAvailable() + linkedContainerCount);
            }
        }
    }

    private static void refreshCurrentMaterialList(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }

        MaterialListBase materialList = DataManager.getMaterialList();
        if (materialList == null) {
            return;
        }

        MaterialListUtils.updateAvailableCounts(materialList.getMaterialsAll(), client.player);
        materialList.refreshPreFilteredList();
        materialList.recreateFilteredList();

        if (client.currentScreen instanceof GuiMaterialList gui) {
            gui.initGui();
        }
    }
}
