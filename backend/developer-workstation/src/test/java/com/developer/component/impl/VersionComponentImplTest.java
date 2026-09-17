package com.developer.component.impl;

import com.developer.entity.FunctionUnit;
import com.developer.entity.Version;
import com.developer.enums.FunctionUnitStatus;
import com.developer.repository.FunctionUnitRepository;
import com.developer.repository.VersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 测试 VersionComponentImpl 的操作者信息获取功能
 * 
 * 验证属性 1: 操作者信息获取的正确性
 */
@ExtendWith(MockitoExtension.class)
class VersionComponentImplTest {
    
    @Mock
    private VersionRepository versionRepository;
    
    @Mock
    private FunctionUnitRepository functionUnitRepository;
    
    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private com.developer.util.DeveloperWorkstationSequenceSynchronizer sequenceSynchronizer;

    @Mock
    private FunctionUnitExporter functionUnitExporter;

    @Mock
    private FunctionUnitSnapshotRestorer snapshotRestorer;

    @Mock
    private com.developer.service.MainTableViewService mainTableViewService;

    @Mock
    private com.developer.repository.SubTableViewConfigRepository subTableViewConfigRepository;

    @Mock
    private com.developer.repository.ForeignKeyRepository foreignKeyRepository;

    @Mock
    private com.developer.repository.LinkFormComponentRepository linkFormComponentRepository;

    @Mock
    private com.developer.repository.EmailConnectionRepository emailConnectionRepository;

    @Mock
    private com.developer.repository.EmailMonitorRuleRepository emailMonitorRuleRepository;

    @Mock
    private com.developer.repository.EmailTemplateRepository emailTemplateRepository;

    @Mock
    private com.developer.repository.TableRelationRepository tableRelationRepository;

    @Mock
    private com.developer.repository.ProcessDefinitionRepository processDefinitionRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private com.developer.service.impl.FunctionUnitDocumentService documentService;

    @InjectMocks
    private VersionComponentImpl versionComponent;
    
    @BeforeEach
    void setUp() {
        // 清理 SecurityContext
        SecurityContextHolder.clearContext();
    }
    
    /**
     * 测试用例 1: createVersion 时有效的认证用户
     * 验证当存在已认证用户时，getCurrentOperator() 返回用户名
     */
    @Test
    void testCreateVersion_WithAuthenticatedUser() throws Exception {
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
                .build();
        
        Version savedVersion = Version.builder()
                .id(1L)
                .functionUnit(functionUnit)
                .versionNumber("1.0.1")
                .changeLog("Test change")
                .publishedBy("testuser")
                .build();
        
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.save(any(Version.class))).thenReturn(savedVersion);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(functionUnitExporter.buildVersionSnapshotPayload(anyLong())).thenReturn(Collections.emptyMap());
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        
        // When: 调用创建版本方法
        Version result = versionComponent.createVersion(1L, "Test change");
        
        // Then: 验证版本创建成功，并且使用了正确的操作者
        assertNotNull(result);
        assertEquals("testuser", result.getPublishedBy());
        verify(versionRepository).save(any(Version.class));
        verify(functionUnitRepository).save(functionUnit);
    }
    
    /**
     * 测试用例 2: createVersion 时无认证信息
     * 验证当没有认证信息时，getCurrentOperator() 返回 "system"
     */
    @Test
    void testCreateVersion_WithoutAuthentication() throws Exception {
        // Given: SecurityContext 为空
        SecurityContextHolder.clearContext();
        
        // 准备测试数据
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L)
                .name("Test Function")
                .currentVersion("1.0.0")
                .build();
        
        Version savedVersion = Version.builder()
                .id(1L)
                .functionUnit(functionUnit)
                .versionNumber("1.0.1")
                .changeLog("Test change")
                .publishedBy("system")
                .build();
        
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.save(any(Version.class))).thenReturn(savedVersion);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(functionUnitExporter.buildVersionSnapshotPayload(anyLong())).thenReturn(Collections.emptyMap());
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        
        // When: 调用创建版本方法
        Version result = versionComponent.createVersion(1L, "Test change");
        
        // Then: 验证版本创建成功，并且使用了 "system" 作为操作者
        assertNotNull(result);
        assertEquals("system", result.getPublishedBy());
        verify(versionRepository).save(any(Version.class));
    }
    
    /**
     * 测试用例 3: rollback 时匿名用户
     * 验证当用户为匿名时，getCurrentOperator() 返回 "system"
     */
    @Test
    void testRollback_WithAnonymousUser() throws Exception {
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
                .currentVersion("1.0.2")
                .status(FunctionUnitStatus.PUBLISHED)
                .build();
        
        Version targetVersion = Version.builder()
                .id(2L)
                .functionUnit(functionUnit)
                .versionNumber("1.0.1")
                .snapshotData(new byte[0])
                .build();
        
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.findById(2L)).thenReturn(Optional.of(targetVersion));
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(functionUnitExporter.buildVersionSnapshotPayload(anyLong())).thenReturn(Collections.emptyMap());
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        when(objectMapper.readValue(any(byte[].class), eq(java.util.Map.class))).thenReturn(Collections.emptyMap());
        
        // When: 调用回滚方法
        FunctionUnit result = versionComponent.rollback(1L, 2L);
        
        // Then: 验证回滚成功，并且使用了 "system" 作为操作者
        assertNotNull(result);
        verify(versionRepository, times(2)).saveAndFlush(any(Version.class)); // 备份版本 + 回滚版本
        verify(functionUnitRepository).save(functionUnit);
    }
    
    @Test
    void testRollback_RestoresSnapshotDocumentsAsNewVersions() throws Exception {
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L).name("Test Function").currentVersion("1.0.2").status(FunctionUnitStatus.DRAFT).build();
        Version targetVersion = Version.builder()
                .id(2L).functionUnit(functionUnit).versionNumber("1.0.1").snapshotData(new byte[0]).build();
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.findById(2L)).thenReturn(Optional.of(targetVersion));
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(functionUnitExporter.buildVersionSnapshotPayload(anyLong())).thenReturn(Collections.emptyMap());
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        when(objectMapper.readValue(any(byte[].class), eq(java.util.Map.class)))
                .thenReturn(java.util.Map.of("documents", java.util.Map.of("DESIGN", "design at 1.0.1")));

        versionComponent.rollback(1L, 2L);

        verify(documentService).appendFromPackage(1L,
                java.util.Map.of(com.developer.enums.AiDocumentType.DESIGN, "design at 1.0.1"),
                "ROLLBACK:1.0.1", "system");
    }

    /**
     * Re-import snapshots take "current + 1" without advancing currentVersion, so after a re-import the
     * rollback backup/rollback numbers must skip versions that already exist (uk_version_fu).
     */
    @Test
    void testRollback_AfterReimportSnapshot_SkipsTakenVersionNumbers() throws Exception {
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L).name("Test Function").status(FunctionUnitStatus.DRAFT).build();
        Version reimportSnapshot = Version.builder()
                .id(2L).functionUnit(functionUnit).versionNumber("1.0.0").snapshotData(new byte[0]).build();
        java.util.Set<String> taken = new java.util.HashSet<>(java.util.Set.of("1.0.0", "1.0.2"));
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.findById(2L)).thenReturn(Optional.of(reimportSnapshot));
        when(versionRepository.findByFunctionUnitIdAndVersionNumber(eq(1L), anyString()))
                .thenAnswer(inv -> taken.contains(inv.<String>getArgument(1))
                        ? Optional.of(Version.builder().build()) : Optional.empty());
        when(versionRepository.saveAndFlush(any(Version.class))).thenAnswer(inv -> {
            taken.add(inv.<Version>getArgument(0).getVersionNumber());
            return inv.getArgument(0);
        });
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(functionUnitExporter.buildVersionSnapshotPayload(anyLong())).thenReturn(Collections.emptyMap());
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        when(objectMapper.readValue(any(byte[].class), eq(java.util.Map.class))).thenReturn(Collections.emptyMap());

        FunctionUnit result = versionComponent.rollback(1L, 2L);

        org.mockito.ArgumentCaptor<Version> saved = org.mockito.ArgumentCaptor.forClass(Version.class);
        verify(versionRepository, times(2)).saveAndFlush(saved.capture());
        assertEquals("Auto backup before rollback", saved.getAllValues().get(0).getChangeLog());
        assertEquals("1.0.1", saved.getAllValues().get(0).getVersionNumber());
        assertEquals("1.0.3", saved.getAllValues().get(1).getVersionNumber());
        assertEquals("1.0.3", result.getCurrentVersion());
    }

    @Test
    void testCreateVersion_SkipsTakenVersionNumber() throws Exception {
        FunctionUnit functionUnit = FunctionUnit.builder().id(1L).name("Test Function").build();
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.findByFunctionUnitIdAndVersionNumber(1L, "1.0.0"))
                .thenReturn(Optional.of(Version.builder().build()));
        when(versionRepository.save(any(Version.class))).thenAnswer(inv -> inv.getArgument(0));
        when(functionUnitExporter.buildVersionSnapshotPayload(anyLong())).thenReturn(Collections.emptyMap());
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);

        Version result = versionComponent.createVersion(1L, "manual");

        assertEquals("1.0.1", result.getVersionNumber());
        assertEquals("1.0.1", functionUnit.getCurrentVersion());
    }

    @Test
    void testRollback_LegacySnapshotLeavesDocumentsUntouched() throws Exception {
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L).name("Test Function").currentVersion("1.0.2").status(FunctionUnitStatus.DRAFT).build();
        Version targetVersion = Version.builder()
                .id(2L).functionUnit(functionUnit).versionNumber("1.0.1").snapshotData(new byte[0]).build();
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.findById(2L)).thenReturn(Optional.of(targetVersion));
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(functionUnitExporter.buildVersionSnapshotPayload(anyLong())).thenReturn(Collections.emptyMap());
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        when(objectMapper.readValue(any(byte[].class), eq(java.util.Map.class))).thenReturn(Collections.emptyMap());

        versionComponent.rollback(1L, 2L);

        verify(documentService).appendFromPackage(eq(1L), eq(java.util.Map.of()), any(), any());
    }

    /**
     * 测试用例 4: 认证对象为 null
     * 验证当认证对象为 null 时，getCurrentOperator() 返回 "system"
     */
    @Test
    void testCreateVersion_WithNullAuthentication() throws Exception {
        // Given: SecurityContext 返回 null 认证
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(null);
        SecurityContextHolder.setContext(securityContext);
        
        // 准备测试数据
        FunctionUnit functionUnit = FunctionUnit.builder()
                .id(1L)
                .name("Test Function")
                .currentVersion("1.0.0")
                .build();
        
        Version savedVersion = Version.builder()
                .id(1L)
                .functionUnit(functionUnit)
                .versionNumber("1.0.1")
                .changeLog("Test change")
                .publishedBy("system")
                .build();
        
        when(functionUnitRepository.findById(1L)).thenReturn(Optional.of(functionUnit));
        when(versionRepository.save(any(Version.class))).thenReturn(savedVersion);
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenReturn(functionUnit);
        when(functionUnitExporter.buildVersionSnapshotPayload(anyLong())).thenReturn(Collections.emptyMap());
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);
        
        // When: 调用创建版本方法
        Version result = versionComponent.createVersion(1L, "Test change");
        
        // Then: 验证版本创建成功，并且使用了 "system" 作为操作者
        assertNotNull(result);
        assertEquals("system", result.getPublishedBy());
        verify(versionRepository).save(any(Version.class));
    }
}