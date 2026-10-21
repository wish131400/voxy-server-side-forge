package dev.xantha.vss.client.prediction;

import java.lang.reflect.Field;
import java.util.Map;

/** Access to planning fixtures lives here, rather than preserving obsolete manager fields in production. */
final class PredictionPlanningTestSupport {
    private static final Map<String, String> LANES = Map.of(
            "planPreparation", "plan", "demandPreparation", "view",
            "workPreparation", "schedule", "loadingPreparation", "loading");

    private PredictionPlanningTestSupport() { }

    static Object get(Object owner, String name) throws ReflectiveOperationException {
        if (owner instanceof PredictionTileManager manager) {
            var pipeline = (PredictionPlanningPipeline) field(manager, "planning").get(manager);
            if (LANES.containsKey(name)) {
                var lane = field(pipeline, LANES.get(name)).get(pipeline);
                return field(lane, "preparation").get(lane);
            }
            for (var entry : Map.of("plan", pipeline.plan, "demand", pipeline.view,
                    "schedule", pipeline.schedule, "loading", pipeline.loading).entrySet()) {
                if (name.equals(entry.getKey() + "Jobs")) return entry.getValue().jobs();
                if (name.equals(entry.getKey() + "Commits")) return entry.getValue().commits();
                if (name.equals(entry.getKey() + "Discards")) return entry.getValue().discards();
            }
        }
        return field(owner, name).get(owner);
    }

    static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
        if (owner instanceof PredictionTileManager manager && LANES.containsKey(name)) {
            var pipeline = get(manager, "planning");
            var lane = field(pipeline, LANES.get(name)).get(pipeline);
            field(lane, "preparation").set(lane, value);
            return;
        }
        field(owner, name).set(owner, value);
    }

    static PredictionPlanPreparation.Result buildPlan(PredictionPlanPreparation.Input input) {
        return PredictionPlanPreparation.build(input, ClientPredictionState.planningCoverage(input.dimension(), input.now()));
    }

    static Object paths(PredictionTileManager manager) throws ReflectiveOperationException {
        var pipeline = (PredictionPlanningPipeline) get(manager, "planning");
        return field(pipeline.viewCalculation, "paths").get(pipeline.viewCalculation);
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
