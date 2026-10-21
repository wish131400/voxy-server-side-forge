package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.api.column.ColumnFilters;
import dev.xantha.vss.common.processing.LoadedColumnData;
import org.junit.jupiter.api.Test;

class ColumnFilterNoProviderTest {
    @Test void unregisteredPipelineReturnsSourceWithoutDecodingOrNeedingContext() {
        assertTrue(ColumnFilters.snapshot().isEmpty());
        // Not a serialized section: parsing it would fail. With no providers,
        // neither the existing decoder nor the new height scan should run.
        var source = new LoadedColumnData(0, 0, new byte[] {(byte) 0xFF}, 1, true);
        assertSame(source, ColumnFilterPipeline.apply(null, source));
        assertNull(ColumnFilterPipeline.apply(null, null));
    }
}
