package com.catx.emioregen.client;

import com.catx.emioregen.client.widget.CycleArrowWidget;
import com.catx.emioregen.client.widget.YRangeGraphWidget;
import com.catx.emioregen.data.OreEntry;
import com.catx.emioregen.data.SizeClass;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A vein, described from the vein's point of view.
 *
 * <p>GregTech ships its own diagram for these. This one exists so the whole mod reads as one
 * thing rather than two, and so a vein gets the same depth chart and the same vocabulary as
 * every other page here. Whether it replaces GregTech's or sits beside it is a config choice.</p>
 */
public class VeinGenEmiRecipe implements EmiRecipe {

    static final int WIDTH = 178;

    private static final int SLOT = 18;
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_ORE_SLOTS = 8;

    private static final int ORES_Y = 20;
    private static final int DIM_Y = 42;
    private static final int SUMMARY_Y = 57;
    private static final int LIST_Y = 70;

    private static final int GRAPH_X = 4;
    private static final int GRAPH_W = 12;
    private static final int TEXT_X = 22;

    private static final int HEADING = 0xFF202020;
    private static final int BODY = 0xFF404040;
    private static final int MUTED = 0xFF6A6A6A;

    private final ResourceLocation id;
    private final String veinName;
    private final Map<String, List<OreEntry>> byDimension;
    private final List<String> dimensions;
    private final EmiStack icon;
    private final OreEntry surfaceRock;
    private final int rows;

    private int dimensionIndex;

    public VeinGenEmiRecipe(ResourceLocation id, String veinName,
                            Map<String, List<OreEntry>> byDimension, OreEntry surfaceRock) {
        this.id = id;
        this.veinName = veinName;
        this.byDimension = byDimension;
        this.dimensions = new ArrayList<>(byDimension.keySet());
        this.surfaceRock = surfaceRock;

        int widest = 0;
        for (List<OreEntry> list : byDimension.values()) {
            widest = Math.max(widest, list.size());
        }
        this.rows = Math.max(1, widest);

        List<OreEntry> sample = byDimension.values().stream().findFirst().orElse(List.of());
        this.icon = sample.isEmpty() ? EmiStack.EMPTY : stackOf(sample.get(0).blockId());
    }

    private static EmiStack stackOf(String blockId) {
        ResourceLocation location = ResourceLocation.tryParse(blockId);
        if (location == null) {
            return EmiStack.EMPTY;
        }
        if (BuiltInRegistries.ITEM.containsKey(location)) {
            return EmiStack.of(BuiltInRegistries.ITEM.get(location));
        }
        if (BuiltInRegistries.FLUID.containsKey(location)) {
            return EmiStack.of(BuiltInRegistries.FLUID.get(location));
        }
        return EmiStack.EMPTY;
    }

    private String currentDimension() {
        if (dimensions.isEmpty()) {
            return "";
        }
        dimensionIndex = Math.floorMod(dimensionIndex, dimensions.size());
        return dimensions.get(dimensionIndex);
    }

    private List<OreEntry> currentOres() {
        return byDimension.getOrDefault(currentDimension(), List.of());
    }

    // ------------------------------------------------------------------

    @Override
    public EmiRecipeCategory getCategory() {
        return OreGenEmiPlugin.VEIN_GEN_CATEGORY;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public List<EmiIngredient> getInputs() {
        return List.of();
    }

    @Override
    public List<EmiStack> getOutputs() {
        // Every ore in the vein, so looking one up finds the vein it belongs to as well as
        // its own page. Unlike the region pages, a vein is a small enough set to be worth it.
        List<EmiStack> out = new ArrayList<>();
        for (List<OreEntry> list : byDimension.values()) {
            for (OreEntry entry : list) {
                EmiStack stack = stackOf(entry.blockId());
                if (!stack.isEmpty() && !out.contains(stack)) {
                    out.add(stack);
                }
            }
        }
        return out;
    }

    @Override
    public boolean supportsRecipeTree() {
        return false;
    }

    @Override
    public int getDisplayWidth() {
        return WIDTH;
    }

    @Override
    public int getDisplayHeight() {
        return LIST_Y + rows * ROW_HEIGHT + 4;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        widgets.addSlot(icon, 2, 2).recipeContext(this);
        widgets.addText(Component.literal(veinName).withStyle(ChatFormatting.BOLD),
                24, 7, HEADING, false);

        // The rock is how you find the vein, so it belongs with the vein's identity rather
        // than trailing after a list of what's inside it.
        if (surfaceRock != null) {
            widgets.addSlot(stackOf(surfaceRock.blockId()), WIDTH - 20, 2)
                    .appendTooltip(Component.translatable(
                            "emioregeneration.ui.marked_by_surface_rock"));
        }

        List<OreEntry> ores = currentOres();
        int shown = Math.min(ores.size(), MAX_ORE_SLOTS);
        int startX = Math.max(2, (WIDTH - shown * SLOT) / 2);
        for (int i = 0; i < shown; i++) {
            widgets.addSlot(stackOf(ores.get(i).blockId()), startX + i * SLOT, ORES_Y);
        }

        if (dimensions.size() > 1) {
            widgets.add(new CycleArrowWidget(2, DIM_Y, -1, () -> true, d -> dimensionIndex += d));
            widgets.add(new CycleArrowWidget(WIDTH - 11, DIM_Y, 1, () -> true, d -> dimensionIndex += d));
        }
        final int labelWidth = WIDTH - 26;
        widgets.addDrawable(13, DIM_Y, labelWidth, 11, (graphics, mx, my, delta) -> {
            Font font = Minecraft.getInstance().font;
            String text = RegionGenEmiRecipe.prettify(currentDimension());
            graphics.drawString(font, text, (labelWidth - font.width(text)) / 2, 2, HEADING, false);
        });

        widgets.addDrawable(4, SUMMARY_Y, WIDTH - 8, 11, (graphics, mx, my, delta) -> {
            List<OreEntry> list = currentOres();
            if (list.isEmpty()) {
                return;
            }
            OreEntry any = list.get(0);
            List<String> parts = new ArrayList<>(3);
            if (any.sizeClass() != SizeClass.UNKNOWN) {
                parts.add(Component.translatable(any.sizeClass().translationKey()).getString());
            }
            parts.add(Component.translatable("emioregeneration.ui.of_chunks",
                    RegionGenEmiRecipe.percent(any.spawnPermille())).getString());
            parts.add(RegionGenEmiRecipe.depthLabel(any));

            Font font = Minecraft.getInstance().font;
            String text = String.join(" \u00b7 ", parts);
            graphics.drawString(font, RegionGenEmiRecipe.trim(font, text, WIDTH - 10),
                    0, 0, BODY, false);
        });

        // Share of the vein, not depth: the summary line above already gives the Y band, and
        // what varies row to row here is how much of the vein each ore accounts for.
        widgets.add(YRangeGraphWidget.shares(GRAPH_X, LIST_Y, GRAPH_W,
                rows * ROW_HEIGHT - 2, ROW_HEIGHT, this::currentOres));

        for (int row = 0; row < rows; row++) {
            final int index = row;
            final int rowY = LIST_Y + row * ROW_HEIGHT;

            // Drawables translate the pose to their own origin, so coordinates here are local.
            widgets.addDrawable(TEXT_X, rowY, WIDTH - TEXT_X - 2, ROW_HEIGHT,
                    (graphics, mx, my, delta) -> {
                        List<OreEntry> list = currentOres();
                        if (index >= list.size()) {
                            return;
                        }
                        drawOreRow(graphics, list.get(index));
                    });
        }

    }

    /** One line per ore: what it is, and how much of the vein it makes up. */
    private void drawOreRow(GuiGraphics graphics, OreEntry entry) {
        Font font = Minecraft.getInstance().font;

        var display = RegionGenEmiRecipe.displayStack(entry.blockId());
        String name = display.isEmpty()
                ? RegionGenEmiRecipe.prettify(entry.blockId())
                : display.getHoverName().getString();

        String share = RegionGenEmiRecipe.percent(entry.sharePermille());
        int shareWidth = font.width(share);
        int available = WIDTH - TEXT_X - 6 - shareWidth;

        graphics.drawString(font, RegionGenEmiRecipe.trim(font, name, available), 0, 4,
                HEADING, false);
        graphics.drawString(font, share, WIDTH - TEXT_X - 4 - shareWidth, 4, BODY, false);
    }

    // ------------------------------------------------------------------

    /** Groups the flat index into one page per vein, keeping dimensions separate. */
    static Map<String, VeinGenEmiRecipe> build(
            java.util.function.Function<String, ResourceLocation> idFactory) {

        Map<String, Map<String, Map<String, OreEntry>>> veins = new LinkedHashMap<>();
        Map<String, OreEntry> rocks = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();

        ClientOreCache.pages().values().forEach(page ->
                page.byDimension().forEach((dimension, byBiome) ->
                        byBiome.values().forEach(entries -> entries.forEach(entry -> {
                            if (!entry.isPartOfVein()) {
                                return;
                            }
                            names.putIfAbsent(entry.sourceId(), entry.veinName());
                            if (entry.isSurface()) {
                                rocks.putIfAbsent(entry.sourceId(), entry);
                                return;
                            }
                            veins.computeIfAbsent(entry.sourceId(), k -> new LinkedHashMap<>())
                                    .computeIfAbsent(dimension, k -> new LinkedHashMap<>())
                                    .putIfAbsent(entry.blockId(), entry);
                        }))));

        Map<String, VeinGenEmiRecipe> out = new LinkedHashMap<>(veins.size());
        veins.forEach((veinId, byDimension) -> {
            Map<String, List<OreEntry>> sorted = new LinkedHashMap<>();
            byDimension.forEach((dimension, byBlock) -> {
                List<OreEntry> list = new ArrayList<>(byBlock.values());
                list.sort((a, b) -> Integer.compare(b.sharePermille(), a.sharePermille()));
                sorted.put(dimension, list);
            });
            out.put(veinId, new VeinGenEmiRecipe(idFactory.apply(veinId),
                    names.getOrDefault(veinId, veinId), sorted, rocks.get(veinId)));
        });
        return out;
    }
}
