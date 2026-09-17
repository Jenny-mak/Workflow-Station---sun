package com.portal.component;

import com.platform.security.entity.BusinessUnit;
import com.platform.security.util.SecurityContextUtils;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.TaskInfo;
import com.portal.repository.BusinessUnitRepository;
import com.portal.repository.ProcessInstanceRepository;
import com.portal.service.PortalWorkspaceAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Standing overlay post-filter keeps delegator-held FIXED_BU_ROLE tasks")
class MineTaskScannerDelegatedOverlayFilterTest {

    private static final String WANG_FANG = "user-e2e-wangfang";
    private static final String LINA = "user-e2e-lina";
    private static final String VIEWER_BU_ID = "bu-index";

    @Mock
    private MineTaskListCache mineTaskListCache;
    @Mock
    private EngineVisibleTaskFetcher engineVisibleTaskFetcher;
    @Mock
    private RequestIdEnricher requestIdEnricher;
    @Mock
    private TaskPermissionEvaluator taskPermissionEvaluator;
    @Mock
    private ClaimForceUnclaimAnnotator claimForceUnclaimAnnotator;
    @Mock
    private ProcessInstanceRepository processInstanceRepository;
    @Mock
    private WorkflowEngineClient workflowEngineClient;
    @Mock
    private VirtualGroupAccessComponent virtualGroupAccessComponent;
    @Mock
    private PortalWorkspaceAuthService portalWorkspaceAuthService;
    @Mock
    private BusinessUnitRepository businessUnitRepository;

    private MineTaskScanner scanner;
    private MockedStatic<SecurityContextUtils> securityContext;

    @BeforeEach
    void setUp() {
        WorkspaceTaskFilterComponent workspaceFilter = new WorkspaceTaskFilterComponent(
                workflowEngineClient, virtualGroupAccessComponent,
                portalWorkspaceAuthService, businessUnitRepository);
        scanner = new MineTaskScanner(
                mineTaskListCache, engineVisibleTaskFetcher, requestIdEnricher,
                taskPermissionEvaluator, claimForceUnclaimAnnotator,
                workspaceFilter, processInstanceRepository);

        securityContext = Mockito.mockStatic(SecurityContextUtils.class);
        securityContext.when(SecurityContextUtils::getCurrentActiveBusinessUnitId)
                .thenReturn(Optional.of(VIEWER_BU_ID));
        securityContext.when(SecurityContextUtils::getCurrentUsername)
                .thenReturn(Optional.of("e2e_wangfang"));
        securityContext.when(SecurityContextUtils::getCurrentActiveRoleId)
                .thenReturn(Optional.empty());

        BusinessUnit index = new BusinessUnit();
        index.setId(VIEWER_BU_ID);
        index.setCode("hase-hmdc");
        lenient().when(businessUnitRepository.findById(VIEWER_BU_ID)).thenReturn(Optional.of(index));
        lenient().when(processInstanceRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(portalWorkspaceAuthService.listWorkspaceContexts(WANG_FANG)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        securityContext.close();
    }

    @Test
    void viewerWorkspaceFilterDropsDelegatorHeldReviewButOverlayKeepsIt() {
        TaskInfo held = TaskInfo.builder()
                .taskId("review-1")
                .assignee(LINA)
                .bpmnAssigneeType("FIXED_BU_ROLE")
                .bpmnBusinessUnitId("E2E_FINANCE")
                .processInstanceId("pi-1")
                .build();

        List<TaskInfo> mineFiltered = scanner.applyPortalPostEngineFilters(WANG_FANG, List.of(held));
        List<TaskInfo> overlay = scanner.applyDelegatedOverlayPostFilters(List.of(held));

        assertThat(mineFiltered).isEmpty();
        assertThat(overlay).extracting(TaskInfo::getTaskId).containsExactly("review-1");
    }
}
