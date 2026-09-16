package dev.xantha.vss.client.prediction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintBiomeCompatTest {
    @Test void preservesCapturedDimensionSeedsIncludingLongOverflow() {
        for (long seed : new long[]{0, 42, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (long modifier : new long[]{0, -123456789L, Long.MAX_VALUE}) {
                assertEquals(modifier, BlueprintBiomeCompat.dimensionModifier(seed,
                        seed + 1791510900L + modifier, seed - 771160217L + modifier));
            }
        }
        assertDoesNotThrow(() -> BlueprintBiomeCompat.dimensionModifier(0,
                2519338626306849043L, 2519338623744177926L));
    }

    @Test void rejectsInconsistentRoutingSeedsInsteadOfChangingSliceLocations() {
        assertThrows(IllegalArgumentException.class,
                () -> BlueprintBiomeCompat.dimensionModifier(42, 1791510942L, 0));
    }
}
