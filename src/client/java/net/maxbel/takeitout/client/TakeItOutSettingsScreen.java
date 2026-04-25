package net.maxbel.takeitout.client;

import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TakeItOutSettingsScreen extends Screen {
    private enum Tab {
        ALL_ITEMS,
        CONTAINERS
    }

    private static final int CONTAINER_ROW_HEIGHT = 28;
    private static final int LIST_TOP = 66;
    private static final int LIST_BOTTOM_MARGIN = 36;
    private static final int HEADER_OFFSET = 12;

    private final Screen parent;
    private Tab activeTab = Tab.ALL_ITEMS;
    private int scrollOffset;
    private BlockPos focusedContainer;

    public TakeItOutSettingsScreen(Screen parent) {
        super(Component.literal("TakeItOut"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int tabY = 28;
        int tabWidth = 88;
        int left = this.width / 2 - 270;

        addRenderableWidget(Button.builder(Component.literal("All Items"), button -> {
            activeTab = Tab.ALL_ITEMS;
            focusedContainer = null;
            scrollOffset = 0;
            requestItems();
        }).bounds(left, tabY, tabWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Containers"), button -> {
            activeTab = Tab.CONTAINERS;
            focusedContainer = null;
            scrollOffset = 0;
            requestItems();
        }).bounds(left + tabWidth + 4, tabY, tabWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Settings"), button ->
                this.minecraft.setScreen(TakeItOutKeybindsScreen.create(this))
        ).bounds(left + (tabWidth + 4) * 2, tabY, tabWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> requestItems())
                .bounds(left + (tabWidth + 4) * 3, tabY, 80, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Look At"), button -> focusTargetedContainer())
                .bounds(left + (tabWidth + 4) * 3 + 84, tabY, 80, 20)
                .build());

        addRenderableWidget(Button.builder(getSortButtonText(), button -> {
                    TakeitoutClient.cycleItemSortMode();
                    button.setMessage(getSortButtonText());
                    scrollOffset = 0;
                })
                .bounds(left + (tabWidth + 4) * 3 + 168, tabY, 96, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());

        requestItems();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xAA101010);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        if (activeTab == Tab.ALL_ITEMS) {
            renderAllItems(guiGraphics, mouseX, mouseY);
        } else {
            renderContainers(guiGraphics, mouseX, mouseY);
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int contentHeight = activeTab == Tab.ALL_ITEMS
                ? getSortedItems().size() * 22
                : getVisibleContainerSources().size() * CONTAINER_ROW_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - getListHeight());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * 18)));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (activeTab == Tab.CONTAINERS && event.button() == 0 && handleContainerClick((int) event.x(), (int) event.y())) {
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    private void renderAllItems(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        List<Takeitout.WorldContainerItemCount> items = getSortedItems();
        int listLeft = this.width / 2 - 155;
        int listTop = LIST_TOP;
        int listWidth = 310;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        guiGraphics.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0x66000000);
        guiGraphics.text(this.font, "Sources: " + WorldContainerSources.linkedSourceCountSnapshot(), listLeft + 6, listTop - HEADER_OFFSET, 0xFFA7F3D0);

        if (items.isEmpty()) {
            guiGraphics.centeredText(
                    this.font,
                    WorldContainerSources.linkedSourceCountSnapshot() == 0 ? "No linked containers" : "No items found",
                    this.width / 2,
                    listTop + 36,
                    0xFFAAAAAA
            );
            return;
        }

        guiGraphics.enableScissor(listLeft, listTop, listLeft + listWidth, listBottom);
        int y = listTop + 6 - scrollOffset;
        for (Takeitout.WorldContainerItemCount item : items) {
            if (y > listTop - 22 && y < listBottom) {
                renderItemRow(guiGraphics, item, listLeft + 8, y, mouseX, mouseY);
            }
            y += 22;
        }
        guiGraphics.disableScissor();
    }

    private void renderItemRow(GuiGraphicsExtractor guiGraphics, Takeitout.WorldContainerItemCount item, int x, int y, int mouseX, int mouseY) {
        ItemStack stack = item.stack();
        String count = "x" + item.count();
        int countX = x + 294 - this.font.width(count);

        guiGraphics.item(stack, x, y);
        guiGraphics.text(this.font, stack.getHoverName(), x + 24, y + 5, 0xFFFFFFFF);
        guiGraphics.text(this.font, count, countX, y + 5, 0xFFA7F3D0);

        if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
            guiGraphics.setTooltipForNextFrame(this.font, stack, mouseX, mouseY);
        }
    }

    private void renderContainers(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        List<WorldContainerSources.SourceEntry> sources = getVisibleContainerSources();
        int listLeft = this.width / 2 - 215;
        int listTop = LIST_TOP;
        int listWidth = 430;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        guiGraphics.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0x66000000);
        guiGraphics.text(
                this.font,
                trim(getContainersHeader(), listWidth - 12),
                listLeft + 6,
                listTop - HEADER_OFFSET,
                0xFFA7F3D0
        );

        if (sources.isEmpty()) {
            guiGraphics.centeredText(this.font, "No containers", this.width / 2, listTop + 36, 0xFFAAAAAA);
            return;
        }

        WorldContainerSources.SourceEntry hoveredSource = null;
        guiGraphics.enableScissor(listLeft, listTop, listLeft + listWidth, listBottom);
        int y = listTop + 6 - scrollOffset;
        for (WorldContainerSources.SourceEntry source : sources) {
            if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                renderContainerRow(guiGraphics, source, listLeft + 8, y, listWidth - 16, mouseX, mouseY);
                if (mouseX >= listLeft + 8 && mouseX < listLeft + listWidth - 8 && mouseY >= y && mouseY < y + 22) {
                    hoveredSource = source;
                }
            }
            y += CONTAINER_ROW_HEIGHT;
        }
        guiGraphics.disableScissor();

        if (hoveredSource != null) {
            renderContainerContentsTooltip(guiGraphics, hoveredSource, mouseX, mouseY);
        }
    }

    private void renderContainerRow(
            GuiGraphicsExtractor guiGraphics,
            WorldContainerSources.SourceEntry source,
            int x,
            int y,
            int width,
            int mouseX,
            int mouseY
    ) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 22;
        guiGraphics.fill(x, y, x + width, y + 22, hovered ? 0x8822D3EE : 0x44000000);

        ItemStack icon = getContainerIcon(source);
        if (!icon.isEmpty()) {
            guiGraphics.item(icon, x + 3, y + 3);
        }

        BlockPos pos = source.pos();
        String status = source.linked() ? "Linked" : "Unlinked";
        String label = status + " | " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " | " + source.dimension();
        guiGraphics.text(
                this.font,
                trim(label, width - 160),
                x + 24,
                y + 7,
                source.linked() ? 0xFFFFFFFF : 0xFFAAAAAA
        );

        int linkButtonX = x + width - 122;
        int deleteButtonX = x + width - 58;
        drawSmallButton(
                guiGraphics,
                linkButtonX,
                y + 2,
                58,
                18,
                source.linked() ? "Unlink" : "Link",
                hovered && mouseX >= linkButtonX && mouseX < linkButtonX + 58
        );
        drawSmallButton(
                guiGraphics,
                deleteButtonX,
                y + 2,
                54,
                18,
                "Delete",
                hovered && mouseX >= deleteButtonX && mouseX < deleteButtonX + 54
        );
    }

    private void renderContainerContentsTooltip(GuiGraphicsExtractor guiGraphics, WorldContainerSources.SourceEntry source, int mouseX, int mouseY) {
        List<Takeitout.WorldContainerItemCount> items = getSortedItemsForSource(WorldContainerSources.sourceKey(source));
        int rows = Math.min(items.size(), 10);
        int width = 180;
        for (int i = 0; i < rows; i++) {
            Takeitout.WorldContainerItemCount item = items.get(i);
            String count = "x" + item.count();
            width = Math.max(
                    width,
                    28 + this.font.width(item.stack().getHoverName()) + this.font.width(count) + 20
            );
        }

        if (items.size() > rows) {
            width = Math.max(width, this.font.width("+" + (items.size() - rows) + " more") + 12);
        }

        int height = 18 + Math.max(1, rows) * 20 + (items.size() > rows ? 10 : 0);
        int x = Math.min(mouseX + 12, this.width - width - 4);
        int y = Math.min(mouseY + 12, this.height - height - 4);

        guiGraphics.fill(x, y, x + width, y + height, 0xEE101010);
        drawBorder(guiGraphics, x, y, width, height, 0xFF22D3EE);
        guiGraphics.text(this.font, "Contents", x + 6, y + 6, 0xFFA7F3D0);

        if (items.isEmpty()) {
            guiGraphics.text(this.font, "Empty or unavailable", x + 6, y + 24, 0xFFAAAAAA);
            return;
        }

        int rowY = y + 20;
        for (int i = 0; i < rows; i++) {
            Takeitout.WorldContainerItemCount item = items.get(i);
            ItemStack stack = item.stack();
            String count = "x" + item.count();
            guiGraphics.item(stack, x + 6, rowY);
            guiGraphics.text(this.font, trim(stack.getHoverName().getString(), width - 62), x + 28, rowY + 5, 0xFFFFFFFF);
            guiGraphics.text(this.font, count, x + width - this.font.width(count) - 6, rowY + 5, 0xFFA7F3D0);
            rowY += 20;
        }

        if (items.size() > rows) {
            guiGraphics.text(this.font, "+" + (items.size() - rows) + " more", x + 6, rowY, 0xFFAAAAAA);
        }
    }

    private boolean handleContainerClick(int mouseX, int mouseY) {
        List<WorldContainerSources.SourceEntry> sources = getVisibleContainerSources();
        int listLeft = this.width / 2 - 215;
        int listTop = LIST_TOP;
        int listWidth = 430;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;
        int y = listTop + 6 - scrollOffset;

        for (WorldContainerSources.SourceEntry source : sources) {
            int linkButtonX = listLeft + 8 + listWidth - 16 - 122;
            int deleteButtonX = listLeft + 8 + listWidth - 16 - 58;
            if (y >= listTop && y + 22 <= listBottom && mouseY >= y + 2 && mouseY < y + 20) {
                if (mouseX >= linkButtonX && mouseX < linkButtonX + 58) {
                    if (WorldContainerSources.setLinked(this.minecraft, source, !source.linked())) {
                        requestItems();
                    }
                    return true;
                }

                if (mouseX >= deleteButtonX && mouseX < deleteButtonX + 54) {
                    if (WorldContainerSources.delete(this.minecraft, source)) {
                        requestItems();
                    }
                    return true;
                }
            }
            y += CONTAINER_ROW_HEIGHT;
        }

        return false;
    }

    private void focusTargetedContainer() {
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) {
            return;
        }

        if (!(this.minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !WorldContainerSources.isSupportedContainer(this.minecraft.level, hit.getBlockPos())) {
            this.minecraft.player.sendOverlayMessage(Component.literal("Look at a chest, barrel or shulker box"));
            return;
        }

        focusedContainer = hit.getBlockPos().immutable();
        activeTab = Tab.CONTAINERS;
        scrollOffset = 0;
        this.minecraft.player.sendOverlayMessage(Component.literal(
                "Showing container at "
                        + focusedContainer.getX() + " "
                        + focusedContainer.getY() + " "
                        + focusedContainer.getZ()
        ));
        requestItems();
    }

    private void requestItems() {
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) {
            return;
        }

        WorldContainerSources.updateContext(this.minecraft);
        TakeitoutClient.WORLD_CONTAINER_ITEMS.clear();
        TakeitoutClient.WORLD_CONTAINER_ITEMS_BY_SOURCE.clear();

        List<Takeitout.WorldContainerSource> sources;
        if (focusedContainer != null) {
            sources = List.of(WorldContainerSources.currentDimensionSource(focusedContainer));
        } else if (activeTab == Tab.CONTAINERS) {
            sources = WorldContainerSources.getAllSourceReferencesSnapshot();
        } else {
            sources = WorldContainerSources.getLinkedSourceReferencesSnapshot();
        }

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new Takeitout.GetWorldContainerItemsPayload(sources)
        );
    }

    private List<WorldContainerSources.SourceEntry> getVisibleContainerSources() {
        if (focusedContainer != null) {
            return List.of(new WorldContainerSources.SourceEntry(
                    WorldContainerSources.currentDimensionSource(focusedContainer).dimension(),
                    focusedContainer,
                    WorldContainerSources.isLinked(focusedContainer)
            ));
        }

        return WorldContainerSources.getAllSourcesSnapshot();
    }

    private String getContainersHeader() {
        String header = "World: " + WorldContainerSources.getCurrentContextLabel();
        if (focusedContainer != null) {
            header += " | Showing: "
                    + focusedContainer.getX() + " "
                    + focusedContainer.getY() + " "
                    + focusedContainer.getZ();
        }
        return header;
    }

    private int getListHeight() {
        return Math.max(0, this.height - LIST_TOP - LIST_BOTTOM_MARGIN);
    }

    private List<Takeitout.WorldContainerItemCount> getSortedItems() {
        List<Takeitout.WorldContainerItemCount> items = new ArrayList<>(TakeitoutClient.WORLD_CONTAINER_ITEMS);
        sortItems(items);
        return items;
    }

    private List<Takeitout.WorldContainerItemCount> getSortedItemsForSource(String source) {
        List<Takeitout.WorldContainerItemCount> items = new ArrayList<>(
                TakeitoutClient.WORLD_CONTAINER_ITEMS_BY_SOURCE.getOrDefault(source, List.of())
        );
        sortItems(items);
        return items;
    }

    private void sortItems(List<Takeitout.WorldContainerItemCount> items) {
        items.sort(getItemComparator());
    }

    private Comparator<Takeitout.WorldContainerItemCount> getItemComparator() {
        Comparator<Takeitout.WorldContainerItemCount> byName = Comparator.comparing(
                item -> item.stack().getHoverName().getString(),
                String.CASE_INSENSITIVE_ORDER
        );

        if (TakeitoutClient.ITEM_SORT_MODE == TakeitoutClient.ItemSortMode.COUNT) {
            return Comparator.comparingInt(Takeitout.WorldContainerItemCount::count)
                    .reversed()
                    .thenComparing(byName);
        }

        return byName.thenComparing(
                Comparator.comparingInt(Takeitout.WorldContainerItemCount::count).reversed()
        );
    }

    private Component getSortButtonText() {
        return Component.literal("Sort: " + TakeitoutClient.ITEM_SORT_MODE.label());
    }

    private ItemStack getContainerIcon(WorldContainerSources.SourceEntry source) {
        if (this.minecraft == null || this.minecraft.level == null) {
            return ItemStack.EMPTY;
        }

        String currentDimension = this.minecraft.level.dimension().identifier().toString();
        if (!source.dimension().equals(currentDimension)) {
            return ItemStack.EMPTY;
        }

        return this.minecraft.level.getBlockState(source.pos()).getBlock().asItem().getDefaultInstance();
    }

    private String trim(String value, int width) {
        return this.font.plainSubstrByWidth(value, width);
    }

    private void drawSmallButton(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height, String label, boolean hovered) {
        guiGraphics.fill(x, y, x + width, y + height, hovered ? 0xFF4B5563 : 0xFF2F2F2F);
        drawBorder(guiGraphics, x, y, width, height, 0xFF9CA3AF);
        guiGraphics.centeredText(this.font, label, x + width / 2, y + 5, 0xFFFFFFFF);
    }

    private void drawBorder(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height, int color) {
        guiGraphics.fill(x, y, x + width, y + 1, color);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, color);
        guiGraphics.fill(x, y, x + 1, y + height, color);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, color);
    }
}
