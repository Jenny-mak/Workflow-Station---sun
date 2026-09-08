package com.platform.common.jdbc;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubTableRowIdentityTest {

    private Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void highestPriorityKeyWins() {
        assertThat(SubTableRowIdentity.identityFieldOf(row("id", 9, SubTableRowIdentity.CANONICAL_FIELD, "abc"))).isEqualTo(SubTableRowIdentity.CANONICAL_FIELD);
        assertThat(SubTableRowIdentity.identityOf(row("id", 9, SubTableRowIdentity.CANONICAL_FIELD, "abc"))).isEqualTo(SubTableRowIdentity.CANONICAL_FIELD + "=abc");
    }

    @Test
    void identityCarriesTheFieldNameSoDifferentKeysWithEqualValuesDiffer() {
        assertThat(SubTableRowIdentity.identityOf(row(SubTableRowIdentity.CANONICAL_FIELD, 7)))
                .isNotEqualTo(SubTableRowIdentity.identityOf(row("id", 7)));
    }

    @Test
    void keyLookupIgnoresCaseButRowIdAndRowIdUnderscoreStayDistinct() {
        assertThat(SubTableRowIdentity.identityOf(row(SubTableRowIdentity.CANONICAL_FIELD.toUpperCase(), "x")))
                .isEqualTo(SubTableRowIdentity.CANONICAL_FIELD + "=x");
        // Designer columns are not identity keys, whatever they are called: which column identifies
        // a row is configuration, supplied by callers that hold the binding.
        assertThat(SubTableRowIdentity.identityFieldOf(row("rowId", "x"))).isNull();
        assertThat(SubTableRowIdentity.identityFieldOf(row("id", "x"))).isNull();
        // …and it IS found when the caller passes the configured key.
        assertThat(SubTableRowIdentity.identityFieldOf(row("rowId", "x"), java.util.List.of("rowId")))
                .isEqualTo("rowId");
    }

    @Test
    void blankAndNullValuesDoNotIdentifyAnything() {
        assertThat(SubTableRowIdentity.hasIdentity(row(SubTableRowIdentity.CANONICAL_FIELD, "   "))).isFalse();
        assertThat(SubTableRowIdentity.hasIdentity(row(SubTableRowIdentity.CANONICAL_FIELD, null))).isFalse();
        assertThat(SubTableRowIdentity.hasIdentity(row("name", "no key here"))).isFalse();
        assertThat(SubTableRowIdentity.identityOf(row("name", "no key here"))).isNull();
        assertThat(SubTableRowIdentity.hasIdentity(null)).isFalse();
    }

    @Test
    void identityValuesCollectEveryKeySoPartialRecordsOfTheSameRowStillMatch() {
        // `id` is a business column unless configuration says otherwise, so only the platform key
        // is collected here…
        assertThat(SubTableRowIdentity.identityValuesOf(
                row(SubTableRowIdentity.CANONICAL_FIELD, "a", "id", 9, "name", "x")))
                .containsExactly("a");
        // …and both are collected once the caller supplies this table's configured key.
        assertThat(SubTableRowIdentity.identityValuesOf(
                row(SubTableRowIdentity.CANONICAL_FIELD, "a", "id", 9, "name", "x"),
                java.util.List.of("id")))
                .containsExactly("a", "9");
        assertThat(SubTableRowIdentity.identityValuesOf(row("name", "x"))).isEmpty();
        assertThat(SubTableRowIdentity.identityValuesOf(null)).isEmpty();
    }

    @Test
    void ensureIdentityAssignsRowIdOnlyWhenTheRowHasNone() {
        Map<String, Object> anonymous = row("name", "x");
        assertThat(SubTableRowIdentity.ensureIdentity(anonymous)).isTrue();
        assertThat(String.valueOf(anonymous.get(SubTableRowIdentity.CANONICAL_FIELD))).isNotBlank();

        // A row already carrying the platform key keeps it, untouched.
        Map<String, Object> identified = row(SubTableRowIdentity.CANONICAL_FIELD, "existing-uuid");
        assertThat(SubTableRowIdentity.ensureIdentity(identified)).isFalse();
        assertThat(identified.get(SubTableRowIdentity.CANONICAL_FIELD)).isEqualTo("existing-uuid");

        // A designer column is not an identity to this class — it has no binding in scope to know
        // whether `id_idw` is that table's key — so the row still gets the platform key, and the
        // designer's own value is left exactly as it was.
        Map<String, Object> businessKeyOnly = row("id_idw", 42);
        assertThat(SubTableRowIdentity.ensureIdentity(businessKeyOnly)).isTrue();
        assertThat(businessKeyOnly).containsKey(SubTableRowIdentity.CANONICAL_FIELD);
        assertThat(businessKeyOnly.get("id_idw")).isEqualTo(42);
    }

    @Test
    void generatedIdentitiesAreUniquePerRow() {
        Map<String, Object> first = row("name", "same");
        Map<String, Object> second = row("name", "same");
        SubTableRowIdentity.ensureIdentity(first);
        SubTableRowIdentity.ensureIdentity(second);
        // Two rows with identical content are two rows — this is exactly what content
        // hashing would have merged.
        assertThat(first.get(SubTableRowIdentity.CANONICAL_FIELD)).isNotEqualTo(second.get(SubTableRowIdentity.CANONICAL_FIELD));
    }

    @Test
    void theSqlExpressionKeepsTheSamePriorityAsTheJavaLookup() {
        String sql = SubTableRowIdentity.sqlIdentityExpression("elem");

        // Derived from the one list, in the one order — SQL that de-duplicates rows and Java that
        // compares them must not be able to disagree about which key wins.
        //
        // Built from IDENTITY_FIELDS rather than spelled out: a literal asserts today's key names,
        // not the property under test, so renaming or trimming a key failed this test even when SQL
        // and Java still agreed perfectly.
        String expected = SubTableRowIdentity.IDENTITY_FIELDS.stream()
                .map(field -> "elem->>'" + field + "'")
                .collect(java.util.stream.Collectors.joining(", ", "COALESCE(", ")"));
        assertThat(sql).isEqualTo(expected);
    }
}
