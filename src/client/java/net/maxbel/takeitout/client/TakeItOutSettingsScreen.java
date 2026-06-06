package net.maxbel.takeitout.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
        CONTAINERS,
        GROUPS
    }

    private static final int CONTAINER_ROW_HEIGHT = 28;
    private static final int LIST_TOP = 66;
    private static final int LIST_BOTTOM_MARGIN = 36;
    private static final int HEADER_OFFSET = 12;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int LIST_CONTENT_PADDING = 6;

    private final Screen parent;
    private Tab activeTab = Tab.ALL_ITEMS;
    private int scrollOffset;
    private BlockPos focusedContainer;
    private EditBox searchField;
    private String searchQuery = "";
    private boolean confirmDeleteAll = false;
    private boolean confirmDeleteAllDumps = false;
    private boolean scrollbarDragging = false;
    private int scrollbarDragStartY;
    private int scrollbarDragStartOffset;

    // Groups tab state
    private String groupInputMode = null;   // null, "create", or "rename"
    private String groupInputTarget = null; // old name when renaming
    private EditBox groupNameField;

    public TakeItOutSettingsScreen(Screen parent) {
        super(Component.literal("TakeItOut"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int tabY = 28;
        int tabWidth = 80;
        int left = this.width / 2 - 270;

        addRenderableWidget(Button.builder(Component.literal("All Items"), button -> {
            activeTab = Tab.ALL_ITEMS;
            focusedContainer = null;
            scrollOffset = 0;
            confirmDeleteAll = false;
            searchField.setVisible(true);
            groupNameField.setVisible(false);
            requestItems();
        }).bounds(left, tabY, tabWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Containers"), button -> {
            activeTab = Tab.CONTAINERS;
            focusedContainer = null;
            scrollOffset = 0;
            searchField.setVisible(false);
            groupNameField.setVisible(false);
            requestItems();
        }).bounds(left + tabWidth + 4, tabY, tabWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Groups"), button -> {
            activeTab = Tab.GROUPS;
            focusedContainer = null;
            scrollOffset = 0;
            groupInputMode = null;
            searchField.setVisible(false);
            groupNameField.setVisible(false);
        }).bounds(left + (tabWidth + 4) * 2, tabY, tabWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Settings"), button ->
                this.minecraft.setScreen(TakeItOutKeybindsScreen.create(this))
        ).bounds(left + (tabWidth + 4) * 3, tabY, tabWidth, 20).build());

        int auxLeft = left + (tabWidth + 4) * 4;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> requestItems())
                .bounds(auxLeft, tabY, 72, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Look At"), button -> focusTargetedContainer())
                .bounds(auxLeft + 76, tabY, 72, 20).build());

        addRenderableWidget(Button.builder(getSortButtonText(), button -> {
                    TakeitoutClient.cycleItemSortMode();
                    button.setMessage(getSortButtonText());
                    scrollOffset = 0;
                })
                .bounds(auxLeft + 152, tabY, 96, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());

        int listLeft = this.width / 2 - 155;
        searchField = new EditBox(this.font, listLeft, 50, 310, 14, Component.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setHint(Component.literal("Search..."));
        searchField.setResponder(text -> {
            searchQuery = text;
            scrollOffset = 0;
        });
        searchField.setVisible(activeTab == Tab.ALL_ITEMS);
        addRenderableWidget(searchField);

        int groupsListLeft = this.width / 2 - 215;
        groupNameField = new EditBox(this.font, groupsListLeft + 8, LIST_TOP + 8, 240, 14, Component.literal("Group name"));
        groupNameField.setMaxLength(32);
        groupNameField.setHint(Component.literal("Group name..."));
        groupNameField.setVisible(false);
        addRenderableWidget(groupNameField);

        requestItems();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xAA101010);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        if (activeTab == Tab.ALL_ITEMS) {
            renderAllItems(guiGraphics, mouseX, mouseY);
        } else if (activeTab == Tab.CONTAINERS) {
            renderContainers(guiGraphics, mouseX, mouseY);
        } else {
            renderGroups(guiGraphics, mouseX, mouseY);
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, getContentHeight() - getListHeight());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * 18)));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int listLeft = getActiveListLeft();
            int listWidth = getActiveListWidth();
            int trackX = listLeft + listWidth - SCROLLBAR_WIDTH;
            int listTop = LIST_TOP;
            int listBottom = this.height - LIST_BOTTOM_MARGIN;
            if (event.x() >= trackX && event.x() < listLeft + listWidth && event.y() >= listTop && event.y() < listBottom) {
                int contentHeight = getContentHeight();
                int[] thumb = getScrollbarThumb(listTop, listBottom, contentHeight);
                if (thumb != null) {
                    if (event.y() >= thumb[0] && event.y() < thumb[0] + thumb[1]) {
                        scrollbarDragging = true;
                        scrollbarDragStartY = (int) event.y();
                        scrollbarDragStartOffset = scrollOffset;
                    } else {
                        int listHeight = listBottom - listTop;
                        int maxScroll = Math.max(0, contentHeight - listHeight);
                        float ratio = (float) (event.y() - listTop) / listHeight;
                        scrollOffset = Math.max(0, Math.min(maxScroll, (int) (ratio * contentHeight)));
                    }
                    return true;
                }
            }
        }

        if (activeTab == Tab.CONTAINERS && event.button() == 0 && handleContainerClick((int) event.x(), (int) event.y())) {
            return true;
        }

        if (activeTab == Tab.ALL_ITEMS && (event.button() == 0 || event.button() == 1)
                && handleAllItemsClick((int) event.x(), (int) event.y(), event.button())) {
            return true;
        }

        if (activeTab == Tab.GROUPS && event.button() == 0 && handleGroupsClick((int) event.x(), (int) event.y())) {
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (scrollbarDragging) {
            int listTop = LIST_TOP;
            int listBottom = this.height - LIST_BOTTOM_MARGIN;
            int listHeight = listBottom - listTop;
            int contentHeight = getContentHeight();
            if (contentHeight > listHeight) {
                int thumbHeight = Math.max(20, listHeight * listHeight / contentHeight);
                int maxThumbTravel = listHeight - thumbHeight;
                int maxScroll = contentHeight - listHeight;
                int delta = (int) event.y() - scrollbarDragStartY;
                int newOffset = scrollbarDragStartOffset + (int) ((long) delta * maxScroll / maxThumbTravel);
                scrollOffset = Math.max(0, Math.min(maxScroll, newOffset));
            }
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (scrollbarDragging && event.button() == 0) {
            scrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    // --- All Items tab ---

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
                    this.width / 2, listTop + 36, 0xFFAAAAAA
            );
            return;
        }

        Takeitout.WorldContainerItemCount hoveredItem = null;
        guiGraphics.enableScissor(listLeft, listTop, listLeft + listWidth, listBottom);
        int y = listTop + 6 - scrollOffset;
        for (Takeitout.WorldContainerItemCount item : items) {
            if (y > listTop - 22 && y < listBottom) {
                renderItemRow(guiGraphics, item, listLeft + 8, y, mouseX, mouseY);
                if (hoveredItem == null && mouseX >= listLeft && mouseX < listLeft + listWidth
                        && mouseY >= y && mouseY < y + 22 && y >= listTop && y < listBottom) {
                    hoveredItem = item;
                }
            }
            y += 22;
        }
        guiGraphics.disableScissor();
        renderScrollbar(guiGraphics, listLeft, listTop, listWidth, listBottom, LIST_CONTENT_PADDING + items.size() * 22);

        if (hoveredItem != null) {
            String line1 = Component.translatable("tooltip.takeitout.take_stack").getString();
            String line2 = Component.translatable("tooltip.takeitout.take_single").getString();
            int tw = Math.max(this.font.width(line1), this.font.width(line2)) + 12;
            int th = 32;
            int tx = Math.min(mouseX + 12, this.width - tw - 4);
            int ty = Math.min(mouseY + 12, this.height - th - 4);
            guiGraphics.fill(tx, ty, tx + tw, ty + th, 0xEE101010);
            drawBorder(guiGraphics, tx, ty, tw, th, 0xFF9CA3AF);
            guiGraphics.text(this.font, line1, tx + 6, ty + 6, 0xFFFFFFFF);
            guiGraphics.text(this.font, line2, tx + 6, ty + 18, 0xFFFFFFFF);
        }
    }

    private void renderItemRow(GuiGraphicsExtractor guiGraphics, Takeitout.WorldContainerItemCount item, int x, int y, int mouseX, int mouseY) {
        ItemStack stack = item.stack();
        String count = "x" + item.count();
        int countX = x + 294 - this.font.width(count);
        boolean hovered = mouseX >= x - 8 && mouseX < x + 302 && mouseY >= y && mouseY < y + 22;
        if (hovered) guiGraphics.fill(x - 8, y, x + 302, y + 22, 0x33FFFFFF);
        guiGraphics.item(stack, x, y + 3);
        guiGraphics.text(this.font, stack.getHoverName(), x + 24, y + 8, 0xFFFFFFFF);
        guiGraphics.text(this.font, count, countX, y + 8, 0xFFA7F3D0);
    }

    // --- Containers tab ---

    private void renderContainers(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        List<WorldContainerSources.SourceEntry> sources = getVisibleContainerSources();
        List<WorldContainerDumps.DumpEntry> dumps = focusedContainer == null
                ? WorldContainerDumps.getAllDumpsSnapshot()
                : List.of();
        int listLeft = this.width / 2 - 215;
        int listTop = LIST_TOP;
        int listWidth = 430;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        guiGraphics.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0x66000000);
        guiGraphics.text(this.font, trim(getContainersHeader(), listWidth - 96), listLeft + 6, listTop - HEADER_OFFSET, 0xFFA7F3D0);

        if (focusedContainer == null && !sources.isEmpty()) {
            int deleteAllBtnX = listLeft + listWidth - 84;
            int deleteAllBtnY = listTop - 16;
            boolean deleteAllHovered = mouseX >= deleteAllBtnX && mouseX < deleteAllBtnX + 80
                    && mouseY >= deleteAllBtnY && mouseY < deleteAllBtnY + 14;
            if (confirmDeleteAll) {
                guiGraphics.fill(deleteAllBtnX, deleteAllBtnY, deleteAllBtnX + 80, deleteAllBtnY + 14, deleteAllHovered ? 0xFFB91C1C : 0xFF7F1D1D);
                drawBorder(guiGraphics, deleteAllBtnX, deleteAllBtnY, 80, 14, 0xFFEF4444);
                guiGraphics.centeredText(this.font, "Confirm?", deleteAllBtnX + 40, deleteAllBtnY + 3, 0xFFFFFFFF);
            } else {
                drawSmallButton(guiGraphics, deleteAllBtnX, deleteAllBtnY, 80, 14, "Delete All", deleteAllHovered);
            }
        }

        if (sources.isEmpty() && dumps.isEmpty()) {
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

        if (!dumps.isEmpty()) {
            if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                guiGraphics.fill(listLeft + 8, y + 9, listLeft + listWidth - 8, y + 10, 0x44F97316);
                guiGraphics.text(this.font, trim("Dump Containers (" + dumps.size() + ")", listWidth - 100), listLeft + 8, y + 2, 0xFFF97316);

                int deleteAllDumpsBtnX = listLeft + listWidth - 84;
                int deleteAllDumpsBtnY = y + 7;
                boolean deleteAllDumpsHovered = mouseX >= deleteAllDumpsBtnX && mouseX < deleteAllDumpsBtnX + 80
                        && mouseY >= deleteAllDumpsBtnY && mouseY < deleteAllDumpsBtnY + 14;
                if (confirmDeleteAllDumps) {
                    guiGraphics.fill(deleteAllDumpsBtnX, deleteAllDumpsBtnY, deleteAllDumpsBtnX + 80, deleteAllDumpsBtnY + 14,
                            deleteAllDumpsHovered ? 0xFFB91C1C : 0xFF7F1D1D);
                    drawBorder(guiGraphics, deleteAllDumpsBtnX, deleteAllDumpsBtnY, 80, 14, 0xFFEF4444);
                    guiGraphics.centeredText(this.font, "Confirm?", deleteAllDumpsBtnX + 40, deleteAllDumpsBtnY + 3, 0xFFFFFFFF);
                } else {
                    guiGraphics.fill(deleteAllDumpsBtnX, deleteAllDumpsBtnY, deleteAllDumpsBtnX + 80, deleteAllDumpsBtnY + 14,
                            deleteAllDumpsHovered ? 0xFF92400E : 0xFF78350F);
                    drawBorder(guiGraphics, deleteAllDumpsBtnX, deleteAllDumpsBtnY, 80, 14, 0xFFF97316);
                    guiGraphics.centeredText(this.font, "Delete All", deleteAllDumpsBtnX + 40, deleteAllDumpsBtnY + 3, 0xFFFFFFFF);
                }
            }
            y += CONTAINER_ROW_HEIGHT;

            for (WorldContainerDumps.DumpEntry dump : dumps) {
                if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                    renderDumpRow(guiGraphics, dump, listLeft + 8, y, listWidth - 16, mouseX, mouseY);
                }
                y += CONTAINER_ROW_HEIGHT;
            }
        }

        guiGraphics.disableScissor();

        int sourceHeight = sources.size() * CONTAINER_ROW_HEIGHT;
        int dumpHeight = dumps.isEmpty() ? 0 : (CONTAINER_ROW_HEIGHT + dumps.size() * CONTAINER_ROW_HEIGHT);
        renderScrollbar(guiGraphics, listLeft, listTop, listWidth, listBottom, LIST_CONTENT_PADDING + sourceHeight + dumpHeight);

        if (hoveredSource != null) {
            renderContainerContentsTooltip(guiGraphics, hoveredSource, mouseX, mouseY);
        }
    }

    private void renderDumpRow(GuiGraphicsExtractor guiGraphics, WorldContainerDumps.DumpEntry dump, int x, int y, int width, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 22;
        guiGraphics.fill(x, y, x + width, y + 22, hovered ? 0x88F97316 : 0x33F97316);
        BlockPos pos = dump.pos();
        String label = (dump.enabled() ? "Dump" : "Disabled") + " | " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        guiGraphics.text(this.font, trim(label, width - 132), x + 4, y + 7, dump.enabled() ? 0xFFFBBF24 : 0xFFAAAAAA);
        int markButtonX = x + width - 122;
        int deleteButtonX = x + width - 58;
        drawSmallButton(guiGraphics, markButtonX, y + 2, 58, 18, dump.enabled() ? "Unmark" : "Mark",
                hovered && mouseX >= markButtonX && mouseX < markButtonX + 58);
        drawSmallButton(guiGraphics, deleteButtonX, y + 2, 54, 18, "Delete",
                hovered && mouseX >= deleteButtonX && mouseX < deleteButtonX + 54);
    }

    private void renderContainerRow(GuiGraphicsExtractor guiGraphics, WorldContainerSources.SourceEntry source, int x, int y, int width, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 22;
        guiGraphics.fill(x, y, x + width, y + 22, hovered ? 0x8822D3EE : 0x44000000);
        ItemStack icon = getContainerIcon(source);
        if (!icon.isEmpty()) guiGraphics.item(icon, x + 3, y + 3);
        BlockPos pos = source.pos();
        String status = source.linked() ? "Linked" : "Unlinked";
        String label = status + " | " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " | " + source.dimension();
        guiGraphics.text(this.font, trim(label, width - 160), x + 24, y + 7, source.linked() ? 0xFFFFFFFF : 0xFFAAAAAA);
        int linkButtonX = x + width - 122;
        int deleteButtonX = x + width - 58;
        drawSmallButton(guiGraphics, linkButtonX, y + 2, 58, 18, source.linked() ? "Unlink" : "Link",
                hovered && mouseX >= linkButtonX && mouseX < linkButtonX + 58);
        drawSmallButton(guiGraphics, deleteButtonX, y + 2, 54, 18, "Delete",
                hovered && mouseX >= deleteButtonX && mouseX < deleteButtonX + 54);
    }

    private void renderContainerContentsTooltip(GuiGraphicsExtractor guiGraphics, WorldContainerSources.SourceEntry source, int mouseX, int mouseY) {
        List<Takeitout.WorldContainerItemCount> items = getSortedItemsForSource(WorldContainerSources.sourceKey(source));
        int rows = Math.min(items.size(), 10);
        int width = 180;
        for (int i = 0; i < rows; i++) {
            Takeitout.WorldContainerItemCount item = items.get(i);
            String count = "x" + item.count();
            width = Math.max(width, 28 + this.font.width(item.stack().getHoverName()) + this.font.width(count) + 20);
        }
        if (items.size() > rows) width = Math.max(width, this.font.width("+" + (items.size() - rows) + " more") + 12);
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
        if (items.size() > rows) guiGraphics.text(this.font, "+" + (items.size() - rows) + " more", x + 6, rowY, 0xFFAAAAAA);
    }

    // --- Groups tab ---

    private void renderGroups(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        List<String> groups = WorldContainerSources.getGroupNames();
        String activeGroup = WorldContainerSources.getCurrentGroupName();
        int listLeft = this.width / 2 - 215;
        int listTop = LIST_TOP;
        int listWidth = 430;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        guiGraphics.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0x66000000);

        guiGraphics.text(this.font, "Groups", listLeft + 6, listTop - HEADER_OFFSET, 0xFFA7F3D0);

        // New Group button in header
        int newBtnX = listLeft + listWidth - 84;
        int newBtnY = listTop - 16;
        boolean newBtnHovered = mouseX >= newBtnX && mouseX < newBtnX + 80 && mouseY >= newBtnY && mouseY < newBtnY + 14;
        if (groupInputMode == null) {
            drawSmallButton(guiGraphics, newBtnX, newBtnY, 80, 14, "+ New Group", newBtnHovered);
        } else {
            drawSmallButton(guiGraphics, newBtnX, newBtnY, 80, 14, "Cancel", newBtnHovered);
        }

        guiGraphics.enableScissor(listLeft, listTop, listLeft + listWidth, listBottom);
        int y = listTop + 6 - scrollOffset;

        // Input row (create / rename mode)
        if (groupInputMode != null) {
            // EditBox is positioned here; just draw the confirm button next to it
            // groupNameField is rendered by the widget system at its fixed coords
            int confirmBtnX = listLeft + 8 + 244;
            int confirmBtnY = listTop + 8;
            boolean confirmHovered = mouseX >= confirmBtnX && mouseX < confirmBtnX + 80 && mouseY >= confirmBtnY && mouseY < confirmBtnY + 14;
            String confirmLabel = "rename".equals(groupInputMode) ? "Rename" : "Create";
            drawSmallButton(guiGraphics, confirmBtnX, confirmBtnY, 80, 14, confirmLabel, confirmHovered);
            y += CONTAINER_ROW_HEIGHT;
        }

        for (String group : groups) {
            if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                renderGroupRow(guiGraphics, group, activeGroup, listLeft + 8, y, listWidth - 16, mouseX, mouseY, groups.size());
            }
            y += CONTAINER_ROW_HEIGHT;
        }

        if (SharedGroupsClient.serverSupportsSharedGroups) {
            if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                guiGraphics.fill(listLeft + 8, y + 10, listLeft + listWidth - 8, y + 11, 0x44FFD700);
                guiGraphics.text(this.font, "Server Groups (" + SharedGroupsClient.SHARED_GROUPS.size() + ")", listLeft + 8, y + 2, 0xFFFFD700);
            }
            y += CONTAINER_ROW_HEIGHT;

            for (Takeitout.SharedGroupEntry shared : SharedGroupsClient.SHARED_GROUPS) {
                if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                    renderSharedGroupRow(guiGraphics, shared, listLeft + 8, y, listWidth - 16, mouseX, mouseY);
                }
                y += CONTAINER_ROW_HEIGHT;
            }
        }

        guiGraphics.disableScissor();

        int inputRow = groupInputMode != null ? 1 : 0;
        int localRows = groups.size() + inputRow;
        int serverRows = SharedGroupsClient.serverSupportsSharedGroups
                ? 1 + SharedGroupsClient.SHARED_GROUPS.size() : 0;
        renderScrollbar(guiGraphics, listLeft, listTop, listWidth, listBottom, LIST_CONTENT_PADDING + (localRows + serverRows) * CONTAINER_ROW_HEIGHT);
    }

    private void renderGroupRow(
            GuiGraphicsExtractor guiGraphics,
            String group,
            String activeGroup,
            int x, int y, int width,
            int mouseX, int mouseY,
            int totalGroups
    ) {
        boolean isActive = group.equals(activeGroup);
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 22;

        guiGraphics.fill(x, y, x + width, y + 22, isActive ? 0x5522D3EE : (hovered ? 0x33FFFFFF : 0x22FFFFFF));
        if (isActive) guiGraphics.fill(x, y, x + 3, y + 22, 0xFF22D3EE);

        if (isActive) {
            if (SharedGroupsClient.serverSupportsSharedGroups) {
                String playerId = this.minecraft != null && this.minecraft.player != null
                        ? this.minecraft.player.getGameProfile().id().toString() : "";
                boolean alreadyShared = SharedGroupsClient.SHARED_GROUPS.stream()
                        .anyMatch(g -> g.authorId().equals(playerId) && g.name().equals(group));
                int shareBtnX = x + width - 76;
                guiGraphics.text(this.font, trim("(active) " + group, width - 90), x + 8, y + 7, 0xFF22D3EE);
                drawSmallButton(guiGraphics, shareBtnX, y + 2, 72, 18, alreadyShared ? "Update" : "Share",
                        hovered && mouseX >= shareBtnX && mouseX < shareBtnX + 72);
            } else {
                guiGraphics.text(this.font, trim("(active) " + group, width - 16), x + 8, y + 7, 0xFF22D3EE);
            }
        } else {
            guiGraphics.text(this.font, trim(group, width - 202), x + 8, y + 7, 0xFFFFFFFF);
            int switchBtnX = x + width - 194;
            int renameBtnX = x + width - 126;
            int deleteBtnX = x + width - 62;
            drawSmallButton(guiGraphics, switchBtnX, y + 2, 62, 18, "Switch",
                    hovered && mouseX >= switchBtnX && mouseX < switchBtnX + 62);
            drawSmallButton(guiGraphics, renameBtnX, y + 2, 58, 18, "Rename",
                    hovered && mouseX >= renameBtnX && mouseX < renameBtnX + 58);
            if (totalGroups > 1) {
                drawSmallButton(guiGraphics, deleteBtnX, y + 2, 58, 18, "Delete",
                        hovered && mouseX >= deleteBtnX && mouseX < deleteBtnX + 58);
            }
        }
    }

    private void renderSharedGroupRow(
            GuiGraphicsExtractor guiGraphics,
            Takeitout.SharedGroupEntry shared,
            int x, int y, int width,
            int mouseX, int mouseY
    ) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 22;
        boolean isOwn = this.minecraft != null && this.minecraft.player != null
                && shared.authorId().equals(this.minecraft.player.getGameProfile().id().toString());

        guiGraphics.fill(x, y, x + width, y + 22, hovered ? 0x33FFD700 : 0x22FFD700);

        int removeBtnX = x + width - 64;
        int importBtnX = isOwn ? removeBtnX - 70 : x + width - 68;

        String label = shared.name() + " - " + shared.authorName();
        guiGraphics.text(this.font, trim(label, importBtnX - x - 8), x + 4, y + 7, 0xFFFFFFFF);
        drawSmallButton(guiGraphics, importBtnX, y + 2, 64, 18, "Import",
                hovered && mouseX >= importBtnX && mouseX < importBtnX + 64);
        if (isOwn) {
            drawSmallButton(guiGraphics, removeBtnX, y + 2, 60, 18, "Remove",
                    hovered && mouseX >= removeBtnX && mouseX < removeBtnX + 60);
        }
    }

    private boolean handleGroupsClick(int mouseX, int mouseY) {
        List<String> groups = WorldContainerSources.getGroupNames();
        String activeGroup = WorldContainerSources.getCurrentGroupName();
        int listLeft = this.width / 2 - 215;
        int listTop = LIST_TOP;
        int listWidth = 430;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        // New Group / Cancel button in header
        int newBtnX = listLeft + listWidth - 84;
        if (mouseX >= newBtnX && mouseX < newBtnX + 80 && mouseY >= listTop - 16 && mouseY < listTop - 2) {
            if (groupInputMode != null) {
                groupInputMode = null;
                groupInputTarget = null;
                groupNameField.setVisible(false);
                groupNameField.setValue("");
            } else {
                groupInputMode = "create";
                groupInputTarget = null;
                groupNameField.setValue("");
                groupNameField.setVisible(true);
                setFocused(groupNameField);
            }
            return true;
        }

        int y = listTop + 6 - scrollOffset;

        // Confirm / create row
        if (groupInputMode != null) {
            int confirmBtnX = listLeft + 8 + 244;
            int confirmBtnY = listTop + 8;
            if (mouseX >= confirmBtnX && mouseX < confirmBtnX + 80 && mouseY >= confirmBtnY && mouseY < confirmBtnY + 14) {
                String name = groupNameField.getValue().trim();
                if (!name.isBlank()) {
                    if ("rename".equals(groupInputMode) && groupInputTarget != null) {
                        WorldContainerSources.renameGroup(groupInputTarget, name);
                    } else {
                        WorldContainerSources.createGroup(name);
                    }
                }
                groupInputMode = null;
                groupInputTarget = null;
                groupNameField.setVisible(false);
                groupNameField.setValue("");
                return true;
            }
            y += CONTAINER_ROW_HEIGHT;
        }

        for (String group : groups) {
            boolean isActive = group.equals(activeGroup);

            if (mouseY >= y + 2 && mouseY < y + 20 && y >= listTop && y + 22 <= listBottom) {
                if (isActive) {
                    if (SharedGroupsClient.serverSupportsSharedGroups) {
                        int rowX = listLeft + 8;
                        int rowWidth = listWidth - 16;
                        int shareBtnX = rowX + rowWidth - 76;
                        if (mouseX >= shareBtnX && mouseX < shareBtnX + 72) {
                            List<Takeitout.SharedGroupDimension> data = WorldContainerSources.getGroupDataForPublishing();
                            ClientPlayNetworking.send(new Takeitout.PublishGroupPayload(group, data));
                            return true;
                        }
                    }
                } else {
                    int rowX = listLeft + 8;
                    int rowWidth = listWidth - 16;
                    int switchBtnX = rowX + rowWidth - 194;
                    int renameBtnX = rowX + rowWidth - 126;
                    int deleteBtnX = rowX + rowWidth - 62;

                    if (mouseX >= switchBtnX && mouseX < switchBtnX + 62) {
                        WorldContainerSources.switchGroup(this.minecraft, group);
                        requestItems();
                        return true;
                    }
                    if (mouseX >= renameBtnX && mouseX < renameBtnX + 58) {
                        groupInputMode = "rename";
                        groupInputTarget = group;
                        groupNameField.setValue(group);
                        groupNameField.setVisible(true);
                        setFocused(groupNameField);
                        scrollOffset = 0;
                        return true;
                    }
                    if (mouseX >= deleteBtnX && mouseX < deleteBtnX + 58 && groups.size() > 1) {
                        WorldContainerSources.deleteGroup(group);
                        return true;
                    }
                }
            }
            y += CONTAINER_ROW_HEIGHT;
        }

        if (SharedGroupsClient.serverSupportsSharedGroups) {
            y += CONTAINER_ROW_HEIGHT; // skip server groups header row
            for (Takeitout.SharedGroupEntry shared : SharedGroupsClient.SHARED_GROUPS) {
                if (mouseY >= y + 2 && mouseY < y + 20 && y >= listTop && y + 22 <= listBottom) {
                    boolean isOwn = this.minecraft != null && this.minecraft.player != null
                            && shared.authorId().equals(this.minecraft.player.getGameProfile().id().toString());

                    int rowX = listLeft + 8;
                    int rowWidth = listWidth - 16;
                    int removeBtnX = rowX + rowWidth - 64;
                    int importBtnX = isOwn ? removeBtnX - 70 : rowX + rowWidth - 68;

                    if (mouseX >= importBtnX && mouseX < importBtnX + 64) {
                        WorldContainerSources.importSharedGroup(shared.name(), shared.dimensions());
                        return true;
                    }
                    if (isOwn && mouseX >= removeBtnX && mouseX < removeBtnX + 60) {
                        ClientPlayNetworking.send(new Takeitout.UnpublishGroupPayload(shared.id()));
                        return true;
                    }
                }
                y += CONTAINER_ROW_HEIGHT;
            }
        }

        return false;
    }

    // --- Containers click / focus ---

    private boolean handleAllItemsClick(int mouseX, int mouseY, int button) {
        List<Takeitout.WorldContainerItemCount> items = getSortedItems();
        int listLeft = this.width / 2 - 155;
        int listTop = LIST_TOP;
        int listWidth = 310;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        if (mouseX < listLeft || mouseX >= listLeft + listWidth) return false;

        int y = listTop + 6 - scrollOffset;
        for (Takeitout.WorldContainerItemCount item : items) {
            if (mouseY >= y && mouseY < y + 22 && y >= listTop && y < listBottom) {
                boolean singleItemMode = (button == 1);
                WorldContainerSources.requestStack(this.minecraft, item.stack(), singleItemMode, true);
                requestItems();
                return true;
            }
            y += 22;
        }
        return false;
    }

    private boolean handleContainerClick(int mouseX, int mouseY) {
        List<WorldContainerSources.SourceEntry> sources = getVisibleContainerSources();
        List<WorldContainerDumps.DumpEntry> dumps = focusedContainer == null
                ? WorldContainerDumps.getAllDumpsSnapshot()
                : List.of();
        int listLeft = this.width / 2 - 215;
        int listTop = LIST_TOP;
        int listWidth = 430;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;
        int deleteAllBtnX = listLeft + listWidth - 84;

        if (focusedContainer == null && !sources.isEmpty()) {
            if (mouseX >= deleteAllBtnX && mouseX < deleteAllBtnX + 80
                    && mouseY >= listTop - 16 && mouseY < listTop - 2) {
                confirmDeleteAllDumps = false;
                if (confirmDeleteAll) {
                    confirmDeleteAll = false;
                    if (WorldContainerSources.deleteAll(this.minecraft)) requestItems();
                } else {
                    confirmDeleteAll = true;
                }
                return true;
            }
        }

        if (!dumps.isEmpty()) {
            int dumpHeaderY = listTop + 6 - scrollOffset + sources.size() * CONTAINER_ROW_HEIGHT;
            if (mouseX >= deleteAllBtnX && mouseX < deleteAllBtnX + 80
                    && mouseY >= dumpHeaderY + 7 && mouseY < dumpHeaderY + 21) {
                confirmDeleteAll = false;
                if (confirmDeleteAllDumps) {
                    confirmDeleteAllDumps = false;
                    if (WorldContainerDumps.deleteAll(this.minecraft)) requestItems();
                } else {
                    confirmDeleteAllDumps = true;
                }
                return true;
            }
        }

        confirmDeleteAll = false;
        confirmDeleteAllDumps = false;

        int y = listTop + 6 - scrollOffset;

        for (WorldContainerSources.SourceEntry source : sources) {
            int linkButtonX = listLeft + 8 + listWidth - 16 - 122;
            int deleteButtonX = listLeft + 8 + listWidth - 16 - 58;
            if (y >= listTop && y + 22 <= listBottom && mouseY >= y + 2 && mouseY < y + 20) {
                if (mouseX >= linkButtonX && mouseX < linkButtonX + 58) {
                    if (WorldContainerSources.setLinked(this.minecraft, source, !source.linked())) requestItems();
                    return true;
                }
                if (mouseX >= deleteButtonX && mouseX < deleteButtonX + 54) {
                    if (WorldContainerSources.delete(this.minecraft, source)) requestItems();
                    return true;
                }
            }
            y += CONTAINER_ROW_HEIGHT;
        }

        if (!dumps.isEmpty()) {
            y += CONTAINER_ROW_HEIGHT;
            for (WorldContainerDumps.DumpEntry dump : dumps) {
                int markButtonX = listLeft + 8 + listWidth - 16 - 122;
                int deleteButtonX = listLeft + 8 + listWidth - 16 - 58;
                if (y >= listTop && y + 22 <= listBottom && mouseY >= y + 2 && mouseY < y + 20) {
                    if (mouseX >= markButtonX && mouseX < markButtonX + 58) {
                        WorldContainerDumps.setEnabled(this.minecraft, dump.pos(), !dump.enabled());
                        return true;
                    }
                    if (mouseX >= deleteButtonX && mouseX < deleteButtonX + 54) {
                        if (WorldContainerDumps.delete(this.minecraft, dump.pos())) requestItems();
                        return true;
                    }
                }
                y += CONTAINER_ROW_HEIGHT;
            }
        }

        return false;
    }

    private void focusTargetedContainer() {
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) return;
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
                "Showing container at " + focusedContainer.getX() + " " + focusedContainer.getY() + " " + focusedContainer.getZ()
        ));
        requestItems();
    }

    private void requestItems() {
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) return;
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
            header += " | Showing: " + focusedContainer.getX() + " " + focusedContainer.getY() + " " + focusedContainer.getZ();
        }
        int linked = WorldContainerSources.linkedSourceCountSnapshot();
        int limit = TakeitoutClient.SERVER_SCAN_LIMIT;
        header += " | Linked: " + linked + (limit > 0 ? "/" + limit : "");
        return header;
    }

    private int getListHeight() {
        return Math.max(0, this.height - LIST_TOP - LIST_BOTTOM_MARGIN);
    }

    private List<Takeitout.WorldContainerItemCount> getSortedItems() {
        List<Takeitout.WorldContainerItemCount> items = new ArrayList<>(TakeitoutClient.WORLD_CONTAINER_ITEMS);
        if (!searchQuery.isBlank()) {
            String q = searchQuery.toLowerCase();
            items.removeIf(item -> !item.stack().getHoverName().getString().toLowerCase().contains(q));
        }
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
                item -> item.stack().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER
        );
        if (TakeitoutClient.ITEM_SORT_MODE == TakeitoutClient.ItemSortMode.COUNT) {
            return Comparator.comparingInt(Takeitout.WorldContainerItemCount::count).reversed().thenComparing(byName);
        }
        return byName.thenComparing(Comparator.comparingInt(Takeitout.WorldContainerItemCount::count).reversed());
    }

    private Component getSortButtonText() {
        return Component.literal("Sort: " + TakeitoutClient.ITEM_SORT_MODE.label());
    }

    private ItemStack getContainerIcon(WorldContainerSources.SourceEntry source) {
        if (this.minecraft == null || this.minecraft.level == null) return ItemStack.EMPTY;
        String currentDimension = this.minecraft.level.dimension().identifier().toString();
        if (!source.dimension().equals(currentDimension)) return ItemStack.EMPTY;
        return this.minecraft.level.getBlockState(source.pos()).getBlock().asItem().getDefaultInstance();
    }

    private int getActiveListLeft() {
        return activeTab == Tab.ALL_ITEMS ? this.width / 2 - 155 : this.width / 2 - 215;
    }

    private int getActiveListWidth() {
        return activeTab == Tab.ALL_ITEMS ? 310 : 430;
    }

    private int getContentHeight() {
        if (activeTab == Tab.ALL_ITEMS) {
            return LIST_CONTENT_PADDING + getSortedItems().size() * 22;
        } else if (activeTab == Tab.CONTAINERS) {
            int sourceHeight = getVisibleContainerSources().size() * CONTAINER_ROW_HEIGHT;
            List<WorldContainerDumps.DumpEntry> dumps = focusedContainer == null
                    ? WorldContainerDumps.getAllDumpsSnapshot()
                    : List.of();
            int dumpHeight = dumps.isEmpty() ? 0 : (CONTAINER_ROW_HEIGHT + dumps.size() * CONTAINER_ROW_HEIGHT);
            return LIST_CONTENT_PADDING + sourceHeight + dumpHeight;
        } else {
            int inputRow = groupInputMode != null ? 1 : 0;
            int localRows = WorldContainerSources.getGroupNames().size() + inputRow;
            int serverRows = SharedGroupsClient.serverSupportsSharedGroups
                    ? 1 + SharedGroupsClient.SHARED_GROUPS.size() : 0;
            return LIST_CONTENT_PADDING + (localRows + serverRows) * CONTAINER_ROW_HEIGHT;
        }
    }

    private int[] getScrollbarThumb(int listTop, int listBottom, int contentHeight) {
        int listHeight = listBottom - listTop;
        if (contentHeight <= listHeight) return null;
        int thumbHeight = Math.max(20, listHeight * listHeight / contentHeight);
        int maxThumbTravel = listHeight - thumbHeight;
        int maxScroll = contentHeight - listHeight;
        int thumbY = listTop + (maxScroll > 0 ? (int) ((long) scrollOffset * maxThumbTravel / maxScroll) : 0);
        return new int[]{thumbY, thumbHeight};
    }

    private void renderScrollbar(GuiGraphicsExtractor guiGraphics, int listLeft, int listTop, int listWidth, int listBottom, int contentHeight) {
        int listHeight = listBottom - listTop;
        if (contentHeight <= listHeight) return;
        int trackX = listLeft + listWidth - SCROLLBAR_WIDTH;
        guiGraphics.fill(trackX, listTop, trackX + SCROLLBAR_WIDTH, listBottom, 0x33FFFFFF);
        int[] thumb = getScrollbarThumb(listTop, listBottom, contentHeight);
        if (thumb != null) {
            int color = scrollbarDragging ? 0xCCFFFFFF : 0x88FFFFFF;
            guiGraphics.fill(trackX + 1, thumb[0], trackX + SCROLLBAR_WIDTH - 1, thumb[0] + thumb[1], color);
        }
    }

    private String trim(String value, int width) {
        return this.font.plainSubstrByWidth(value, width);
    }

    private void drawSmallButton(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height, String label, boolean hovered) {
        guiGraphics.fill(x, y, x + width, y + height, hovered ? 0xFF4B5563 : 0xFF2F2F2F);
        drawBorder(guiGraphics, x, y, width, height, 0xFF9CA3AF);
        guiGraphics.centeredText(this.font, label, x + width / 2, y + height / 2 - 4, 0xFFFFFFFF);
    }

    private void drawBorder(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height, int color) {
        guiGraphics.fill(x, y, x + width, y + 1, color);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, color);
        guiGraphics.fill(x, y, x + 1, y + height, color);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, color);
    }
}
