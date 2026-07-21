package com.catx.emioregen.data;

/**
 * Coarse size bucket for a vein or ore cluster, so the UI can say "Large" instead of
 * printing a raw block count that means little without context.
 *
 * <p>Thresholds are in blocks and were chosen against GregTech's own vein sizes, where a
 * typical small vein is around 16 blocks across and the largest run past 64.</p>
 */
public enum SizeClass {
    UNKNOWN("unknown", 0),
    TINY("tiny", 8),
    SMALL("small", 16),
    MEDIUM("medium", 32),
    LARGE("large", 64),
    HUGE("huge", Integer.MAX_VALUE);

    private final String key;
    private final int upperBound;

    SizeClass(String key, int upperBound) {
        this.key = key;
        this.upperBound = upperBound;
    }

    public static SizeClass of(int sizeBlocks) {
        if (sizeBlocks < 0) {
            return UNKNOWN;
        }
        for (SizeClass c : values()) {
            if (c != UNKNOWN && sizeBlocks <= c.upperBound) {
                return c;
            }
        }
        return HUGE;
    }

    public String translationKey() {
        return "emioregeneration.size." + key;
    }

    /** ARGB colour used for the size pip in the recipe UI. */
    public int color() {
        return switch (this) {
            case TINY -> 0xFF7F8C8D;
            case SMALL -> 0xFF3498DB;
            case MEDIUM -> 0xFF2ECC71;
            case LARGE -> 0xFFF39C12;
            case HUGE -> 0xFFE74C3C;
            case UNKNOWN -> 0xFF555555;
        };
    }
}
