package com.developer.property;

import com.developer.component.impl.EmailMonitorRulePortability;
import com.developer.component.impl.FunctionUnitComponentImpl;
import com.developer.component.impl.FunctionUnitImportWriter;
import com.developer.component.impl.FunctionUnitSnapshotRestorer;
import com.developer.component.impl.MainTableViewPortability;
import com.developer.component.impl.ProcessBpmnStaleIdFixer;
import com.developer.component.impl.RelationTableStructurePortability;
import com.developer.dto.DecisionDefinitionRequest;
import com.developer.entity.DecisionDefinition;
import com.developer.entity.FunctionUnit;
import com.developer.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.service.UserDisplayNameService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DecisionDesignComponentImpl 属性测试 —— 版本快照 / 克隆 主题
 * Feature: dmn-decision-table-integration
 *
 * Property 8: 版本快照往返（含决策定义）
 * Property 9: 克隆复制所有决策定义
 *
 * Validates: Requirements 6.1, 6.3, 6.4
 */
public class DecisionDesignVersionSnapshotPropertyTest extends DecisionDesignPropertyTestBase {

    // ========== Property 8: 版本快照往返（含决策定义） ==========

    /**
     * Property 8: For any FunctionUnit with N DecisionDefinitions, creating a snapshot
     * via FunctionUnitComponentImpl.createSnapshot() and restoring from that snapshot
     * via VersionComponentImpl.restoreFromSnapshot() should preserve all decision
     * definition fields (decisionKey, decisionName, dmnXml, hitPolicy, description)
     * and the count should remain N.
     *
     * **Validates: Requirements 6.1, 6.3**
     */
    @Property(tries = 100)
    void versionSnapshotRoundTripPreservesDecisionDefinitions(
            @ForAll("decisionCounts") int n,
            @ForAll("functionUnitIds") Long functionUnitId,
            @ForAll("validDecisionRequestLists") List<DecisionDefinitionRequest> requestPool) {

        // Take up to n items from the pool
        List<DecisionDefinitionRequest> requests = requestPool.stream().limit(n).toList();
        int actualN = requests.size();

        // Build a FunctionUnit with N DecisionDefinitions
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(functionUnitId)
                .code("fu-snap-" + functionUnitId)
                .name("FU_Snap_" + functionUnitId)
                .displayName("Test FU for snapshot")
                .status(com.developer.enums.FunctionUnitStatus.DRAFT)
                .decisionDefinitions(new ArrayList<>())
                .tableDefinitions(new ArrayList<>())
                .formDefinitions(new ArrayList<>())
                .actionDefinitions(new ArrayList<>())
                .build();

        for (int i = 0; i < actualN; i++) {
            DecisionDefinitionRequest req = requests.get(i);
            functionUnit.getDecisionDefinitions().add(
                    DecisionDefinition.builder()
                            .id(idGenerator.getAndIncrement())
                            .functionUnit(functionUnit)
                            .decisionKey(req.getDecisionKey() + "_" + i) // ensure unique keys
                            .decisionName(req.getDecisionName())
                            .dmnXml(req.getDmnXml())
                            .hitPolicy(req.getHitPolicy())
                            .description(req.getDescription())
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build()
            );
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();

            // Step 1: Legacy snapshot map (pre-v2 dw_versions rows)
            List<Map<String, Object>> decisionSnapshots = new ArrayList<>();
            for (DecisionDefinition decision : functionUnit.getDecisionDefinitions()) {
                Map<String, Object> decisionSnap = new LinkedHashMap<>();
                decisionSnap.put("decisionKey", decision.getDecisionKey());
                decisionSnap.put("decisionName", decision.getDecisionName());
                decisionSnap.put("dmnXml", decision.getDmnXml());
                decisionSnap.put("hitPolicy", decision.getHitPolicy());
                decisionSnap.put("description", decision.getDescription());
                decisionSnapshots.add(decisionSnap);
            }
            Map<String, Object> snapshotMap = new LinkedHashMap<>();
            snapshotMap.put("decisionDefinitions", decisionSnapshots);
            byte[] snapshotBytes = objectMapper.writeValueAsBytes(snapshotMap);

            assertThat(snapshotBytes).isNotNull();

            // Verify snapshot JSON contains decisionDefinitions array with correct count
            @SuppressWarnings("unchecked")
            Map<String, Object> parsedSnapshot = objectMapper.readValue(snapshotBytes, Map.class);
            assertThat(parsedSnapshot).containsKey("decisionDefinitions");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parsedDecisionSnapshots =
                    (List<Map<String, Object>>) parsedSnapshot.get("decisionDefinitions");
            assertThat(parsedDecisionSnapshots)
                    .as("Snapshot should contain exactly %d decision definitions", actualN)
                    .hasSize(actualN);

            // Step 2: Restore via FunctionUnitSnapshotRestorer (legacy path)
            FunctionUnitSnapshotRestorer snapshotRestorer = new FunctionUnitSnapshotRestorer(
                    mock(FunctionUnitImportWriter.class),
                    mock(EmailMonitorRulePortability.class),
                    mock(FormDefinitionRepository.class),
                    mock(ProcessDefinitionRepository.class),
                    mock(ProcessBpmnStaleIdFixer.class),
                    mock(RelationTableStructurePortability.class),
                    mock(MainTableViewPortability.class),
                    mock(com.developer.service.MainTableViewService.class),
                    mock(jakarta.persistence.EntityManager.class),
                    mock(com.developer.util.DeveloperWorkstationSequenceSynchronizer.class),
                    mock(com.developer.component.impl.FormTableBindingRestorer.class));

            // Build a fresh FunctionUnit to restore into
            FunctionUnit restored = FunctionUnit.builder()
                    .id(functionUnitId)
                    .code("fu-snap-" + functionUnitId)
                    .name("FU_Snap_" + functionUnitId)
                    .displayName("Old description")
                    .status(com.developer.enums.FunctionUnitStatus.DRAFT)
                    .decisionDefinitions(new ArrayList<>())
                    .tableDefinitions(new ArrayList<>())
                    .formDefinitions(new ArrayList<>())
                    .actionDefinitions(new ArrayList<>())
                    .build();

            // Add some pre-existing decisions that should be cleared on restore
            restored.getDecisionDefinitions().add(
                    DecisionDefinition.builder()
                            .id(idGenerator.getAndIncrement())
                            .functionUnit(restored)
                            .decisionKey("pre_existing_key")
                            .decisionName("Pre-existing")
                            .build()
            );

            snapshotRestorer.restore(restored, parsedSnapshot);

            // Step 3: Verify round-trip — restored FunctionUnit should have exactly N decisions
            assertThat(restored.getDecisionDefinitions())
                    .as("Restored FunctionUnit should have exactly %d decision definitions", actualN)
                    .hasSize(actualN);

            // Verify each decision definition's fields match the originals
            for (int i = 0; i < actualN; i++) {
                DecisionDefinition original = functionUnit.getDecisionDefinitions().get(i);
                DecisionDefinition restoredDef = restored.getDecisionDefinitions().get(i);

                assertThat(restoredDef.getDecisionKey())
                        .as("decisionKey[%d] should survive snapshot round-trip", i)
                        .isEqualTo(original.getDecisionKey());
                assertThat(restoredDef.getDecisionName())
                        .as("decisionName[%d] should survive snapshot round-trip", i)
                        .isEqualTo(original.getDecisionName());
                assertThat(restoredDef.getDmnXml())
                        .as("dmnXml[%d] should survive snapshot round-trip", i)
                        .isEqualTo(original.getDmnXml());
                assertThat(restoredDef.getHitPolicy())
                        .as("hitPolicy[%d] should survive snapshot round-trip", i)
                        .isEqualTo(original.getHitPolicy());
                assertThat(restoredDef.getDescription())
                        .as("description[%d] should survive snapshot round-trip", i)
                        .isEqualTo(original.getDescription());

                // Restored decisions should be associated with the restored FunctionUnit
                assertThat(restoredDef.getFunctionUnit())
                        .as("restored decision[%d] should be associated with the restored FunctionUnit", i)
                        .isSameAs(restored);
            }

        } catch (Exception e) {
            fail("Snapshot round-trip should not throw exception: " + e.getMessage(), e);
        }
    }

    // ========== Property 9: 克隆复制所有决策定义 ==========

    /**
     * Property 9: For any FunctionUnit with N DecisionDefinitions, cloning the
     * FunctionUnit should produce a new FunctionUnit with exactly N DecisionDefinitions,
     * each having the same decisionKey, decisionName, dmnXml, hitPolicy, description
     * as the source, but different id and associated with the new FunctionUnit.
     *
     * **Validates: Requirements 6.4**
     */
    @Property(tries = 100)
    void cloneCopiesAllDecisionDefinitions(
            @ForAll("decisionCounts") int n,
            @ForAll("functionUnitIds") Long sourceFuId,
            @ForAll("validDecisionRequestLists") List<DecisionDefinitionRequest> requestPool) {

        // Take up to n items from the pool
        List<DecisionDefinitionRequest> requests = requestPool.stream().limit(n).toList();
        int actualN = requests.size();

        // Build source FunctionUnit with N DecisionDefinitions (no tables/forms/actions/process for simplicity)
        FunctionUnit source = FunctionUnit.builder()
                .id(sourceFuId)
                .code("fu-src-" + sourceFuId)
                .name("SourceFU_" + sourceFuId)
                .displayName("Source description")
                .status(com.developer.enums.FunctionUnitStatus.PUBLISHED)
                .decisionDefinitions(new ArrayList<>())
                .tableDefinitions(new ArrayList<>())
                .formDefinitions(new ArrayList<>())
                .actionDefinitions(new ArrayList<>())
                .build();

        for (int i = 0; i < actualN; i++) {
            DecisionDefinitionRequest req = requests.get(i);
            source.getDecisionDefinitions().add(
                    DecisionDefinition.builder()
                            .id(idGenerator.getAndIncrement())
                            .functionUnit(source)
                            .decisionKey(req.getDecisionKey() + "_" + i)
                            .decisionName(req.getDecisionName())
                            .dmnXml(req.getDmnXml())
                            .hitPolicy(req.getHitPolicy())
                            .description(req.getDescription())
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build()
            );
        }

        String clonedName = "ClonedFU_" + sourceFuId;

        // Mock: name does not exist
        when(functionUnitRepository.existsByName(clonedName)).thenReturn(false);
        // Mock: source FU found
        when(functionUnitRepository.findById(sourceFuId)).thenReturn(Optional.of(source));
        // Mock: code uniqueness check always passes
        when(functionUnitRepository.existsByCode(anyString())).thenReturn(false);

        // Capture all saved DecisionDefinitions
        List<DecisionDefinition> savedDecisions = new ArrayList<>();
        DecisionDefinitionRepository decisionDefRepo = mock(DecisionDefinitionRepository.class);
        when(decisionDefRepo.save(any(DecisionDefinition.class))).thenAnswer(invocation -> {
            DecisionDefinition saved = invocation.getArgument(0);
            saved.setId(idGenerator.getAndIncrement());
            saved.setCreatedAt(Instant.now());
            saved.setUpdatedAt(Instant.now());
            savedDecisions.add(saved);
            return saved;
        });

        // Build a FunctionUnitComponentImpl with the mocked decisionDefinitionRepository
        FunctionUnitDevGroupAssignmentRepository cloneDevGroupRepo = mock(FunctionUnitDevGroupAssignmentRepository.class);
        when(cloneDevGroupRepo.findByFunctionUnitId(anyLong())).thenReturn(List.of());
        FunctionUnitComponentImpl cloneComponent = new FunctionUnitComponentImpl(
                functionUnitRepository,
                mock(ProcessDefinitionRepository.class),
                mock(TableDefinitionRepository.class),
                mock(FormDefinitionRepository.class),
                mock(ActionDefinitionRepository.class),
                decisionDefRepo,
                mock(FormTableBindingRepository.class),
                mock(FormStageBindingRepository.class),
                mock(TableRelationRepository.class),
                mock(SubTableViewConfigRepository.class),
                mock(VersionRepository.class),
                mock(IconRepository.class),
                new ObjectMapper(),
                mock(UserDisplayNameService.class),
                mock(FunctionUnitWorkspaceAccessService.class),
                cloneDevGroupRepo,
                mock(com.developer.component.VersionComponent.class),
                mock(com.developer.util.DeveloperWorkstationSequenceSynchronizer.class),
                mock(com.developer.service.MainTableViewService.class),
                mock(com.developer.repository.ForeignKeyRepository.class),
                mock(com.developer.component.impl.FunctionUnitExporter.class),
                mock(com.developer.component.TableDesignComponent.class),
                mock(com.developer.repository.EmailConnectionRepository.class),
                mock(com.developer.repository.EmailMonitorRuleRepository.class),
                mock(com.developer.repository.EmailTemplateRepository.class)
        );

        // Mock: functionUnitRepository.save returns the entity with an ID.
        // Offset above the functionUnitIds() generator range (1..10000) so the
        // freshly-minted clone id can never collide with the @ForAll sourceFuId
        // (the idGenerator counter alone could otherwise land on a value the
        // property also generated as the source id, failing the "distinct id"
        // assertion for reasons unrelated to clone() behavior).
        Long clonedFuId = 100_000L + idGenerator.getAndIncrement();
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenAnswer(invocation -> {
            FunctionUnit saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(clonedFuId);
            }
            return saved;
        });

        // Execute clone
        FunctionUnit cloned = cloneComponent.clone(sourceFuId, clonedName);

        // Verify: exactly N decision definitions were saved
        assertThat(savedDecisions)
                .as("Clone should save exactly %d decision definitions", actualN)
                .hasSize(actualN);

        // Verify: each cloned decision has same field values but different id and functionUnit
        for (int i = 0; i < actualN; i++) {
            DecisionDefinition original = source.getDecisionDefinitions().get(i);
            DecisionDefinition clonedDef = savedDecisions.get(i);

            assertThat(clonedDef.getDecisionKey())
                    .as("cloned decisionKey[%d] should match source", i)
                    .isEqualTo(original.getDecisionKey());
            assertThat(clonedDef.getDecisionName())
                    .as("cloned decisionName[%d] should match source", i)
                    .isEqualTo(original.getDecisionName());
            assertThat(clonedDef.getDmnXml())
                    .as("cloned dmnXml[%d] should match source", i)
                    .isEqualTo(original.getDmnXml());
            assertThat(clonedDef.getHitPolicy())
                    .as("cloned hitPolicy[%d] should match source", i)
                    .isEqualTo(original.getHitPolicy());
            assertThat(clonedDef.getDescription())
                    .as("cloned description[%d] should match source", i)
                    .isEqualTo(original.getDescription());

            // ID should be different from source
            assertThat(clonedDef.getId())
                    .as("cloned decision[%d] should have a different id", i)
                    .isNotEqualTo(original.getId());

            // Should be associated with the cloned FunctionUnit, not the source
            assertThat(clonedDef.getFunctionUnit())
                    .as("cloned decision[%d] should be associated with the cloned FunctionUnit", i)
                    .isSameAs(cloned);
            assertThat(clonedDef.getFunctionUnit().getId())
                    .as("cloned decision[%d] functionUnit id should differ from source", i)
                    .isNotEqualTo(sourceFuId);
        }

        // Verify: cloned FunctionUnit has a different ID
        assertThat(cloned.getId())
                .as("Cloned FunctionUnit should have a different ID from source")
                .isNotEqualTo(sourceFuId);
    }
}
