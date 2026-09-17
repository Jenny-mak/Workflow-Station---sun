package com.developer.component.impl;

import com.developer.entity.FunctionUnit;
import com.developer.enums.AiDocumentType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.ActionDefinitionRepository;
import com.developer.repository.DecisionDefinitionRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormStageBindingRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.service.impl.FunctionUnitDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Requirements / Design 文档随导出包与版本快照流转：导出写 documents/*.md 并登记进 manifest，
 * 导入解析还原成同一结构；旧包（没有文档）解析结果里没有这个键。
 */
@ExtendWith(MockitoExtension.class)
class FunctionUnitDocumentPortabilityTest {

    @Mock private FunctionUnitRepository functionUnitRepository;
    @Mock private TableDefinitionRepository tableDefinitionRepository;
    @Mock private FormDefinitionRepository formDefinitionRepository;
    @Mock private ActionDefinitionRepository actionDefinitionRepository;
    @Mock private DecisionDefinitionRepository decisionDefinitionRepository;
    @Mock private FormStageBindingRepository formStageBindingRepository;
    @Mock private TableRelationRepository tableRelationRepository;
    @Mock private FunctionUnitWorkspaceAccessService accessService;

    private final FunctionUnitDocumentService documentService = mock(FunctionUnitDocumentService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private FunctionUnitExporter exporter;

    @BeforeEach
    void setUp() {
        lenient().when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(
                FunctionUnit.builder().name("Order").code("order").currentVersion("1.0.0").build()));
        exporter = ExportImportTestComponents.exporter(functionUnitRepository, tableDefinitionRepository,
                formDefinitionRepository, actionDefinitionRepository, decisionDefinitionRepository,
                formStageBindingRepository, tableRelationRepository, accessService, objectMapper, documentService);
    }

    private void givenDocuments(Map<AiDocumentType, String> docs) {
        Map<AiDocumentType, String> contents = new EnumMap<>(AiDocumentType.class);
        contents.putAll(docs);
        lenient().when(documentService.latestContents(1L)).thenReturn(contents);
        Map<String, String> payload = new java.util.LinkedHashMap<>();
        contents.forEach((type, content) -> payload.put(type.name(), content));
        lenient().when(documentService.packagePayload(1L)).thenReturn(payload);
    }

    private Map<String, Object> exportAndParse() {
        byte[] zip = exporter.exportFunctionUnit(1L);
        return new ExportImportPackageParser(objectMapper)
                .parseImportPackage(new MockMultipartFile("file", "order.zip", "application/zip", zip));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportedPackageCarriesTheDocumentsAndParsesBack() {
        givenDocuments(Map.of(AiDocumentType.REQUIREMENTS, "# 需求\n金额保留 4 位", AiDocumentType.DESIGN, "# Design"));

        Map<String, Object> parsed = exportAndParse();

        Map<String, Object> manifest = (Map<String, Object>) parsed.get("manifest");
        Map<String, Object> components = (Map<String, Object>) manifest.get("components");
        assertEquals(List.of("documents/requirements.md", "documents/design.md"), components.get("documents"));
        assertEquals(Map.of(AiDocumentType.REQUIREMENTS, "# 需求\n金额保留 4 位", AiDocumentType.DESIGN, "# Design"),
                FunctionUnitDocumentService.fromPackage(parsed.get(FunctionUnitDocumentService.PACKAGE_KEY)));
    }

    @Test
    void packageWithoutDocumentsHasNoDocumentsKey() {
        givenDocuments(Map.of());

        Map<String, Object> parsed = exportAndParse();

        assertFalse(parsed.containsKey(FunctionUnitDocumentService.PACKAGE_KEY));
        assertTrue(FunctionUnitDocumentService.fromPackage(parsed.get(FunctionUnitDocumentService.PACKAGE_KEY))
                .isEmpty());
    }

    @Test
    void versionSnapshotCarriesTheLatestDocuments() {
        givenDocuments(Map.of(AiDocumentType.DESIGN, "# Design v7"));

        Map<String, Object> snapshot = exporter.buildVersionSnapshotPayload(1L);

        assertEquals(Map.of("DESIGN", "# Design v7"), snapshot.get(FunctionUnitDocumentService.PACKAGE_KEY));
    }

    @Test
    void malformedDocumentsAreRejected() {
        assertThrows(DeveloperBusinessException.class, () -> FunctionUnitDocumentService.fromPackage("text"));
        assertThrows(DeveloperBusinessException.class,
                () -> FunctionUnitDocumentService.fromPackage(Map.of("NOTES", "x")));
        assertThrows(DeveloperBusinessException.class,
                () -> FunctionUnitDocumentService.fromPackage(Map.of("DESIGN", 3)));
    }
}
