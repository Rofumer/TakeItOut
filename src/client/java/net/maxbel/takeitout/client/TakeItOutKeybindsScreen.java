package net.maxbel.takeitout.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.ControlsOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TakeItOutKeybindsScreen extends Screen {
    private final Screen parent;
    private final GameOptions options;
    private List<KeyBinding> modKeyBindings;

    public TakeItOutKeybindsScreen(Screen parent, GameOptions options) {
        super(Text.literal("TakeItOut Keybindings"));
        this.parent = parent;
        this.options = options;
    }

    @Override
    protected void init() {
        int y = 40;
        int spacing = 24;

        modKeyBindings = Arrays.stream(options.allKeys)
                .filter(kb -> kb.getCategory().equals("TakeItOut"))
                .collect(Collectors.toList());

        for (int i = 0; i < modKeyBindings.size(); i++) {
            KeyBinding key = modKeyBindings.get(i);
            addDrawableChild(ButtonWidget.builder(Text.literal("Change keybind setting: "+key.getBoundKeyLocalizedText().getString()), b -> {
                // Откройте экран редактирования бинда
                // Предполагается, что вы реализуете KeyBindingEditScreen
                MinecraftClient.getInstance().setScreen(new ControlsOptionsScreen(this, client.options));
            }).position(this.width / 2 - 100, y + i * spacing).size(200, 20).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> {
            client.setScreen(parent);
        }).position(this.width / 2 - 100, this.height - 30).size(200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context,mouseX,mouseY,delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }
}
