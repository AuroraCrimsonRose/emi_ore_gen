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

import java.util.ArrayList;
import java.util.List;

/**
 * The inverse of the ore page: everything that generates in one biome.
 *
 * <p>Answers "I'm standing here, what can I find" rather than "where do I find this", which is
 * the question a player actually has while exploring. Built from the same index, just walked
 * from the other end.</p>
 */
public class BiomeGenEmiRecipe implements EmiRecipe {

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
    private final String dimensionId;
    private final String biomeId;
    private final List<OreEntry> entries;
    private final int rowsPerPage;

    private int page;

    public BiomeGenEmiRecipe(ResourceLocation id, String dimensionId, String biomeId,
                             List<OreEntry> entries) {
        this.id = id;
        this.dimensionId = dimensionId;
        this.biomeId = biomeId;
        this.entries = entries;
        this.rowsPerPage = Math.max(4, Config.MAX_ENTRIES_PER_PAGE.get() * 2);
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
        return OreGenEmiPlugin.BIOME_GEN_CATEGORY;
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
        // Deliberately empty. Listing every ore here would attach this page to every ore
        // lookup in the game, drowning the ore's own page in one entry per biome.
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
        widgets.addText(Component.literal(prettify(biomeId)).withStyle(ChatFormatting.BOLD),
                4, HEADER_Y, HEADING, false);
        widgets.addText(Component.literal(prettify(dimensionId)), 4, HEADER_Y + 11, MUTED, false);

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

        ResourceLocation location = ResourceLocation.tryParse(entry.blockId());
        String name = prettify(entry.blockId());
        if (location != null && BuiltInRegistries.ITEM.containsKey(location)) {
            var item = BuiltInRegistries.ITEM.get(location);
            graphics.renderFakeItem(item.getDefaultInstance(), 0, 1);
            name = item.getDefaultInstance().getHoverName().getString();
        } else if (location != null && BuiltInRegistries.FLUID.containsKey(location)) {
            name = prettify(entry.blockId());
        }

        int textX = TEXT_X - ICON_X;
        graphics.drawString(font, trim(font, name, WIDTH - TEXT_X - 4), textX, 0, HEADING, false);

        List<String> parts = new ArrayList<>(3);
        if (entry.sizeClass() != SizeClass.UNKNOWN) {
            parts.add(Component.translatable(entry.sizeClass().translationKey()).getString());
        }
        parts.add(percent(entry.spawnPermille()));
        parts.add(depthLabel(entry));

        graphics.drawString(font, trim(font, String.join(" \u00b7 ", parts), WIDTH - TEXT_X - 4),
                textX, 10, BODY, false);
    }

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
