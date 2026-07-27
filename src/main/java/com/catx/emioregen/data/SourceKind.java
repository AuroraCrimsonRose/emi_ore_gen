package com.catx.emioregen.data;

/** Which worldgen system an {@link OreEntry} was extracted from. */
public enum SourceKind {
    /** A vanilla-style {@code PlacedFeature} backed by an {@code OreConfiguration}. */
    FEATURE("feature"),
    /** A GregTech CEu ore vein from the {@code gtceu:ore_vein} registry. */
    GT_VEIN("gt_vein"),
    /** A GregTech CEu bedrock ore deposit, mined by the Bedrock Ore Miner. */
    GT_BEDROCK_ORE("gt_bedrock_ore"),
    /** A GregTech CEu bedrock fluid reservoir, tapped by the Fluid Rig. */
    GT_BEDROCK_FLUID("gt_bedrock_fluid"),
    /** A GregTech CEu surface rock, scattered above the vein it advertises. */
    GT_SURFACE_ROCK("gt_surface_rock"),
    /** An Immersive Petroleum underground reservoir, tapped by a pumpjack. */
    IP_RESERVOIR("ip_reservoir"),
    /** An Immersive Engineering mineral deposit, worked by an excavator. */
    IE_MINERAL("ie_mineral"),
    /** A fluid deposit placed by a worldgen feature, such as TFMG's oil. */
    FLUID_DEPOSIT("fluid_deposit");

    private final String key;

    SourceKind(String key) {
        this.key = key;
    }

    /** Suffix for the {@code emioregeneration.source.<key>} translation key. */
    public String translationKey() {
        return "emioregeneration.source." + key;
    }
}
