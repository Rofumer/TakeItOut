package net.maxbel.takeitout.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TakeItOutKeybindsScreen extends Screen {
    private final Screen parent;
    private final Options options;
    private List<KeyMapping> modKeyBindings;

    public TakeItOutKeybindsScreen(Screen parent, Options options) {
        super(Component.literal("TakeItOut Keybindings"));
        this.parent = parent;
        this.options = options;
    }

    @Override
    protected void init() {
        int y = 40;
        int spacing = 24;

        modKeyBindings = Arrays.stream(options.keyMappings)
                .filter(kb -> kb.getCategory().equals("key.category.takeitout"))
                .collect(Collectors.toList());

        for (int i = 0; i < modKeyBindings.size(); i++) {
            KeyMapping key = modKeyBindings.get(i);

            addRenderableWidget(Button.builder(
                    Component.literal("Change keybind: " + key.getTranslatedKeyMessage().getString()),
                    b -> Minecraft.getInstance().setScreen(new OptionsScreen(this, options))
            ).bounds(this.width / 2 - 100, y + i * spacing, 200, 20).build());
        }

        addRenderableWidget(Button.builder(
                Component.literal("Done"),
                b -> Minecraft.getInstance().setScreen(parent)
        ).bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, delta);
    }
}