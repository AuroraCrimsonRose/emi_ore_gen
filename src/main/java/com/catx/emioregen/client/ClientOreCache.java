package com.catx.emioregen.client;

import com.catx.emioregen.data.OreEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Client-side view of the ore index, shaped for the two-level cycling UI.
 *
 * <p>Structure is block -> dimension -> biome -> entries. The biome level always carries an
 * {@link #ANY_BIOME} bucket holding occurrences that apply dimension-wide, so a page never
 * ends up with an empty biome list.</p>
 */
public final class ClientOreCache {

    /** Sentinel biome key for occurrences that apply to a whole dimension. */
    public static final String ANY_BIOME = "";

    private static Map<String, OrePage> pages = Map.of();

    private ClientOreCache() {
    }

    public static void update(List<OreEntry> entries) {
        Map<String, Map<String, Map<String, List<OreEntry>>>> building = new LinkedHashMap<>();

        for (OreEntry entry : entries) {
            Map<String, Map<String, List<OreEntry>>> byDimension =
                    building.computeIfAbsent(entry.blockId(), k -> new TreeMap<>());

            Map<String, List<OreEntry>> byBiome =
                    byDimension.computeIfAbsent(entry.dimensionId(), k -> new TreeMap<>());

            if (entry.isDimensionWide()) {
                byBiome.computeIfAbsent(ANY_BIOME, k -> new ArrayList<>()).add(entry);
            } else {
                for (String biome : entry.biomeIds()) {
                    byBiome.computeIfAbsent(biome, k -> new ArrayList<>()).add(entry);
                }
            }
        }

        Map<String, OrePage> built = new LinkedHashMap<>(building.size());
        building.forEach((blockId, byDimension) -> {
            byDimension.values().forEach(byBiome ->
                    byBiome.values().forEach(list -> list.sort(ENTRY_ORDER)));
            built.put(blockId, new OrePage(blockId, byDimension));
        });

        pages = built;
    }

    public static void clear() {
        pages = Map.of();
    }

    public static Map<String, OrePage> pages() {
        return pages;
    }

    /**
     * Richest occurrences first, so the most useful line is the one a player sees without
     * scrolling: commonest vein, then largest share of it, then biggest.
     */
    private static final Comparator<OreEntry> ENTRY_ORDER =
            Comparator.comparingInt(OreEntry::spawnPermille).reversed()
                    .thenComparing(Comparator.comparingInt(OreEntry::sharePermille).reversed())
                    .thenComparing(Comparator.comparingInt(OreEntry::sizeBlocks).reversed())
                    .thenComparing(OreEntry::sourceId);

    /** Everything known about one ore block, indexed for the dimension and biome cyclers. */
    public record OrePage(String blockId, Map<String, Map<String, List<OreEntry>>> byDimension) {

        public List<String> dimensions() {
            return new ArrayList<>(byDimension.keySet());
        }

        /** Biome keys for a dimension, with {@link #ANY_BIOME} sorted first when present. */
        public List<String> biomes(String dimension) {
            Map<String, List<OreEntry>> byBiome = byDimension.get(dimension);
            if (byBiome == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>(new TreeSet<>(byBiome.keySet()));
            if (out.remove(ANY_BIOME)) {
                out.add(0, ANY_BIOME);
            }
            return out;
        }

        public List<OreEntry> entries(String dimension, String biome) {
            Map<String, List<OreEntry>> byBiome = byDimension.get(dimension);
            if (byBiome == null) {
                return List.of();
            }
            List<OreEntry> exact = byBiome.get(biome);
            return exact == null ? List.of() : exact;
        }

        /** Any entry, used to pick the icon and title for the page. */
        public OreEntry sample() {
            for (Map<String, List<OreEntry>> byBiome : byDimension.values()) {
                for (List<OreEntry> list : byBiome.values()) {
                    if (!list.isEmpty()) {
                        return list.get(0);
                    }
                }
            }
            return null;
        }
    }
}
