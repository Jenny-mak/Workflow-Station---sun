package com.admin.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.jdbc.SubTableRowIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A binding whose table declares no primary key must still report a row-identity column, so runtime
 * code can tell two rows apart without inventing a rule of its own.
 *
 * <p>Most tables are in that state — measured 13 of 24 in dev, including PRIMARY (main) bindings and
 * every RELATED one, since the PK subquery only reads {@code dw_field_definitions}. The platform
 * already gives those rows an identity: {@link SubTableRowIdentity#ensureIdentity} stamps
 * {@code row_id = UUID.randomUUID()} on any sub-table row persisted without one. Reporting "no
 * primary key" to the client contradicted that, and every consumer needing row identity filled the
 * gap by guessing — hardcoded {@code 'id'} / {@code 'row_id'} column names, or "does this value look
 * like a UUID" shape tests. Each of those silently answers wrong on some Function Unit: the shape
 * test in particular reads a real {@code prefixedSequence} key such as {@code Corr-000004} as "not a
 * primary key".
 *
 * <p>A designer-declared PK always wins, so the 11 tables that configure one are unaffected.
 */
class FormTableBindingLoaderRowIdentityFallbackTest {

    private static List<String> resolve(List<String> designerPrimaryKeyFields) throws Exception {
        return resolve(designerPrimaryKeyFields, java.util.Set.of());
    }

    private static List<String> resolve(
            List<String> designerPrimaryKeyFields, java.util.Set<String> designerFieldNames)
            throws Exception {
        FormTableBindingLoader loader = new FormTableBindingLoader(null, new ObjectMapper());
        Method m = FormTableBindingLoader.class.getDeclaredMethod(
                "resolveRowIdentityFields", List.class, java.util.Set.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> out = (List<String>) m.invoke(loader, designerPrimaryKeyFields, designerFieldNames);
        return out;
    }

    @Test
    @DisplayName("designer primary key wins untouched")
    void designerPrimaryKeyWins() throws Exception {
        assertThat(resolve(List.of("correspondence_id"))).containsExactly("correspondence_id");
    }

    @Test
    @DisplayName("composite designer primary key is preserved in order")
    void compositePrimaryKeyPreserved() throws Exception {
        assertThat(resolve(List.of("case_number", "line_no")))
                .containsExactly("case_number", "line_no");
    }

    @Test
    @DisplayName("no designer primary key falls back to the platform row identity")
    void noPrimaryKeyFallsBackToRowIdentity() throws Exception {
        assertThat(resolve(List.of())).containsExactly(SubTableRowIdentity.CANONICAL_FIELD);
    }

    @Test
    @DisplayName("a NULL primary key column (the SQL subquery's empty result) falls back too")
    void nullPrimaryKeyFallsBackToRowIdentity() throws Exception {
        assertThat(resolve(null)).containsExactly(SubTableRowIdentity.CANONICAL_FIELD);
    }

    /**
     * A designer field named like the platform key, and declared as the primary key, is the normal
     * case (three tables in dev do exactly this) — it must simply be honoured as the PK.
     */
    @Test
    @DisplayName("a designer PK that happens to share the platform key's name is honoured")
    void designerPkNamedLikePlatformKeyIsHonoured() throws Exception {
        assertThat(resolve(
                List.of(SubTableRowIdentity.CANONICAL_FIELD),
                java.util.Set.of(SubTableRowIdentity.CANONICAL_FIELD, "amount")))
                .containsExactly(SubTableRowIdentity.CANONICAL_FIELD);
    }

    /**
     * The dangerous shape: a BUSINESS column shares the platform key's name while the table declares
     * no primary key. The generated UUID would be indistinguishable from that column's own values,
     * so no identity is reported rather than keying rows by an unrelated business column.
     */
    @Test
    @DisplayName("a non-PK business column shadowing the platform key yields no identity")
    void businessColumnShadowingPlatformKeyYieldsNoIdentity() throws Exception {
        assertThat(resolve(List.of(), java.util.Set.of(SubTableRowIdentity.CANONICAL_FIELD, "amount")))
                .isEmpty();
    }

    /**
     * The fallback must name the column the write side actually stamps, or the two halves drift
     * apart again — the very mismatch this fixes.
     */
    @Test
    @DisplayName("the fallback column is the one ensureIdentity writes")
    void fallbackMatchesWhatEnsureIdentityWrites() throws Exception {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("name", "x");

        assertThat(SubTableRowIdentity.ensureIdentity(row)).isTrue();

        String stamped = resolve(null).get(0);
        assertThat(row).containsKey(stamped);

        // The identity is a generated UUID, not a business value the designer supplied — that is
        // what makes it safe to key rows by on a table with no primary key.
        assertThatCode(() -> java.util.UUID.fromString(String.valueOf(row.get(stamped))))
                .doesNotThrowAnyException();
    }
}
