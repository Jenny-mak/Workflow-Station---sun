package com.developer.property;

import com.developer.component.FunctionUnitComponent;
import com.developer.component.impl.DecisionDesignComponentImpl;
import com.developer.component.impl.FunctionUnitComponentImpl;
import com.developer.component.impl.FunctionUnitExporter;
import com.developer.dto.DecisionDefinitionRequest;
import com.developer.repository.*;
import com.developer.service.DecisionDefinitionService;
import com.developer.validation.DmnXmlParser;
import com.developer.validation.DmnXmlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.service.UserDisplayNameService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DecisionDesignComponentImpl 属性测试 —— 共享基类
 * Feature: dmn-decision-table-integration
 *
 * 持有 {@code @BeforeProperty setUp()}、所有 {@code @Provide} generator、辅助 Arbitrary
 * 与辅助方法（如 buildTestZip），供按主题拆分的各子测试类继承。
 *
 * Validates: Requirements 2.2, 2.5, 3.2, 3.3, 3.4, 3.5, 3.6, 3.8, 6.1, 6.3, 7.2
 */
public abstract class DecisionDesignPropertyTestBase {

    protected DecisionDefinitionService decisionDefinitionService;
    protected DmnXmlValidator dmnXmlValidator;
    protected DmnXmlParser dmnXmlParser;
    protected FunctionUnitComponent functionUnitComponent;
    protected DecisionDesignComponentImpl component;

    // For Property 4: FunctionUnitComponentImpl to test decisionCount in toResponse
    protected FunctionUnitRepository functionUnitRepository;
    protected FunctionUnitComponentImpl functionUnitComponentImpl;
    protected FunctionUnitDevGroupAssignmentRepository functionUnitDevGroupAssignmentRepository;

    protected AtomicLong idGenerator;

    @BeforeProperty
    void setUp() {
        decisionDefinitionService = mock(DecisionDefinitionService.class);
        dmnXmlValidator = mock(DmnXmlValidator.class);
        dmnXmlParser = mock(DmnXmlParser.class);
        functionUnitComponent = mock(FunctionUnitComponent.class);

        component = new DecisionDesignComponentImpl(
                decisionDefinitionService,
                dmnXmlValidator,
                dmnXmlParser,
                functionUnitComponent
        );

        // Set up FunctionUnitComponentImpl with mocked dependencies for Property 4
        functionUnitRepository = mock(FunctionUnitRepository.class);
        functionUnitDevGroupAssignmentRepository = mock(FunctionUnitDevGroupAssignmentRepository.class);
        when(functionUnitDevGroupAssignmentRepository.findByFunctionUnitId(anyLong())).thenReturn(List.of());
        functionUnitComponentImpl = new FunctionUnitComponentImpl(
                functionUnitRepository,
                mock(ProcessDefinitionRepository.class),
                mock(TableDefinitionRepository.class),
                mock(FormDefinitionRepository.class),
                mock(ActionDefinitionRepository.class),
                mock(DecisionDefinitionRepository.class),
                mock(FormTableBindingRepository.class),
                mock(FormStageBindingRepository.class),
                mock(TableRelationRepository.class),
                mock(SubTableViewConfigRepository.class),
                mock(VersionRepository.class),
                mock(IconRepository.class),
                new ObjectMapper(),
                mock(UserDisplayNameService.class),
                mock(FunctionUnitWorkspaceAccessService.class),
                functionUnitDevGroupAssignmentRepository,
                mock(com.developer.component.VersionComponent.class),
                mock(com.developer.util.DeveloperWorkstationSequenceSynchronizer.class),
                mock(com.developer.service.MainTableViewService.class),
                mock(com.developer.repository.ForeignKeyRepository.class),
                mock(FunctionUnitExporter.class),
                mock(com.developer.component.TableDesignComponent.class),
                mock(com.developer.repository.EmailConnectionRepository.class),
                mock(com.developer.repository.EmailMonitorRuleRepository.class),
                mock(com.developer.repository.EmailTemplateRepository.class),
                new com.developer.service.impl.FunctionUnitDocumentService(
                org.mockito.Mockito.mock(com.developer.repository.AiDocumentRepository.class),
                org.mockito.Mockito.mock(com.developer.repository.AiStudioThreadStateRepository.class))
        );

        idGenerator = new AtomicLong(1L);
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<DecisionDefinitionRequest> validDecisionRequests() {
        return Combinators.combine(
                validDecisionKeys(),
                validDecisionNames(),
                validDmnXmlStrings(),
                validHitPolicies(),
                validDescriptions()
        ).as((key, name, xml, hitPolicy, desc) -> {
            DecisionDefinitionRequest req = new DecisionDefinitionRequest();
            req.setDecisionKey(key);
            req.setDecisionName(name);
            req.setDmnXml(xml);
            req.setHitPolicy(hitPolicy);
            req.setDescription(desc);
            return req;
        });
    }

    @Provide
    Arbitrary<Long> functionUnitIds() {
        return Arbitraries.longs().between(1L, 10000L);
    }

    @Provide
    Arbitrary<Integer> decisionCounts() {
        return Arbitraries.integers().between(0, 20);
    }

    @Provide
    Arbitrary<List<DecisionDefinitionRequest>> validDecisionRequestLists() {
        return validDecisionRequests().list().ofMinSize(0).ofMaxSize(20);
    }

    @Provide
    Arbitrary<Long[]> functionUnitIdPairs() {
        return Combinators.combine(
                Arbitraries.longs().between(1L, 5000L),
                Arbitraries.longs().between(5001L, 10000L)
        ).as((id1, id2) -> new Long[]{id1, id2});
    }

    // ========== Helper Arbitraries ==========

    private Arbitrary<String> validDecisionKeys() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(20)
                .map(s -> "dk" + s);
    }

    private Arbitrary<String> validDecisionNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(30)
                .map(s -> "Decision " + s);
    }

    private Arbitrary<String> validDmnXmlStrings() {
        return Combinators.combine(
                validDecisionKeys(),
                Arbitraries.of("FIRST", "UNIQUE", "ANY", "PRIORITY",
                        "COLLECT", "RULE_ORDER", "OUTPUT_ORDER")
        ).as((key, hp) ->
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\"" +
                " id=\"definitions\" name=\"definitions\"" +
                " namespace=\"http://camunda.org/schema/1.0/dmn\">\n" +
                "  <decision id=\"" + key + "\" name=\"Test\">\n" +
                "    <decisionTable id=\"dt_1\" hitPolicy=\"" + hp + "\">\n" +
                "      <input id=\"input_1\" label=\"Amount\">\n" +
                "        <inputExpression id=\"ie_1\" typeRef=\"double\">\n" +
                "          <text>amount</text>\n" +
                "        </inputExpression>\n" +
                "      </input>\n" +
                "      <output id=\"output_1\" label=\"Result\" name=\"result\" typeRef=\"string\" />\n" +
                "    </decisionTable>\n" +
                "  </decision>\n" +
                "</definitions>"
        );
    }

    private Arbitrary<String> validHitPolicies() {
        return Arbitraries.of("FIRST", "UNIQUE", "ANY", "PRIORITY",
                "COLLECT", "RULE_ORDER", "OUTPUT_ORDER");
    }

    private Arbitrary<String> validDescriptions() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .ofMinLength(0)
                        .ofMaxLength(50)
                        .map(s -> "Desc: " + s)
        );
    }

    // ========== Helper: Build a test ZIP for import ==========

    protected byte[] buildTestZip(String fuName, String dmnXml) {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {

            // Add manifest.json
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("name", fuName);
            manifest.put("code", "fu-import-test");
            manifest.put("version", "1.0.0");
            manifest.put("description", "Test import");

            Map<String, Object> components = new LinkedHashMap<>();
            components.put("process", null);
            components.put("tables", List.of());
            components.put("forms", List.of());
            components.put("actions", List.of());
            components.put("decisions", List.of("decisions/decision_0.dmn"));
            manifest.put("components", components);

            byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
            zos.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
            zos.write(manifestBytes);
            zos.closeEntry();

            // Add decision DMN file
            byte[] dmnBytes = dmnXml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            zos.putNextEntry(new java.util.zip.ZipEntry("decisions/decision_0.dmn"));
            zos.write(dmnBytes);
            zos.closeEntry();

            zos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build test ZIP: " + e.getMessage(), e);
        }
    }
}
