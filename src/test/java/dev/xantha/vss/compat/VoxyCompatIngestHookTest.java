package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoxyCompatIngestHookTest {
    @Test
    void onlyVerifiedAppliedHooksCanReportIngestCompletion() {
        assertFalse(VoxyCompat.hasProcessJobCompletionHook(VoidWorker.class));
        assertTrue(VoxyCompat.hasProcessJobCompletionHook(VerifiedWorker.class));
        assertFalse(VoxyCompat.hasProcessJobCompletionHook(UnreadyWorker.class));
        assertFalse(VoxyCompat.hasProcessJobCompletionHook(RenamedWorker.class));
        assertFalse(VoxyCompat.hasProcessJobCompletionHook(ReturningWorker.class));
        assertFalse(VoxyCompat.hasProcessJobCompletionHook(ArgWorker.class));
    }

    public static final class VerifiedWorker {
        public static boolean vss$ingestTrackingReady() { return true; }
    }

    public static final class UnreadyWorker {
        public static boolean vss$ingestTrackingReady() { return false; }
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
