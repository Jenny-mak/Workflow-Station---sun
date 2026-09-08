package com.workflow.email.inbound;

import com.platform.common.util.ApiResponseBodyUnwrap;
import com.workflow.dto.response.ProcessInstanceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Starts email-created cases through user-portal (full start enrich path) and can hydrate
 * {@code up_process_instance} for engine-only starts that still need a portal row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailMonitorPortalSyncComponent {

    private final RestTemplate restTemplate;

    @Value("${user-portal.url:http://user-portal:8080}")
    private String userPortalUrl;

    @Value("${portal.internal.api-token:${PORTAL_INTERNAL_API_TOKEN:}}")
    private String portalInternalApiToken;

    /**
     * Starts the process through user-portal so PK / formula / Owner / audit / Request ID
     * and first-task auto-complete match a Portal UI start.
     */
    public ProcessInstanceResult startPortalProcess(
            String processDefinitionKey,
            String functionUnitCode,
            String startUserId,
            String businessKey,
            Map<String, Object> variables) {
        if (!StringUtils.hasText(portalInternalApiToken)) {
            return fail("portal.internal.api-token not configured");
        }
        if (!StringUtils.hasText(startUserId)) {
            return fail("system initiator user id is required");
        }
        try {
            return postStart(processDefinitionKey, functionUnitCode, startUserId, businessKey, variables);
        } catch (Exception e) {
            log.warn("Portal email start failed: {}", e.getMessage());
            return fail(e.getMessage());
        }
    }

    public void hydratePortalProcessInstanceAsync(String processInstanceId) {
        hydratePortalProcessInstanceAsync(processInstanceId, null);
    }

    /**
     * @param engineSnapshot optional start-time process fields (key, businessKey, variables, …)
     *                       so portal can persist without a JWT-authenticated engine GET
     */
    public void hydratePortalProcessInstanceAsync(String processInstanceId, Map<String, Object> engineSnapshot) {
        if (!StringUtils.hasText(processInstanceId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                // Allow the engine start transaction to commit before portal reads variables/tasks.
                Thread.sleep(500);
                if (!StringUtils.hasText(portalInternalApiToken)) {
                    log.warn("portal.internal.api-token not configured; skip portal hydrate for {}", processInstanceId);
                    return;
                }
                String url = userPortalUrl + "/api/portal/internal/runtime/hydrate-process-instance";
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Internal-Token", portalInternalApiToken);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("processInstanceId", processInstanceId);
                if (engineSnapshot != null && !engineSnapshot.isEmpty()) {
                    body.put("engineSnapshot", engineSnapshot);
                }
                restTemplate.postForObject(
                        url,
                        new HttpEntity<>(body, headers),
                        Map.class);
                log.info("Requested portal hydrate for email-started process {}", processInstanceId);
            } catch (Exception e) {
                // FALLBACK(ux): hydrate is best-effort after email start; failure must not block case creation.
                log.warn("Portal hydrate failed for email-started process {}: {}", processInstanceId, e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private ProcessInstanceResult postStart(
            String processDefinitionKey,
            String functionUnitCode,
            String startUserId,
            String businessKey,
            Map<String, Object> variables) {
        String url = userPortalUrl + "/api/portal/internal/runtime/start-process";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", portalInternalApiToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startUserId", startUserId);
        if (StringUtils.hasText(functionUnitCode)) {
            body.put("functionUnitCode", functionUnitCode);
        }
        if (StringUtils.hasText(processDefinitionKey)) {
            body.put("processDefinitionKey", processDefinitionKey);
        }
        body.put("businessKey", businessKey);
        body.put("variables", variables);
        Map<String, Object> response = restTemplate.postForObject(
                url, new HttpEntity<>(body, headers), Map.class);
        return toStartResult(response);
    }

    private ProcessInstanceResult toStartResult(Map<String, Object> response) {
        if (response == null) {
            return fail("empty portal response");
        }
        if (!Boolean.TRUE.equals(response.get("success"))) {
            return fail(errorMessage(response));
        }
        Map<String, Object> data = ApiResponseBodyUnwrap.unwrapDataMap(response);
        Object id = data.get("id");
        if (id == null) {
            id = data.get("processInstanceId");
        }
        if (id == null || String.valueOf(id).isBlank()) {
            return fail("portal start returned no processInstanceId");
        }
        return ProcessInstanceResult.builder()
                .success(true)
                .processInstanceId(String.valueOf(id))
                .processDefinitionId(stringValue(data.get("processDefinitionId")))
                .processDefinitionKey(stringValue(data.get("processDefinitionKey")))
                .businessKey(stringValue(data.get("businessKey")))
                .name(stringValue(data.get("processDefinitionName")))
                .startUserId(stringValue(data.get("startUserId")))
                .build();
    }

    private static String errorMessage(Map<String, Object> response) {
        Object error = response.get("error");
        if (error instanceof Map<?, ?> map && map.get("message") != null) {
            return String.valueOf(map.get("message"));
        }
        return "portal start failed";
    }

    private static ProcessInstanceResult fail(String message) {
        return ProcessInstanceResult.builder()
                .success(false)
                .message(message)
                .build();
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
