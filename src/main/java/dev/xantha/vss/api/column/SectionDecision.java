package dev.xantha.vss.api.column;

import java.util.Objects;

/** A whole 16^3 section decision; UPDATE is replacement, not a per-voxel patch. */
public record SectionDecision(Action action, LightMode lightMode) {
    public static final SectionDecision UPDATE = new SectionDecision(Action.UPDATE, LightMode.KEEP);
    public static final SectionDecision CLEAR = new SectionDecision(Action.CLEAR, LightMode.NONE);
    public static final SectionDecision UNTOUCHED = new SectionDecision(Action.UNTOUCHED, LightMode.KEEP);

    public SectionDecision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(lightMode, "lightMode");
    }

    public enum Action {
        /** Send the real source section, including confirmed source air. */
        UPDATE,
        /** Explicitly replace this section's blocks with air. */
        CLEAR,
        /** Send nothing and leave the client's existing Voxy section unchanged. */
        UNTOUCHED
    }

    public enum LightMode {
        KEEP, SKY_ONLY, NONE
    }

    /** Conservative, order-independent composition of multiple registered filters. */
    SectionDecision intersect(SectionDecision other) {
        Action combined = action == Action.UNTOUCHED || other.action == Action.UNTOUCHED
                ? Action.UNTOUCHED : action == Action.CLEAR || other.action == Action.CLEAR
                ? Action.CLEAR : Action.UPDATE;
        LightMode light = lightMode.ordinal() >= other.lightMode.ordinal() ? lightMode : other.lightMode;
        return new SectionDecision(combined, light);
    }
}
