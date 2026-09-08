package com.platform.common.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The platform's generated row identity: what it is named, and that renaming it did not orphan the
 * rows already persisted under the old name.
 *
 * <p>The key was {@code row_id}, which reads like — and in three dev tables literally is — an
 * ordinary designer column. Anything holding a row then had to answer "is this value the row's
 * identity or a business value in a same-named column", and every consumer answered it differently
 * (hardcoded column names, UUID-shape tests), each wrong on some Function Unit.
 */
class SubTableRowIdentityKeyTest {

    private static final String LEGACY_FIELD = "row_id";

    @Test
    @DisplayName("the written key names the platform and the UUID, and cannot pass for a column")
    void canonicalFieldIsUnambiguous() {
        assertThat(SubTableRowIdentity.CANONICAL_FIELD).isEqualTo("platformRowUuid");
        assertThat(SubTableRowIdentity.CANONICAL_FIELD).isNotEqualTo(LEGACY_FIELD);
    }

    /**
     * A leading {@code __} marks a key as meta to the row sanitizers, which drop every such key but
     * {@code __subTables__} — an identity stripped before it is read would be worse than an
     * ambiguous one.
     */
    @Test
    @DisplayName("the key is not __-prefixed, so row sanitizers do not strip it")
    void canonicalFieldIsNotMetaPrefixed() {
        assertThat(SubTableRowIdentity.CANONICAL_FIELD).doesNotStartWith("__");
    }

    @Test
    @DisplayName("a row with no identity is stamped with a UUID under the new key")
    void ensureIdentityWritesUuidUnderNewKey() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "x");

        assertThat(SubTableRowIdentity.ensureIdentity(row)).isTrue();

        assertThat(row).containsKey(SubTableRowIdentity.CANONICAL_FIELD);
        assertThat(row).doesNotContainKey(LEGACY_FIELD);
        assertThatCode(() -> UUID.fromString(String.valueOf(row.get(SubTableRowIdentity.CANONICAL_FIELD))))
                .doesNotThrowAnyException();
    }

    /**
     * {@code row_id} is a designer column on real tables here (ATM_Transaction declares it as its
     * business primary key), so treating the name itself as an identity would read a business value
     * as a row key. Identity comes from configuration; a caller that holds the binding passes it.
     */
    @Test
    @DisplayName("a designer column is not an identity by name, only by configuration")
    void designerColumnIsNotAnIdentityByName() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(LEGACY_FIELD, "ATM-DC-PW-TRANS-000003");

        assertThat(SubTableRowIdentity.hasIdentity(row)).isFalse();
        assertThat(SubTableRowIdentity.identityFieldOf(row)).isNull();

        // Told that this table is keyed by that column, the same row resolves.
        assertThat(SubTableRowIdentity.identityFieldOf(row, List.of(LEGACY_FIELD)))
                .isEqualTo(LEGACY_FIELD);
    }

    @Test
    @DisplayName("the platform key outranks a configured designer key when a row carries both")
    void platformKeyOutranksConfiguredKey() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(LEGACY_FIELD, "business-value");
        row.put(SubTableRowIdentity.CANONICAL_FIELD, "generated-uuid");

        assertThat(SubTableRowIdentity.identityFieldOf(row, List.of(LEGACY_FIELD)))
                .isEqualTo(SubTableRowIdentity.CANONICAL_FIELD);
    }

    /** The SQL side is generated from the list, so it cannot drift from the Java. */
    @Test
    @DisplayName("SQL identity expression names the platform key and no guessed column")
    void sqlExpressionNamesOnlyThePlatformKey() {
        String sql = SubTableRowIdentity.sqlIdentityExpression("elem");

        assertThat(sql).contains("elem->>'" + SubTableRowIdentity.CANONICAL_FIELD + "'");
        assertThat(sql).doesNotContain("elem->>'" + LEGACY_FIELD + "'");
        assertThat(sql).doesNotContain("elem->>'id'");
    }
}
