package com.developer.security;

import com.platform.common.i18n.I18nService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SSE 结束（含客户端断开）时的异步再分派没有安全上下文：拦截器必须放行，
 * 否则会往已不可用的响应里写 401 并抛 AsyncRequestNotUsableException。普通分派照旧拦截。
 */
class DeveloperPermissionInterceptorAsyncDispatchTest {

    static class StreamController {
        @RequireDeveloperPermission("FUNCTION_UNIT_VIEW")
        public void events() {
        }
    }

    private final DeveloperPermissionChecker checker = mock(DeveloperPermissionChecker.class);
    private final I18nService i18n = mock(I18nService.class);
    private final DeveloperPermissionInterceptor interceptor = new DeveloperPermissionInterceptor(checker, i18n);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new StreamController(), StreamController.class.getMethod("events"));
    }

    @Test
    void asyncRedispatchIsNotRechecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai-generation/studio-thread/1/events");
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handler()));
        assertEquals(200, response.getStatus());
        assertEquals("", response.getContentAsString());
        verifyNoInteractions(checker);
    }

    @Test
    void regularDispatchWithoutAuthenticationIsStillRejected() throws Exception {
        when(i18n.getMessage(anyString())).thenReturn("unauthorized");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai-generation/studio-thread/1/events");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, handler()));
        assertEquals(401, response.getStatus());
    }
}
