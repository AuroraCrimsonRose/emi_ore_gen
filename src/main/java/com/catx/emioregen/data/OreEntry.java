package com.catx.emioregen.data;

import java.util.List;

/**
 * One ore (or fluid) occurrence, as extracted from worldgen on the logical server
 * and shipped to the client for display.
 *
 * <p>Percentages are stored as permille (0-1000) so the display layer can decide how to round
 * them. The UI rounds to the nearest 5% but shows "&lt;5%" rather than "0%" for anything
 * that is rare but non-zero.</p>
 *
 * @param blockId          registry id of the block/item shown as the page subject
 * @param sourceId         registry id of the placed feature or GT vein this came from
 * @param kind             which system produced this entry
 * @param dimensionId      registry id of the dimension, e.g. {@code minecraft:overworld}
 * @param biomeIds         biomes this occurrence applies to; empty means "everywhere in the dimension"
 * @param minY             lowest Y this can generate at
 * @param maxY             highest Y this can generate at
 * @param meanY            midpoint of the generation band, or the mode for triangle distributions
 * @param worldMinY        build limit floor of the dimension, for graph scaling
 * @param worldMaxY        build limit ceiling of the dimension, for graph scaling
 * @param sizeBlocks       cluster/vein size in blocks; -1 when unknown
 * @param spawnPermille    likelihood this vein/feature is present, 0-1000
 * @param sharePermille    this ore's share of the vein it belongs to, 0-1000
 * @param veinName         human-facing vein name, or empty for standalone features
 * @param indicatorBlockId surface indicator block (GT surface rock / dust pile), or empty
 * @param dropIds          what mining this actually yields, resolved from the block's loot table
 */
public record OreEntry(
        String blockId,
        String sourceId,
        SourceKind kind,
        String dimensionId,
        List<String> biomeIds,
        int minY,
        int maxY,
        int meanY,
        int worldMinY,
        int worldMaxY,
        int sizeBlocks,
        int spawnPermille,
        int sharePermille,
        String veinName,
        String indicatorBlockId,
        List<String> dropIds
) {

    /** Copy carrying resolved loot-table drops. Kept separate so extraction stays loot-agnostic. */
    public OreEntry withDrops(List<String> drops) {
        return new OreEntry(blockId, sourceId, kind, dimensionId, biomeIds, minY, maxY, meanY,
                worldMinY, worldMaxY, sizeBlocks, spawnPermille, sharePermille, veinName,
                indicatorBlockId, drops);
    }

    /** True when this entry describes a fluid reservoir rather than a solid ore. */
    public boolean isFluid() {
        return kind == SourceKind.GT_BEDROCK_FLUID;
    }

    /**
     * True when this sits on the surface rather than at a depth. Surface rocks follow the
     * terrain, so a Y range for them would be a number about the landscape, not about the ore.
     */
    public boolean isSurface() {
        return kind == SourceKind.GT_SURFACE_ROCK;
    }

    /** True when this entry is one component of a multi-ore vein. */
    public boolean isPartOfVein() {
        return !veinName.isEmpty();
    }

    public SizeClass sizeClass() {
        return SizeClass.of(sizeBlocks);
    }

    /**
     * Applies to every biome in its dimension. GregTech veins with no biome filter and
     * vanilla features that appear in all of a dimension's biomes both end up here.
     */
    public boolean isDimensionWide() {
        return biomeIds.isEmpty();
    }
}
