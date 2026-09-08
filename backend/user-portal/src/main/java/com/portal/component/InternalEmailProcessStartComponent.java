package com.portal.component;

import com.portal.dto.InternalEmailProcessStartRequest;
import com.portal.dto.ProcessInstanceInfo;
import com.portal.dto.ProcessStartRequest;
import com.portal.exception.PortalException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Email monitor → Portal start. Reuses {@link ProcessStartComponent} so PK / FK / Owner /
 * audit / formula / Request ID / first-task auto-complete stay on one path.
 */
@Component
@RequiredArgsConstructor
public class InternalEmailProcessStartComponent {

    private final ProcessStartComponent processStartComponent;

    public ProcessInstanceInfo start(InternalEmailProcessStartRequest request) {
        if (request == null || !StringUtils.hasText(request.getStartUserId())) {
            throw new PortalException("400", "startUserId is required");
        }
        String processKey = firstNonBlank(request.getFunctionUnitCode(), request.getProcessDefinitionKey());
        if (!StringUtils.hasText(processKey)) {
            throw new PortalException("400", "functionUnitCode or processDefinitionKey is required");
        }
        ProcessStartRequest start = new ProcessStartRequest();
        start.setProcessDefinitionKey(processKey);
        start.setBusinessKey(request.getBusinessKey());
        Map<String, Object> formData = request.getVariables() != null
                ? request.getVariables()
                : new HashMap<>();
        start.setFormData(formData);
        return processStartComponent.startProcessFromInternal(
                request.getStartUserId().trim(), processKey, start);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
