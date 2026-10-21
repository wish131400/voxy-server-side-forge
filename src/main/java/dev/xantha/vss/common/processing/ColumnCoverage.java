package dev.xantha.vss.common.processing;

/**
 * Provenance of a column body. A filtered update owns only its explicit sections;
 * it must never certify full-column coverage or be stored in the ordinary cache.
 * The SHA-256 key identifies the ordered filter IDs/versions and isolates caches.
 */
public record ColumnCoverage(String filterKey) {
    public static final ColumnCoverage FULL = new ColumnCoverage("");

    public ColumnCoverage {
        if (filterKey == null || (!filterKey.isEmpty() && !filterKey.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Invalid column filter cache identity");
        }
    }

    public boolean isFiltered() { return !filterKey.isEmpty(); }
}
