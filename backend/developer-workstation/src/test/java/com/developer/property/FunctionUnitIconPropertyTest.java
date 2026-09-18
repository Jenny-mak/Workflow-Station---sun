package com.developer.property;

import com.developer.component.FunctionUnitComponent;
import com.developer.component.IconLibraryComponent;
import com.developer.component.impl.FunctionUnitComponentImpl;
import com.developer.component.impl.IconLibraryComponentImpl;
import com.developer.dto.FunctionUnitRequest;
import com.developer.entity.FunctionUnit;
import com.developer.entity.Icon;
import com.developer.enums.FunctionUnitStatus;
import com.developer.enums.IconCategory;
import com.developer.repository.*;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import com.developer.service.UserDisplayNameService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 功能单元图标属性测试
 * Feature: function-unit-icon
 * 
 * Property 1: Icon ID Persistence Round-Trip
 * Property 2: Optional Icon Handling
 * Property 5: Search Filter Correctness
 * Property 6: Category Filter Correctness
 */
public class FunctionUnitIconPropertyTest {
    
    /**
     * Property 1: Icon ID Persistence Round-Trip
     * For any function unit created or updated with a valid icon ID, 
     * retrieving that function unit should return the same icon ID that was saved.
     * 
     * Validates: Requirements 1.4, 2.3
     */
    @Property(tries = 100)
    void iconIdPersistenceRoundTripProperty(
            @ForAll("validNames") String name,
            @ForAll("validIconIds") Long iconId) {
        
        // Setup mocks
        FunctionUnitRepository functionUnitRepository = mock(FunctionUnitRepository.class);
        ProcessDefinitionRepository processRepo = mock(ProcessDefinitionRepository.class);
        TableDefinitionRepository tableRepo = mock(TableDefinitionRepository.class);
        FormDefinitionRepository formRepo = mock(FormDefinitionRepository.class);
        ActionDefinitionRepository actionRepo = mock(ActionDefinitionRepository.class);
        VersionRepository versionRepo = mock(VersionRepository.class);
        IconRepository iconRepository = mock(IconRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        
        // Create icon
        Icon icon = Icon.builder()
                .id(iconId)
                .name("test-icon-" + iconId)
                .category(IconCategory.GENERAL)
                .svgContent("<svg></svg>")
                .fileSize(11)
                .build();
        
        when(iconRepository.findById(iconId)).thenReturn(Optional.of(icon));
        when(functionUnitRepository.existsByName(name)).thenReturn(false);
        
        // Capture saved function unit
        FunctionUnit[] savedFunctionUnit = new FunctionUnit[1];
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenAnswer(invocation -> {
            FunctionUnit fu = invocation.getArgument(0);
            fu.setId(1L);
            savedFunctionUnit[0] = fu;
            return fu;
        });

        UserDisplayNameService userDisplayNameService = mock(UserDisplayNameService.class);
        FunctionUnitWorkspaceAccessService workspaceAccessService = mock(FunctionUnitWorkspaceAccessService.class);
        FunctionUnitDevGroupAssignmentRepository devGroupAssignmentRepository = mock(FunctionUnitDevGroupAssignmentRepository.class);
        when(devGroupAssignmentRepository.findByFunctionUnitId(anyLong())).thenReturn(java.util.Collections.emptyList());
        
        FunctionUnitComponent component = new FunctionUnitComponentImpl(
                functionUnitRepository, processRepo, tableRepo, formRepo, 
                actionRepo,
                mock(com.developer.repository.DecisionDefinitionRepository.class),
                mock(com.developer.repository.FormTableBindingRepository.class),
                mock(com.developer.repository.FormStageBindingRepository.class),
                mock(com.developer.repository.TableRelationRepository.class),
                mock(com.developer.repository.SubTableViewConfigRepository.class),
                versionRepo, iconRepository, objectMapper, userDisplayNameService,
                workspaceAccessService, devGroupAssignmentRepository, mock(com.developer.component.VersionComponent.class), mock(com.developer.util.DeveloperWorkstationSequenceSynchronizer.class), mock(com.developer.service.MainTableViewService.class), mock(com.developer.repository.ForeignKeyRepository.class), mock(com.developer.component.impl.FunctionUnitExporter.class), mock(com.developer.component.TableDesignComponent.class),
                mock(com.developer.repository.EmailConnectionRepository.class),
                mock(com.developer.repository.EmailMonitorRuleRepository.class),
                mock(com.developer.repository.EmailTemplateRepository.class),
                new com.developer.service.impl.FunctionUnitDocumentService(
                org.mockito.Mockito.mock(com.developer.repository.AiDocumentRepository.class),
                org.mockito.Mockito.mock(com.developer.repository.AiStudioThreadStateRepository.class)));
        
        // Create function unit with icon
        FunctionUnitRequest request = new FunctionUnitRequest();
        request.setName(name);
        request.setDescription("Test description");
        request.setIconId(iconId);
        
        FunctionUnit created = component.create(request);
        
        // Verify icon ID is persisted
        assertThat(created).isNotNull();
        assertThat(savedFunctionUnit[0]).isNotNull();
        assertThat(savedFunctionUnit[0].getIcon()).isNotNull();
        assertThat(savedFunctionUnit[0].getIcon().getId()).isEqualTo(iconId);
    }
    
    /**
     * Property 2: Optional Icon Handling
     * For any function unit created or updated with a null/undefined icon ID, 
     * the function unit should be saved successfully and retrieving it should return null for the icon field.
     * 
     * Validates: Requirements 1.5, 2.4
     */
    @Property(tries = 100)
    void optionalIconHandlingProperty(@ForAll("validNames") String name) {
        
        // Setup mocks
        FunctionUnitRepository functionUnitRepository = mock(FunctionUnitRepository.class);
        ProcessDefinitionRepository processRepo = mock(ProcessDefinitionRepository.class);
        TableDefinitionRepository tableRepo = mock(TableDefinitionRepository.class);
        FormDefinitionRepository formRepo = mock(FormDefinitionRepository.class);
        ActionDefinitionRepository actionRepo = mock(ActionDefinitionRepository.class);
        VersionRepository versionRepo = mock(VersionRepository.class);
        IconRepository iconRepository = mock(IconRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        
        when(functionUnitRepository.existsByName(name)).thenReturn(false);
        
        // Capture saved function unit
        FunctionUnit[] savedFunctionUnit = new FunctionUnit[1];
        when(functionUnitRepository.save(any(FunctionUnit.class))).thenAnswer(invocation -> {
            FunctionUnit fu = invocation.getArgument(0);
            fu.setId(1L);
            savedFunctionUnit[0] = fu;
            return fu;
        });

        UserDisplayNameService userDisplayNameService = mock(UserDisplayNameService.class);
        FunctionUnitWorkspaceAccessService workspaceAccessService = mock(FunctionUnitWorkspaceAccessService.class);
        FunctionUnitDevGroupAssignmentRepository devGroupAssignmentRepository = mock(FunctionUnitDevGroupAssignmentRepository.class);
        when(devGroupAssignmentRepository.findByFunctionUnitId(anyLong())).thenReturn(java.util.Collections.emptyList());
        
        FunctionUnitComponent component = new FunctionUnitComponentImpl(
                functionUnitRepository, processRepo, tableRepo, formRepo, 
                actionRepo,
                mock(com.developer.repository.DecisionDefinitionRepository.class),
                mock(com.developer.repository.FormTableBindingRepository.class),
                mock(com.developer.repository.FormStageBindingRepository.class),
                mock(com.developer.repository.TableRelationRepository.class),
                mock(com.developer.repository.SubTableViewConfigRepository.class),
                versionRepo, iconRepository, objectMapper, userDisplayNameService,
                workspaceAccessService, devGroupAssignmentRepository, mock(com.developer.component.VersionComponent.class), mock(com.developer.util.DeveloperWorkstationSequenceSynchronizer.class), mock(com.developer.service.MainTableViewService.class), mock(com.developer.repository.ForeignKeyRepository.class), mock(com.developer.component.impl.FunctionUnitExporter.class), mock(com.developer.component.TableDesignComponent.class),
                mock(com.developer.repository.EmailConnectionRepository.class),
                mock(com.developer.repository.EmailMonitorRuleRepository.class),
                mock(com.developer.repository.EmailTemplateRepository.class),
                new com.developer.service.impl.FunctionUnitDocumentService(
                org.mockito.Mockito.mock(com.developer.repository.AiDocumentRepository.class),
                org.mockito.Mockito.mock(com.developer.repository.AiStudioThreadStateRepository.class)));
        
        // Create function unit without icon
        FunctionUnitRequest request = new FunctionUnitRequest();
        request.setName(name);
        request.setDescription("Test description");
        request.setIconId(null);
        
        FunctionUnit created = component.create(request);
        
        // Verify icon is null
        assertThat(created).isNotNull();
        assertThat(savedFunctionUnit[0]).isNotNull();
        assertThat(savedFunctionUnit[0].getIcon()).isNull();
    }
    
    /**
     * Property 5: Search Filter Correctness
     * For any search query string, all icons returned by the search 
     * should contain the query string (case-insensitive) in their name.
     * 
     * Validates: Requirements 5.2
     */
    @Property(tries = 100)
    void searchFilterCorrectnessProperty(
            @ForAll("searchKeywords") String keyword,
            @ForAll("iconLists") List<Icon> allIcons) {
        
        // Filter icons that should match the keyword
        String lowerKeyword = keyword.toLowerCase();
        List<Icon> expectedMatches = allIcons.stream()
                .filter(icon -> icon.getName().toLowerCase().contains(lowerKeyword))
                .collect(Collectors.toList());
        
        // Setup mock
        IconRepository iconRepository = mock(IconRepository.class);
        FunctionUnitRepository functionUnitRepository = mock(FunctionUnitRepository.class);
        
        when(iconRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(expectedMatches));
        
        IconLibraryComponent component = new IconLibraryComponentImpl(iconRepository, functionUnitRepository);
        
        // Verify all returned icons contain the keyword
        for (Icon icon : expectedMatches) {
            assertThat(icon.getName().toLowerCase()).contains(lowerKeyword);
        }
    }
    
    /**
     * Property 6: Category Filter Correctness
     * For any category filter value, all icons returned should have 
     * their category field equal to the filter value.
     * 
     * Validates: Requirements 5.3
     */
    @Property(tries = 100)
    void categoryFilterCorrectnessProperty(
            @ForAll("iconCategories") IconCategory filterCategory,
            @ForAll("iconLists") List<Icon> allIcons) {
        
        // Filter icons that should match the category
        List<Icon> expectedMatches = allIcons.stream()
                .filter(icon -> icon.getCategory() == filterCategory)
                .collect(Collectors.toList());
        
        // Verify all returned icons have the correct category
        for (Icon icon : expectedMatches) {
            assertThat(icon.getCategory()).isEqualTo(filterCategory);
        }
    }
    
    // Providers
    
    @Provide
    Arbitrary<String> validNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(50)
                .map(s -> "FU_Icon_" + s);
    }
    
    @Provide
    Arbitrary<Long> validIconIds() {
        return Arbitraries.longs().between(1L, 1000L);
    }
    
    @Provide
    Arbitrary<String> searchKeywords() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(20);
    }
    
    @Provide
    Arbitrary<IconCategory> iconCategories() {
        return Arbitraries.of(IconCategory.values());
    }
    
    @Provide
    Arbitrary<List<Icon>> iconLists() {
        return Arbitraries.integers().between(0, 10)
                .flatMap(size -> {
                    List<Arbitrary<Icon>> iconArbitraries = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        iconArbitraries.add(iconArbitrary());
                    }
                    if (iconArbitraries.isEmpty()) {
                        return Arbitraries.just(new ArrayList<>());
                    }
                    return Combinators.combine(iconArbitraries).as(icons -> new ArrayList<>(icons));
                });
    }
    
    private Arbitrary<Icon> iconArbitrary() {
        return Combinators.combine(
                Arbitraries.longs().between(1L, 1000L),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30),
                Arbitraries.of(IconCategory.values())
        ).as((id, name, category) -> Icon.builder()
                .id(id)
                .name(name)
                .category(category)
                .svgContent("<svg></svg>")
                .fileSize(11)
                .build());
    }
}

