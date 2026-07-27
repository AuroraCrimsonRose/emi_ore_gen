package com.catx.emioregen.client.widget;

import com.catx.emioregen.data.OreEntry;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Supplier;

/**
 * Vertical depth chart for the currently selected dimension and biome.
 *
 * <p>The track spans the dimension's full build height. Each occurrence draws a translucent band
 * over its Y range with a brighter tick at its mean, so overlapping veins read as stacked bands
 * and a player can see at a glance which depth covers the most sources.</p>
 *
 * <p>Deliberately unlabelled: the exact numbers are printed on every row beside it, so axis
 * labels here would only repeat themselves and crowd the column.</p>
 */
public class YRangeGraphWidget extends Widget {

    private static final int TRACK = 0xFF48484F;
    private static final int TRACK_EDGE = 0xFF2A2A30;
    private static final int GRIDLINE = 0xFF303038;
    private static final int MEAN = 0xFFFFFFFF;

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int rowHeight;
    private final boolean shareMode;
    private final Supplier<List<OreEntry>> entries;

    /** Plots each entry's share of its vein rather than its depth. */
    public static YRangeGraphWidget shares(int x, int y, int width, int height, int rowHeight,
                                           Supplier<List<OreEntry>> entries) {
        return new YRangeGraphWidget(x, y, width, height, rowHeight, true, entries);
    }

    public YRangeGraphWidget(int x, int y, int width, int height, int rowHeight,
                             Supplier<List<OreEntry>> entries) {
        this(x, y, width, height, rowHeight, false, entries);
    }

    private YRangeGraphWidget(int x, int y, int width, int height, int rowHeight,
                              boolean shareMode, Supplier<List<OreEntry>> entries) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.rowHeight = rowHeight;
        this.shareMode = shareMode;
        this.entries = entries;
    }

    @Override
    public Bounds getBounds() {
        return new Bounds(x, y, width, height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        List<OreEntry> list = entries.get();

        // Bedrock reservoirs and surface rocks have no depth to plot, so a depth axis would be
        // a flat line saying nothing. Show how common each one is instead.
        boolean anyDepth = list.stream().anyMatch(entry ->
                !entry.isFluid() && !entry.isSurface() && entry.sizeBlocks() >= 0);
        if (!list.isEmpty() && (shareMode || !anyDepth)) {
            renderProportions(graphics, list);
            return;
        }

        int worldMin = -64;
        int worldMax = 320;
        if (!list.isEmpty()) {
            worldMin = list.get(0).worldMinY();
            worldMax = list.get(0).worldMaxY();
        }
        if (worldMax <= worldMin) {
            worldMax = worldMin + 1;
        }

        graphics.fill(x, y, x + width, y + height, TRACK);
        graphics.fill(x, y, x + width, y + 1, TRACK_EDGE);
        graphics.fill(x, y + height - 1, x + width, y + height, TRACK_EDGE);

        // Gridlines every 64 blocks give the eye something to measure against.
        for (int level = ceilTo(worldMin, 64); level < worldMax; level += 64) {
            int py = toPixel(level, worldMin, worldMax);
            graphics.fill(x, py, x + width, py + 1, GRIDLINE);
        }

        if (list.isEmpty()) {
            return;
        }

        // Cap how many bands are drawn so a pathological modpack can't turn this into mush.
        int drawn = Math.min(list.size(), 8);
        int bandWidth = Math.max(2, (width - 2) / drawn);

        for (int i = 0; i < drawn; i++) {
            OreEntry entry = list.get(i);
            int left = x + 1 + i * bandWidth;
            int right = Math.min(x + width - 1, left + bandWidth - 1);

            int top = toPixel(Math.min(entry.maxY(), worldMax), worldMin, worldMax);
            int bottom = toPixel(Math.max(entry.minY(), worldMin), worldMin, worldMax);
            if (bottom <= top) {
                bottom = top + 1;
            }

            graphics.fill(left, top, right, bottom, entry.sizeClass().color() & 0xB0FFFFFF);

            int meanPixel = toPixel(entry.meanY(), worldMin, worldMax);
            graphics.fill(left, meanPixel, right, meanPixel + 1, MEAN);
        }
    }

    /**
     * One bar per entry, aligned with the text row it belongs to, filled in proportion to how
     * often that source turns up.
     */
    private void renderProportions(GuiGraphics graphics, List<OreEntry> list) {
        graphics.fill(x, y, x + width, y + height, TRACK);

        int drawn = Math.min(list.size(), Math.max(1, height / rowHeight));
        for (int i = 0; i < drawn; i++) {
            OreEntry entry = list.get(i);
            int top = y + i * rowHeight + 1;
            int bottom = Math.min(y + height - 1, top + rowHeight - 4);
            if (bottom <= top) {
                continue;
            }

            int permille = shareMode ? entry.sharePermille() : entry.spawnPermille();
            int filled = Math.max(1, Math.round((bottom - top) * (permille / 1000f)));

            // Fills upward from the row's baseline, so a taller bar reads as "more".
            graphics.fill(x, top, x + width, bottom, GRIDLINE);
            graphics.fill(x, bottom - filled, x + width, bottom,
                    entry.sizeClass().color() & 0xD0FFFFFF);
        }
    }

    /** Maps a world Y to a pixel row, with the top of the widget being the sky. */
    private int toPixel(int worldY, int worldMin, int worldMax) {
        double fraction = (double) (worldY - worldMin) / (worldMax - worldMin);
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return y + (int) Math.round((1.0 - fraction) * (height - 1));
    }

    private static int ceilTo(int value, int step) {
        int mod = Math.floorMod(value, step);
        return mod == 0 ? value : value + (step - mod);
    }
}
