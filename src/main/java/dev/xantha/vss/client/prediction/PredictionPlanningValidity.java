package dev.xantha.vss.client.prediction;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;

/**
 * 后台规划结果的纯校验边界，不缓存版本，也不发布状态。
 * <p>调用方在客户端线程提供当前标量、已发布快照身份和 Voxy 上传证据。不同通道保留
 * 原有精度：普通计划允许小幅移动，加载统计要求相同居民/目标/视角，避免旧结果恢复就绪。
 */
final class PredictionPlanningValidity {
    private PredictionPlanningValidity() { }

    record Current(VssLodLayout layout, PredictionWorkView view, VssLodFocus focus,
                   double x, double y, double z, int settings, long revision, long removalRevision,
                   long loadingVersion, boolean closed, boolean paused) {
        boolean active() { return !closed && !paused; }
        boolean noViewCut(PredictionWorkView requested) { return view == null || !view.cutFrom(requested); }
    }

    static boolean view(PredictionDemandPreparation.Input input, Current current, Set<PredictionTileKey> desired) {
        return current.active() && input.layout().equals(current.layout()) && input.desired().equals(desired)
                && Objects.equals(input.focus(), current.focus()) && current.noViewCut(input.view());
    }

    static boolean plan(PredictionPlanPreparation.Result result, Current current, Predicate<PredictionTileKey> uploaded) {
        var input = result.input();
        if (!current.active() || !input.layout().equals(current.layout()) || input.settings() != current.settings()
                || input.revision() != current.revision() || input.removalRevision() != current.removalRevision()
                || !Objects.equals(input.focus(), current.focus())
                || Math.hypot(input.x() - current.x(), input.z() - current.z()) > 128
                || Math.abs(input.y() - current.y()) > 128 || !current.noViewCut(input.view())) return false;
        int radius = PredictionVoxyOwnership.radiusBlocks(input.voxyRadius());
        for (var key : result.owned()) {
            int span = current.layout().tileBlocks(key.lod()), margin = input.previousOwned().contains(key) ? 32 : 64;
            double x = key.tileX() * (double) span, z = key.tileZ() * (double) span;
            if (!PredictionVoxyOwnership.containsBox(current.x(), current.y(), current.z(), Math.max(0, radius - margin),
                    x, input.minY(), z, x + span, input.maxY(), z + span) || !uploaded.test(key)) return false;
        }
        return true;
    }

    static boolean schedule(PredictionSchedulePreparation.Input input, Current current, List<PredictionTileKey> plan) {
        return current.active() && input.layout().equals(current.layout()) && input.revision() == current.revision()
                && input.plan() == plan && Objects.equals(input.focus(), current.focus()) && current.noViewCut(input.view());
    }

    static boolean loading(PredictionLoadingPreparation.Input request, Current current,
            List<PredictionTileKey> plan, RenderSnapshot residents, RenderSnapshot drawable,
            Map<PredictionTileKey, Integer> bands) {
        var input = request.scheduling();
        return schedule(input, current, plan) && request.stateVersion() == current.loadingVersion()
                && input.residents() == residents && request.drawable() == drawable && input.bands() == bands
                && (current.view() == null || !current.view().changedFrom(input.view()));
    }
}
