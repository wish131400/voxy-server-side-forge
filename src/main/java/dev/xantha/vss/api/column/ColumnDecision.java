package dev.xantha.vss.api.column;

import java.util.Map;
import java.util.Objects;

/**
 * One immutable decision for the whole column. Missing entries use defaultDecision;
 * section Y is a signed section coordinate (block Y >> 4), never a block coordinate.
 * Overrides outside the supplied dimension range reject the column.
 */
public record ColumnDecision(SectionDecision defaultDecision, Map<Integer, SectionDecision> sections) {
    public ColumnDecision {
        Objects.requireNonNull(defaultDecision, "defaultDecision");
        sections = Map.copyOf(sections);
    }

    public static ColumnDecision updateAll() {
        return new ColumnDecision(SectionDecision.UPDATE, Map.of());
    }

    public SectionDecision section(int sectionY) {
        return sections.getOrDefault(sectionY, defaultDecision);
    }
}
