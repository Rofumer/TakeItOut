package net.maxbel.takeitout.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.maxbel.takeitout.Takeitout;
import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private TextFieldWidget searchField;
    private String searchQuery = "";
    private boolean confirmDeleteAll = false;
    private boolean confirmDeleteAllDumps = false;
    private boolean scrollbarDragging = false;
    private int scrollbarDragStartY;
    private int scrollbarDragStartOffset;

    // Groups tab state
    private String groupInputMode = null;   // null, "create", or "rename"
    private String groupInputTarget = null; // old name when renaming
    private TextFieldWidget groupNameField;

    public TakeItOutSettingsScreen(Screen parent) {
        super(Text.literal("TakeItOut"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int tabY = 28;
        int tabWidth = 80;
        int left = this.width / 2 - 270;

        addDrawableChild(ButtonWidget.builder(Text.literal("All Items"), button -> {
            activeTab = Tab.ALL_ITEMS;
            focusedContainer = null;
            scrollOffset = 0;
            confirmDeleteAll = false;
            confirmDeleteAllDumps = false;
            searchField.setVisible(true);
            groupNameField.setVisible(false);
            requestItems();
        }).position(left, tabY).size(tabWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Containers"), button -> {
            activeTab = Tab.CONTAINERS;
            focusedContainer = null;
            scrollOffset = 0;
            searchField.setVisible(false);
            groupNameField.setVisible(false);
            requestItems();
        }).position(left + tabWidth + 4, tabY).size(tabWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Groups"), button -> {
            activeTab = Tab.GROUPS;
            focusedContainer = null;
            scrollOffset = 0;
            groupInputMode = null;
            searchField.setVisible(false);
            groupNameField.setVisible(false);
        }).position(left + (tabWidth + 4) * 2, tabY).size(tabWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Settings"), button ->
                this.client.setScreen(TakeItOutKeybindsScreen.create(this))
        ).position(left + (tabWidth + 4) * 3, tabY).size(tabWidth, 20).build());

        int auxLeft = left + (tabWidth + 4) * 4;
        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> requestItems())
                .position(auxLeft, tabY)
                .size(72, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Look At"), button -> focusTargetedContainer())
                .position(auxLeft + 76, tabY)
                .size(72, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(getSortButtonText(), button -> {
                    TakeitoutClient.cycleItemSortMode();
                    button.setMessage(getSortButtonText());
                    scrollOffset = 0;
                })
                .position(auxLeft + 152, tabY)
                .size(96, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.client.setScreen(parent))
                .position(this.width / 2 - 100, this.height - 28)
                .size(200, 20)
                .build());

        int listLeft = this.width / 2 - 155;
        searchField = new TextFieldWidget(this.textRenderer, listLeft, 50, 310, 14, Text.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setPlaceholder(Text.literal("Search..."));
        searchField.setChangedListener(text -> {
            searchQuery = text;
            scrollOffset = 0;
        });
        searchField.setVisible(activeTab == Tab.ALL_ITEMS);
        addDrawableChild(searchField);

        int groupsListLeft = this.width / 2 - 215;
        groupNameField = new TextFieldWidget(this.textRenderer, groupsListLeft + 8, LIST_TOP + 8, 240, 14, Text.literal("Group name"));
        groupNameField.setMaxLength(32);
        groupNameField.setPlaceholder(Text.literal("Group name..."));
        groupNameField.setVisible(false);
        addDrawableChild(groupNameField);

        requestItems();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xAA101010);

        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, 0xFFFFFFFF);

        if (activeTab == Tab.ALL_ITEMS) {
            renderAllItems(context, mouseX, mouseY);
        } else if (activeTab == Tab.CONTAINERS) {
            renderContainers(context, mouseX, mouseY);
        } else {
            renderGroups(context, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, getContentHeight() - getListHeight());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (verticalAmount * 18)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int listLeft = getActiveListLeft();
            int listWidth = getActiveListWidth();
            int trackX = listLeft + listWidth - SCROLLBAR_WIDTH;
            int listTop = LIST_TOP;
            int listBottom = this.height - LIST_BOTTOM_MARGIN;
            if (mouseX >= trackX && mouseX < listLeft + listWidth && mouseY >= listTop && mouseY < listBottom) {
                int contentHeight = getContentHeight();
                int[] thumb = getScrollbarThumb(listTop, listBottom, contentHeight);
                if (thumb != null) {
                    if (mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1]) {
                        scrollbarDragging = true;
                        scrollbarDragStartY = (int) mouseY;
                        scrollbarDragStartOffset = scrollOffset;
                    } else {
                        int listHeight = listBottom - listTop;
                        int maxScroll = Math.max(0, contentHeight - listHeight);
                        float ratio = (float) (mouseY - listTop) / listHeight;
                        scrollOffset = Math.max(0, Math.min(maxScroll, (int) (ratio * contentHeight)));
                    }
                    return true;
                }
            }
        }

        if (activeTab == Tab.CONTAINERS && button == 0 && handleContainerClick((int) mouseX, (int) mouseY)) {
            return true;
        }

        if (activeTab == Tab.ALL_ITEMS && (button == 0 || button == 1)
                && handleAllItemsClick((int) mouseX, (int) mouseY, button)) {
            return true;
        }

        if (activeTab == Tab.GROUPS && button == 0 && handleGroupsClick((int) mouseX, (int) mouseY)) {
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (scrollbarDragging) {
            int listTop = LIST_TOP;
            int listBottom = this.height - LIST_BOTTOM_MARGIN;
            int listHeight = listBottom - listTop;
            int contentHeight = getContentHeight();
            if (contentHeight > listHeight) {
                int thumbHeight = Math.max(20, listHeight * listHeight / contentHeight);
                int maxThumbTravel = listHeight - thumbHeight;
                int maxScroll = contentHeight - listHeight;
                int delta = (int) mouseY - scrollbarDragStartY;
                int newOffset = scrollbarDragStartOffset + (int) ((long) delta * maxScroll / maxThumbTravel);
                scrollOffset = Math.max(0, Math.min(maxScroll, newOffset));
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scrollbarDragging && button == 0) {
            scrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
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

        Takeitout.WorldContainerItemCount hoveredItem = null;
        context.enableScissor(listLeft, listTop, listLeft + listWidth, listBottom);
        int y = listTop + 6 - scrollOffset;
        for (Takeitout.WorldContainerItemCount item : items) {
            if (y > listTop - 22 && y < listBottom) {
                renderItemRow(context, item, listLeft + 8, y, mouseX, mouseY);
                if (hoveredItem == null && mouseX >= listLeft && mouseX < listLeft + listWidth
                        && mouseY >= y && mouseY < y + 22 && y >= listTop && y < listBottom) {
                    hoveredItem = item;
                }
            }
            y += 22;
        }
        context.disableScissor();
        renderScrollbar(context, listLeft, listTop, listWidth, listBottom, LIST_CONTENT_PADDING + items.size() * 22);

        if (hoveredItem != null) {
            if (isShulkerStack(hoveredItem.stack())) {
                renderShulkerContentsTooltip(context, hoveredItem.stack(), mouseX, mouseY);
            } else {
                context.drawTooltip(this.textRenderer, List.of(
                        Text.translatable("tooltip.takeitout.take_stack"),
                        Text.translatable("tooltip.takeitout.take_single")
                ), mouseX, mouseY);
            }
        }
    }

    private void renderItemRow(DrawContext context, Takeitout.WorldContainerItemCount item, int x, int y, int mouseX, int mouseY) {
        ItemStack stack = item.stack();
        String count = "x" + item.count();
        int countX = x + 294 - this.textRenderer.getWidth(count);

        boolean hovered = mouseX >= x - 8 && mouseX < x + 302 && mouseY >= y && mouseY < y + 22;
        if (hovered) {
            context.fill(x - 8, y, x + 302, y + 22, 0x33FFFFFF);
        }

        context.drawItem(stack, x, y + 3);
        context.drawTextWithShadow(this.textRenderer, stack.getName(), x + 24, y + 8, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, count, countX, y + 8, 0xFFA7F3D0);

        if (isShulkerStack(stack)) {
            String summary = shulkerContentSummary(stack);
            if (!summary.isEmpty()) {
                int nameEnd = x + 24 + this.textRenderer.getWidth(stack.getName()) + 4;
                int maxWidth = countX - nameEnd - 4;
                if (maxWidth > 0) {
                    context.drawTextWithShadow(this.textRenderer, trim(summary, maxWidth), nameEnd, y + 8, 0xFF888888);
                }
            }
        }
    }

    private static boolean isShulkerStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static String shulkerContentSummary(ItemStack shulker) {
        List<ItemStack> stacks = copyShulkerContents(shulker);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack inner : stacks) {
            if (!inner.isEmpty()) {
                counts.merge(inner.getName().getString(), inner.getCount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("(");
        int i = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(" x").append(e.getValue());
            if (++i >= 3) {
                if (counts.size() > 3) {
                    sb.append("...");
                }
                break;
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private static List<ItemStack> copyShulkerContents(ItemStack shulker) {
        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(27, ItemStack.EMPTY);
        ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
        if (container == null) {
            return stacks;
        }

        int i = 0;
        for (ItemStack stack : container.stream().toList()) {
            if (i >= stacks.size()) {
                break;
            }
            stacks.set(i, stack == null ? ItemStack.EMPTY : stack);
            i++;
        }
        return stacks;
    }

    private void renderShulkerContentsTooltip(DrawContext context, ItemStack shulker, int mouseX, int mouseY) {
        List<ItemStack> raw = copyShulkerContents(shulker);
        Map<String, ItemStack> byName = new LinkedHashMap<>();
        for (ItemStack s : raw) {
            if (!s.isEmpty()) {
                byName.merge(s.getName().getString(), s, (a, b) -> {
                    ItemStack merged = a.copy();
                    merged.setCount(a.getCount() + b.getCount());
                    return merged;
                });
            }
        }
        List<ItemStack> entries = new ArrayList<>(byName.values());

        String line1 = Text.translatable("tooltip.takeitout.take_stack").getString();
        String line2 = Text.translatable("tooltip.takeitout.take_single").getString();
        int rows = Math.min(entries.size(), 10);
        int width = Math.max(this.textRenderer.getWidth(line1), this.textRenderer.getWidth(line2)) + 12;
        for (int i = 0; i < rows; i++) {
            ItemStack s = entries.get(i);
            String cnt = "x" + s.getCount();
            width = Math.max(width, 28 + this.textRenderer.getWidth(s.getName()) + this.textRenderer.getWidth(cnt) + 20);
        }
        if (entries.size() > rows) {
            width = Math.max(width, this.textRenderer.getWidth("+" + (entries.size() - rows) + " more") + 12);
        }

        int height = 18 + Math.max(1, rows) * 20 + (entries.size() > rows ? 10 : 0) + 28;
        int x = Math.min(mouseX + 12, this.width - width - 4);
        int y = Math.min(mouseY + 12, this.height - height - 4);

        context.fill(x, y, x + width, y + height, 0xEE101010);
        drawBorder(context, x, y, width, height, 0xFF9CA3AF);
        context.drawTextWithShadow(this.textRenderer, "Contents", x + 6, y + 6, 0xFFA7F3D0);

        if (entries.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, "Empty", x + 6, y + 24, 0xFFAAAAAA);
        } else {
            int rowY = y + 20;
            for (int i = 0; i < rows; i++) {
                ItemStack s = entries.get(i);
                String cnt = "x" + s.getCount();
                context.drawItem(s, x + 6, rowY);
                context.drawTextWithShadow(this.textRenderer, trim(s.getName().getString(), width - 62), x + 28, rowY + 5, 0xFFFFFFFF);
                context.drawTextWithShadow(this.textRenderer, cnt, x + width - this.textRenderer.getWidth(cnt) - 6, rowY + 5, 0xFFA7F3D0);
                rowY += 20;
            }
            if (entries.size() > rows) {
                context.drawTextWithShadow(this.textRenderer, "+" + (entries.size() - rows) + " more", x + 6, rowY, 0xFFAAAAAA);
            }
        }

        int actY = y + height - 26;
        context.fill(x, actY, x + width, actY + 1, 0x44FFFFFF);
        context.drawTextWithShadow(this.textRenderer, line1, x + 6, actY + 4, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, line2, x + 6, actY + 15, 0xFFFFFFFF);
    }

    private void renderContainers(DrawContext context, int mouseX, int mouseY) {
        List<WorldContainerSources.SourceEntry> sources = getVisibleContainerSources();
        List<WorldContainerDumps.DumpEntry> dumps = focusedContainer == null
                ? WorldContainerDumps.getAllDumpsSnapshot()
                : List.of();
        int listLeft = this.width / 2 - 215;
        int listTop = LIST_TOP;
        int listWidth = 430;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        context.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0x66000000);
        context.drawTextWithShadow(
                this.textRenderer,
                trim(getContainersHeader(), listWidth - 96),
                listLeft + 6,
                listTop - HEADER_OFFSET,
                0xFFA7F3D0
        );

        if (focusedContainer == null && !sources.isEmpty()) {
            int deleteAllBtnX = listLeft + listWidth - 84;
            int deleteAllBtnY = listTop - 16;
            boolean deleteAllHovered = mouseX >= deleteAllBtnX && mouseX < deleteAllBtnX + 80
                    && mouseY >= deleteAllBtnY && mouseY < deleteAllBtnY + 14;
            if (confirmDeleteAll) {
                context.fill(deleteAllBtnX, deleteAllBtnY, deleteAllBtnX + 80, deleteAllBtnY + 14,
                        deleteAllHovered ? 0xFFB91C1C : 0xFF7F1D1D);
                drawBorder(context, deleteAllBtnX, deleteAllBtnY, 80, 14, 0xFFEF4444);
                context.drawCenteredTextWithShadow(this.textRenderer, "Confirm?", deleteAllBtnX + 40, deleteAllBtnY + 3, 0xFFFFFFFF);
            } else {
                drawSmallButton(context, deleteAllBtnX, deleteAllBtnY, 80, 14, "Delete All", deleteAllHovered);
            }
        }

        if (sources.isEmpty() && dumps.isEmpty()) {
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

        if (!dumps.isEmpty()) {
            if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                context.fill(listLeft + 8, y + 9, listLeft + listWidth - 8, y + 10, 0x44F97316);
                context.drawTextWithShadow(this.textRenderer, trim("Dump Containers (" + dumps.size() + ")", listWidth - 100), listLeft + 8, y + 2, 0xFFF97316);

                int deleteAllDumpsBtnX = listLeft + listWidth - 84;
                int deleteAllDumpsBtnY = y + 7;
                boolean deleteAllDumpsHovered = mouseX >= deleteAllDumpsBtnX && mouseX < deleteAllDumpsBtnX + 80
                        && mouseY >= deleteAllDumpsBtnY && mouseY < deleteAllDumpsBtnY + 14;
                if (confirmDeleteAllDumps) {
                    context.fill(deleteAllDumpsBtnX, deleteAllDumpsBtnY, deleteAllDumpsBtnX + 80, deleteAllDumpsBtnY + 14,
                            deleteAllDumpsHovered ? 0xFFB91C1C : 0xFF7F1D1D);
                    drawBorder(context, deleteAllDumpsBtnX, deleteAllDumpsBtnY, 80, 14, 0xFFEF4444);
                    context.drawCenteredTextWithShadow(this.textRenderer, "Confirm?", deleteAllDumpsBtnX + 40, deleteAllDumpsBtnY + 3, 0xFFFFFFFF);
                } else {
                    context.fill(deleteAllDumpsBtnX, deleteAllDumpsBtnY, deleteAllDumpsBtnX + 80, deleteAllDumpsBtnY + 14,
                            deleteAllDumpsHovered ? 0xFF92400E : 0xFF78350F);
                    drawBorder(context, deleteAllDumpsBtnX, deleteAllDumpsBtnY, 80, 14, 0xFFF97316);
                    context.drawCenteredTextWithShadow(this.textRenderer, "Delete All", deleteAllDumpsBtnX + 40, deleteAllDumpsBtnY + 3, 0xFFFFFFFF);
                }
            }
            y += CONTAINER_ROW_HEIGHT;

            for (WorldContainerDumps.DumpEntry dump : dumps) {
                if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                    renderDumpRow(context, dump, listLeft + 8, y, listWidth - 16, mouseX, mouseY);
                }
                y += CONTAINER_ROW_HEIGHT;
            }
        }

        context.disableScissor();

        int sourceHeight = sources.size() * CONTAINER_ROW_HEIGHT;
        int dumpHeight = dumps.isEmpty() ? 0 : (CONTAINER_ROW_HEIGHT + dumps.size() * CONTAINER_ROW_HEIGHT);
        renderScrollbar(context, listLeft, listTop, listWidth, listBottom, LIST_CONTENT_PADDING + sourceHeight + dumpHeight);

        if (hoveredSource != null) {
            renderContainerContentsTooltip(context, hoveredSource, mouseX, mouseY);
        }
    }

    private void renderDumpRow(
            DrawContext context,
            WorldContainerDumps.DumpEntry dump,
            int x,
            int y,
            int width,
            int mouseX,
            int mouseY
    ) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 22;
        context.fill(x, y, x + width, y + 22, hovered ? 0x88F97316 : 0x33F97316);

        BlockPos pos = dump.pos();
        String label = (dump.enabled() ? "Dump" : "Disabled") + " | " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        context.drawTextWithShadow(this.textRenderer, trim(label, width - 132), x + 4, y + 7, dump.enabled() ? 0xFFFBBF24 : 0xFFAAAAAA);

        int markButtonX = x + width - 122;
        int deleteButtonX = x + width - 58;
        drawSmallButton(context, markButtonX, y + 2, 58, 18, dump.enabled() ? "Unmark" : "Mark",
                hovered && mouseX >= markButtonX && mouseX < markButtonX + 58);
        drawSmallButton(context, deleteButtonX, y + 2, 54, 18, "Delete",
                hovered && mouseX >= deleteButtonX && mouseX < deleteButtonX + 54);
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

    // --- Groups tab ---

    private void renderGroups(DrawContext context, int mouseX, int mouseY) {
        List<String> groups = WorldContainerSources.getGroupNames();
        String activeGroup = WorldContainerSources.getCurrentGroupName();
        int listLeft = this.width / 2 - 215;
        int listTop = LIST_TOP;
        int listWidth = 430;
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        context.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0x66000000);
        context.drawTextWithShadow(this.textRenderer, "Groups", listLeft + 6, listTop - HEADER_OFFSET, 0xFFA7F3D0);

        int newBtnX = listLeft + listWidth - 84;
        int newBtnY = listTop - 16;
        boolean newBtnHovered = mouseX >= newBtnX && mouseX < newBtnX + 80 && mouseY >= newBtnY && mouseY < newBtnY + 14;
        drawSmallButton(context, newBtnX, newBtnY, 80, 14, groupInputMode == null ? "+ New Group" : "Cancel", newBtnHovered);

        context.enableScissor(listLeft, listTop, listLeft + listWidth, listBottom);
        int y = listTop + 6 - scrollOffset;

        if (groupInputMode != null) {
            int confirmBtnX = listLeft + 8 + 244;
            int confirmBtnY = listTop + 8;
            boolean confirmHovered = mouseX >= confirmBtnX && mouseX < confirmBtnX + 80 && mouseY >= confirmBtnY && mouseY < confirmBtnY + 14;
            String confirmLabel = "rename".equals(groupInputMode) ? "Rename" : "Create";
            drawSmallButton(context, confirmBtnX, confirmBtnY, 80, 14, confirmLabel, confirmHovered);
            y += CONTAINER_ROW_HEIGHT;
        }

        for (String group : groups) {
            if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                renderGroupRow(context, group, activeGroup, listLeft + 8, y, listWidth - 16, mouseX, mouseY, groups.size());
            }
            y += CONTAINER_ROW_HEIGHT;
        }

        if (SharedGroupsClient.serverSupportsSharedGroups) {
            if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                context.fill(listLeft + 8, y + 10, listLeft + listWidth - 8, y + 11, 0x44FFD700);
                context.drawTextWithShadow(this.textRenderer, "Server Groups (" + SharedGroupsClient.SHARED_GROUPS.size() + ")", listLeft + 8, y + 2, 0xFFFFD700);
            }
            y += CONTAINER_ROW_HEIGHT;

            for (Takeitout.SharedGroupEntry shared : SharedGroupsClient.SHARED_GROUPS) {
                if (y > listTop - CONTAINER_ROW_HEIGHT && y < listBottom) {
                    renderSharedGroupRow(context, shared, listLeft + 8, y, listWidth - 16, mouseX, mouseY);
                }
                y += CONTAINER_ROW_HEIGHT;
            }
        }

        context.disableScissor();

        int inputRow = groupInputMode != null ? 1 : 0;
        int localRows = groups.size() + inputRow;
        int serverRows = SharedGroupsClient.serverSupportsSharedGroups
                ? 1 + SharedGroupsClient.SHARED_GROUPS.size() : 0;
        renderScrollbar(context, listLeft, listTop, listWidth, listBottom, LIST_CONTENT_PADDING + (localRows + serverRows) * CONTAINER_ROW_HEIGHT);
    }

    private void renderGroupRow(
            DrawContext context,
            String group,
            String activeGroup,
            int x, int y, int width,
            int mouseX, int mouseY,
            int totalGroups
    ) {
        boolean isActive = group.equals(activeGroup);
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 22;

        context.fill(x, y, x + width, y + 22, isActive ? 0x5522D3EE : (hovered ? 0x33FFFFFF : 0x22FFFFFF));
        if (isActive) {
            context.fill(x, y, x + 3, y + 22, 0xFF22D3EE);
        }

        if (isActive) {
            if (SharedGroupsClient.serverSupportsSharedGroups) {
                String playerId = this.client != null && this.client.player != null
                        ? this.client.player.getGameProfile().getId().toString() : "";
                boolean alreadyShared = SharedGroupsClient.SHARED_GROUPS.stream()
                        .anyMatch(g -> g.authorId().equals(playerId) && g.name().equals(group));
                int shareBtnX = x + width - 76;
                context.drawTextWithShadow(this.textRenderer, trim("(active) " + group, width - 90), x + 8, y + 7, 0xFF22D3EE);
                drawSmallButton(context, shareBtnX, y + 2, 72, 18, alreadyShared ? "Update" : "Share",
                        hovered && mouseX >= shareBtnX && mouseX < shareBtnX + 72);
            } else {
                context.drawTextWithShadow(this.textRenderer, trim("(active) " + group, width - 16), x + 8, y + 7, 0xFF22D3EE);
            }
        } else {
            context.drawTextWithShadow(this.textRenderer, trim(group, width - 202), x + 8, y + 7, 0xFFFFFFFF);
            int switchBtnX = x + width - 194;
            int renameBtnX = x + width - 126;
            int deleteBtnX = x + width - 62;
            drawSmallButton(context, switchBtnX, y + 2, 62, 18, "Switch",
                    hovered && mouseX >= switchBtnX && mouseX < switchBtnX + 62);
            drawSmallButton(context, renameBtnX, y + 2, 58, 18, "Rename",
                    hovered && mouseX >= renameBtnX && mouseX < renameBtnX + 58);
            if (totalGroups > 1) {
                drawSmallButton(context, deleteBtnX, y + 2, 58, 18, "Delete",
                        hovered && mouseX >= deleteBtnX && mouseX < deleteBtnX + 58);
            }
        }
    }

    private void renderSharedGroupRow(
            DrawContext context,
            Takeitout.SharedGroupEntry shared,
            int x, int y, int width,
            int mouseX, int mouseY
    ) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 22;
        boolean isOwn = this.client != null && this.client.player != null
                && shared.authorId().equals(this.client.player.getGameProfile().getId().toString());

        context.fill(x, y, x + width, y + 22, hovered ? 0x33FFD700 : 0x22FFD700);

        int removeBtnX = x + width - 64;
        int importBtnX = isOwn ? removeBtnX - 70 : x + width - 68;

        String label = shared.name() + " - " + shared.authorName();
        context.drawTextWithShadow(this.textRenderer, trim(label, importBtnX - x - 8), x + 4, y + 7, 0xFFFFFFFF);
        drawSmallButton(context, importBtnX, y + 2, 64, 18, "Import",
                hovered && mouseX >= importBtnX && mouseX < importBtnX + 64);
        if (isOwn) {
            drawSmallButton(context, removeBtnX, y + 2, 60, 18, "Remove",
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

        int newBtnX = listLeft + listWidth - 84;
        if (mouseX >= newBtnX && mouseX < newBtnX + 80 && mouseY >= listTop - 16 && mouseY < listTop - 2) {
            if (groupInputMode != null) {
                groupInputMode = null;
                groupInputTarget = null;
                groupNameField.setVisible(false);
                groupNameField.setText("");
            } else {
                groupInputMode = "create";
                groupInputTarget = null;
                groupNameField.setText("");
                groupNameField.setVisible(true);
                setFocused(groupNameField);
            }
            return true;
        }

        int y = listTop + 6 - scrollOffset;

        if (groupInputMode != null) {
            int confirmBtnX = listLeft + 8 + 244;
            int confirmBtnY = listTop + 8;
            if (mouseX >= confirmBtnX && mouseX < confirmBtnX + 80 && mouseY >= confirmBtnY && mouseY < confirmBtnY + 14) {
                String name = groupNameField.getText().trim();
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
                groupNameField.setText("");
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
                        WorldContainerSources.switchGroup(this.client, group);
                        requestItems();
                        return true;
                    }
                    if (mouseX >= renameBtnX && mouseX < renameBtnX + 58) {
                        groupInputMode = "rename";
                        groupInputTarget = group;
                        groupNameField.setText(group);
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
                    boolean isOwn = this.client != null && this.client.player != null
                            && shared.authorId().equals(this.client.player.getGameProfile().getId().toString());

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

        if (mouseX < listLeft || mouseX >= listLeft + listWidth) {
            return false;
        }

        int y = listTop + 6 - scrollOffset;
        for (Takeitout.WorldContainerItemCount item : items) {
            if (mouseY >= y && mouseY < y + 22 && y >= listTop && y < listBottom) {
                MinecraftClient client = MinecraftClient.getInstance();
                // LMB → full stack; RMB → single item
                boolean singleItemMode = (button == 1);
                WorldContainerSources.requestStack(client, item.stack(), singleItemMode, true);
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

        // Check Sources Delete All button (above list)
        if (focusedContainer == null && !sources.isEmpty()) {
            if (mouseX >= deleteAllBtnX && mouseX < deleteAllBtnX + 80
                    && mouseY >= listTop - 16 && mouseY < listTop - 2) {
                confirmDeleteAllDumps = false;
                if (confirmDeleteAll) {
                    confirmDeleteAll = false;
                    if (WorldContainerSources.deleteAll(this.client)) {
                        requestItems();
                    }
                } else {
                    confirmDeleteAll = true;
                }
                return true;
            }
        }

        // Check Dumps Delete All button (in dump section header)
        if (!dumps.isEmpty()) {
            int dumpHeaderY = listTop + 6 - scrollOffset + sources.size() * CONTAINER_ROW_HEIGHT;
            if (mouseX >= deleteAllBtnX && mouseX < deleteAllBtnX + 80
                    && mouseY >= dumpHeaderY + 7 && mouseY < dumpHeaderY + 21) {
                confirmDeleteAll = false;
                if (confirmDeleteAllDumps) {
                    confirmDeleteAllDumps = false;
                    if (WorldContainerDumps.deleteAll(this.client)) {
                        requestItems();
                    }
                } else {
                    confirmDeleteAllDumps = true;
                }
                return true;
            }
        }

        // Clicking anywhere else resets both confirm states
        confirmDeleteAll = false;
        confirmDeleteAllDumps = false;

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

        if (!dumps.isEmpty()) {
            y += CONTAINER_ROW_HEIGHT; // skip section header

            for (WorldContainerDumps.DumpEntry dump : dumps) {
                int markButtonX = listLeft + 8 + listWidth - 16 - 122;
                int deleteButtonX = listLeft + 8 + listWidth - 16 - 58;
                if (y >= listTop && y + 22 <= listBottom && mouseY >= y + 2 && mouseY < y + 20) {
                    if (mouseX >= markButtonX && mouseX < markButtonX + 58) {
                        WorldContainerDumps.setEnabled(this.client, dump.pos(), !dump.enabled());
                        return true;
                    }
                    if (mouseX >= deleteButtonX && mouseX < deleteButtonX + 54) {
                        if (WorldContainerDumps.delete(this.client, dump.pos())) {
                            requestItems();
                        }
                        return true;
                    }
                }
                y += CONTAINER_ROW_HEIGHT;
            }
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
        int linked = WorldContainerSources.linkedSourceCountSnapshot();
        int limit = TakeitoutClient.SERVER_SCAN_LIMIT;
        header += " | Linked: " + linked + (limit > 0 ? "/" + limit : "");
        return header;
    }

    private int getListHeight() {
        return Math.max(0, this.height - LIST_TOP - LIST_BOTTOM_MARGIN);
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

    private void renderScrollbar(DrawContext context, int listLeft, int listTop, int listWidth, int listBottom, int contentHeight) {
        int listHeight = listBottom - listTop;
        if (contentHeight <= listHeight) return;
        int trackX = listLeft + listWidth - SCROLLBAR_WIDTH;
        context.fill(trackX, listTop, trackX + SCROLLBAR_WIDTH, listBottom, 0x33FFFFFF);
        int[] thumb = getScrollbarThumb(listTop, listBottom, contentHeight);
        if (thumb != null) {
            int color = scrollbarDragging ? 0xCCFFFFFF : 0x88FFFFFF;
            context.fill(trackX + 1, thumb[0], trackX + SCROLLBAR_WIDTH - 1, thumb[0] + thumb[1], color);
        }
    }

    private List<Takeitout.WorldContainerItemCount> getSortedItems() {
        List<Takeitout.WorldContainerItemCount> items = new ArrayList<>(TakeitoutClient.WORLD_CONTAINER_ITEMS);
        if (!searchQuery.isBlank()) {
            String q = searchQuery.toLowerCase();
            items.removeIf(item -> !itemMatchesQuery(item.stack(), q));
        }
        sortItems(items);
        return items;
    }

    private static boolean itemMatchesQuery(ItemStack stack, String q) {
        if (stack.getName().getString().toLowerCase().contains(q)) {
            return true;
        }
        if (isShulkerStack(stack)) {
            for (ItemStack inner : copyShulkerContents(stack)) {
                if (!inner.isEmpty() && inner.getName().getString().toLowerCase().contains(q)) {
                    return true;
                }
            }
        }
        return false;
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
        context.drawCenteredTextWithShadow(this.textRenderer, label, x + width / 2, y + height / 2 - 4, 0xFFFFFFFF);
    }

    private void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }
}
