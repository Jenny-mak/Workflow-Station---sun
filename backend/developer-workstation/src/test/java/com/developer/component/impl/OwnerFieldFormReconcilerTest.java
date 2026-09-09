package com.developer.component.impl;

import com.developer.entity.FieldDefinition;
import com.developer.entity.FormDefinition;
import com.developer.entity.FormTableBinding;
import com.developer.entity.TableDefinition;
import com.developer.enums.BindingType;
import com.developer.enums.DataType;
import com.developer.enums.TableType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.FieldDefinitionRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormTableBindingRepository;
import com.developer.repository.TableDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.i18n.I18nService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Owner-field save reconciliation (docs/design/owner-field-component.md §3.4):
 * multiple Owners per table are allowed; the column must already exist as VARCHAR;
 * same field across forms must share source; CURRENT_ASSIGNEE is allowed on MAIN and SUB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OwnerFieldFormReconciler")
class OwnerFieldFormReconcilerTest {

    private static final long FU_ID = 7L;
    private static final long MAIN_TABLE_ID = 100L;
    private static final long SUB_TABLE_ID = 200L;
    private static final long SUB_BINDING_ID = 64L;

    @Mock
    private FormDefinitionRepository formDefinitionRepository;

    @Mock
    private FormTableBindingRepository formTableBindingRepository;

    @Mock
    private TableDefinitionRepository tableDefinitionRepository;

    @Mock
    private FieldDefinitionRepository fieldDefinitionRepository;

    @Mock
    private I18nService i18nService;

    private OwnerFieldFormReconciler reconciler;

    private TableDefinition mainTable;
    private TableDefinition subTable;
    private FormDefinition currentForm;

    @BeforeEach
    void setUp() {
        reconciler = new OwnerFieldFormReconciler(
                formDefinitionRepository, formTableBindingRepository,
                tableDefinitionRepository, fieldDefinitionRepository,
                new ObjectMapper(), i18nService);
        when(i18nService.getMessage(anyString(), any(Object[].class))).thenAnswer(inv -> inv.getArgument(0));
        when(i18nService.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));

        mainTable = TableDefinition.builder()
                .id(MAIN_TABLE_ID).tableName("asset_main").tableType(TableType.MAIN).build();
        subTable = TableDefinition.builder()
                .id(SUB_TABLE_ID).tableName("asset_items").tableType(TableType.SUB).build();
        currentForm = FormDefinition.builder().id(1L).boundTable(mainTable).build();

        when(formDefinitionRepository.findByFunctionUnitIdWithBindings(FU_ID))
                .thenReturn(List.of(currentForm));
        when(formTableBindingRepository.findByFormIdWithTable(anyLong())).thenReturn(List.of(
                FormTableBinding.builder().id(1L).bindingType(BindingType.PRIMARY).table(mainTable).build()));
        when(formTableBindingRepository.findByIdWithTable(SUB_BINDING_ID)).thenReturn(
                java.util.Optional.of(
                        FormTableBinding.builder().id(SUB_BINDING_ID).bindingType(BindingType.SUB).table(subTable).build()));
        when(tableDefinitionRepository.findById(MAIN_TABLE_ID)).thenReturn(java.util.Optional.of(mainTable));
        when(tableDefinitionRepository.findById(SUB_TABLE_ID)).thenReturn(java.util.Optional.of(subTable));
        stubColumns(MAIN_TABLE_ID, varchar("case_owner"), varchar("current_handler"), varchar("status"));
        stubColumns(SUB_TABLE_ID, varchar("row_owner"));
    }

    @Nested
    @DisplayName("existing columns")
    class ExistingColumns {

        @Test
        @DisplayName("binding Owner to an existing VARCHAR column succeeds and never inserts")
        void existingVarcharOk() {
            assertThatCode(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithMainOwner("case_owner", "{\"source\":\"CREATOR\"}")))
                    .doesNotThrowAnyException();
            verify(fieldDefinitionRepository, never()).save(any());
        }

        @Test
        @DisplayName("two Owner fields on the same table (different columns) are allowed")
        void multipleOwnersOnTable() {
            Map<String, Object> config = new HashMap<>();
            config.put("rule", List.of(
                    ownerNode("case_owner", "{\"source\":\"CREATOR\"}"),
                    ownerNode("current_handler", "{\"source\":\"CASE_HANDLER\"}")));

            assertThatCode(() -> reconciler.reconcile(FU_ID, currentForm, config))
                    .doesNotThrowAnyException();
            verify(fieldDefinitionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a config without owner fields is a no-op")
        void noOwnerNoWork() {
            reconciler.reconcile(FU_ID, currentForm,
                    Map.of("rule", List.of(Map.of("type", "input", "field", "title"))));

            verify(formDefinitionRepository, never()).findByFunctionUnitIdWithBindings(anyLong());
            verify(fieldDefinitionRepository, never()).save(any());
        }

        @Test
        @DisplayName("sub-form Creator on an existing VARCHAR column succeeds")
        void subCreatorOk() {
            assertThatCode(() -> reconciler.reconcile(FU_ID, currentForm, configWithSubOwner("row_owner")))
                    .doesNotThrowAnyException();
            verify(fieldDefinitionRepository, never()).save(any());
        }

        @Test
        @DisplayName("CASE_HANDLER on a sub-table form succeeds")
        void subCaseHandlerOk() {
            assertThatCode(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithSubOwner("row_owner", "{\"source\":\"CASE_HANDLER\"}")))
                    .doesNotThrowAnyException();
            verify(fieldDefinitionRepository, never()).save(any());
        }

        @Test
        @DisplayName("legacy CURRENT_ASSIGNEE is the same source as CASE_HANDLER")
        void legacyAssigneeAliasMatchesCaseHandler() {
            FormDefinition otherForm = FormDefinition.builder().id(2L).boundTable(mainTable).build();
            otherForm.setConfigJson(configWithMainOwner("current_handler", "{\"source\":\"CURRENT_ASSIGNEE\"}"));
            when(formDefinitionRepository.findByFunctionUnitIdWithBindings(FU_ID))
                    .thenReturn(List.of(currentForm, otherForm));

            assertThatCode(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithMainOwner("current_handler", "{\"source\":\"CASE_HANDLER\"}")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("legacy allowGroup-only config defaults to CREATOR")
        void legacyAllowGroupDefaultsCreator() {
            assertThatCode(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithMainOwner("case_owner", "{\"allowGroup\":true}")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("rule violations")
    class Violations {

        @Test
        @DisplayName("the same field twice on one form is rejected")
        void duplicateFieldOnForm() {
            Map<String, Object> config = new HashMap<>();
            config.put("rule", List.of(
                    ownerNode("case_owner", "{\"source\":\"CREATOR\"}"),
                    ownerNode("case_owner", "{\"source\":\"CREATOR\"}")));

            assertThatThrownBy(() -> reconciler.reconcile(FU_ID, currentForm, config))
                    .isInstanceOf(DeveloperBusinessException.class)
                    .hasMessageContaining("form.owner.duplicate");
        }

        @Test
        @DisplayName("same column with different source across forms is rejected")
        void crossFormSourceConflict() {
            FormDefinition otherForm = FormDefinition.builder().id(2L).boundTable(mainTable).build();
            otherForm.setConfigJson(configWithMainOwner("current_handler", "{\"source\":\"CURRENT_ASSIGNEE\"}"));
            when(formDefinitionRepository.findByFunctionUnitIdWithBindings(FU_ID))
                    .thenReturn(List.of(currentForm, otherForm));

            assertThatThrownBy(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithMainOwner("current_handler", "{\"source\":\"CREATOR\"}")))
                    .isInstanceOf(DeveloperBusinessException.class)
                    .hasMessageContaining("form.owner.cross_form_conflict");
        }

        @Test
        @DisplayName("a missing column fails the save and is not provisioned")
        void missingColumn() {
            assertThatThrownBy(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithMainOwner("ghost_owner", "{\"source\":\"CREATOR\"}")))
                    .isInstanceOf(DeveloperBusinessException.class)
                    .hasMessageContaining("form.owner.column_missing");
            verify(fieldDefinitionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a PK / computed / audit / non-VARCHAR column cannot be Owner")
        void invalidColumn() {
            stubColumns(MAIN_TABLE_ID, FieldDefinition.builder()
                    .fieldName("id").dataType(DataType.VARCHAR).isPrimaryKey(true).build());

            assertThatThrownBy(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithMainOwner("id", "{\"source\":\"CREATOR\"}")))
                    .isInstanceOf(DeveloperBusinessException.class)
                    .hasMessageContaining("form.owner.column_invalid");
        }

        @Test
        @DisplayName("invalid ownerConfig JSON fails the save")
        void invalidConfig() {
            assertThatThrownBy(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithMainOwner("case_owner", "{not json")))
                    .isInstanceOf(DeveloperBusinessException.class)
                    .hasMessageContaining("form.owner.config_invalid");
        }

        @Test
        @DisplayName("unknown source fails the save; CURRENT_HANDLER is no longer an alias")
        void unknownSource() {
            assertThatThrownBy(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithMainOwner("case_owner", "{\"source\":\"TEAM\"}")))
                    .isInstanceOf(DeveloperBusinessException.class)
                    .hasMessageContaining("form.owner.config_invalid");

            assertThatThrownBy(() -> reconciler.reconcile(FU_ID, currentForm,
                    configWithMainOwner("case_owner", "{\"source\":\"CURRENT_HANDLER\"}")))
                    .isInstanceOf(DeveloperBusinessException.class)
                    .hasMessageContaining("form.owner.config_invalid");
        }

        @Test
        @DisplayName("an owner on an unbound form fails the save")
        void unboundForm() {
            FormDefinition unbound = FormDefinition.builder().id(3L).build();
            when(formTableBindingRepository.findByFormIdWithTable(3L)).thenReturn(List.of());

            assertThatThrownBy(() -> reconciler.reconcile(FU_ID, unbound,
                    configWithMainOwner("case_owner", null)))
                    .isInstanceOf(DeveloperBusinessException.class)
                    .hasMessageContaining("form.owner.unbound");
        }
    }

    private void stubColumns(long tableId, FieldDefinition... columns) {
        when(fieldDefinitionRepository.findByTableDefinitionIdOrderBySortOrderAsc(tableId))
                .thenReturn(List.of(columns));
    }

    private static FieldDefinition varchar(String name) {
        return FieldDefinition.builder()
                .fieldName(name)
                .dataType(DataType.VARCHAR)
                .isPrimaryKey(false)
                .isComputed(false)
                .build();
    }

    private static Map<String, Object> configWithMainOwner(String field, String ownerConfig) {
        Map<String, Object> config = new HashMap<>();
        config.put("rule", new java.util.ArrayList<>(List.of(ownerNode(field, ownerConfig))));
        return config;
    }

    private Map<String, Object> configWithSubOwner(String field) {
        return configWithSubOwner(field, null);
    }

    private Map<String, Object> configWithSubOwner(String field, String ownerConfig) {
        Map<String, Object> config = new HashMap<>();
        config.put("rule", List.of());
        config.put("subForms", Map.of(
                String.valueOf(SUB_BINDING_ID), Map.of("rule", List.of(ownerNode(field, ownerConfig)))));
        return config;
    }

    private static Map<String, Object> ownerNode(String field, String ownerConfig) {
        Map<String, Object> node = new HashMap<>();
        node.put("type", "owner");
        node.put("field", field);
        node.put("title", "Owner");
        if (ownerConfig != null) {
            node.put("props", Map.of("ownerConfig", ownerConfig));
        }
        return node;
    }
}
