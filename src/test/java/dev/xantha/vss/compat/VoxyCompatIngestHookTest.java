package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoxyCompatIngestHookTest {
    @Test
    void onlyVoidNoArgWorkerCanReportIngestCompletion() {
        assertTrue(VoxyCompat.hasProcessJobCompletionHook(VoidWorker.class));
        assertFalse(VoxyCompat.hasProcessJobCompletionHook(RenamedWorker.class));
        assertFalse(VoxyCompat.hasProcessJobCompletionHook(ReturningWorker.class));
        assertFalse(VoxyCompat.hasProcessJobCompletionHook(ArgWorker.class));
    }

    private static final class VoidWorker {
        private void processJob() {
        }
    }

    private static final class RenamedWorker {
        private void ingestWorker() {
        }
    }

    private static final class ReturningWorker {
        private boolean processJob() {
            return true;
        }
    }

    private static final class ArgWorker {
        private void processJob(int budget) {
        }
    }
}
