package com.portal.component;

import com.portal.dto.InternalEmailProcessStartRequest;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.dto.ProcessStartRequest;
import com.portal.exception.PortalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalEmailProcessStartComponentTest {

    @Mock
    private ProcessStartComponent processStartComponent;

    @InjectMocks
    private InternalEmailProcessStartComponent component;

    @Test
    void prefersFunctionUnitCodeAsProcessKey() {
        InternalEmailProcessStartRequest request = new InternalEmailProcessStartRequest();
        request.setStartUserId("system");
        request.setFunctionUnitCode("FU-MCY");
        request.setProcessDefinitionKey("case_process");
        request.setBusinessKey("email:m1");
        request.setVariables(Map.of("title", "x"));
        when(processStartComponent.startProcessFromInternal(any(), any(), any()))
                .thenReturn(ProcessInstanceInfo.builder().id("pi-1").build());

        ProcessInstanceInfo started = component.start(request);

        assertThat(started.getId()).isEqualTo("pi-1");
        ArgumentCaptor<ProcessStartRequest> captor = ArgumentCaptor.forClass(ProcessStartRequest.class);
        verify(processStartComponent).startProcessFromInternal(eq("system"), eq("FU-MCY"), captor.capture());
        assertThat(captor.getValue().getBusinessKey()).isEqualTo("email:m1");
        assertThat(captor.getValue().getFormData()).containsEntry("title", "x");
    }

    @Test
    void rejectsMissingProcessKey() {
        InternalEmailProcessStartRequest request = new InternalEmailProcessStartRequest();
        request.setStartUserId("system");

        assertThatThrownBy(() -> component.start(request))
                .isInstanceOf(PortalException.class)
                .hasMessageContaining("functionUnitCode");
    }
}
