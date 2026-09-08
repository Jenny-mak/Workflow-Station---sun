package com.developer.component.impl;

import com.developer.client.AdminCenterAutomationFlowClient;
import com.developer.repository.ActionDefinitionRepository;
import com.developer.repository.DecisionDefinitionRepository;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.EmailMonitorRuleRepository;
import com.developer.repository.EmailTemplateRepository;
import com.developer.repository.FormDefinitionRepository;
import com.developer.repository.FormStageBindingRepository;
import com.developer.repository.FormTableBindingRepository;
import com.developer.repository.FunctionUnitDevGroupAssignmentRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.repository.LinkFormComponentRepository;
import com.developer.repository.ProcessDefinitionRepository;
import com.developer.repository.SubTableViewConfigRepository;
import com.developer.repository.TableDefinitionRepository;
import com.developer.repository.TableRelationRepository;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.util.DeveloperWorkstationSequenceSynchronizer;
import com.developer.validation.DmnXmlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 测试辅助：用拆分前 ExportImportComponentImpl 的 15 个依赖装配出门面 + 协作类。
 * 把 platformVersion 设为旧默认值 "1.0.0"，保持导出 manifest 行为一致。
 */
public final class ExportImportTestComponents {

    private ExportImportTestComponents() {
    }

    public static FunctionUnitExporter exporter(
            FunctionUnitRepository functionUnitRepository,
            TableDefinitionRepository tableDefinitionRepository,
            FormDefinitionRepository formDefinitionRepository,
            ActionDefinitionRepository actionDefinitionRepository,
            DecisionDefinitionRepository decisionDefinitionRepository,
            FormStageBindingRepository formStageBindingRepository,
            TableRelationRepository tableRelationRepository,
            FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService,
            ObjectMapper objectMapper) {
        EmailConnectionRepository emailConnectionRepository = Mockito.mock(EmailConnectionRepository.class);
        Mockito.lenient().when(emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(Mockito.anyLong()))
                .thenReturn(java.util.List.of());
        EmailMonitorRuleRepository emailMonitorRuleRepository = Mockito.mock(EmailMonitorRuleRepository.class);
        Mockito.lenient().when(emailMonitorRuleRepository.findByFunctionUnitIdOrderByNameAsc(Mockito.anyLong()))
                .thenReturn(java.util.List.of());
        EmailTemplateRepository emailTemplateRepository = Mockito.mock(EmailTemplateRepository.class);
        Mockito.lenient().when(emailTemplateRepository.findByFunctionUnitIdOrderByNameAsc(Mockito.anyLong()))
                .thenReturn(java.util.List.of());
        LinkFormComponentRepository linkFormComponentRepository = Mockito.mock(LinkFormComponentRepository.class);
        Mockito.lenient().when(linkFormComponentRepository.findByFunctionUnitIdOrderBySortOrderAsc(Mockito.anyLong()))
                .thenReturn(java.util.List.of());
        RelationTableStructurePortability relationTablePortability =
                Mockito.mock(RelationTableStructurePortability.class);
        Mockito.lenient().when(relationTablePortability.exportByIds(Mockito.anyList()))
                .thenReturn(java.util.List.of());
        MainTableViewPortability mainTableViewPortability = Mockito.mock(MainTableViewPortability.class);
        Mockito.lenient().when(mainTableViewPortability.export(Mockito.anyLong(), Mockito.anyMap()))
                .thenReturn(java.util.List.of());
        FunctionUnitExporter exporter = new FunctionUnitExporter(
                functionUnitRepository,
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository,
                decisionDefinitionRepository,
                emailConnectionRepository,
                emailMonitorRuleRepository,
                emailTemplateRepository,
                formStageBindingRepository,
                tableRelationRepository,
                Mockito.mock(SubTableViewConfigRepository.class),
                linkFormComponentRepository,
                relationTablePortability,
                mainTableViewPortability,
                functionUnitWorkspaceAccessService,
                objectMapper);
        ReflectionTestUtils.setField(exporter, "platformVersion", "1.0.0");
        return exporter;
    }

    public static ExportImportComponentImpl build(
            FunctionUnitRepository functionUnitRepository,
            ProcessDefinitionRepository processDefinitionRepository,
            TableDefinitionRepository tableDefinitionRepository,
            FormDefinitionRepository formDefinitionRepository,
            ActionDefinitionRepository actionDefinitionRepository,
            DecisionDefinitionRepository decisionDefinitionRepository,
            FormTableBindingRepository formTableBindingRepository,
            FormStageBindingRepository formStageBindingRepository,
            TableRelationRepository tableRelationRepository,
            DmnXmlParser dmnXmlParser,
            FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService,
            FunctionUnitDevGroupAssignmentRepository functionUnitDevGroupAssignmentRepository,
            EntityManager entityManager,
            ObjectMapper objectMapper,
            DeveloperWorkstationSequenceSynchronizer sequenceSynchronizer) {
        return build(functionUnitRepository, processDefinitionRepository, tableDefinitionRepository,
                formDefinitionRepository, actionDefinitionRepository, decisionDefinitionRepository,
                formTableBindingRepository, formStageBindingRepository, tableRelationRepository,
                dmnXmlParser, functionUnitWorkspaceAccessService, functionUnitDevGroupAssignmentRepository,
                entityManager, objectMapper, sequenceSynchronizer,
                Mockito.mock(AdminCenterAutomationFlowClient.class));
    }

    /** 需要断言 FR-B13 导入前 flow 引用校验行为时，传入受控的 flow 解析客户端 */
    public static ExportImportComponentImpl build(
            FunctionUnitRepository functionUnitRepository,
            ProcessDefinitionRepository processDefinitionRepository,
            TableDefinitionRepository tableDefinitionRepository,
            FormDefinitionRepository formDefinitionRepository,
            ActionDefinitionRepository actionDefinitionRepository,
            DecisionDefinitionRepository decisionDefinitionRepository,
            FormTableBindingRepository formTableBindingRepository,
            FormStageBindingRepository formStageBindingRepository,
            TableRelationRepository tableRelationRepository,
            DmnXmlParser dmnXmlParser,
            FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService,
            FunctionUnitDevGroupAssignmentRepository functionUnitDevGroupAssignmentRepository,
            EntityManager entityManager,
            ObjectMapper objectMapper,
            DeveloperWorkstationSequenceSynchronizer sequenceSynchronizer,
            AdminCenterAutomationFlowClient automationFlowClient) {

        ExportImportPackageParser packageParser = new ExportImportPackageParser(objectMapper);

        FunctionUnitExporter exporter = exporter(
                functionUnitRepository,
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository,
                decisionDefinitionRepository,
                formStageBindingRepository,
                tableRelationRepository,
                functionUnitWorkspaceAccessService,
                objectMapper);

        FunctionUnitImportWriter importWriter = new FunctionUnitImportWriter(
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository,
                decisionDefinitionRepository,
                Mockito.mock(EmailConnectionRepository.class),
                Mockito.mock(EmailTemplateRepository.class),
                formTableBindingRepository,
                Mockito.mock(LinkFormComponentRepository.class),
                tableRelationRepository,
                Mockito.mock(SubTableViewConfigRepository.class),
                dmnXmlParser,
                objectMapper);

        ProcessBpmnStaleIdFixer staleIdFixer = new ProcessBpmnStaleIdFixer(
                tableDefinitionRepository,
                formDefinitionRepository,
                actionDefinitionRepository);

        FunctionUnitImporter importer = new FunctionUnitImporter(
                functionUnitRepository,
                processDefinitionRepository,
                formDefinitionRepository,
                entityManager,
                sequenceSynchronizer,
                packageParser,
                importWriter,
                new EmailMonitorRulePortability(
                        Mockito.mock(EmailMonitorRuleRepository.class), objectMapper),
                staleIdFixer,
                Mockito.mock(com.developer.component.VersionComponent.class),
                Mockito.mock(RelationTableStructurePortability.class),
                Mockito.mock(MainTableViewPortability.class),
                Mockito.mock(com.developer.service.MainTableViewService.class),
                automationFlowClient);

        return new ExportImportComponentImpl(
                functionUnitRepository,
                packageParser,
                exporter,
                importer);
    }
}
