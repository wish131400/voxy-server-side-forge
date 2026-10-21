package dev.xantha.vss.api.column;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.common.processing.ColumnCoverage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColumnFiltersTest {
    private static final AtomicInteger calls = new AtomicInteger();
    @BeforeAll static void register() {
        dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();
        assertThrows(IllegalArgumentException.class, () -> ColumnFilters.register("invalid", 1, c -> null));
        assertThrows(IllegalArgumentException.class, () -> ColumnFilters.register("test:bad", 0, c -> null));
        ColumnFilters.register("test:z", 2, c -> {
            calls.incrementAndGet();
            if (c.chunkX() == 1) return null;
            if (c.chunkX() == 2) throw new IllegalStateException("provider failure");
            if (c.chunkX() == 3) return new ColumnDecision(SectionDecision.UPDATE, Map.of(4, SectionDecision.CLEAR));
            return new ColumnDecision(SectionDecision.UPDATE,
                    Map.of(0, SectionDecision.CLEAR, 1, new SectionDecision(SectionDecision.Action.UPDATE, SectionDecision.LightMode.SKY_ONLY)));
        });
        assertThrows(IllegalArgumentException.class, () -> ColumnFilters.register("test:z", 3, c -> null));
        ColumnFilters.register("test:a", 1, c -> {
            calls.incrementAndGet();
            return new ColumnDecision(SectionDecision.UPDATE, Map.of(0, SectionDecision.UNTOUCHED));
        });
    }

    private static ColumnInput input(int x) {
        return new ColumnInput(Level.OVERWORLD, x, 0, 0, 2, List.of(
                new ColumnInput.Section(0, false, null, null), new ColumnInput.Section(1, true, null, null)));
    }

    @Test void wholeColumnCallbacksComposeConservatively() {
        int before = calls.get();
        ColumnDecision result = ColumnFilters.snapshot().decide(input(0));
        assertEquals(2, calls.get() - before);
        assertEquals(SectionDecision.Action.UNTOUCHED, result.section(0).action());
        assertEquals(SectionDecision.LightMode.SKY_ONLY, result.section(1).lightMode());
    }

    @Test void policyIdentityIncludesSortedIdsAndVersionsAndFreezesRegistration() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                "vss-column-filter-api-1\ntest:a=1\ntest:z=2\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, ColumnFilters.snapshot().cacheKey());
        assertSame(ColumnFilters.snapshot(), ColumnFilters.snapshot());
        assertThrows(IllegalStateException.class, () -> ColumnFilters.register("test:late", 1, c -> ColumnDecision.updateAll()));
    }

    @Test void badDecisionsRejectTheColumnInsteadOfReturningFullData() {
        for (int x = 1; x <= 3; x++) {
            int selected = x;
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> ColumnFilters.snapshot().decide(input(selected)));
            assertTrue(error.getMessage().contains("test:z"));
        }
    }

    @Test void callerCannotMutateTheSnapshotOrDecisions() {
        byte[] light = new byte[2048]; light[0] = 7;
        var section = new ColumnInput.Section(0, false, light, null);
        light[0] = 42; section.blockLight()[0] = 99;
        assertEquals(7, section.blockLight()[0]);
        assertThrows(UnsupportedOperationException.class, () -> input(0).sections().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> new ColumnDecision(SectionDecision.UPDATE, Map.of()).sections().put(0, SectionDecision.CLEAR));
    }

    @Test void malformedInputsAndCoverageKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ColumnInput.Section(0, true, new byte[4], null));
        assertThrows(IllegalArgumentException.class, () -> new ColumnInput(Level.OVERWORLD, 0, 0, 0, 2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ColumnCoverage("legacy-full"));
        assertFalse(ColumnCoverage.FULL.isFiltered());
    }
}
