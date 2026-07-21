package com.catx.emioregen.client;

import com.catx.emioregen.Config;
import com.catx.emioregen.client.widget.CycleArrowWidget;
import com.catx.emioregen.client.widget.YRangeGraphWidget;
import com.catx.emioregen.data.OreEntry;
import com.catx.emioregen.data.SizeClass;
import com.catx.emioregen.data.SourceKind;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One EMI page per ore, with a dimension cycler, a biome cycler beneath it, and a depth chart.
 *
 * <p>EMI builds widgets once when a page is opened and then renders them every frame, so the
 * selection indices live on the recipe and the widgets read them through suppliers. That means
 * a page remembers where the player left it, which is usually what you want when flipping back
 * and forth between two ores.</p>
 *
 * <p><b>Coordinate systems differ by widget type.</b> {@code Widget} subclasses draw in panel
 * coordinates, but {@code addDrawable} translates the pose stack to the drawable's own origin
 * first, so everything inside those callbacks must be relative to the drawable, not the panel.
 * Mixing the two up silently doubles every offset.</p>
 */
public class OreGenEmiRecipe implements EmiRecipe {

    static final int WIDTH = 178;

    private static final int SLOT = 18;
    private static final int ROW_HEIGHT = 21;
    private static final int MAX_DROP_SLOTS = 8;

    /**
     * Tag families that describe the same material further along the processing chain, or the
     * signs of it on the surface. Every one of these is material-scoped ({@code crushed_ores/iron}
     * holds only iron), so pulling in their members cannot over-match the way the host-stone
     * tags do. Looking up a crushed ore, a dust pile or a GregTech surface rock should land on
     * the page that says where the material comes from.
     */
    private static final List<String> RELATED_TAG_PREFIXES = List.of(
            "raw_materials/", "crushed_ores/", "purified_ores/", "refined_ores/",
            "dusts/", "small_dusts/", "tiny_dusts/", "impure_dusts/", "pure_dusts/",
            "surface_rocks/", "gems/");

    private static final int GRAPH_X = 4;
    private static final int GRAPH_W = 12;
    private static final int ICON_X = 20;
    private static final int TEXT_X = 40;

    // EMI's default theme is the light vanilla panel, so text has to be dark to be legible.
    private static final int HEADING = 0xFF202020;
    private static final int BODY = 0xFF404040;
    private static final int MUTED = 0xFF6A6A6A;

    private final ResourceLocation id;
    private final ClientOreCache.OrePage page;
    private final EmiStack subject;

    /** Everything that should resolve to this page on lookup. Not all of it is displayed. */
    private final List<EmiStack> lookupStacks;

    /** What mining this actually yields. Displayed, and also part of the lookup set. */
    private final List<EmiStack> dropStacks;

    private final int rowsPerPage;

    // Rows collapse when unused, so the panel is only as tall as the page needs.
    private final boolean hasDrops;
    private final boolean hasGeneration;
    private final boolean hasSourcePaging;
    private final int dropsY;
    private final int dimY;
    private final int biomeY;
    private final int sourceY;
    private final int listY;

    private final Map<String, Optional<EmiRecipe>> veinRecipeCache = new HashMap<>();

    private int dimensionIndex;
    private int biomeIndex;
    private int sourcePage;

    public OreGenEmiRecipe(ResourceLocation id, ClientOreCache.OrePage page) {
        this.id = id;
        this.page = page;
        this.subject = resolveStack(page.blockId());
        this.rowsPerPage = Config.MAX_ENTRIES_PER_PAGE.get();

        this.dropStacks = resolveDrops(page);
        this.lookupStacks = buildLookupSet(this.subject, this.dropStacks);

        this.hasDrops = !dropStacks.isEmpty();
        this.hasGeneration = !page.byDimension().isEmpty();
        this.hasSourcePaging = hasGeneration && maxSourcesAnywhere(page) > rowsPerPage;

        int y = 22;
        this.dropsY = y;
        if (hasDrops) {
            y += SLOT + 4;
        }
        this.dimY = y;
        this.biomeY = y + 13;
        this.sourceY = y + 26;
        if (!hasGeneration) {
            this.listY = y + 4;
        } else {
            this.listY = (hasSourcePaging ? y + 39 : y + 26) + 4;
        }
    }

    /** Ores resolve as items; bedrock fluid reservoirs resolve as fluids. */
    private static EmiStack resolveStack(String blockId) {
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

    private static List<EmiStack> resolveDrops(ClientOreCache.OrePage page) {
        OreEntry sample = page.sample();
        if (sample == null) {
            return List.of();
        }
        LinkedHashSet<EmiStack> out = new LinkedHashSet<>();
        for (String dropId : sample.dropIds()) {
            ResourceLocation location = ResourceLocation.tryParse(dropId);
            if (location != null && BuiltInRegistries.ITEM.containsKey(location)) {
                out.add(EmiStack.of(BuiltInRegistries.ITEM.get(location)));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Everything a player might click expecting to land here: the ore itself, every host-stone
     * variant of it, and whatever it drops.
     *
     * <p>The variants come from the {@code ores/*} tags. GregTech registers a separate block per
     * host stone and strips vanilla ore generation while leaving the vanilla blocks in the game,
     * so all of those need to funnel to the one page that knows where the ore comes from. The
     * variants are looked up but not drawn — a dozen recoloured cubes is noise, whereas the
     * drops are what a player is actually after.</p>
     */
    private static List<EmiStack> buildLookupSet(EmiStack subject, List<EmiStack> drops) {
        LinkedHashSet<EmiStack> out = new LinkedHashSet<>();
        if (!subject.isEmpty()) {
            out.add(subject);
        }

        ItemStack stack = subject.getItemStack();
        if (stack != null && !stack.isEmpty()) {
            // Strictly material tags. "ores_in_ground/*" groups by host stone and would pull in
            // every unrelated ore sharing that stone.
            List<String> materials = stack.getTags()
                    .map(tag -> tag.location().getPath())
                    .filter(path -> path.startsWith("ores/"))
                    .map(path -> path.substring("ores/".length()))
                    .toList();

            for (String material : materials) {
                addTagMembers(out, "ores/" + material);
                for (String prefix : RELATED_TAG_PREFIXES) {
                    addTagMembers(out, prefix + material);
                }
            }
        }

        // A fluid reservoir is far easier to find by its bucket than by the fluid itself.
        Fluid fluid = subject.getKeyOfType(Fluid.class);
        if (fluid != null && fluid.getBucket() != Items.AIR) {
            out.add(EmiStack.of(fluid.getBucket()));
        }

        out.addAll(drops);
        return List.copyOf(out);
    }

    private static void addTagMembers(LinkedHashSet<EmiStack> out, String path) {
        TagKey<Item> key = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("c", path));
        BuiltInRegistries.ITEM.getTag(key).ifPresent(holders ->
                holders.forEach(holder -> out.add(EmiStack.of(holder.value()))));
    }

    /** Worst-case source count across every dimension and biome, to fix the panel height. */
    private static int maxSourcesAnywhere(ClientOreCache.OrePage page) {
        int max = 0;
        for (String dimension : page.dimensions()) {
            for (String biome : page.biomes(dimension)) {
                int count = page.entries(dimension, biome).size();
                if (!ClientOreCache.ANY_BIOME.equals(biome)) {
                    count += page.entries(dimension, ClientOreCache.ANY_BIOME).size();
                }
                max = Math.max(max, count);
            }
        }
        return max;
    }

    // ------------------------------------------------------------------
    // Selection state
    // ------------------------------------------------------------------

    private List<String> dimensions() {
        return page.dimensions();
    }

    private String currentDimension() {
        List<String> dims = dimensions();
        if (dims.isEmpty()) {
            return "";
        }
        dimensionIndex = Math.floorMod(dimensionIndex, dims.size());
        return dims.get(dimensionIndex);
    }

    private List<String> biomes() {
        return page.biomes(currentDimension());
    }

    private String currentBiome() {
        List<String> list = biomes();
        if (list.isEmpty()) {
            return ClientOreCache.ANY_BIOME;
        }
        biomeIndex = Math.floorMod(biomeIndex, list.size());
        return list.get(biomeIndex);
    }

    /**
     * Occurrences for the current selection. A biome-specific selection also shows the
     * dimension-wide entries, because those genuinely do generate in that biome too.
     */
    private List<OreEntry> currentEntries() {
        String dimension = currentDimension();
        String biome = currentBiome();

        List<OreEntry> out = new ArrayList<>(page.entries(dimension, biome));
        if (!ClientOreCache.ANY_BIOME.equals(biome)) {
            out.addAll(page.entries(dimension, ClientOreCache.ANY_BIOME));
        }
        return out;
    }

    private int sourcePageCount() {
        int size = currentEntries().size();
        return Math.max(1, (size + rowsPerPage - 1) / rowsPerPage);
    }

    private List<OreEntry> visibleEntries() {
        List<OreEntry> all = currentEntries();
        if (all.isEmpty()) {
            return all;
        }
        sourcePage = Math.floorMod(sourcePage, sourcePageCount());
        int from = sourcePage * rowsPerPage;
        return all.subList(from, Math.min(all.size(), from + rowsPerPage));
    }

    // ------------------------------------------------------------------
    // EmiRecipe
    // ------------------------------------------------------------------

    @Override
    public EmiRecipeCategory getCategory() {
        return OreGenEmiPlugin.ORE_GEN_CATEGORY;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public List<EmiIngredient> getInputs() {
        // Same stacks on both sides, so right-click ("what uses this") finds the page too.
        return List.copyOf(lookupStacks);
    }

    @Override
    public List<EmiStack> getOutputs() {
        return lookupStacks;
    }

    @Override
    public boolean supportsRecipeTree() {
        // Reference material, not a craft, so it has no place in the recipe tree.
        return false;
    }

    @Override
    public int getDisplayWidth() {
        return WIDTH;
    }

    @Override
    public int getDisplayHeight() {
        return hasGeneration
                ? listY + rowsPerPage * ROW_HEIGHT + 4
                : listY + 14;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        // --- Panel-coordinate widgets ---------------------------------
        widgets.addSlot(subject, 2, 2).recipeContext(this);
        widgets.addText(subjectName().copy().withStyle(ChatFormatting.BOLD), 24, 7, HEADING, false);

        addDropSlots(widgets);

        if (!hasGeneration) {
            // Nothing to cycle through, so say the one useful thing and stop.
            widgets.addDrawable(4, listY, WIDTH - 8, 12, (graphics, mouseX, mouseY, delta) ->
                    graphics.drawString(Minecraft.getInstance().font,
                            Component.translatable("emioregeneration.ui.not_natural"),
                            0, 0, MUTED, false));
            return;
        }

        addCycler(widgets, dimY, () -> dimensions().size(),
                () -> Component.literal(shortId(currentDimension())),
                delta -> {
                    dimensionIndex += delta;
                    biomeIndex = 0;
                    sourcePage = 0;
                });
        widgets.add(new RegionLink(dimY, false));

        addCycler(widgets, biomeY, () -> biomes().size(), this::biomeLabel,
                delta -> {
                    biomeIndex += delta;
                    sourcePage = 0;
                });
        widgets.add(new RegionLink(biomeY, true));

        if (hasSourcePaging) {
            addCycler(widgets, sourceY, this::sourcePageCount, this::sourceLabel,
                    delta -> sourcePage += delta);
        }

        widgets.add(new YRangeGraphWidget(GRAPH_X, listY, GRAPH_W,
                rowsPerPage * ROW_HEIGHT - 2, ROW_HEIGHT, this::visibleEntries));

        // --- Drawables: everything below is in drawable-local coordinates ---
        for (int row = 0; row < rowsPerPage; row++) {
            final int index = row;
            final int rowY = listY + row * ROW_HEIGHT;

            widgets.addDrawable(ICON_X, rowY, WIDTH - ICON_X - 2, ROW_HEIGHT,
                    (graphics, mouseX, mouseY, delta) -> {
                        List<OreEntry> visible = visibleEntries();
                        if (index >= visible.size()) {
                            return;
                        }
                        OreEntry entry = visible.get(index);
                        drawIndicator(graphics, entry, 0, 2);
                        drawRow(graphics, entry, TEXT_X - ICON_X, 0);
                    });

            widgets.add(new VeinLink(row, TEXT_X, rowY, WIDTH - TEXT_X - 2, 10));
        }

        widgets.addDrawable(TEXT_X, listY, WIDTH - TEXT_X - 2, 10,
                (graphics, mouseX, mouseY, delta) -> {
                    if (currentEntries().isEmpty()) {
                        graphics.drawString(Minecraft.getInstance().font,
                                Component.translatable("emioregeneration.ui.no_data"),
                                0, 0, MUTED, false);
                    }
                });
    }

    /**
     * GregTech's own Ore Vein Diagram for a vein, if it has one.
     *
     * <p>GregTech registers those as synthetic recipes under {@code /ore_vein_diagram/<vein>}.
     * Resolved lazily and cached, because it can only be looked up once EMI has finished baking,
     * which is long after this recipe was constructed.</p>
     */
    private EmiRecipe veinRecipe(OreEntry entry) {
        if (entry.kind() != SourceKind.GT_VEIN || !entry.isPartOfVein()) {
            return null;
        }
        return veinRecipeCache.computeIfAbsent(entry.sourceId(), sourceId -> {
            VeinGenEmiRecipe own = OreGenEmiPlugin.veinPage(sourceId);
            if (own != null) {
                return Optional.of(own);
            }

            ResourceLocation vein = ResourceLocation.tryParse(sourceId);
            if (vein == null) {
                return Optional.empty();
            }
            EmiRecipeManager manager = EmiApi.getRecipeManager();

            EmiRecipe direct = manager.getRecipe(ResourceLocation.fromNamespaceAndPath(
                    vein.getNamespace(), "/ore_vein_diagram/" + vein.getPath()));
            if (direct != null) {
                return Optional.of(direct);
            }

            // Namespaces can differ for datapack veins, so fall back to matching the path.
            for (EmiRecipe candidate : manager.getRecipes()) {
                ResourceLocation id = candidate.getId();
                if (id != null && id.getPath().endsWith("/ore_vein_diagram/" + vein.getPath())) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** Hit area over a cycler label, linking to that region's own generation page. */
    private final class RegionLink extends Widget {
        private final Bounds bounds;
        private final boolean biome;

        private RegionLink(int y, boolean biome) {
            this.bounds = new Bounds(13, y, WIDTH - 26, 11);
            this.biome = biome;
        }

        private RegionGenEmiRecipe target() {
            if (!biome) {
                return OreGenEmiPlugin.dimensionPage(currentDimension());
            }
            String current = currentBiome();
            if (ClientOreCache.ANY_BIOME.equals(current)) {
                return null;
            }
            return OreGenEmiPlugin.biomePage(currentDimension(), current);
        }

        @Override
        public Bounds getBounds() {
            return bounds;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            if (!bounds.contains(mouseX, mouseY) || target() == null) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            String text = biome ? biomeLabel().getString() : shortId(currentDimension());
            int width = Math.min(bounds.width(), font.width(text));
            int left = bounds.x() + (bounds.width() - width) / 2;
            graphics.fill(left, bounds.y() + 11, left + width, bounds.y() + 12, HEADING);
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (button != 0 || !bounds.contains(mouseX, mouseY)) {
                return false;
            }
            RegionGenEmiRecipe target = target();
            if (target == null) {
                return false;
            }
            // Open the category, then focus this one, so EMI's page arrows walk every other
            // biome or dimension instead of stranding the player on a single page.
            EmiApi.displayRecipeCategory(target.getCategory());
            EmiApi.focusRecipe(target);
            return true;
        }
    }

    /** Invisible hit area over a row's title, linking through to GregTech's vein diagram. */
    private final class VeinLink extends Widget {
        private final int index;
        private final Bounds bounds;

        private VeinLink(int index, int x, int y, int width, int height) {
            this.index = index;
            this.bounds = new Bounds(x, y, width, height);
        }

        private OreEntry entry() {
            List<OreEntry> visible = visibleEntries();
            return index < visible.size() ? visible.get(index) : null;
        }

        @Override
        public Bounds getBounds() {
            return bounds;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            OreEntry entry = entry();
            if (entry == null) {
                return;
            }
            EmiRecipe target = veinRecipe(entry);
            if (target == null) {
                return;
            }
            // Underline only on hover, so the page stays quiet until there's something to click.
            if (bounds.contains(mouseX, mouseY)) {
                Font font = Minecraft.getInstance().font;
                int width = Math.min(bounds.width(), font.width(entry.veinName()));
                graphics.fill(bounds.x(), bounds.y() + 9, bounds.x() + width,
                        bounds.y() + 10, HEADING);
            }
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (button != 0 || !bounds.contains(mouseX, mouseY)) {
                return false;
            }
            OreEntry entry = entry();
            if (entry == null) {
                return false;
            }
            EmiRecipe target = veinRecipe(entry);
            if (target == null) {
                return false;
            }
            EmiApi.displayRecipeCategory(target.getCategory());
            EmiApi.focusRecipe(target);
            return true;
        }
    }

    /** What you get for mining it, centred so the row doesn't look pinned to one edge. */
    private void addDropSlots(WidgetHolder widgets) {
        if (!hasDrops) {
            return;
        }

        int shown = Math.min(dropStacks.size(), MAX_DROP_SLOTS);
        int hidden = dropStacks.size() - shown;
        int rowWidth = shown * SLOT + (hidden > 0 ? 20 : 0);
        int startX = Math.max(2, (WIDTH - rowWidth) / 2);

        for (int i = 0; i < shown; i++) {
            widgets.addSlot(dropStacks.get(i), startX + i * SLOT, dropsY);
        }

        if (hidden > 0) {
            int x = startX + shown * SLOT;
            widgets.addDrawable(x, dropsY, 20, SLOT, (graphics, mouseX, mouseY, delta) ->
                    graphics.drawString(Minecraft.getInstance().font,
                            "+" + hidden, 2, 5, MUTED, false));
        }
    }

    /** Left arrow, centred label, right arrow. Arrows grey out when there is nothing to cycle. */
    private void addCycler(WidgetHolder widgets, int y,
                           java.util.function.IntSupplier optionCount,
                           java.util.function.Supplier<Component> label,
                           CycleArrowWidget.StepHandler handler) {
        widgets.add(new CycleArrowWidget(2, y, -1, () -> optionCount.getAsInt() > 1, handler));
        widgets.add(new CycleArrowWidget(WIDTH - 11, y, 1, () -> optionCount.getAsInt() > 1, handler));

        final int labelWidth = WIDTH - 26;
        widgets.addDrawable(13, y, labelWidth, 11, (graphics, mouseX, mouseY, delta) -> {
            Font font = Minecraft.getInstance().font;
            String text = trim(font, label.get().getString(), labelWidth - 2);
            graphics.drawString(font, text, (labelWidth - font.width(text)) / 2, 2, HEADING, false);
        });
    }

    /**
     * GregTech scatters surface rocks above its veins, which is the cheapest prospecting
     * signal in the game, so the icon earns its place next to the vein it belongs to.
     */
    private void drawIndicator(GuiGraphics graphics, OreEntry entry, int x, int y) {
        if (entry.indicatorBlockId().isEmpty()) {
            return;
        }
        ResourceLocation location = ResourceLocation.tryParse(entry.indicatorBlockId());
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return;
        }
        graphics.renderFakeItem(BuiltInRegistries.ITEM.get(location).getDefaultInstance(), x, y);
    }

    private void drawRow(GuiGraphics graphics, OreEntry entry, int x, int y) {
        Font font = Minecraft.getInstance().font;
        int available = WIDTH - TEXT_X - 4;

        String title = entry.isPartOfVein()
                ? entry.veinName()
                : shortId(entry.sourceId());
        if (entry.isPartOfVein() && entry.sharePermille() < 1000) {
            title = title + "  " + percent(entry.sharePermille());
        }
        graphics.drawString(font, trim(font, title, available), x, y, HEADING, false);

        boolean sized = entry.sizeClass() != SizeClass.UNKNOWN;
        if (sized) {
            // Size pip, so the size class is legible without reading the word.
            graphics.fill(x, y + 10, x + 3, y + 17, entry.sizeClass().color());
        }

        List<String> parts = new ArrayList<>(3);
        if (sized) {
            parts.add(Component.translatable(entry.sizeClass().translationKey()).getString());
        }
        parts.add(percent(entry.spawnPermille()));
        parts.add(RegionGenEmiRecipe.depthLabel(entry));

        String detail = String.join(" \u00b7 ", parts);
        int detailX = sized ? x + 6 : x;
        graphics.drawString(font, trim(font, detail, available - (sized ? 6 : 0)),
                detailX, y + 10, BODY, false);
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private Component subjectName() {
        if (subject.isEmpty()) {
            return Component.literal(shortId(page.blockId()));
        }
        return subject.getName().copy();
    }

    private Component biomeLabel() {
        String biome = currentBiome();
        return ClientOreCache.ANY_BIOME.equals(biome)
                ? Component.translatable("emioregeneration.ui.any_biome")
                : Component.literal(shortId(biome));
    }

    private Component sourceLabel() {
        return Component.translatable("emioregeneration.ui.sources_paged",
                sourcePage + 1, sourcePageCount());
    }

    /**
     * Rounds to the nearest 5%, but never reports 0% for something that can actually happen —
     * a one-in-a-thousand vein is rare, not impossible, and the difference matters to a player
     * deciding whether to keep looking.
     */
    private static String percent(int permille) {
        if (permille <= 0) {
            return "0%";
        }
        int rounded = Math.round(permille / 10f / 5f) * 5;
        return rounded == 0 ? "<5%" : rounded + "%";
    }

    /** Drops the namespace and prettifies the path: {@code minecraft:lush_caves} -> Lush Caves. */
    private static String shortId(String id) {
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

    private static String trim(Font font, String text, int maxWidth) {
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
