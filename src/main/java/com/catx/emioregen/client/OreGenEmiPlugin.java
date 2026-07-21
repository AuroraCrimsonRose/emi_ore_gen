package com.catx.emioregen.client;

import com.catx.emioregen.Config;
import com.catx.emioregen.EMIOreGeneration;
import com.catx.emioregen.data.OreEntry;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@EmiEntrypoint
public class OreGenEmiPlugin implements EmiPlugin {

    public static final EmiRecipeCategory ORE_GEN_CATEGORY = new EmiRecipeCategory(
            EMIOreGeneration.id("ore_gen"), EmiStack.of(Items.IRON_ORE));

    public static final EmiRecipeCategory BIOME_GEN_CATEGORY = new EmiRecipeCategory(
            EMIOreGeneration.id("biome_gen"), EmiStack.of(Items.GRASS_BLOCK));

    public static final EmiRecipeCategory DIMENSION_GEN_CATEGORY = new EmiRecipeCategory(
            EMIOreGeneration.id("dimension_gen"), EmiStack.of(Items.COMPASS));

    public static final EmiRecipeCategory VEIN_GEN_CATEGORY = new EmiRecipeCategory(
            EMIOreGeneration.id("vein_gen"), EmiStack.of(Items.RAW_IRON));

    /** GregTech registers its own vein diagrams under this path. */
    private static final String GT_VEIN_DIAGRAM_PATH = "/ore_vein_diagram/";

    private static final String ORE_TAG_PREFIX = "ores/";
    private static final String SURFACE_ROCK_TAG_PREFIX = "surface_rocks/";

    /**
     * Biome pages by "dimension|biome", so an ore page can link straight to the biome it's
     * describing. Populated during registration and read on click, long afterwards.
     */
    private static final Map<String, RegionGenEmiRecipe> BIOME_PAGES = new LinkedHashMap<>();
    private static final Map<String, RegionGenEmiRecipe> DIMENSION_PAGES = new LinkedHashMap<>();
    private static final Map<String, VeinGenEmiRecipe> VEIN_PAGES = new LinkedHashMap<>();

    public static VeinGenEmiRecipe veinPage(String veinId) {
        return VEIN_PAGES.get(veinId);
    }

    public static RegionGenEmiRecipe biomePage(String dimensionId, String biomeId) {
        return BIOME_PAGES.get(dimensionId + '|' + biomeId);
    }

    public static RegionGenEmiRecipe dimensionPage(String dimensionId) {
        return DIMENSION_PAGES.get(dimensionId);
    }

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(ORE_GEN_CATEGORY);
        registry.addCategory(BIOME_GEN_CATEGORY);
        registry.addCategory(DIMENSION_GEN_CATEGORY);

        registerRegionPages(registry);
        registerVeinPages(registry);

        Map<String, ClientOreCache.OrePage> pages = buildPages();

        int registered = 0;
        int barren = 0;
        for (Map.Entry<String, ClientOreCache.OrePage> entry : pages.entrySet()) {
            ClientOreCache.OrePage page = entry.getValue();

            // The leading slash marks the id as synthetic. Without it EMI looks the recipe up in
            // the recipe manager, fails to find it, and logs an error for every single page.
            OreGenEmiRecipe recipe = new OreGenEmiRecipe(
                    EMIOreGeneration.id("/ore_gen/" + sanitise(entry.getKey())), page);

            if (recipe.getOutputs().isEmpty()) {
                continue;
            }
            registry.addRecipe(recipe);
            registered++;
            if (page.byDimension().isEmpty()) {
                barren++;
            }
        }

        EMIOreGeneration.LOGGER.info(
                "Registered {} ore generation pages ({} with no natural generation)",
                registered, barren);
    }

    // ------------------------------------------------------------------
    // Biome pages
    // ------------------------------------------------------------------

    /**
     * Inverts the index into one page per biome.
     *
     * <p>The ore pages answer "where do I find this". These answer "I'm standing here, what is
     * under me", which is the question that actually comes up while exploring. Dimension-wide
     * occurrences are folded into every biome of that dimension, because that is where they
     * genuinely are.</p>
     */
    private static void registerRegionPages(EmiRegistry registry) {
        BIOME_PAGES.clear();
        DIMENSION_PAGES.clear();

        Map<String, Map<String, List<OreEntry>>> byDimension = new TreeMap<>();
        Map<String, List<OreEntry>> dimensionWide = new TreeMap<>();

        ClientOreCache.pages().values().forEach(page ->
                page.byDimension().forEach((dimension, byBiome) ->
                        byBiome.forEach((biome, entries) -> {
                            if (ClientOreCache.ANY_BIOME.equals(biome)) {
                                dimensionWide.computeIfAbsent(dimension, k -> new ArrayList<>())
                                        .addAll(entries);
                            } else {
                                byDimension.computeIfAbsent(dimension, k -> new TreeMap<>())
                                        .computeIfAbsent(biome, k -> new ArrayList<>())
                                        .addAll(entries);
                            }
                        })));

        byDimension.forEach((dimension, byBiome) -> byBiome.forEach((biome, entries) -> {
            List<OreEntry> combined = new ArrayList<>(entries);
            combined.addAll(dimensionWide.getOrDefault(dimension, List.of()));

            List<OreEntry> cleaned = dedupeByBlock(combined);
            if (cleaned.isEmpty()) {
                return;
            }

            RegionGenEmiRecipe recipe = RegionGenEmiRecipe.forBiome(
                    EMIOreGeneration.id("/biome_gen/" + sanitise(dimension + "_" + biome)),
                    dimension, biome, cleaned);

            BIOME_PAGES.put(dimension + '|' + biome, recipe);
            registry.addRecipe(recipe);
        }));

        // One level up: everything anywhere in the dimension, however patchy its biome coverage.
        Set<String> allDimensions = new java.util.LinkedHashSet<>(byDimension.keySet());
        allDimensions.addAll(dimensionWide.keySet());

        for (String dimension : allDimensions) {
            List<OreEntry> combined = new ArrayList<>(dimensionWide.getOrDefault(dimension, List.of()));
            Map<String, List<OreEntry>> byBiome = byDimension.getOrDefault(dimension, Map.of());
            byBiome.values().forEach(combined::addAll);

            List<OreEntry> cleaned = dedupeByBlock(combined);
            if (cleaned.isEmpty()) {
                continue;
            }

            RegionGenEmiRecipe recipe = RegionGenEmiRecipe.forDimension(
                    EMIOreGeneration.id("/dimension_gen/" + sanitise(dimension)),
                    dimension, byBiome.size(), cleaned);

            DIMENSION_PAGES.put(dimension, recipe);
            registry.addRecipe(recipe);
        }

        EMIOreGeneration.LOGGER.info("Registered {} biome and {} dimension generation pages",
                BIOME_PAGES.size(), DIMENSION_PAGES.size());
    }

    /** One row per block on a biome page: the same ore from two veins is still one ore here. */
    private static List<OreEntry> dedupeByBlock(List<OreEntry> entries) {
        Map<String, OreEntry> best = new LinkedHashMap<>();
        for (OreEntry entry : entries) {
            best.merge(entry.blockId(), entry,
                    (a, b) -> b.spawnPermille() > a.spawnPermille() ? b : a);
        }
        return best.values().stream()
                .sorted(Comparator.comparingInt(OreEntry::spawnPermille).reversed()
                        .thenComparing(OreEntry::blockId))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Vein pages
    // ------------------------------------------------------------------

    /**
     * Registers this mod's own vein diagrams, and optionally retires GregTech's.
     *
     * <p>Two diagrams for the same vein sitting in adjacent tabs is worse than either alone, so
     * the default is to replace. GregTech's is a perfectly good page — this one just speaks the
     * same language as the rest of the mod and carries the depth chart.</p>
     */
    private static void registerVeinPages(EmiRegistry registry) {
        VEIN_PAGES.clear();

        if (!Config.OWN_VEIN_DIAGRAM.get()) {
            return;
        }

        registry.addCategory(VEIN_GEN_CATEGORY);
        VEIN_PAGES.putAll(VeinGenEmiRecipe.build(
                veinId -> EMIOreGeneration.id("/vein_gen/" + sanitise(veinId))));
        VEIN_PAGES.values().forEach(registry::addRecipe);

        // GregTech's plugin loads before this one, so its recipes are already present.
        registry.removeRecipes(recipe -> {
            ResourceLocation id = recipe.getId();
            return id != null && id.getPath().contains(GT_VEIN_DIAGRAM_PATH);
        });

        EMIOreGeneration.LOGGER.info(
                "Registered {} vein pages and removed GregTech's vein diagrams", VEIN_PAGES.size());
    }

    // ------------------------------------------------------------------
    // Page construction
    // ------------------------------------------------------------------

    /**
     * Builds one page per ore material rather than one per block.
     *
     * <p>Materials come from the {@code c:ores/*} tags, which exist whether or not anything
     * generates: an ore that a modpack has disabled, or that only ever comes from a machine,
     * still deserves a page that says so instead of silently having no answer. Worldgen is then
     * attached to whichever material it belongs to, and any ore with no common tag keeps its own
     * page so nothing gets dropped on the floor.</p>
     */
    private static Map<String, ClientOreCache.OrePage> buildPages() {
        Map<String, String> representatives = new LinkedHashMap<>();
        Map<String, List<ClientOreCache.OrePage>> sources = new LinkedHashMap<>();

        BuiltInRegistries.ITEM.getTags().forEach(pair -> {
            TagKey<Item> tag = pair.getFirst();
            ResourceLocation location = tag.location();
            if (!location.getNamespace().equals("c") || !location.getPath().startsWith(ORE_TAG_PREFIX)) {
                return;
            }
            representative(pair.getSecond()).ifPresent(blockId -> {
                representatives.put(location.toString(), blockId);
                sources.computeIfAbsent(location.toString(), k -> new ArrayList<>());
            });
        });

        ClientOreCache.pages().forEach((blockId, page) -> {
            Set<String> keys = materialKeys(blockId);
            if (keys.isEmpty()) {
                representatives.putIfAbsent(blockId, blockId);
                sources.computeIfAbsent(blockId, k -> new ArrayList<>()).add(page);
                return;
            }
            // Attach to every material it claims to be. GregTech tags its tin ore as both
            // cassiterite and tin, while Mekanism's tin ore only carries tin; picking one tag
            // would leave Mekanism's block on a page saying nothing generates.
            for (String key : keys) {
                representatives.putIfAbsent(key, blockId);
                sources.computeIfAbsent(key, k -> new ArrayList<>()).add(page);
            }
        });

        Map<String, ClientOreCache.OrePage> out = new LinkedHashMap<>(sources.size());
        sources.forEach((key, group) -> out.put(key, normalise(
                new ClientOreCache.OrePage(representatives.get(key), mergeDimensions(group)))));
        return out;
    }

    /**
     * The plainest spelling of a material, used for the page's icon and title.
     *
     * <p>Vanilla first, because a player looking up quartz expects Nether Quartz Ore rather than
     * whichever modded variant happened to sort first. Then the shortest id, which reliably
     * picks the base block over {@code deepslate_x_ore} or {@code mars_x_ore}.</p>
     */
    private static Optional<String> representative(HolderSet.Named<Item> members) {
        return members.stream()
                .map(Holder::value)
                .map(BuiltInRegistries.ITEM::getKey)
                .filter(java.util.Objects::nonNull)
                .map(ResourceLocation::toString)
                .min(Comparator.<String>comparingInt(id -> id.startsWith("minecraft:") ? 0 : 1)
                        .thenComparingInt(String::length)
                        .thenComparing(Comparator.naturalOrder()));
    }

    /**
     * Every material this block counts as.
     *
     * <p>A block can legitimately belong to more than one, and surface rocks are folded into the
     * ore they advertise so the rock shows up on the page a player is actually reading rather
     * than on a lonely page of its own.</p>
     */
    private static Set<String> materialKeys(String blockId) {
        ResourceLocation location = ResourceLocation.tryParse(blockId);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return Set.of();
        }

        Set<String> keys = new LinkedHashSet<>();
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(location));
        stack.getTags().map(TagKey::location).forEach(id -> {
            if (!id.getNamespace().equals("c")) {
                return;
            }
            String path = id.getPath();
            if (path.startsWith(ORE_TAG_PREFIX)) {
                keys.add(id.toString());
            } else if (path.startsWith(SURFACE_ROCK_TAG_PREFIX)) {
                keys.add("c:" + ORE_TAG_PREFIX + path.substring(SURFACE_ROCK_TAG_PREFIX.length()));
            }
        });
        return keys;
    }

    private static Map<String, Map<String, List<OreEntry>>> mergeDimensions(
            List<ClientOreCache.OrePage> group) {

        Map<String, Map<String, List<OreEntry>>> merged = new TreeMap<>();
        for (ClientOreCache.OrePage page : group) {
            page.byDimension().forEach((dimension, byBiome) -> {
                Map<String, List<OreEntry>> target =
                        merged.computeIfAbsent(dimension, k -> new TreeMap<>());
                byBiome.forEach((biome, entries) ->
                        target.computeIfAbsent(biome, k -> new ArrayList<>()).addAll(entries));
            });
        }
        return merged;
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    /**
     * Removes noise the merge exposes: repeated occurrences, and biome cycling that leads
     * nowhere.
     *
     * <p>Merging host-stone variants means the same feature arrives once per variant. And a
     * feature present in most but not quite all of a dimension's biomes produces a biome list
     * where every entry says the same thing — arrows that change the label and nothing else are
     * worse than no arrows.</p>
     */
    private static ClientOreCache.OrePage normalise(ClientOreCache.OrePage page) {
        Map<String, Map<String, List<OreEntry>>> byDimension = new TreeMap<>();

        page.byDimension().forEach((dimension, byBiome) -> {
            final Map<String, List<OreEntry>> cleaned = new TreeMap<>();
            byBiome.forEach((biome, entries) -> {
                List<OreEntry> deduped = dedupe(entries);
                if (!deduped.isEmpty()) {
                    cleaned.put(biome, deduped);
                }
            });

            if (cleaned.isEmpty()) {
                return;
            }

            boolean uniform = cleaned.size() > 1 && cleaned.values().stream()
                    .map(OreGenEmiPlugin::signature)
                    .distinct()
                    .count() == 1;

            if (uniform) {
                Map<String, List<OreEntry>> collapsed = new TreeMap<>();
                collapsed.put(ClientOreCache.ANY_BIOME, cleaned.values().iterator().next());
                byDimension.put(dimension, collapsed);
            } else {
                byDimension.put(dimension, cleaned);
            }
        });

        return new ClientOreCache.OrePage(page.blockId(), byDimension);
    }

    private static List<OreEntry> dedupe(List<OreEntry> entries) {
        Map<String, OreEntry> unique = new LinkedHashMap<>();
        for (OreEntry entry : entries) {
            unique.putIfAbsent(key(entry), entry);
        }
        return new ArrayList<>(unique.values());
    }

    /** Identity of an occurrence, ignoring which host-stone block it was found through. */
    private static String key(OreEntry entry) {
        return entry.sourceId() + '|' + entry.veinName() + '|' + entry.minY() + '|'
                + entry.maxY() + '|' + entry.spawnPermille() + '|' + entry.sharePermille();
    }

    private static String signature(List<OreEntry> entries) {
        return entries.stream().map(OreGenEmiPlugin::key).sorted()
                .collect(Collectors.joining(";"));
    }

    /** Resource location paths only accept [a-z0-9_.-/], so namespaced ids need flattening. */
    private static String sanitise(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }
}
