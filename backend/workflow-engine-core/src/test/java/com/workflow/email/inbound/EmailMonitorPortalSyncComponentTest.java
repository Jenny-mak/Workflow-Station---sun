package com.workflow.email.inbound;

import com.workflow.dto.response.ProcessInstanceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailMonitorPortalSyncComponentTest {

    private RestTemplate restTemplate;
    private EmailMonitorPortalSyncComponent component;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        component = new EmailMonitorPortalSyncComponent(restTemplate);
        ReflectionTestUtils.setField(component, "userPortalUrl", "http://user-portal:8080");
        ReflectionTestUtils.setField(component, "portalInternalApiToken", "secret-token");
    }

    @Test
    void startPortalProcessReadsProcessInstanceIdFromApiResponse() {
        when(restTemplate.postForObject(contains("/start-process"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of(
                        "success", true,
                        "data", Map.of("id", "pi-42", "processDefinitionKey", "FU-MCY")));

        ProcessInstanceResult result = component.startPortalProcess(
                "case_process", "FU-MCY", "system", "email:m1", Map.of("title", "x"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProcessInstanceId()).isEqualTo("pi-42");
        assertThat(result.getProcessDefinitionKey()).isEqualTo("FU-MCY");
    }

    @Test
    void startPortalProcessFailsWhenTokenMissing() {
        ReflectionTestUtils.setField(component, "portalInternalApiToken", "");

        ProcessInstanceResult result = component.startPortalProcess(
                "case_process", "FU-MCY", "system", "email:m1", Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("api-token");
    }
}
