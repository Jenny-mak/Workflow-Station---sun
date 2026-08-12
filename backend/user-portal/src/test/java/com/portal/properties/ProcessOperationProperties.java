package com.portal.properties;

import com.portal.client.WorkflowEngineClient;
import com.portal.component.ChangeHistoryComponent;
import com.portal.component.FunctionUnitAccessComponent;
import com.portal.component.MiOverlayComponent;
import com.portal.component.ProcessApplicationQueryComponent;
import com.portal.component.ProcessComponent;
import com.portal.component.ProcessDraftComponent;
import com.portal.component.ProcessStartComponent;
import com.portal.component.SubTableEnrichmentComponent;
import com.portal.component.SubTablePhysicalMetadataCache;
import com.portal.component.TaskFormComponent;
import com.portal.dto.ProcessDefinitionInfo;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.dto.ProcessStartRequest;
import com.portal.entity.FavoriteProcess;
import com.portal.entity.ProcessDraft;
import com.portal.repository.FavoriteProcessRepository;
import com.portal.repository.ProcessDraftRepository;
import com.portal.repository.ProcessHistoryRepository;
import com.portal.repository.ProcessInstanceRepository;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.mockito.Mockito;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
class ProcessOperationProperties {

    private ProcessComponent processComponent;
    private FavoriteProcessRepository favoriteProcessRepository;
    private ProcessDraftRepository processDraftRepository;
    private ProcessInstanceRepository processInstanceRepository;
    private ProcessHistoryRepository processHistoryRepository;
    private FunctionUnitAccessComponent functionUnitAccessComponent;
    private WorkflowEngineClient workflowEngineClient;
    private ProcessDraftComponent processDraftComponent;

    @BeforeTry
    void setUp() {
        favoriteProcessRepository = Mockito.mock(FavoriteProcessRepository.class);
        processDraftRepository = Mockito.mock(ProcessDraftRepository.class);
        processInstanceRepository = Mockito.mock(ProcessInstanceRepository.class);
        processHistoryRepository = Mockito.mock(ProcessHistoryRepository.class);
        functionUnitAccessComponent = Mockito.mock(FunctionUnitAccessComponent.class);
        workflowEngineClient = Mockito.mock(WorkflowEngineClient.class);
        
        // 创建 ProcessDraftComponent
        processDraftComponent = new ProcessDraftComponent(processDraftRepository, functionUnitAccessComponent, Mockito.mock(RestTemplate.class), Mockito.mock(jakarta.persistence.EntityManager.class));
        
        // 使用 Spy 来 mock getFunctionUnitContent 方法
        com.portal.service.PortalWorkspaceAuthService portalWorkspaceAuthService =
                Mockito.mock(com.portal.service.PortalWorkspaceAuthService.class);
        Mockito.when(portalWorkspaceAuthService.listWorkspaceContexts(Mockito.any()))
                .thenReturn(Collections.emptyList());
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate =
                Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        com.portal.service.UserDisplayNameResolver userDisplayNameResolver =
                Mockito.mock(com.portal.service.UserDisplayNameResolver.class);
        com.platform.common.i18n.I18nService i18nService =
                Mockito.mock(com.platform.common.i18n.I18nService.class);

        SubTablePhysicalMetadataCache subTablePhysicalMetadataCache = new SubTablePhysicalMetadataCache(jdbcTemplate);
        MiOverlayComponent miOverlayComponent = new MiOverlayComponent(workflowEngineClient, subTablePhysicalMetadataCache);
        SubTableEnrichmentComponent subTableEnrichmentComponent = new SubTableEnrichmentComponent(
                processInstanceRepository, jdbcTemplate, miOverlayComponent, subTablePhysicalMetadataCache);
        ProcessApplicationQueryComponent processApplicationQueryComponent = new ProcessApplicationQueryComponent(
                processInstanceRepository, workflowEngineClient,
                new com.portal.component.EngineSubTableHydrator(workflowEngineClient), userDisplayNameResolver,
                miOverlayComponent, subTableEnrichmentComponent,
                Mockito.mock(com.portal.component.RequestIdEnricher.class),
                Mockito.mock(com.portal.component.MainTableViewInvolvementChecker.class),
                Mockito.mock(com.portal.component.MainTableViewAccessResolver.class),
                jdbcTemplate);
        ProcessStartComponent processStartComponent = new ProcessStartComponent(
                processInstanceRepository,
                processHistoryRepository,
                functionUnitAccessComponent,
                workflowEngineClient,
                Mockito.mock(ChangeHistoryComponent.class),
                portalWorkspaceAuthService,
                restTemplate,
                jdbcTemplate,
                Mockito.mock(com.portal.component.MeetingParticipantVariablesPersistence.class),
                Mockito.mock(com.portal.component.ProcessSubTablePrimaryKeyEnricherComponent.class),
                Mockito.mock(TaskFormComponent.class),
                userDisplayNameResolver,
                i18nService,
                Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        processComponent = Mockito.spy(new ProcessComponent(favoriteProcessRepository, processInstanceRepository, Mockito.mock(com.portal.repository.ActionDefinitionRepository.class), functionUnitAccessComponent, workflowEngineClient, processDraftComponent, restTemplate, i18nService, processStartComponent, processApplicationQueryComponent, subTableEnrichmentComponent));
        // ProcessStartComponent resolves FU content via the (lazy) ProcessComponent facade — point it at the spy
        // so the getFunctionUnitContent stub below stays effective for startProcess.
        org.springframework.test.util.ReflectionTestUtils.setField(processStartComponent, "processComponent", processComponent);
        
        // Mock getFunctionUnitContent 返回包含 BPMN XML 的内容
        String mockBpmnXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><bpmn:definitions></bpmn:definitions>";
        Map<String, Object> mockContent = new HashMap<>();
        mockContent.put("name", "测试流程");
        // startProcess 现要求可解析的 Flowable process key（API 的 flowableProcessDefinitionKey 或 BPMN <process id>）
        mockContent.put("processes", List.of(Map.of(
                "data", mockBpmnXml,
                "flowableProcessDefinitionKey", "test-process")));
        doReturn(mockContent).when(processComponent).getFunctionUnitContent(any(String.class));
        
        // Mock WorkflowEngineClient 为可用状态
        when(workflowEngineClient.isAvailable()).thenReturn(true);
        
        // Mock 部署流程成功
        when(workflowEngineClient.deployProcess(any(), any(), any()))
                .thenReturn(Optional.of(Map.of("success", true, "deploymentId", "deploy-123")));
        
        // Mock 启动流程成功
        when(workflowEngineClient.startProcess(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String processKey = invocation.getArgument(0);
                    String businessKey = invocation.getArgument(1);
                    String startUserId = invocation.getArgument(2);
                    // 与 ApiResponseBodyUnwrap.unwrapDataMap 后的结构一致（顶层即字段）
                    return Map.of(
                            "processInstanceId", "pi-" + System.currentTimeMillis(),
                            "processDefinitionId", "pd-" + processKey,
                            "processDefinitionKey", processKey,
                            "businessKey", businessKey != null ? businessKey : "",
                            "startUserId", startUserId
                    );
                });
        
        // Mock FunctionUnitAccessComponent 返回功能单元内容（包含 BPMN XML）
        when(functionUnitAccessComponent.resolveFunctionUnitId(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // i18n: 返回 key 本身，避免分类等展示字段为 null
        when(i18nService.getMessage(any(String.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // getAvailableProcessDefinitions 现从 admin-center 拉取已部署功能单元（/deployed/latest）
        when(restTemplate.getForObject(contains("/function-units/deployed/latest"), eq(Map.class)))
                .thenReturn(Map.of("success", true, "data", List.of(Map.of(
                        "id", "fu-1",
                        "code", "leave-request",
                        "name", "请假流程",
                        "description", "测试流程",
                        "version", 1))));
        when(functionUnitAccessComponent.filterAccessibleFunctionUnits(any(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        // startProcess 现要求 admin-center 存在「已部署且启用」的目录版本（active-for-start pin），
        // 且 pin.id 必须与 resolveFunctionUnitId(processKey)（上方恒等 stub）一致。
        when(restTemplate.exchange(
                contains("/active-for-start"),
                eq(org.springframework.http.HttpMethod.GET),
                isNull(),
                Mockito.<org.springframework.core.ParameterizedTypeReference<Map<String, Object>>>any()))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    String enc = url.substring(url.indexOf("/code/") + "/code/".length(),
                            url.indexOf("/active-for-start"));
                    String code = java.net.URLDecoder.decode(enc, java.nio.charset.StandardCharsets.UTF_8);
                    return org.springframework.http.ResponseEntity.ok(
                            Map.<String, Object>of("id", code, "code", code, "version", 1));
                });
    }

    @Property(tries = 20)
    @Label("属性: 流程定义列表应包含所有可用流程")
    void processDefinitionListShouldContainAllAvailable(@ForAll("validUserIds") String userId) {
        when(favoriteProcessRepository.findByUserIdOrderByDisplayOrderAsc(userId))
                .thenReturn(Collections.emptyList());
        
        List<ProcessDefinitionInfo> definitions = 
                processComponent.getAvailableProcessDefinitions(userId, null, null);
        
        assertThat(definitions).isNotEmpty();
        assertThat(definitions).allMatch(d -> 
                d.getId() != null && d.getKey() != null && d.getName() != null);
    }

    @Property(tries = 20)
    @Label("属性: 按分类筛选应只返回匹配的流程")
    void filterByCategoryShouldReturnMatchingOnly(
            @ForAll("validUserIds") String userId,
            @ForAll("categories") String category) {
        when(favoriteProcessRepository.findByUserIdOrderByDisplayOrderAsc(userId))
                .thenReturn(Collections.emptyList());
        
        List<ProcessDefinitionInfo> definitions = 
                processComponent.getAvailableProcessDefinitions(userId, category, null);
        
        assertThat(definitions).allMatch(d -> d.getCategory().equals(category));
    }

    @Property(tries = 20)
    @Label("属性: 发起流程应返回有效的流程实例")
    void startProcessShouldReturnValidInstance(
            @ForAll("validUserIds") String userId,
            @ForAll("processKeys") String processKey) {
        ProcessStartRequest request = new ProcessStartRequest();
        request.setBusinessKey("BK-" + System.currentTimeMillis());
        
        ProcessInstanceInfo instance = processComponent.startProcess(userId, processKey, request);
        
        assertThat(instance).isNotNull();
        assertThat(instance.getId()).isNotNull();
        assertThat(instance.getStartUserId()).isEqualTo(userId);
        assertThat(instance.getStatus()).isEqualTo("RUNNING");
    }

    @Property(tries = 20)
    @Label("属性: 空流程Key应抛出异常")
    void emptyProcessKeyShouldThrowException(@ForAll("validUserIds") String userId) {
        ProcessStartRequest request = new ProcessStartRequest();
        
        assertThatThrownBy(() -> processComponent.startProcess(userId, "", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Process key cannot be empty");
    }

    @Property(tries = 20)
    @Label("属性: 空用户ID应抛出异常")
    void emptyUserIdShouldThrowException(@ForAll("processKeys") String processKey) {
        ProcessStartRequest request = new ProcessStartRequest();
        
        assertThatThrownBy(() -> processComponent.startProcess("", processKey, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User ID cannot be empty");
    }

    @Property(tries = 20)
    @Label("属性: 收藏切换应正确更新状态")
    void toggleFavoriteShouldUpdateStatus(
            @ForAll("validUserIds") String userId,
            @ForAll("processKeys") String processKey) {
        when(favoriteProcessRepository.findByUserIdAndProcessDefinitionKey(userId, processKey))
                .thenReturn(Optional.empty());
        when(favoriteProcessRepository.save(any(FavoriteProcess.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        
        boolean result = processComponent.toggleFavorite(userId, processKey);
        
        assertThat(result).isTrue();
        verify(favoriteProcessRepository).save(any(FavoriteProcess.class));
    }

    @Property(tries = 20)
    @Label("属性: 取消收藏应删除记录")
    void unfavoriteShouldDeleteRecord(
            @ForAll("validUserIds") String userId,
            @ForAll("processKeys") String processKey) {
        FavoriteProcess existing = new FavoriteProcess();
        existing.setUserId(userId);
        existing.setProcessDefinitionKey(processKey);
        
        when(favoriteProcessRepository.findByUserIdAndProcessDefinitionKey(userId, processKey))
                .thenReturn(Optional.of(existing));
        
        boolean result = processComponent.toggleFavorite(userId, processKey);
        
        assertThat(result).isFalse();
        verify(favoriteProcessRepository).delete(existing);
    }

    @Property(tries = 20)
    @Label("属性: 保存草稿应持久化数据")
    void saveDraftShouldPersistData(
            @ForAll("validUserIds") String userId,
            @ForAll("processKeys") String processKey) {
        Map<String, Object> formData = new HashMap<>();
        formData.put("field1", "value1");
        
        when(processDraftRepository.findFirstByUserIdAndProcessDefinitionKeyOrderByUpdatedAtDesc(
                userId, processKey)).thenReturn(Optional.empty());
        when(processDraftRepository.save(any(ProcessDraft.class)))
                .thenAnswer(inv -> {
                    ProcessDraft draft = inv.getArgument(0);
                    draft.setId(1L);
                    return draft;
                });
        
        ProcessDraft draft = processComponent.saveDraft(userId, processKey, formData);
        
        assertThat(draft).isNotNull();
        assertThat(draft.getUserId()).isEqualTo(userId);
        assertThat(draft.getProcessDefinitionKey()).isEqualTo(processKey);
    }

    @Property(tries = 20)
    @Label("属性: 更新草稿应保留ID")
    void updateDraftShouldPreserveId(
            @ForAll("validUserIds") String userId,
            @ForAll("processKeys") String processKey) {
        ProcessDraft existing = new ProcessDraft();
        existing.setId(100L);
        existing.setUserId(userId);
        existing.setProcessDefinitionKey(processKey);
        existing.setCreatedAt(LocalDateTime.now().minusDays(1));
        
        when(processDraftRepository.findFirstByUserIdAndProcessDefinitionKeyOrderByUpdatedAtDesc(
                userId, processKey)).thenReturn(Optional.of(existing));
        when(processDraftRepository.save(any(ProcessDraft.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        
        Map<String, Object> newFormData = new HashMap<>();
        newFormData.put("field1", "newValue");
        
        ProcessDraft updated = processComponent.saveDraft(userId, processKey, newFormData);
        
        assertThat(updated.getId()).isEqualTo(100L);
        assertThat(updated.getUpdatedAt()).isAfter(updated.getCreatedAt());
    }

    @Provide
    Arbitrary<String> validUserIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(s -> "user-" + s);
    }

    @Provide
    Arbitrary<String> processKeys() {
        return Arbitraries.of("leave-request", "expense-claim", "purchase-request");
    }

    @Provide
    Arbitrary<String> categories() {
        return Arbitraries.of("人事", "财务", "采购");
    }
}
