package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.i18n.I18nService;
import com.portal.component.OwnerFieldComponent.OwnerWriteContext;
import com.portal.exception.PortalException;
import com.portal.service.UserDisplayNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Owner write-path: Creator pins startUserId; Current Assignee follows the snapshot;
 * later submits must not turn Creator into the current actor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OwnerFieldComponent")
class OwnerFieldComponentTest {

    private static final String FU = "asset-register";
    private static final String START = "u-initiator";
    private static final String ACTOR = "u-approver";
    private static final String ASSIGNEE = "u-bob";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private UserDisplayNameResolver userDisplayNameResolver;

    @Mock
    private I18nService i18nService;

    @Mock
    private PortalPrimaryKeyAllocationComponent primaryKeyAllocationComponent;

    private OwnerFieldComponent component;

    private final List<String> formConfigs = new ArrayList<>();
    private final List<Object[]> bindingRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        component = new OwnerFieldComponent(
                jdbcTemplate, new ObjectMapper(), userDisplayNameResolver,
                i18nService, primaryKeyAllocationComponent);
        when(primaryKeyAllocationComponent.resolveFunctionUnitIdForAllocation(anyString()))
                .thenReturn(10L);
        // Matching a row to its previous version uses the identity its table declares. These
        // fixtures key rows by `row_id`, so that is their configured primary key — read from Table
        // Design, not assumed from the column's name.
        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(List.of("row_id"));
        when(i18nService.getMessage(anyString(), any(Object[].class))).thenAnswer(inv -> inv.getArgument(0));
        when(i18nService.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(userDisplayNameResolver.resolveIfExists(START)).thenReturn(Optional.of("Initiator"));
        when(userDisplayNameResolver.resolveIfExists(ACTOR)).thenReturn(Optional.of("Approver"));
        when(userDisplayNameResolver.resolveIfExists(ASSIGNEE)).thenReturn(Optional.of("Bob"));
        stubDisplayNameResolver();
        stubExistenceProbe(true);
        stubQueries();
        formConfigs.add("""
                {"rule":[
                  {"type":"owner","field":"case_owner","props":{"ownerConfig":"{\\"source\\":\\"CREATOR\\"}"}},
                  {"type":"owner","field":"current_handler","props":{"ownerConfig":"{\\"source\\":\\"CURRENT_ASSIGNEE\\"}"}}
                 ],
                 "subForms":{"64":{"rule":[
                   {"type":"owner","field":"row_owner","props":{"ownerConfig":"{\\"source\\":\\"CREATOR\\"}"}},
                   {"type":"owner","field":"row_handler","props":{"ownerConfig":"{\\"source\\":\\"CURRENT_ASSIGNEE\\"}"}}
                 ]}}}
                """);
        bindingRows.add(new Object[]{64L, "asset_items"});
    }

    @Nested
    @DisplayName("costs nothing when absent")
    class Absent {

        @Test
        @DisplayName("no owner field anywhere: variables untouched, no metadata query")
        void probeShortCircuits() {
            stubExistenceProbe(false);
            Map<String, Object> variables = new HashMap<>(Map.of("title", "Laptop"));

            component.applyOnSubmit(FU, startContext(), variables);

            assertThat(variables).isEqualTo(Map.of("title", "Laptop"));
            verify(primaryKeyAllocationComponent, never())
                    .resolveFunctionUnitIdForAllocation(anyString());
        }

        @Test
        @DisplayName("this FU has no owner field: variables untouched")
        void otherFunctionUnitHasThem() {
            formConfigs.clear();
            formConfigs.add("{\"rule\":[{\"type\":\"input\",\"field\":\"title\"}]}");
            Map<String, Object> variables = new HashMap<>(Map.of("title", "Laptop"));

            component.applyOnSubmit(FU, startContext(), variables);

            assertThat(variables).isEqualTo(Map.of("title", "Laptop"));
        }
    }

    @Nested
    @DisplayName("CREATOR")
    class Creator {

        @Test
        @DisplayName("empty main Creator is filled with startUserId, not the current actor")
        void fillsStartUserNotActor() {
            Map<String, Object> variables = new HashMap<>(Map.of("title", "Laptop"));

            component.applyOnSubmit(FU, approvalContext(null), variables);

            assertThat(variables.get("case_owner")).isEqualTo("user:" + START);
            assertThat(variables.get("case_owner__display")).isEqualTo("Initiator");
        }

        @Test
        @DisplayName("a later submit does not change Creator to the current approver")
        void laterSubmitKeepsInitiator() {
            Map<String, Object> previous = new HashMap<>();
            previous.put("case_owner", "user:" + START);
            previous.put("case_owner__display", "Old Name");
            Map<String, Object> variables = new HashMap<>(previous);
            variables.put("case_owner", "user:" + ACTOR);

            component.applyOnSubmit(FU, approvalContext(previous), variables);

            assertThat(variables.get("case_owner")).isEqualTo("user:" + START);
            assertThat(variables.get("case_owner__display")).isEqualTo("Initiator");
        }

        @Test
        @DisplayName("empty sub-table Creator is filled with the row creator")
        void fillsSubRowsWhenEmpty() {
            Map<String, Object> variables = variablesWithSubRows(
                    new HashMap<>(Map.of("qty", 1)),
                    new HashMap<>(Map.of("qty", 2, "row_owner", "user:" + START, "row_id", "r2")));
            Map<String, Object> previous = variablesWithSubRows(
                    new HashMap<>(Map.of("qty", 2, "row_owner", "user:" + START, "row_id", "r2")));

            component.applyOnSubmit(FU, new OwnerWriteContext(ACTOR, START, null, null, previous), variables);

            List<Map<String, Object>> rows = subRows(variables);
            assertThat(rows.get(0).get("row_owner")).isEqualTo("user:" + ACTOR);
            assertThat(rows.get(0).get("row_owner__display")).isEqualTo("Approver");
            assertThat(rows.get(1).get("row_owner")).isEqualTo("user:" + START);
            assertThat(rows.get(1).get("row_owner__display")).isEqualTo("Initiator");
        }

        @Test
        @DisplayName("unknown user is a validation error, not a silently stored id")
        void userNotFound() {
            when(userDisplayNameResolver.resolveIfExists("ghost")).thenReturn(Optional.empty());
            Map<String, Object> previous = new HashMap<>();
            previous.put("case_owner", "user:ghost");
            Map<String, Object> variables = new HashMap<>(previous);

            assertThatThrownBy(() -> component.applyOnSubmit(FU, approvalContext(previous), variables))
                    .isInstanceOf(PortalException.class)
                    .hasMessageContaining("portal.owner.user_not_found");
        }

        @Test
        @DisplayName("client-supplied __display is never trusted")
        void clientDisplayIgnored() {
            Map<String, Object> previous = new HashMap<>();
            previous.put("case_owner", "user:" + START);
            Map<String, Object> variables = new HashMap<>(previous);
            variables.put("case_owner__display", "FORGED NAME");

            component.applyOnSubmit(FU, approvalContext(previous), variables);

            assertThat(variables.get("case_owner__display")).isEqualTo("Initiator");
        }
    }

    @Nested
    @DisplayName("CURRENT_ASSIGNEE")
    class CurrentAssignee {

        @Test
        @DisplayName("submit overwrites Current Assignee from the snapshot")
        void submitOverwritesFromSnapshot() {
            Map<String, Object> variables = new HashMap<>();
            variables.put("current_handler", "user:forged");

            component.applyOnSubmit(FU, new OwnerWriteContext(ACTOR, START, ASSIGNEE, null, Map.of()), variables);

            assertThat(variables.get("current_handler")).isEqualTo("user:" + ASSIGNEE);
            assertThat(variables.get("current_handler__display")).isEqualTo("Name-" + ASSIGNEE);
        }

        @Test
        @DisplayName("a candidate pool writes comma-separated user: ids onto the Owner column")
        void poolWritesUserIdsOnOwnerColumn() {
            Map<String, Object> variables = new HashMap<>();

            component.applyAssigneeSnapshot(FU, variables, null, "u-a,u-b");

            assertThat(variables.get("current_handler")).isEqualTo("user:u-a,user:u-b");
            assertThat(variables.get("current_handler__display")).isEqualTo("Name-u-a, Name-u-b");
            assertThat(variables.get("case_owner")).isNull();
        }

        @Test
        @DisplayName("submit writes Current Assignee onto sub-table rows")
        void submitWritesSubCurrentAssignee() {
            Map<String, Object> variables = variablesWithSubRows(new HashMap<>(Map.of("qty", 1)));

            component.applyOnSubmit(FU, approvalContext(null), variables);

            assertThat(subRows(variables).get(0).get("row_handler")).isEqualTo("user:" + ASSIGNEE);
            assertThat(subRows(variables).get(0).get("row_handler__display")).isEqualTo("Name-" + ASSIGNEE);
        }

        @Test
        @DisplayName("applyAssigneeSnapshot writes Current Assignee onto existing sub-table rows")
        void snapshotWritesSubRows() {
            Map<String, Object> variables = variablesWithSubRows(new HashMap<>(Map.of("qty", 1)));

            component.applyAssigneeSnapshot(FU, variables, ASSIGNEE, null);

            assertThat(variables.get("current_handler")).isEqualTo("user:" + ASSIGNEE);
            assertThat(subRows(variables).get(0).get("row_handler")).isEqualTo("user:" + ASSIGNEE);
            assertThat(subRows(variables).get(0).get("row_owner")).isNull();
        }

        @Test
        @DisplayName("read projection refreshes display from stored Owner ids and does not overlay snapshot")
        void readDoesNotOverlaySnapshot() {
            Map<String, Object> variables = new HashMap<>();
            variables.put("current_handler", "user:" + ASSIGNEE);
            variables.put("current_handler__display", "Stale");

            component.projectForRead(FU,
                    new OwnerWriteContext(ACTOR, START, "u-other", "u-a,u-b", null), variables);

            assertThat(variables.get("current_handler")).isEqualTo("user:" + ASSIGNEE);
            assertThat(variables.get("current_handler__display")).isEqualTo("Name-" + ASSIGNEE);
        }

        @Test
        @DisplayName("parseStoredUserIds splits one or many user: tokens")
        void parseStoredUserIds() {
            assertThat(OwnerFieldComponent.parseStoredUserIds("user:u-a,user:u-b"))
                    .containsExactly("u-a", "u-b");
            assertThat(OwnerFieldComponent.parseStoredUserIds("user:" + ASSIGNEE))
                    .containsExactly(ASSIGNEE);
            assertThat(OwnerFieldComponent.parseStoredUserIds("group:bu|role")).isEmpty();
            assertThat(OwnerFieldComponent.parseStoredUserIds("")).isEmpty();
        }

        @Test
        @DisplayName("applyAssigneeSnapshot does not change Creator")
        void snapshotSkipsCreator() {
            Map<String, Object> variables = new HashMap<>();
            variables.put("case_owner", "user:" + START);
            variables.put("case_owner__display", "Initiator");

            component.applyAssigneeSnapshot(FU, variables, ASSIGNEE, null);

            assertThat(variables.get("case_owner")).isEqualTo("user:" + START);
            assertThat(variables.get("current_handler")).isEqualTo("user:" + ASSIGNEE);
        }
    }

    private OwnerWriteContext startContext() {
        return new OwnerWriteContext(START, START, null, null, null);
    }

    private OwnerWriteContext approvalContext(Map<String, Object> previous) {
        return new OwnerWriteContext(ACTOR, START, ASSIGNEE, null, previous);
    }

    @SuppressWarnings("unchecked")
    private void stubDisplayNameResolver() {
        when(userDisplayNameResolver.resolveBatch(any())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            Map<String, String> out = new HashMap<>();
            if (keys != null) {
                for (String key : keys) {
                    if (key != null) {
                        out.put(key, "Name-" + key);
                    }
                }
            }
            return out;
        });
        when(userDisplayNameResolver.resolveCached(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Map<String, String> cache = inv.getArgument(1);
            if (cache != null && cache.containsKey(key)) {
                return cache.get(key);
            }
            return "Name-" + key;
        });
        when(userDisplayNameResolver.collectAssigneeUserKeys(any(), any())).thenAnswer(inv -> {
            Set<String> keys = new LinkedHashSet<>();
            Object assignee = inv.getArgument(0);
            Object candidates = inv.getArgument(1);
            if (assignee instanceof String s && !s.isBlank()) {
                keys.add(s);
            }
            if (candidates instanceof String s && !s.isBlank()) {
                for (String part : s.split(",")) {
                    if (!part.isBlank()) {
                        keys.add(part.trim());
                    }
                }
            }
            return keys;
        });
        when(userDisplayNameResolver.resolveCurrentAssigneeDisplay(any(), any(), any())).thenAnswer(inv -> {
            Object assignee = inv.getArgument(0);
            if (assignee instanceof String s && !s.isBlank()) {
                return "Name-" + s;
            }
            Object candidates = inv.getArgument(1);
            return candidates instanceof String s ? s : null;
        });
    }

    private void stubExistenceProbe(boolean exists) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenReturn(exists);
    }

    @SuppressWarnings("unchecked")
    private void stubQueries() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    if (sql.contains("dw_form_definitions")) {
                        List<Object> mapped = new ArrayList<>();
                        for (String config : formConfigs) {
                            mapped.add(mapper.mapRow(stringResultSet(config), mapped.size()));
                        }
                        return mapped;
                    }
                    return List.of();
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (!sql.contains("dw_form_table_bindings")) {
                return null;
            }
            RowCallbackHandler handler = invocation.getArgument(1);
            for (Object[] row : bindingRows) {
                handler.processRow(bindingResultSet(row));
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    private ResultSet stringResultSet(String value) throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(value);
        return rs;
    }

    private ResultSet bindingResultSet(Object[] row) throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getLong("binding_id")).thenReturn((Long) row[0]);
        when(rs.getString("table_name")).thenReturn((String) row[1]);
        return rs;
    }

    @SafeVarargs
    private static Map<String, Object> variablesWithSubRows(Map<String, Object>... rows) {
        Map<String, Object> variables = new HashMap<>();
        Map<String, Object> slices = new LinkedHashMap<>();
        slices.put("64", new ArrayList<>(List.of(rows)));
        variables.put("__subTables__", slices);
        return variables;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> subRows(Map<String, Object> variables) {
        Map<String, Object> slices = (Map<String, Object>) variables.get("__subTables__");
        return (List<Map<String, Object>>) slices.get("64");
    }
}
