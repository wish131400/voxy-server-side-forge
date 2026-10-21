package dev.xantha.vss.api;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VSSApiDispatchTest {
    private List<VoxelColumnConsumer> consumers;
    private List<VoxelColumnProcessingConsumer> processors;
    private List<VoxelColumnConsumer> oldConsumers;
    private List<VoxelColumnProcessingConsumer> oldProcessors;
    @BeforeEach void isolateConsumers() throws Exception {
        consumers = list("COLUMN_CONSUMERS");
        processors = list("PROCESSING_COLUMN_CONSUMERS");
        oldConsumers = new ArrayList<>(consumers);
        oldProcessors = new ArrayList<>(processors);
        consumers.clear();
        processors.clear();
    }
    @AfterEach void restoreConsumers() {
        consumers.clear(); consumers.addAll(oldConsumers);
        processors.clear(); processors.addAll(oldProcessors);
    }
    @SuppressWarnings("unchecked") private static <T> List<T> list(String name) throws Exception {
        Field field = VSSApi.class.getDeclaredField(name);
        field.setAccessible(true);
        return (List<T>) field.get(null);
    }
    private boolean dispatch() { return VSSApi.dispatchColumnAndReport(null, null, 0, 0,
            new VoxelColumnData(null, 10L)); }

    @Test void noConsumerDoesNotReportAColumnAsAccepted() { assertFalse(dispatch()); }

    @Test void successRequiresEveryInvokedConsumerToAccept() {
        var calls = new ArrayList<String>();
        VSSApi.registerColumnConsumer((level, dimension, x, z, data) -> calls.add("observer"));
        VSSApi.registerColumnProcessingConsumer((level, dimension, x, z, data) -> { calls.add("first"); return false; });
        VSSApi.registerColumnProcessingConsumer((level, dimension, x, z, data) -> { calls.add("second"); return true; });
        assertFalse(dispatch());
        assertEquals(List.of("observer", "first", "second"), calls);
    }

    @Test void oneThrowingConsumerCannotBlockOtherConsumers() {
        var calls = new ArrayList<String>();
        VSSApi.registerColumnConsumer((level, dimension, x, z, data) -> { throw new IllegalStateException("fixture"); });
        VSSApi.registerColumnConsumer((level, dimension, x, z, data) -> calls.add("next"));
        VSSApi.registerColumnProcessingConsumer((level, dimension, x, z, data) -> { calls.add("processor"); return true; });
        assertFalse(dispatch());
        assertEquals(List.of("next", "processor"), calls);
    }

    @Test void acceptingProcessingConsumerReportsSuccess() {
        VSSApi.registerColumnProcessingConsumer((level, dimension, x, z, data) -> true);
        assertTrue(dispatch());
    }
}
