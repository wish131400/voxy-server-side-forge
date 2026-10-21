package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class PredictionGpuSelectionPolicyTest {
    private static Matrix4f view(double yaw) { return new Matrix4f().rotationY((float) yaw); }

    @Test void fixedSceneRequiresOnlyCapacityAndGpuSupport() {
        var policy = new PredictionGpuSelectionPolicy();
        assertTrue(policy.eligible(true, 16_384, 64));
        assertTrue(policy.eligible(true, 16_384, 64));
        assertFalse(policy.eligible(false, 16_384, 64));
        assertFalse(policy.eligible(true, 16_383, 64));
        assertFalse(policy.eligible(true, 16_384, 63));
        String previous = System.getProperty("vss.disableGpuPredictionSelection");
        try {
            System.setProperty("vss.disableGpuPredictionSelection", "true");
            assertFalse(policy.eligible(true, 16_384, 64));
        } finally {
            if (previous == null) System.clearProperty("vss.disableGpuPredictionSelection");
            else System.setProperty("vss.disableGpuPredictionSelection", previous);
        }
    }

    @Test void stableResidentsAreIndependentOfVisibleMembershipAndReleaseWithdrawnItems() {
        var plan = new PredictionVisiblePlan<Integer, net.minecraft.world.phys.AABB>(box -> box);
        var first = new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1);
        var second = new net.minecraft.world.phys.AABB(2, 0, 0, 3, 1, 1);
        plan.put(1, first); plan.put(2, second);
        var residents = plan.residents();
        plan.selectStable(Vec3.ZERO, view(0), new Matrix4f(), null, java.util.Set.of(1));
        assertSame(residents, plan.residents());
        plan.selectStable(Vec3.ZERO, view(.01), new Matrix4f(), null, java.util.Set.of(2));
        assertSame(residents, plan.residents());
        plan.remove(1);
        assertEquals(java.util.List.of(second), plan.residents());
        plan.clear(); assertTrue(plan.residents().isEmpty());
    }
}
