package com.admin.component;

import com.admin.dto.response.ImportResult;
import com.admin.dto.response.ValidationResult;
import com.admin.entity.FunctionUnit;
import com.admin.enums.FunctionUnitStatus;
import com.admin.exception.AdminBusinessException;
import com.admin.repository.FunctionUnitAccessRepository;
import com.admin.repository.FunctionUnitAuditAccessRepository;
import com.admin.repository.FunctionUnitContentRepository;
import com.admin.repository.FunctionUnitDependencyRepository;
import com.admin.repository.FunctionUnitRepository;
import com.platform.common.i18n.I18nService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DW one-click deploy always POSTs /validate after import. Same code+version overwrite
 * leaves Admin catalog DEPLOYED; content validation must run without demoting status.
 */
@ExtendWith(MockitoExtension.class)
class FunctionUnitLifecycleValidateTest {

    @Mock
    private FunctionUnitRepository functionUnitRepository;
    @Mock
    private FunctionUnitDependencyRepository dependencyRepository;
    @Mock
    private FunctionUnitContentRepository contentRepository;
    @Mock
    private FunctionUnitAccessRepository accessRepository;
    @Mock
    private FunctionUnitAuditAccessRepository auditAccessRepository;
    @Mock
    private FunctionUnitValidationComponent validationComponent;
    @Mock
    private FunctionUnitVersionComponent versionComponent;
    @Mock
    private FunctionUnitLookup functionUnitLookup;
    @Mock
    private I18nService i18nService;

    @InjectMocks
    private FunctionUnitLifecycleComponent lifecycleComponent;

    @Test
    void validateFunctionUnit_whenDraftAndValid_marksValidated() {
        FunctionUnit unit = unit(FunctionUnitStatus.DRAFT);
        when(functionUnitLookup.getById("fu-1")).thenReturn(unit);
        when(validationComponent.validate("fu-1")).thenReturn(ValidationResult.success());
        when(functionUnitRepository.save(unit)).thenReturn(unit);

        ValidationResult result = lifecycleComponent.validateFunctionUnit("fu-1", "tester");

        assertThat(result.isValid()).isTrue();
        assertThat(result.getStatus()).isEqualTo(FunctionUnitStatus.VALIDATED.name());
        assertThat(unit.getStatus()).isEqualTo(FunctionUnitStatus.VALIDATED);
        verify(functionUnitRepository).save(unit);
    }

    @Test
    void validateFunctionUnit_whenDeployedAndValid_keepsDeployed() {
        FunctionUnit unit = unit(FunctionUnitStatus.DEPLOYED);
        when(functionUnitLookup.getById("fu-1")).thenReturn(unit);
        when(validationComponent.validate("fu-1")).thenReturn(ValidationResult.success());

        ValidationResult result = lifecycleComponent.validateFunctionUnit("fu-1", "tester");

        assertThat(result.isValid()).isTrue();
        assertThat(result.getStatus()).isEqualTo(FunctionUnitStatus.DEPLOYED.name());
        assertThat(unit.getStatus()).isEqualTo(FunctionUnitStatus.DEPLOYED);
        verify(functionUnitRepository, never()).save(any());
    }

    @Test
    void validateFunctionUnit_whenValidatedAndValid_keepsValidated() {
        FunctionUnit unit = unit(FunctionUnitStatus.VALIDATED);
        when(functionUnitLookup.getById("fu-1")).thenReturn(unit);
        when(validationComponent.validate("fu-1")).thenReturn(ValidationResult.success());

        ValidationResult result = lifecycleComponent.validateFunctionUnit("fu-1", "tester");

        assertThat(result.isValid()).isTrue();
        assertThat(result.getStatus()).isEqualTo(FunctionUnitStatus.VALIDATED.name());
        assertThat(unit.getStatus()).isEqualTo(FunctionUnitStatus.VALIDATED);
        verify(functionUnitRepository, never()).save(any());
    }

    @Test
    void validateFunctionUnit_whenDeployedAndInvalid_keepsDeployed() {
        FunctionUnit unit = unit(FunctionUnitStatus.DEPLOYED);
        when(functionUnitLookup.getById("fu-1")).thenReturn(unit);
        when(validationComponent.validate("fu-1")).thenReturn(ValidationResult.failure(
                List.of(ImportResult.ValidationError.builder()
                        .type("BPMN")
                        .message("bad")
                        .build())));

        ValidationResult result = lifecycleComponent.validateFunctionUnit("fu-1", "tester");

        assertThat(result.isValid()).isFalse();
        assertThat(unit.getStatus()).isEqualTo(FunctionUnitStatus.DEPLOYED);
        verify(functionUnitRepository, never()).save(any());
    }

    @Test
    void validateFunctionUnit_whenArchived_throwsInvalidStatus() {
        FunctionUnit unit = unit(FunctionUnitStatus.ARCHIVED);
        when(functionUnitLookup.getById("fu-1")).thenReturn(unit);
        when(i18nService.getMessage(eq("admin.fu.validate_draft_only"), any(Object[].class)))
                .thenReturn("only draft");

        assertThatThrownBy(() -> lifecycleComponent.validateFunctionUnit("fu-1", "tester"))
                .isInstanceOf(AdminBusinessException.class)
                .extracting("errorCode")
                .isEqualTo("INVALID_STATUS");
        verify(validationComponent, never()).validate(any());
    }

    private static FunctionUnit unit(FunctionUnitStatus status) {
        return FunctionUnit.builder()
                .id("fu-1")
                .code("demo")
                .name("Demo")
                .version("1.0.0")
                .status(status)
                .enabled(true)
                .build();
    }
}
