package com.portal.properties;

import com.portal.component.FunctionUnitAccessComponent;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeTry;
import org.mockito.Mockito;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 功能单元访问控制属性测试
 * 验证禁用状态访问控制和功能单元列表过滤
 */
class FunctionUnitAccessProperties {

    private FunctionUnitAccessComponent accessComponent;
    private RestTemplate restTemplate;

    @BeforeTry
    void setUp() {
        restTemplate = Mockito.mock(RestTemplate.class);
        accessComponent = new FunctionUnitAccessComponent(restTemplate);
    }

    // ==================== Property 6: 禁用状态访问控制 ====================

    @Property(tries = 20)
    @Label("属性: 禁用的功能单元应阻止访问")
    void disabledFunctionUnitShouldBlockAccess(
            @ForAll("validUserIds") String userId,
            @ForAll("functionUnitIds") String functionUnitId) {
        // 模拟功能单元已禁用
        mockFunctionUnitEnabled(functionUnitId, false);
        
        assertThatThrownBy(() -> accessComponent.checkFunctionUnitAccess(userId, functionUnitId))
                .isInstanceOf(FunctionUnitAccessComponent.FunctionUnitDisabledException.class)
                .hasMessageContaining("disabled");
    }

    @Property(tries = 20)
    @Label("属性: 启用的功能单元应允许有权限用户访问")
    void enabledFunctionUnitShouldAllowAuthorizedAccess(
            @ForAll("validUserIds") String userId,
            @ForAll("functionUnitIds") String functionUnitId) {
        // 模拟功能单元已启用
        mockFunctionUnitEnabled(functionUnitId, true);
        // 模拟已配置角色且用户持有该角色
        mockFunctionUnitAccess(functionUnitId, List.of("role-allowed"));
        mockUserRoles(userId, List.of("role-allowed"));
        
        // 不应抛出异常
        accessComponent.checkFunctionUnitAccess(userId, functionUnitId);
    }

    @Property(tries = 20)
    @Label("属性: isFunctionUnitEnabled 应正确返回启用状态")
    void isFunctionUnitEnabledShouldReturnCorrectStatus(
            @ForAll("functionUnitIds") String functionUnitId,
            @ForAll boolean enabled) {
        mockFunctionUnitEnabled(functionUnitId, enabled);
        
        boolean result = accessComponent.isFunctionUnitEnabled(functionUnitId);
        
        assertThat(result).isEqualTo(enabled);
    }

    @Property(tries = 20)
    @Label("属性: 无已部署 catalog 记录的功能单元应判定为禁用（fail-closed）")
    void unresolvableFunctionUnitShouldBeTreatedAsDisabled(
            @ForAll("functionUnitIds") String functionUnitCode) {
        // No catalog row: every resolve lookup misses, so enabled status cannot be confirmed.
        mockFunctionUnitResolveMiss(functionUnitCode);

        assertThat(accessComponent.isFunctionUnitEnabled(functionUnitCode)).isFalse();
    }

    @Property(tries = 20)
    @Label("属性: 未配置访问权限的功能单元应拒绝所有用户")
    void unconfiguredAccessShouldDenyAllUsers(
            @ForAll("validUserIds") String userId,
            @ForAll("functionUnitIds") String functionUnitId) {
        mockFunctionUnitEnabled(functionUnitId, true);
        mockFunctionUnitAccess(functionUnitId, Collections.emptyList());
        mockUserRoles(userId, List.of("role-manager"));

        assertThatThrownBy(() -> accessComponent.checkFunctionUnitAccess(userId, functionUnitId))
                .isInstanceOf(FunctionUnitAccessComponent.FunctionUnitAccessDeniedException.class);
    }

    @Property(tries = 20)
    @Label("属性: 无权限用户访问启用的功能单元应被拒绝")
    void unauthorizedUserShouldBeDeniedAccess(
            @ForAll("validUserIds") String userId,
            @ForAll("functionUnitIds") String functionUnitId,
            @ForAll("roleIds") String requiredRoleId) {
        // 模拟功能单元已启用
        mockFunctionUnitEnabled(functionUnitId, true);
        // 模拟需要特定角色
        mockFunctionUnitAccess(functionUnitId, List.of(requiredRoleId));
        // 模拟用户没有该角色
        mockUserRoles(userId, Collections.emptyList());
        
        assertThatThrownBy(() -> accessComponent.checkFunctionUnitAccess(userId, functionUnitId))
                .isInstanceOf(FunctionUnitAccessComponent.FunctionUnitAccessDeniedException.class)
                .hasMessageContaining("permission");
    }

    // ==================== Property 7: 功能单元列表过滤 ====================

    @Property(tries = 20)
    @Label("属性: 禁用的功能单元应从列表中过滤掉")
    void disabledFunctionUnitsShouldBeFilteredOut(
            @ForAll("validUserIds") String userId) {
        // 创建混合列表：一些启用，一些禁用
        List<Map<String, Object>> units = new ArrayList<>();
        units.add(createFunctionUnit("unit-1", "Unit 1", true));
        units.add(createFunctionUnit("unit-2", "Unit 2", false));
        units.add(createFunctionUnit("unit-3", "Unit 3", true));
        units.add(createFunctionUnit("unit-4", "Unit 4", false));
        
        // 模拟无访问限制
        for (Map<String, Object> unit : units) {
            String unitId = (String) unit.get("id");
            if (Boolean.TRUE.equals(unit.get("enabled"))) {
                mockFunctionUnitAccess(unitId, List.of("portal-role"));
            } else {
                mockFunctionUnitAccess(unitId, Collections.emptyList());
            }
        }
        mockUserRoles(userId, List.of("portal-role"));
        
        List<Map<String, Object>> filtered = accessComponent.filterAccessibleFunctionUnits(userId, units);
        
        // 只应返回启用的功能单元
        assertThat(filtered).hasSize(2);
        assertThat(filtered).allMatch(u -> Boolean.TRUE.equals(u.get("enabled")));
        assertThat(filtered.stream().map(u -> u.get("id")))
                .containsExactlyInAnyOrder("unit-1", "unit-3");
    }

    @Property(tries = 20)
    @Label("属性: 过滤后的列表应只包含用户有权限的功能单元")
    void filteredListShouldOnlyContainAuthorizedUnits(
            @ForAll("validUserIds") String userId,
            @ForAll("roleIds") String userRoleId) {
        // 创建功能单元列表
        List<Map<String, Object>> units = new ArrayList<>();
        units.add(createFunctionUnit("unit-1", "Unit 1", true));
        units.add(createFunctionUnit("unit-2", "Unit 2", true));
        units.add(createFunctionUnit("unit-3", "Unit 3", true));
        
        // unit-1 需要用户的角色
        mockFunctionUnitAccess("unit-1", List.of(userRoleId));
        // unit-2 需要其他角色
        mockFunctionUnitAccess("unit-2", List.of("other-role"));
        // unit-3 未配置访问权限
        mockFunctionUnitAccess("unit-3", Collections.emptyList());
        
        // 用户有 userRoleId
        mockUserRoles(userId, List.of(userRoleId));
        
        List<Map<String, Object>> filtered = accessComponent.filterAccessibleFunctionUnits(userId, units);
        
        // 应仅返回 unit-1（有权限）；unit-3 未配置访问权限不可见
        assertThat(filtered).hasSize(1);
        assertThat(filtered.stream().map(u -> u.get("id")))
                .containsExactly("unit-1");
    }

    @Property(tries = 20)
    @Label("属性: 空列表过滤应返回空列表")
    void emptyListFilterShouldReturnEmpty(@ForAll("validUserIds") String userId) {
        List<Map<String, Object>> filtered = accessComponent.filterAccessibleFunctionUnits(userId, Collections.emptyList());
        
        assertThat(filtered).isEmpty();
    }

    @Property(tries = 20)
    @Label("属性: null列表过滤应返回空列表")
    void nullListFilterShouldReturnEmpty(@ForAll("validUserIds") String userId) {
        List<Map<String, Object>> filtered = accessComponent.filterAccessibleFunctionUnits(userId, null);
        
        assertThat(filtered).isEmpty();
    }

    @Property(tries = 20)
    @Label("属性: 所有功能单元禁用时应返回空列表")
    void allDisabledShouldReturnEmpty(@ForAll("validUserIds") String userId) {
        List<Map<String, Object>> units = new ArrayList<>();
        units.add(createFunctionUnit("unit-1", "Unit 1", false));
        units.add(createFunctionUnit("unit-2", "Unit 2", false));
        
        mockUserRoles(userId, Collections.emptyList());
        
        List<Map<String, Object>> filtered = accessComponent.filterAccessibleFunctionUnits(userId, units);
        
        assertThat(filtered).isEmpty();
    }

    @Property(tries = 20)
    @Label("属性: Admin 移除访问权限后应立即不可见")
    void removedAccessShouldDenyImmediately(
            @ForAll("validUserIds") String userId,
            @ForAll("functionUnitIds") String functionUnitId) {
        mockFunctionUnitEnabled(functionUnitId, true);
        mockFunctionUnitAccess(functionUnitId, List.of("role-manager"));
        mockUserRoles(userId, List.of("role-manager"));

        assertThat(accessComponent.canAccessFunctionUnit(userId, functionUnitId)).isTrue();

        mockFunctionUnitAccess(functionUnitId, Collections.emptyList());

        assertThat(accessComponent.canAccessFunctionUnit(userId, functionUnitId)).isFalse();
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> createFunctionUnit(String id, String name, boolean enabled) {
        Map<String, Object> unit = new HashMap<>();
        unit.put("id", id);
        unit.put("name", name);
        unit.put("enabled", enabled);
        return unit;
    }

    @SuppressWarnings("unchecked")
    private void mockFunctionUnitEnabled(String functionUnitIdOrCode, boolean enabled) {
        mockFunctionUnitResolveIdentity(functionUnitIdOrCode);
        String catalogId = catalogIdFor(functionUnitIdOrCode);
        Map<String, Object> response = new HashMap<>();
        response.put("id", catalogId);
        response.put("enabled", enabled);

        // Detail lookups are keyed by catalog UUID, never by the dw code.
        when(restTemplate.exchange(
                contains("/function-units/" + catalogId),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(response));
    }

    /**
     * Deployed catalog rows are keyed by UUID while callers pass dw codes / process keys, so the
     * fixtures must not conflate the two — {@code isFunctionUnitEnabled} resolves before it reads
     * the enabled flag, and a fixture that returned the code as its own catalog id would hide that.
     */
    private String catalogIdFor(String functionUnitIdOrCode) {
        return UUID.nameUUIDFromBytes(functionUnitIdOrCode.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    /**
     * {@link FunctionUnitAccessComponent#canAccessFunctionUnit} resolves dw/catalog codes before
     * loading access rows; property tests stub identity resolve so existing access mocks stay valid.
     */
    @SuppressWarnings("unchecked")
    private void mockFunctionUnitResolveIdentity(String functionUnitIdOrCode) {
        if (functionUnitIdOrCode == null || functionUnitIdOrCode.isBlank()) {
            return;
        }
        String encoded = java.net.URLEncoder.encode(functionUnitIdOrCode, java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", catalogIdFor(functionUnitIdOrCode));

        when(restTemplate.exchange(
                contains("/function-units/by-process-key/" + encoded),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("not found"));

        when(restTemplate.exchange(
                contains("/function-units/code/" + encoded + "/latest"),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(payload));
    }

    /** Every admin-center resolve route misses, i.e. the code has no deployed catalog entry. */
    @SuppressWarnings("unchecked")
    private void mockFunctionUnitResolveMiss(String functionUnitCode) {
        when(restTemplate.exchange(
                contains("/function-units/"),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("not found"));
    }

    @SuppressWarnings("unchecked")
    private void mockFunctionUnitAccess(String functionUnitIdOrCode, List<String> roleIds) {
        mockFunctionUnitResolveIdentity(functionUnitIdOrCode);
        List<Map<String, Object>> accessList = new ArrayList<>();
        for (String roleId : roleIds) {
            Map<String, Object> access = new HashMap<>();
            access.put("targetType", "ROLE");
            access.put("targetId", roleId);
            access.put("targetCode", roleId);
            accessList.add(access);
        }
        
        // Two call paths reach access rows: filterAccessibleFunctionUnits passes catalog ids from the
        // admin-center list straight through, while canAccessFunctionUnit resolves a code first.
        for (String key : Set.of(functionUnitIdOrCode, catalogIdFor(functionUnitIdOrCode))) {
            when(restTemplate.exchange(
                    contains("/function-units/" + key + "/access"),
                    eq(HttpMethod.GET),
                    isNull(),
                    any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(accessList));
        }
    }

    @SuppressWarnings("unchecked")
    private void mockUserRoles(String userId, List<String> roleIds) {
        List<Map<String, Object>> roleList = new ArrayList<>();
        for (String roleId : roleIds) {
            Map<String, Object> role = new HashMap<>();
            role.put("id", roleId);
            roleList.add(role);
        }
        
        when(restTemplate.exchange(
                contains("/users/" + userId + "/roles"),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(roleList));
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<String> validUserIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(s -> "user-" + s);
    }

    @Provide
    Arbitrary<String> functionUnitIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(s -> "fu-" + s);
    }

    @Provide
    Arbitrary<String> roleIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(s -> "role-" + s);
    }
}
