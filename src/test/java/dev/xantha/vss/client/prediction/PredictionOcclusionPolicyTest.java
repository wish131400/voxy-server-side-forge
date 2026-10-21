package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class PredictionOcclusionPolicyTest {
    @Test void visibleWorkloadAllowsComparisonDuringRemoteUpdatesButRealChangesResetIt() {
        var legacy = new PredictionOcclusionPolicy(); var local = new PredictionOcclusionPolicy(); var view = new Matrix4f();
        for (int frame=1; frame<=90; frame++) {
            boolean a=legacy.choose(view,0,0,0,256,256,frame,frame);
            if(legacy.sample())legacy.accept(legacy.epoch(),a,a?2_000_000:1_000_000);
            boolean b=local.choose(view,0,0,0,256,256,7,3);
            if(local.sample())local.accept(local.epoch(),b,b?2_000_000:1_000_000);
        }
        assertTrue(legacy.diagnostics().contains("measuring-hiz"));
        assertTrue(local.diagnostics().contains("selected-direct"));
        assertTrue(local.diagnostics().contains("occlusionComparisons=1"));
        long previous=local.epoch();local.choose(view,0,0,0,256,256,8,3);
        assertNotEquals(previous,local.epoch());local.accept(previous,false,1);
        assertTrue(local.diagnostics().contains("measuring-direct"));
        long next=local.epoch();local.choose(view,0,0,0,256,256,8,4);
        assertNotEquals(next,local.epoch(),"material changes also reset the measured workload");
    }
    @Test void selectsMeasuredWinnerRetestsAndRejectsStaleResults() {
        var policy = new PredictionOcclusionPolicy();
        var view = new Matrix4f();
        assertTrue(policy.choose(view, 0, 0, 0, 256, 256, 1, 1));
        long epoch = policy.epoch();
        for (int i = 0; i < 8; i++) policy.accept(epoch, true, 7_500_000);
        assertFalse(policy.choose(view, 0, 0, 0, 256, 256, 1, 1));
        for (int i = 0; i < 8; i++) policy.accept(epoch, false, 6_400_000);
        assertFalse(policy.choose(view, 0, 0, 0, 256, 256, 1, 1));
        policy.choose(view, 20, 0, 0, 256, 256, 1, 1);
        long next = policy.epoch(); assertNotEquals(epoch, next);
        for (int i = 0; i < 8; i++) policy.accept(epoch, false, 1);
        for (int i = 0; i < 8; i++) policy.accept(next, false, 8_000_000);
        assertTrue(policy.choose(view, 20, 0, 0, 256, 256, 1, 1));
        for (int i = 0; i < 8; i++) policy.accept(next, true, 4_000_000);
        assertTrue(policy.choose(view, 20, 0, 0, 256, 256, 1, 1));
        for (int i = 0; i < 481; i++) policy.choose(view, 20, 0, 0, 256, 256, 1, 1);
        assertNotEquals(next, policy.epoch(), "stable views must eventually reconsider the other path");
    }

    @Test void smallTimingDifferencesKeepTheWinner() {
        var policy = new PredictionOcclusionPolicy(); var view = new Matrix4f();
        policy.choose(view, 0, 0, 0, 256, 256, 1, 1);
        long epoch = policy.epoch();
        for (int i = 0; i < 8; i++) policy.accept(epoch, true, 5_100_000);
        for (int i = 0; i < 8; i++) policy.accept(epoch, false, 5_000_000);
        assertTrue(policy.choose(view, 0, 0, 0, 256, 256, 1, 1));
    }
}
