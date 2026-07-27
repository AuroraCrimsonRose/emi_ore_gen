package com.catx.emioregen.server;

import java.util.List;
import java.util.Map;

/**
 * What a feature produces, for features that will not say.
 *
 * <p>Most mods either use {@code minecraft:ore} or at least serialise their target blocks, and
 * both of those are read directly from worldgen. A few serialise nothing at all: their
 * configured feature is literally <code>{"type": "...", "config": {}}</code> and everything is
 * decided in Java when the chunk generates. For those, the placement is still readable, which
 * gives real rarity and depth, but the resource itself has to come from somewhere.</p>
 *
 * <p>This is that somewhere, and it is deliberately the last resort. Every entry here is a
 * maintenance burden that breaks silently if the mod renames something, so it is only worth
 * adding for resources a player would actually go looking for.</p>
 */
final class OpaqueFeatures {

    /** Placed feature id to the resources it yields. */
    private static final Map<String, List<String>> YIELDS = Map.of(
            // The Factory Must Grow: bedrock-level crude oil, read from OilDepositFeature.
            // One in four chunks for a deposit, one in five hundred for a well.
            "tfmg:oil_deposit", List.of("tfmg:crude_oil"),
            "tfmg:oil_well", List.of("tfmg:crude_oil")
    );

    private OpaqueFeatures() {
    }

    /** Resources this feature is known to produce, or empty when it is not in the table. */
    static List<String> yieldsOf(String placedFeatureId) {
        return YIELDS.getOrDefault(placedFeatureId, List.of());
    }
}
