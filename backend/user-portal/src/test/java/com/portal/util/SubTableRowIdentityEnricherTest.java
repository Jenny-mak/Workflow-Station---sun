package com.portal.util;

import com.platform.common.jdbc.SubTableRowIdentity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubTableRowIdentityEnricherTest {

    private Map<String, Object> variablesWith(Object subTables) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("applicant", "user-1");
        variables.put("__subTables__", subTables);
        return variables;
    }

    private Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return row;
    }

    private Map<String, Object> slices(String key, List<Map<String, Object>> rows) {
        Map<String, Object> subTables = new LinkedHashMap<>();
        subTables.put(key, rows);
        return subTables;
    }

    /**
     * Every row without the platform key gets one — including a row that already carries a designer
     * column such as {@code id_idw}.
     *
     * <p>This used to assert the opposite, because {@code id_idw} was one of several likely column
     * names treated as an identity. That is exactly the guess this class no longer makes: a column
     * called {@code id_idw} (or {@code id}, or {@code row_id}) is business data whose meaning comes
     * from Table Design, and this enricher has no binding in scope to consult. Stamping the
     * platform key is harmless — the designer's own value is untouched, and code that DOES know the
     * binding still resolves identity from the configured primary key.
     */
    @Test
    void everyRowWithoutThePlatformKeyGetsOneAndDesignerValuesAreUntouched() {
        Map<String, Object> anonymous = row("card_number", "4111", "merchant_name", "ACME");
        Map<String, Object> withDesignerColumn = row("id_idw", 5001, "card_number", "4222");
        Map<String, Object> variables =
                variablesWith(slices("50533", List.of(anonymous, withDesignerColumn)));

        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(variables)).isEqualTo(2);
        assertThat(String.valueOf(anonymous.get(SubTableRowIdentity.CANONICAL_FIELD))).isNotBlank();
        assertThat(String.valueOf(withDesignerColumn.get(SubTableRowIdentity.CANONICAL_FIELD)))
                .isNotBlank();
        // The designer's column keeps its value — the platform key is added beside it, not over it.
        assertThat(withDesignerColumn.get("id_idw")).isEqualTo(5001);
    }

    /** A row that already carries the platform key is left exactly as it is. */
    @Test
    void rowsThatAlreadyCarryThePlatformKeyAreLeftAlone() {
        Map<String, Object> identified =
                row(SubTableRowIdentity.CANONICAL_FIELD, "existing-uuid", "card_number", "4333");
        Map<String, Object> variables = variablesWith(slices("50533", List.of(identified)));

        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(variables)).isZero();
        assertThat(identified.get(SubTableRowIdentity.CANONICAL_FIELD)).isEqualTo("existing-uuid");
    }

    @Test
    void twoRowsWithIdenticalBusinessValuesGetDistinctIdentities() {
        Map<String, Object> first = row("amount", 100);
        Map<String, Object> second = row("amount", 100);
        Map<String, Object> variables = variablesWith(slices("b1", List.of(first, second)));

        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(variables)).isEqualTo(2);
        assertThat(first.get(SubTableRowIdentity.CANONICAL_FIELD)).isNotEqualTo(second.get(SubTableRowIdentity.CANONICAL_FIELD));
    }

    @Test
    void theOneAllowedLevelOfNestedSubTablesIsCoveredToo() {
        Map<String, Object> child = row("line", "a");
        Map<String, Object> parent = row("header", "h");
        parent.put("__subTables__", slices("child-binding", List.of(child)));
        Map<String, Object> variables = variablesWith(slices("parent-binding", List.of(parent)));

        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(variables)).isEqualTo(2);
        assertThat(String.valueOf(child.get(SubTableRowIdentity.CANONICAL_FIELD))).isNotBlank();
    }

    @Test
    void runningTwiceAssignsNothingNew() {
        Map<String, Object> variables = variablesWith(slices("b1", List.of(row("amount", 1))));
        SubTableRowIdentityEnricher.ensureRowIdentities(variables);
        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(variables)).isZero();
    }

    @Test
    void aliasCopiesDoNotGetNewUuidsWhenANumericSliceExists() {
        Map<String, Object> canonical = row("channel", "Email");
        Map<String, Object> aliasCopy = row("channel", "Email");
        Map<String, Object> subTables = new LinkedHashMap<>();
        subTables.put("1301", List.of(canonical));
        subTables.put("ACQ Correspondence", List.of(aliasCopy));
        Map<String, Object> variables = variablesWith(subTables);

        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(variables)).isEqualTo(1);
        assertThat(String.valueOf(canonical.get(SubTableRowIdentity.CANONICAL_FIELD))).isNotBlank();
        assertThat(aliasCopy).doesNotContainKey(SubTableRowIdentity.CANONICAL_FIELD);
    }

    @Test
    void nameOnlySlicesStillReceiveIdentityWhenNoNumericBindingExists() {
        Map<String, Object> anonymous = row("channel", "Email");
        Map<String, Object> variables = variablesWith(slices("ACQ Correspondence", List.of(anonymous)));

        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(variables)).isEqualTo(1);
        assertThat(String.valueOf(anonymous.get(SubTableRowIdentity.CANONICAL_FIELD))).isNotBlank();
    }

    @Test
    void payloadsWithoutSubTablesAreUntouched() {
        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(null)).isZero();
        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(new LinkedHashMap<>())).isZero();
        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(variablesWith(new LinkedHashMap<>()))).isZero();
        // A slice holding something other than a row list is left for the sanitizer/validator
        // to reject rather than silently reshaped here.
        assertThat(SubTableRowIdentityEnricher.ensureRowIdentities(variablesWith(slices("b1", null)))).isZero();
    }

}
