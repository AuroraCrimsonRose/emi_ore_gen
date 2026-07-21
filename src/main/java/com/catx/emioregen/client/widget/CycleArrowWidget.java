package com.catx.emioregen.client.widget;

import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.sounds.SoundEvents;

/**
 * A small left/right arrow that steps a caller-owned index.
 *
 * <p>Drawn by hand rather than sampling EMI's button atlas, so it does not depend on the
 * internal layout of EMI's texture sheet and needs no resources of its own.</p>
 */
public class CycleArrowWidget extends Widget {

    /** Receives the step (-1 or +1) when the arrow is clicked. */
    @FunctionalInterface
    public interface StepHandler {
        void step(int delta);
    }

    private static final int WIDTH = 9;
    private static final int HEIGHT = 11;

    private static final int ENABLED = 0xFFD8D8D8;
    private static final int HOVERED = 0xFFFFFFFF;
    private static final int DISABLED = 0xFF4A4A4A;

    private final int x;
    private final int y;
    private final int direction;
    private final StepHandler handler;
    private final java.util.function.BooleanSupplier enabled;

    public CycleArrowWidget(int x, int y, int direction,
                            java.util.function.BooleanSupplier enabled,
                            StepHandler handler) {
        this.x = x;
        this.y = y;
        this.direction = direction < 0 ? -1 : 1;
        this.enabled = enabled;
        this.handler = handler;
    }

    @Override
    public Bounds getBounds() {
        return new Bounds(x, y, WIDTH, HEIGHT);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        boolean on = enabled.getAsBoolean();
        boolean hovered = on && getBounds().contains(mouseX, mouseY);
        int color = !on ? DISABLED : hovered ? HOVERED : ENABLED;

        // A triangle built from stacked rows: widest at the tip, narrowing to the base.
        int rows = HEIGHT / 2 + 1;
        for (int row = 0; row < rows; row++) {
            int half = row;
            int top = y + HEIGHT / 2 - half;
            int bottom = y + HEIGHT / 2 + half + 1;
            int column = direction < 0 ? x + 1 + row : x + WIDTH - 2 - row;
            graphics.fill(column, top, column + 1, bottom, color);
        }
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0 || !enabled.getAsBoolean() || !getBounds().contains(mouseX, mouseY)) {
            return false;
        }
        handler.step(direction);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                    .forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
        }
        return true;
    }
}
