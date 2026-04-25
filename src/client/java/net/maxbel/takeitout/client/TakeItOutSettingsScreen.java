package net.maxbel.takeitout.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

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
        super(Text.literal("TakeItOut"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int tabY = 28;
        int tabWidth = 88;
        int left = this.width / 2 - 270;

        addDrawableChild(ButtonWidget.builder(Text.literal("All Items"), button -> {
            activeTab = Tab.ALL_ITEMS;
            focusedContainer = null;
            scrollOffset = 0;
            requestItems();
        }).position(left, tabY).size(tabWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Containers"), button -> {
            activeTab = Tab.CONTAINERS;
            focusedContainer = null;
            scrollOffset = 0;
            requestItems();
        }).position(left + tabWidth + 4, tabY).size(tabWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Settings"), button ->
                this.client.setScreen(TakeItOutKeybindsScreen.create(this))
        ).position(left + (tabWidth + 4) * 2, tabY).size(tabWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> requestItems())
                .position(left + (tabWidth + 4) * 3, tabY)
                .size(80, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Look At"), button -> focusTargetedContainer())
                .position(left + (tabWidth + 4) * 3 + 84, tabY)
                .size(80, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(getSortButtonText(), button -> {
                    TakeitoutClient.cycleItemSortMode();
                    button.setMessage(getSortButtonText());
                    scrollOffset = 0;
                })
                .position(left + (tabWidth + 4) * 3 + 168, tabY)
                .size(96, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.client.setScreen(parent))
                .position(this.width / 2 - 100, this.height - 28)
                .size(200, 20)
                .build());

        requestItems();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xAA101010);

        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);

        if (activeTab == Tab.ALL_ITEMS) {
            renderAllItems(context, mouseX, mouseY);
        } else {
            renderContainers(context, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int contentHeight = activeTab == Tab.ALL_ITEMS
                ? getSortedItems().size() * 22
                : getVisibleContainerSources().size() * CONTAINER_ROW_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - getListHeight());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (verticalAmount * 18)));
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (activeTab == Tab.CONTAINERS && click.button() == 0 && handleContainerClick((int) click.x(), (int) click.y())) {
            return true;
        }

        return super.mouseClicked(click, doubled);
    }

    private void renderAllItems(DrawContext context, int mouseX, int mouseY) {
        List<Takeitout.WorldContainerItemCount> items = getSortedItems();
        int listLeft = this.width / 2 - 155;
        int listTop = LIST_TOP;
        int listWidth = 310;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        context.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0x66000000);
        context.drawTextWithShadow(this.textRenderer, "Sources: " + WorldContainerSources.linkedSourceCountSnapshot(), listLeft + 6, listTop - HEADER_OFFSET, 0xFFA7F3D0);

        if (items.isEmpty()) {
            context.drawCenteredTextWithShadow(
                    this.textRenderer,
                    Text.literal(WorldContainerSources.linkedSourceCountSnapshot() == 0 ? "No linked containers" : "No items found"),
                    this.width / 2,
                    listTop + 36,
                    0xFFAAAAAA
            );
            return;
        }

        context.enableScissor(listLeft, listTop, listLeft + listWidth, listBottom);
        int y = listTop + 6 - scrollOffset;
        for (Takeitout.WorldContainerItemCount item : items) {
            if (y > listTop - 22 && y < listBottom) {
                renderItemRow(context, item, listLeft + 8, y, mouseX, mouseY);
            }
            y += 22;
        }
        context.disableScissor();
    }

    private void renderItemRow(DrawContext context, Takeitout.WorldContainerItemCount item, int x, int y, int mouseX, int mouseY) {
        ItemStack stack = item.stack();
        String count = "x" + item.count();
        int countX = x + 294 - this.textRenderer.getWidth(count);

        context.drawItem(stack, x, y);
        context.drawTextWithShadow(this.textRenderer, stack.getName(), x + 24, y + 5, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, count, countX, y + 5, 0xFFA7F3D0);

        if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
            context.drawItemTooltip(this.textRenderer, stack, mouseX, mouseY);
        }
    }

    private void renderContainers(DrawContext context, int mouseX, int mouseY) {
        List<WorldContainerSources.SourceEntry> sources = getVisibleContainerSources();
        int listLeft = this.width / 2 - 215;
        int listTop = LIST_TOP;
        int listWidth = 430;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        context.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0x66000000);
        context.drawTextWithShadow(
                this.textRenderer,
                trim(getContainersHeader(), listWidth - 12),
                listLeft + 6,
                listTop - HEADER_OFFSET,
                0xFFA7F3D0
        );

        if (sources.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No containers"), this.width / 2, listTop + 36, 0xFFAAAAAA);
            return;
        }

        WorldContainerSources.SourceEntry hoveredSource = null;
        context.enableScissor(listLeft, listTop, listLeft + listWidth, listBottom);
        int y = listTop + 6 - scrollOffset;
        for (WorldContainerSources.SourceEntry source : sources) {
            if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                renderContainerRow(context, source, listLeft + 8, y, listWidth - 16, mouseX, mouseY);
                if (mouseX >= listLeft + 8 && mouseX < listLeft + listWidth - 8 && mouseY >= y && mouseY < y + 22) {
                    hoveredSource = source;
                }
            }
            y += CONTAINER_ROW_HEIGHT;
        }
        context.disableScissor();

        if (hoveredSource != null) {
            renderContainerContentsTooltip(context, hoveredSource, mouseX, mouseY);
        }
    }

    private void renderContainerRow(DrawContext context, WorldContainerSources.SourceEntry source, int x, int y, int width, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 22;
        context.fill(x, y, x + width, y + 22, hovered ? 0x8822D3EE : 0x44000000);

        ItemStack icon = getContainerIcon(source);
        if (!icon.isEmpty()) {
            context.drawItem(icon, x + 3, y + 3);
        }

        BlockPos pos = source.pos();
        String status = source.linked() ? "Linked" : "Unlinked";
        String label = status + " | " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " | " + source.dimension();
        context.drawTextWithShadow(this.textRenderer, trim(label, width - 160), x + 24, y + 7, source.linked() ? 0xFFFFFFFF : 0xFFAAAAAA);

        int linkButtonX = x + width - 122;
        int deleteButtonX = x + width - 58;
        drawSmallButton(context, linkButtonX, y + 2, 58, 18, source.linked() ? "Unlink" : "Link", hovered && mouseX >= linkButtonX && mouseX < linkButtonX + 58);
        drawSmallButton(context, deleteButtonX, y + 2, 54, 18, "Delete", hovered && mouseX >= deleteButtonX && mouseX < deleteButtonX + 54);
    }

    private void renderContainerContentsTooltip(DrawContext context, WorldContainerSources.SourceEntry source, int mouseX, int mouseY) {
        List<Takeitout.WorldContainerItemCount> items = getSortedItemsForSource(WorldContainerSources.sourceKey(source));
        int rows = Math.min(items.size(), 10);
        int width = 180;
        for (int i = 0; i < rows; i++) {
            Takeitout.WorldContainerItemCount item = items.get(i);
            width = Math.max(width, 28 + this.textRenderer.getWidth(item.stack().getName()) + this.textRenderer.getWidth(" x" + item.count()) + 12);
        }

        int height = 18 + Math.max(1, rows) * 20 + (items.size() > rows ? 10 : 0);
        int x = Math.min(mouseX + 12, this.width - width - 4);
        int y = Math.min(mouseY + 12, this.height - height - 4);

        context.fill(x, y, x + width, y + height, 0xEE101010);
        drawBorder(context, x, y, width, height, 0xFF22D3EE);
        context.drawTextWithShadow(this.textRenderer, "Contents", x + 6, y + 6, 0xFFA7F3D0);

        if (items.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, "Empty or unavailable", x + 6, y + 24, 0xFFAAAAAA);
            return;
        }

        int rowY = y + 20;
        for (int i = 0; i < rows; i++) {
            Takeitout.WorldContainerItemCount item = items.get(i);
            ItemStack stack = item.stack();
            context.drawItem(stack, x + 6, rowY);
            context.drawTextWithShadow(this.textRenderer, trim(stack.getName().getString(), width - 62), x + 28, rowY + 5, 0xFFFFFFFF);
            String count = "x" + item.count();
            context.drawTextWithShadow(this.textRenderer, count, x + width - this.textRenderer.getWidth(count) - 6, rowY + 5, 0xFFA7F3D0);
            rowY += 20;
        }

        if (items.size() > rows) {
            context.drawTextWithShadow(this.textRenderer, "+" + (items.size() - rows) + " more", x + 6, rowY, 0xFFAAAAAA);
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
            if (y >= listTop
                    && y + 22 <= listBottom
                    && mouseY >= y + 2
                    && mouseY < y + 20) {
                if (mouseX >= linkButtonX && mouseX < linkButtonX + 58) {
                    if (WorldContainerSources.setLinked(this.client, source, !source.linked())) {
                        requestItems();
                    }
                    return true;
                }

                if (mouseX >= deleteButtonX && mouseX < deleteButtonX + 54) {
                    if (WorldContainerSources.delete(this.client, source)) {
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || !(client.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !WorldContainerSources.isSupportedContainer(client.world, hit.getBlockPos())) {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Look at a chest, barrel or shulker box"), true);
            }
            return;
        }

        focusedContainer = hit.getBlockPos().toImmutable();
        activeTab = Tab.CONTAINERS;
        scrollOffset = 0;
        client.player.sendMessage(
                Text.literal("Showing container at "
                        + focusedContainer.getX() + " "
                        + focusedContainer.getY() + " "
                        + focusedContainer.getZ()),
                true
        );
        requestItems();
    }

    private void requestItems() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        WorldContainerSources.updateContext(client);
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
        ClientPlayNetworking.send(new Takeitout.GetWorldContainerItemsPayload(sources));
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
                item -> item.stack().getName().getString(),
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

    private Text getSortButtonText() {
        return Text.literal("Sort: " + TakeitoutClient.ITEM_SORT_MODE.label());
    }

    private ItemStack getContainerIcon(WorldContainerSources.SourceEntry source) {
        if (this.client == null || this.client.world == null) {
            return ItemStack.EMPTY;
        }

        String currentDimension = this.client.world.getRegistryKey().getValue().toString();
        if (!source.dimension().equals(currentDimension)) {
            return ItemStack.EMPTY;
        }

        Block block = this.client.world.getBlockState(source.pos()).getBlock();
        return block.asItem().getDefaultStack();
    }

    private String trim(String value, int width) {
        return this.textRenderer.trimToWidth(value, width);
    }

    private void drawSmallButton(DrawContext context, int x, int y, int width, int height, String label, boolean hovered) {
        context.fill(x, y, x + width, y + height, hovered ? 0xFF4B5563 : 0xFF2F2F2F);
        drawBorder(context, x, y, width, height, 0xFF9CA3AF);
        context.drawCenteredTextWithShadow(this.textRenderer, label, x + width / 2, y + 5, 0xFFFFFFFF);
    }

    private void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }
}
