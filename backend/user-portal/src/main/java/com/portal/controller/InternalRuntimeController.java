package com.portal.controller;

import com.portal.component.InternalEmailProcessStartComponent;
import com.portal.component.ProcessInstanceHydrationComponent;
import com.portal.component.ProcessRuntimePurgeComponent;
import com.portal.config.PortalInternalApiProperties;
import com.portal.dto.InternalEmailProcessStartRequest;
import com.portal.dto.ProcessInstanceInfo;
import com.platform.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 供 admin-center 编排调用的内部 API（X-Internal-Token）
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/internal/runtime")
@RequiredArgsConstructor
public class InternalRuntimeController {

    private final PortalInternalApiProperties portalInternalApiProperties;
    private final ProcessRuntimePurgeComponent processRuntimePurgeComponent;
    private final ProcessInstanceHydrationComponent processInstanceHydrationComponent;
    private final InternalEmailProcessStartComponent internalEmailProcessStartComponent;

    @PostMapping("/hydrate-process-instance")
    public ApiResponse<Map<String, Object>> hydrateProcessInstance(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        portalInternalApiProperties.requireValidToken(token);
        Object rawId = body != null ? body.get("processInstanceId") : null;
        String processInstanceId = rawId != null ? String.valueOf(rawId).trim() : null;
        if (!StringUtils.hasText(processInstanceId)) {
            return ApiResponse.error("BAD_REQUEST", "processInstanceId 不能为空");
        }
        // Optional start-time snapshot from workflow-engine (avoids JWT-protected engine GET).
        Map<String, Object> snapshot = null;
        if (body != null) {
            Object nested = body.get("engineSnapshot");
            if (nested instanceof Map<?, ?> map) {
                snapshot = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) {
                        snapshot.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            }
        }
        processInstanceHydrationComponent.requireProcessInstance(processInstanceId, snapshot);
        return ApiResponse.success(Map.of("processInstanceId", processInstanceId, "hydrated", true));
    }

    @PostMapping("/start-process")
    public ApiResponse<ProcessInstanceInfo> startProcessFromEmail(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody @Valid InternalEmailProcessStartRequest body) {
        portalInternalApiProperties.requireValidToken(token);
        return ApiResponse.success(internalEmailProcessStartComponent.start(body));
    }

    @PostMapping("/purge-by-catalog")
    public ApiResponse<Map<String, Object>> purgeByCatalog(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody Map<String, String> body) {
        portalInternalApiProperties.requireValidToken(token);
        String rawCatalogId = body != null ? body.get("catalogId") : null;
        if (!StringUtils.hasText(rawCatalogId)) {
            return ApiResponse.error("BAD_REQUEST", "catalogId 不能为空");
        }
        String catalogId = rawCatalogId.trim();
        return ApiResponse.success(processRuntimePurgeComponent.purgeByCatalogId(catalogId));
    }
}
