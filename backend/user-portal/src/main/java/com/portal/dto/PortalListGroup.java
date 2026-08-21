package com.portal.dto;

/**
 * One group of a whole list result set. {@code count} is {@code COUNT(*) GROUP BY} over the same
 * predicate the page was drawn from — never the number of rows on the current page.
 *
 * <p>{@code label} may be null when the grouped cell is empty; the frontend still requires
 * {@code count} to be present (it is a primitive here so Jackson cannot omit it).
 */
public record PortalListGroup(String label, long count) {

    public PortalListGroup {
        if (count < 0) {
            throw new IllegalArgumentException("group count cannot be negative");
        }
    }
}
