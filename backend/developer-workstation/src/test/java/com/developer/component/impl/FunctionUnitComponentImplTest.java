package com.developer.component.impl;

import com.developer.component.VersionComponent;
import com.developer.dto.FunctionUnitRequest;
import com.developer.dto.ValidationResult;
import com.developer.entity.FunctionUnit;
import com.developer.entity.ProcessDefinition;
import com.developer.entity.Version;
import com.developer.enums.FunctionUnitStatus;
import com.developer.repository.*;
import com.developer.util.XmlEncodingUtil;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.UserDisplayNameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * 测试 FunctionUnitComponentImpl 的操作者信息获取功能
 * 
 * 验证属性 1: 操作者信息获取的正确性
 */
@ExtendWith(MockitoExtension.class)
class FunctionUnitComponentImplTest {
    
    @Mock
    private FunctionUnitRepository functionUnitRepository;
    
    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;
    
    @Mock
    private TableDefinitionRepository tableDefinitionRepository;
    
    @Mock
    private FormDefinitionRepository formDefinitionRepository;
    
    @Mock
    private ActionDefinitionRepository actionDefinitionRepository;
    
    @Mock
    private VersionRepository versionRepository;
    
    @Mock
    private IconRepository iconRepository;
    
    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private UserDisplayNameService userDisplayNameService;

    @Mock
    private FunctionUnitWorkspaceAccessService functionUnitWorkspaceAccessService;

    @Mock
    private FunctionUnitDevGroupAssignmentRepository functionUnitDevGroupAssignmentRepository;

    @Mock
    private VersionComponent versionComponent;

    @Mock
    private ForeignKeyRepository foreignKeyRepository;

    @Mock
    private com.developer.service.MainTableViewService mainTableViewService;

    // FunctionUnitComponentImpl 的构造参数在拆分出这些协作者后没有同步到测试里，
    // @InjectMocks 于是传 null —— publish 走到 functionUnitExporter 才 NPE。
    @Mock
    private FunctionUnitExporter functionUnitExporter;

    @Mock
    private FunctionUnitCodeGenerator codeGenerator;

    @Mock
    private FunctionUnitValidator validator;

    @Mock
    private FunctionUnitSnapshotFactory snapshotFactory;

    @Mock
    private FunctionUnitCloner cloner;

    @Mock
    private FunctionUnitResponseAssembler responseAssembler;

    @InjectMocks
    private FunctionUnitComponentImpl functionUnitComponent;
    
    @BeforeEach
    void setUp() {
        // 清理 SecurityContext
        SecurityContextHolder.clearContext();
        lenient().doNothing().when(functionUnitWorkspaceAccessService)
                .assertCanAccess(any(Long.class), any(WorkspaceAccessAction.class));
        lenient().when(functionUnitWorkspaceAccessService.visibleFunctionUnitIds()).thenReturn(null);
    }

    @Test
    void create_shouldPersistInitialProcessDefinitionWithUniqueProcessIdFromCode() {
        when(functionUnitRepository.existsByName("New Unit")).thenReturn(false);
        when(functionUnitRepository.existsByCode(any())).thenReturn(false);

        when(functionUnitRepository.save(any(FunctionUnit.class))).thenAnswer(invocation -> {
            FunctionUnit fu = invocation.getArgument(0);
            fu.setId(99L);
            return fu;
        });
        when(processDefinitionRepository.save(any(ProcessDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        FunctionUnitRequest request = new FunctionUnitRequest();
        request.setName("New Unit");
        request.setDescription("d");

        FunctionUnit created = functionUnitComponent.create(request);

        assertNotNull(created);
        assertEquals(99L, created.getId());
        assertNotNull(created.getCode());
        assertTrue(created.getCode().startsWith("new-unit-"));

        ArgumentCaptor<FunctionUnit> fuCaptor = ArgumentCaptor.forClass(FunctionUnit.class);
        verify(functionUnitRepository).save(fuCaptor.capture());
        String expectedProcessId = fuCaptor.getValue().getCode();

        ArgumentCaptor<ProcessDefinition> pdCaptor = ArgumentCaptor.forClass(ProcessDefinition.class);
        verify(processDefinitionRepository).save(pdCaptor.capture());
        ProcessDefinition saved = pdCaptor.getValue();
        assertEquals(99L, saved.getFunctionUnitVersionId());
        assertSame(created, saved.getFunctionUnit());

        String decoded = XmlEncodingUtil.smartDecode(saved.getBpmnXml());
        assertTrue(decoded.contains("<bpmn:process id=\"" + expectedProcessId + "\""));
        assertTrue(decoded.contains("bpmnElement=\"" + expectedProcessId + "\""));
    }
    
    /**
     * 测试用例 1: publish 时有效的认证用户
     * 验证当存在已认证用户时，getCurrentOperator() 返回用户名
     */
    @Test
    void testPublish_WithAuthenticatedUser() throws Exception {
        // Given: 设置 SecurityContext 包含认证用户
        Authentication auth = new UsernamePasswordAuthenticationToken("testuser", "password");
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(securityContext);
        
        // 准备测试数据
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L)
                .name("Test Function")
                .currentVersion("1.0.0")
                .status(FunctionUnitStatus.DRAFT)
                .build();
        
        Version savedVersion = Version.builder()
                .id(1L)
                .functionUnit(functionUnit)
                .versionNumber("1.0.1")
                .changeLog("Test publish")
                .publishedBy("testuser")
                .build();
        
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.save(any(Version.class))).thenReturn(savedVersion);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        
        // Mock validation result
        ValidationResult validationResult = new ValidationResult();
        // 使用反射或者直接创建一个有效的 ValidationResult
        
        // When: 调用发布方法
        FunctionUnit result = functionUnitComponent.publish(1L, "Test publish");
        
        // Then: 验证发布成功，并且使用了正确的操作者
        assertNotNull(result);
        assertEquals(FunctionUnitStatus.PUBLISHED, result.getStatus());
        verify(versionRepository).save(any(Version.class));
        verify(functionUnitRepository).save(functionUnit);
    }
    
    /**
     * After a re-import snapshot took 1.0.0 without advancing currentVersion, publish must write a new
     * snapshot under the next free number instead of treating 1.0.0 as "already published".
     */
    @Test
    void testPublish_AfterReimportSnapshot_CreatesSnapshotUnderNextFreeVersion() throws Exception {
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L).name("Test Function").status(FunctionUnitStatus.DRAFT).build();
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.findByFunctionUnitIdAndVersionNumber(1L, "1.0.0"))
                .thenReturn(Optional.of(Version.builder().versionNumber("1.0.0").build()));
        when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);

        FunctionUnit result = functionUnitComponent.publish(1L, "Test publish");

        ArgumentCaptor<Version> saved = ArgumentCaptor.forClass(Version.class);
        verify(versionRepository).save(saved.capture());
        assertEquals("1.0.1", saved.getValue().getVersionNumber());
        assertEquals("1.0.1", result.getCurrentVersion());
        assertEquals(FunctionUnitStatus.PUBLISHED, result.getStatus());
    }

    /**
     * 测试用例 2: publish 时无认证信息
     * 验证当没有认证信息时，getCurrentOperator() 返回 "system"
     */
    @Test
    void testPublish_WithoutAuthentication() throws Exception {
        // Given: SecurityContext 为空
        SecurityContextHolder.clearContext();
        
        // 准备测试数据
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L)
                .name("Test Function")
                .currentVersion("1.0.0")
                .status(FunctionUnitStatus.DRAFT)
                .build();
        
        Version savedVersion = Version.builder()
                .id(1L)
                .functionUnit(functionUnit)
                .versionNumber("1.0.1")
                .changeLog("Test publish")
                .publishedBy("system")
                .build();
        
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.save(any(Version.class))).thenReturn(savedVersion);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        
        // When: 调用发布方法
        FunctionUnit result = functionUnitComponent.publish(1L, "Test publish");
        
        // Then: 验证发布成功，并且使用了 "system" 作为操作者
        assertNotNull(result);
        assertEquals(FunctionUnitStatus.PUBLISHED, result.getStatus());
        verify(versionRepository).save(any(Version.class));
    }
    
    /**
     * 测试用例 3: publish 时匿名用户
     * 验证当用户为匿名时，getCurrentOperator() 返回 "system"
     */
    @Test
    void testPublish_WithAnonymousUser() throws Exception {
        // Given: 设置匿名认证
        Authentication auth = new AnonymousAuthenticationToken(
            "key", "anonymous", Collections.singletonList(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        );
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(securityContext);
        
        // 准备测试数据
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L)
                .name("Test Function")
                .currentVersion("1.0.0")
                .status(FunctionUnitStatus.DRAFT)
                .build();
        
        Version savedVersion = Version.builder()
                .id(1L)
                .functionUnit(functionUnit)
                .versionNumber("1.0.1")
                .changeLog("Test publish")
                .publishedBy("system")
                .build();
        
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.save(any(Version.class))).thenReturn(savedVersion);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        
        // When: 调用发布方法
        FunctionUnit result = functionUnitComponent.publish(1L, "Test publish");
        
        // Then: 验证发布成功，并且使用了 "system" 作为操作者
        assertNotNull(result);
        assertEquals(FunctionUnitStatus.PUBLISHED, result.getStatus());
        verify(versionRepository).save(any(Version.class));
    }
    
    /**
     * 测试用例 4: 认证对象为 null
     * 验证当认证对象为 null 时，getCurrentOperator() 返回 "system"
     */
    @Test
    void testPublish_WithNullAuthentication() throws Exception {
        // Given: SecurityContext 返回 null 认证
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(null);
        SecurityContextHolder.setContext(securityContext);
        
        // 准备测试数据
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L)
                .name("Test Function")
                .currentVersion("1.0.0")
                .status(FunctionUnitStatus.DRAFT)
                .build();
        
        Version savedVersion = Version.builder()
                .id(1L)
                .functionUnit(functionUnit)
                .versionNumber("1.0.1")
                .changeLog("Test publish")
                .publishedBy("system")
                .build();
        
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.save(any(Version.class))).thenReturn(savedVersion);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        
        // When: 调用发布方法
        FunctionUnit result = functionUnitComponent.publish(1L, "Test publish");
        
        // Then: 验证发布成功，并且使用了 "system" 作为操作者
        assertNotNull(result);
        assertEquals(FunctionUnitStatus.PUBLISHED, result.getStatus());
        verify(versionRepository).save(any(Version.class));
    }

    @Test
    void delete_nonArchivedFunctionUnit_shouldArchiveInsteadOfDelete() {
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L)
                .name("kk")
                .code("kk-20260101-abc123")
                .status(FunctionUnitStatus.PUBLISHED)
                .build();
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(functionUnitRepository.save(functionUnit)).thenReturn(functionUnit);

        functionUnitComponent.delete(1L);

        assertEquals(FunctionUnitStatus.ARCHIVED, functionUnit.getStatus());
        verify(functionUnitRepository).save(functionUnit);
        verify(functionUnitRepository, never()).delete(any(FunctionUnit.class));
        verify(functionUnitDevGroupAssignmentRepository, never()).deleteByFunctionUnitId(any());
    }

    @Test
    void delete_archivedFunctionUnit_shouldPermanentlyDelete() {
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(2L)
                .name("kk")
                .code("kk-20260101-abc123")
                .status(FunctionUnitStatus.ARCHIVED)
                .build();
        when(functionUnitRepository.findById(2L)).thenReturn(Optional.of(functionUnit));

        functionUnitComponent.delete(2L);

        // Foreign keys must be bulk-deleted (and the context flushed) BEFORE the JPA
        // cascade walks the table/field graph, otherwise Hibernate tries to null the
        // NOT NULL dw_foreign_keys.field_id and the delete fails entirely.
        InOrder order = inOrder(foreignKeyRepository, functionUnitRepository);
        order.verify(foreignKeyRepository).deleteByFunctionUnitId(2L);
        order.verify(foreignKeyRepository).flush();
        order.verify(functionUnitRepository).delete(functionUnit);
        verify(functionUnitRepository, never()).save(any(FunctionUnit.class));
    }
}