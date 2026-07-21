package com.catx.emioregen.client;

import com.catx.emioregen.Config;
import com.catx.emioregen.client.widget.CycleArrowWidget;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.List;

/**
 * The inverse of the ore page: everything that generates in one place.
 *
 * <p>Serves both the biome and dimension categories, which differ only in how wide a net they
 * cast. Ore pages answer "where do I find this"; these answer "I'm standing here, what is under
 * me", which is the question that actually comes up while exploring.</p>
 */
public class RegionGenEmiRecipe implements EmiRecipe {

    static final int WIDTH = 178;

    private static final int ROW_HEIGHT = 21;
    private static final int HEADER_Y = 4;
    private static final int PAGER_Y = 28;
    private static final int LIST_Y = 46;

    private static final int ICON_X = 4;
    private static final int TEXT_X = 24;

    private static final int HEADING = 0xFF202020;
    private static final int BODY = 0xFF404040;
    private static final int MUTED = 0xFF6A6A6A;

    private final ResourceLocation id;
    private final EmiRecipeCategory category;
    private final String title;
    private final String subtitle;
    private final String dimensionId;
    private final String biomeId;
    private final List<OreEntry> entries;
    private final int rowsPerPage;

    private int page;

    private RegionGenEmiRecipe(ResourceLocation id, EmiRecipeCategory category,
                               String title, String subtitle,
                               String dimensionId, String biomeId, List<OreEntry> entries) {
        this.id = id;
        this.category = category;
        this.title = title;
        this.subtitle = subtitle;
        this.dimensionId = dimensionId;
        this.biomeId = biomeId;
        this.entries = entries;
        // Region pages are pure lists, so they can afford to be denser than an ore page.
        this.rowsPerPage = Math.max(4, Config.MAX_ENTRIES_PER_PAGE.get() * 2);
    }

    public static RegionGenEmiRecipe forBiome(ResourceLocation id, String dimensionId,
                                             String biomeId, List<OreEntry> entries) {
        return new RegionGenEmiRecipe(id, OreGenEmiPlugin.BIOME_GEN_CATEGORY,
                prettify(biomeId), prettify(dimensionId), dimensionId, biomeId, entries);
    }

    public static RegionGenEmiRecipe forDimension(ResourceLocation id, String dimensionId,
                                                  int biomeCount, List<OreEntry> entries) {
        String subtitle = Component.translatable(
                "emioregeneration.ui.across_biomes", biomeCount).getString();
        return new RegionGenEmiRecipe(id, OreGenEmiPlugin.DIMENSION_GEN_CATEGORY,
                prettify(dimensionId), subtitle, dimensionId, "", entries);
    }

    public String dimensionId() {
        return dimensionId;
    }

    public String biomeId() {
        return biomeId;
    }

    private int pageCount() {
        return Math.max(1, (entries.size() + rowsPerPage - 1) / rowsPerPage);
    }

    private List<OreEntry> visible() {
        if (entries.isEmpty()) {
            return entries;
        }
        page = Math.floorMod(page, pageCount());
        int from = page * rowsPerPage;
        return entries.subList(from, Math.min(entries.size(), from + rowsPerPage));
    }

    // ------------------------------------------------------------------

    @Override
    public EmiRecipeCategory getCategory() {
        return category;
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
        // Deliberately empty. Listing every ore here would attach this page to every ore lookup
        // in the game, burying an ore's own page under one entry per region it appears in.
        return List.of();
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
        return LIST_Y + rowsPerPage * ROW_HEIGHT + 4;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        widgets.addText(Component.literal(title).withStyle(ChatFormatting.BOLD),
                4, HEADER_Y, HEADING, false);
        widgets.addText(Component.literal(subtitle), 4, HEADER_Y + 11, MUTED, false);

        if (pageCount() > 1) {
            widgets.add(new CycleArrowWidget(2, PAGER_Y, -1, () -> true, delta -> page += delta));
            widgets.add(new CycleArrowWidget(WIDTH - 11, PAGER_Y, 1, () -> true, delta -> page += delta));

            final int labelWidth = WIDTH - 26;
            widgets.addDrawable(13, PAGER_Y, labelWidth, 11, (graphics, mx, my, delta) -> {
                Font font = Minecraft.getInstance().font;
                String text = Component.translatable("emioregeneration.ui.sources_paged",
                        page + 1, pageCount()).getString();
                graphics.drawString(font, text, (labelWidth - font.width(text)) / 2, 2,
                        HEADING, false);
            });
        }

        for (int row = 0; row < rowsPerPage; row++) {
            final int index = row;
            final int rowY = LIST_Y + row * ROW_HEIGHT;

            // Drawables translate the pose to their own origin, so everything below is local.
            widgets.addDrawable(ICON_X, rowY, WIDTH - ICON_X - 2, ROW_HEIGHT,
                    (graphics, mx, my, delta) -> {
                        List<OreEntry> list = visible();
                        if (index >= list.size()) {
                            return;
                        }
                        drawRow(graphics, list.get(index));
                    });
        }
    }

    private void drawRow(GuiGraphics graphics, OreEntry entry) {
        Font font = Minecraft.getInstance().font;

        ItemStack display = displayStack(entry.blockId());
        String name = display.isEmpty()
                ? prettify(entry.blockId())
                : display.getHoverName().getString();
        if (!display.isEmpty()) {
            graphics.renderFakeItem(display, 0, 1);
        }

        int textX = TEXT_X - ICON_X;
        int available = WIDTH - TEXT_X - 8;
        graphics.drawString(font, trim(font, name, available), textX, 0, HEADING, false);

        List<String> parts = new ArrayList<>(3);
        if (entry.sizeClass() != SizeClass.UNKNOWN) {
            parts.add(Component.translatable(entry.sizeClass().translationKey()).getString());
        }
        parts.add(percent(entry.spawnPermille()));
        parts.add(depthLabel(entry));

        graphics.drawString(font, trim(font, String.join(" \u00b7 ", parts), available),
                textX, 10, BODY, false);
    }

    /**
     * An icon for anything the index can name. Fluids have no item of their own, so the bucket
     * stands in for them rather than leaving a blank space where every other row has a picture.
     */
    static ItemStack displayStack(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return ItemStack.EMPTY;
        }
        if (BuiltInRegistries.ITEM.containsKey(location)) {
            return BuiltInRegistries.ITEM.get(location).getDefaultInstance();
        }
        if (BuiltInRegistries.FLUID.containsKey(location)) {
            Fluid fluid = BuiltInRegistries.FLUID.get(location);
            if (fluid.getBucket() != Items.AIR) {
                return fluid.getBucket().getDefaultInstance();
            }
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------
    // Shared formatting, also used by the ore page
    // ------------------------------------------------------------------

    static String depthLabel(OreEntry entry) {
        if (entry.isSurface()) {
            return Component.translatable("emioregeneration.ui.surface").getString();
        }
        if (entry.isFluid()) {
            return Component.translatable("emioregeneration.ui.bedrock_layer").getString();
        }
        return Component.translatable("emioregeneration.ui.y_short",
                entry.minY(), entry.maxY()).getString();
    }

    static String percent(int permille) {
        if (permille <= 0) {
            return "0%";
        }
        int rounded = Math.round(permille / 10f / 5f) * 5;
        return rounded == 0 ? "<5%" : rounded + "%";
    }

    static String prettify(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] parts = path.split("[_/]");
        StringBuilder sb = new StringBuilder(path.length());
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    static String trim(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int budget = maxWidth - font.width("..");
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.width(sb.toString() + c) > budget) {
                break;
            }
            sb.append(c);
        }
        return sb + "..";
    }
}
