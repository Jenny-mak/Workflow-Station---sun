package com.admin.component;

import com.admin.entity.ActionDefinition;
import com.admin.repository.ActionDefinitionRepository;
import com.admin.repository.FunctionUnitAccessRepository;
import com.admin.repository.FunctionUnitContentRepository;
import com.admin.repository.FunctionUnitDependencyRepository;
import com.admin.repository.FunctionUnitRepository;
import com.admin.service.FunctionUnitAccessService;
import com.admin.service.FunctionUnitAuditAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.i18n.I18nService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionUnitImportActionFlushTest {

    @Mock
    private FunctionUnitRepository functionUnitRepository;
    @Mock
    private FunctionUnitDependencyRepository dependencyRepository;
    @Mock
    private FunctionUnitContentRepository contentRepository;
    @Mock
    private FunctionUnitAccessRepository accessRepository;
    @Mock
    private FunctionUnitAccessService functionUnitAccessService;
    @Mock
    private FunctionUnitAuditAccessService functionUnitAuditAccessService;
    @Mock
    private FunctionUnitPackageParser packageParser;
    @Mock
    private ActionDefinitionRepository actionDefinitionRepository;
    @Mock
    private FunctionUnitVersionComponent versionComponent;
    @Mock
    private RelationTableStructureImporter relationTableStructureImporter;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private I18nService i18nService;
    @Mock
    private EmailConnectionSyncComponent emailConnectionSyncComponent;
    @Mock
    private EmailMonitorSyncComponent emailMonitorSyncComponent;
    @Mock
    private ImportBpmnStructureValidator importBpmnStructureValidator;
    @Mock
    private ImportViewAccessValidator importViewAccessValidator;

    @InjectMocks
    private FunctionUnitImportComponent importComponent;

    @Test
    void saveImportedActions_flushesDeleteBeforeInsertingSameActionName() {
        when(actionDefinitionRepository.save(any(ActionDefinition.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReflectionTestUtils.invokeMethod(
                importComponent,
                "saveImportedActions",
                "ce946f29-09b8-497e-ba85-839ab45fa663",
                List.of(Map.of(
                        "actionName", "Mark Complete",
                        "actionType", "APPROVE")));

        InOrder order = inOrder(actionDefinitionRepository);
        order.verify(actionDefinitionRepository)
                .deleteByFunctionUnitId("ce946f29-09b8-497e-ba85-839ab45fa663");
        order.verify(actionDefinitionRepository).flush();
        order.verify(actionDefinitionRepository).save(any(ActionDefinition.class));
    }
}
