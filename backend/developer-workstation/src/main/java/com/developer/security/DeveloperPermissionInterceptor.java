package com.developer.security;

import com.platform.common.i18n.I18nService;
import com.platform.security.util.SecurityContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Developer permission check interceptor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeveloperPermissionInterceptor implements HandlerInterceptor {
    
    private final DeveloperPermissionChecker permissionChecker;
    private final I18nService i18nService;
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) 
            throws Exception {
        
        log.debug("DeveloperPermissionInterceptor triggered");
        log.debug("Request: {} {}", request.getMethod(), request.getRequestURI());
        log.debug("Handler: {}", handler.getClass().getName());
        
        if (!(handler instanceof HandlerMethod)) {
            log.info("Handler is not HandlerMethod, skipping");
            return true;
        }

        // SSE 等异步请求结束（含客户端断开）时 Spring 会再分派一次：首次分派已鉴过权，
        // 而此时安全上下文不再建立，若照常检查会往已不可用的响应里写 401，抛 AsyncRequestNotUsableException
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
            return true;
        }
        
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        log.debug("Handler method: {}.{}", handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName());
        
        // Check method-level annotation
        RequireDeveloperPermission methodAnnotation = handlerMethod.getMethodAnnotation(RequireDeveloperPermission.class);
        
        // Check class-level annotation
        RequireDeveloperPermission classAnnotation = handlerMethod.getBeanType().getAnnotation(RequireDeveloperPermission.class);
        
        // Method-level takes precedence
        RequireDeveloperPermission annotation = methodAnnotation != null ? methodAnnotation : classAnnotation;
        
        if (annotation == null) {
            log.debug("No permission annotation found, allowing access");
            return true;
        }
        
        log.debug("Required permissions: {}", Arrays.toString(annotation.value()));
        
        // Only trust authenticated JWT principal from SecurityContext.
        // Do not fallback to client-provided headers when auth context is missing.
        if (!SecurityContextUtils.isAuthenticated()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"" + i18nService.getMessage("auth.unauthorized") + "\"}");
            return false;
        }
        
        String userId = getUserIdFromRequest(request);
        if (userId == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"" + i18nService.getMessage("auth.cannot_get_user_id") + "\"}");
            return false;
        }
        
        String[] requiredPermissions = annotation.value();
        RequireDeveloperPermission.Mode mode = annotation.mode();
        
        boolean hasPermission = checkPermissions(userId, requiredPermissions, mode);
        
        if (!hasPermission) {
            log.warn("User {} does not have required permissions: {}", userId, Arrays.toString(requiredPermissions));
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"FORBIDDEN\",\"message\":\"" + i18nService.getMessage("auth.no_permission") + "\"}");
            return false;
        }
        
        return true;
    }
    
    private String getUserIdFromRequest(HttpServletRequest request) {
        // Trust user identity from JWT-parsed SecurityContext only.
        Optional<String> securityContextUserId = SecurityContextUtils.getCurrentUserId();
        return securityContextUserId.orElse(null);
    }
    
    private boolean checkPermissions(String userId, String[] requiredPermissions, RequireDeveloperPermission.Mode mode) {
        Set<String> userPermissions = permissionChecker.getUserPermissions(userId);
        
        // Convert permission code from annotation format to lowercase colon format (e.g. FUNCTION_UNIT_VIEW -> function_unit:view)
        if (mode == RequireDeveloperPermission.Mode.ALL) {
            return Arrays.stream(requiredPermissions)
                .map(this::convertPermissionCode)
                .allMatch(userPermissions::contains);
        } else {
            return Arrays.stream(requiredPermissions)
                .map(this::convertPermissionCode)
                .anyMatch(userPermissions::contains);
        }
    }
    
    /**
     * Convert permission code from UPPER_SNAKE_CASE to lowercase colon format.
     * The resource segment can itself contain underscores (e.g. FUNCTION_UNIT) and the
     * action segment can be multi-word (e.g. ASSIGN_DEV_GROUP), so a naive "split on the
     * last underscore" is wrong for codes like FUNCTION_UNIT_ASSIGN_DEV_GROUP. We match the
     * annotation against the known resource prefixes (aligned with admin-center's
     * DeveloperPermission enum) and treat the remainder as the action.
     * Examples: FUNCTION_UNIT_VIEW -> function_unit:view,
     *           FUNCTION_UNIT_ASSIGN_DEV_GROUP -> function_unit:assign_dev_group.
     */
    private static final java.util.List<String> KNOWN_RESOURCE_PREFIXES = java.util.List.of(
            "FUNCTION_UNIT", "PROCESS", "FORM", "TABLE", "ACTION", "DECISION");

    private String convertPermissionCode(String code) {
        for (String resource : KNOWN_RESOURCE_PREFIXES) {
            if (code.equals(resource)) {
                return resource.toLowerCase();
            }
            if (code.startsWith(resource + "_")) {
                String action = code.substring(resource.length() + 1).toLowerCase();
                return resource.toLowerCase() + ":" + action;
            }
        }
        // Fallback: original last-underscore heuristic for unrecognised resources.
        int lastUnderscore = code.lastIndexOf('_');
        if (lastUnderscore > 0) {
            String resource = code.substring(0, lastUnderscore).toLowerCase();
            String action = code.substring(lastUnderscore + 1).toLowerCase();
            return resource + ":" + action;
        }
        return code.toLowerCase();
    }
}
