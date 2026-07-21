package com.catx.emioregen;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common config. Kept deliberately small: the mod's job is to report what worldgen already
 * says, so most behaviour is not something a player should need to tune.
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue INCLUDE_GREGTECH = BUILDER
            .comment("Index GregTech CEu ore veins, bedrock ore deposits and bedrock fluid reservoirs.",
                    "GregTech ships its own EMI ore vein category, so turn this off if you would",
                    "rather not see the same veins listed twice.")
            .define("includeGregTech", true);

    public static final ModConfigSpec.BooleanValue INCLUDE_BEDROCK_FLUIDS = BUILDER
            .comment("Index GregTech bedrock fluid reservoirs alongside solid ores.")
            .define("includeBedrockFluids", true);

    public static final ModConfigSpec.BooleanValue SHOW_SURFACE_INDICATORS = BUILDER
            .comment("Show the GregTech surface rock that marks a vein, next to that vein's entry.")
            .define("showSurfaceIndicators", true);

    public static final ModConfigSpec.BooleanValue OWN_VEIN_DIAGRAM = BUILDER
            .comment("Show ore veins using this mod's own diagram, styled to match its other",
                    "pages, and hide GregTech's. Turn this off to keep GregTech's version.")
            .define("useOwnVeinDiagram", true);

    public static final ModConfigSpec.IntValue MAX_ENTRIES_PER_PAGE = BUILDER
            .comment("How many separate occurrences to list at once before paging.")
            .defineInRange("maxEntriesPerPage", 3, 1, 8);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
