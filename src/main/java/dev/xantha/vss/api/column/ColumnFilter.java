package dev.xantha.vss.api.column;

/**
 * Called once for a whole source column, before compression and cache admission.
 * The input contains no live world/player objects. Do not block, generate terrain,
 * or retain mutable external state. Exceptions reject this column, never fall back
 * to sending the unfiltered data. A cache hit reuses the already filtered result.
 */
@FunctionalInterface
public interface ColumnFilter {
    ColumnDecision filter(ColumnInput column);
}
