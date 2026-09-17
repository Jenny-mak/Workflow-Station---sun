package com.developer.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Role codes bound through the legacy {@code sys_virtual_group_roles} table, used when
 * {@code UserRoleService#getEffectiveRolesForUser} (which reads {@code sys_role_assignments}) finds none.
 *
 * <p>
 * Never returns a default role: a team member without a capability role is read-only
 * ({@link FunctionUnitWorkspaceAccessService}), and a lookup failure must not grant one.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyVirtualGroupRoleLookup {
    private static final String SQL = "SELECT DISTINCT r.code FROM sys_virtual_group_members vgm "
            + "JOIN sys_virtual_group_roles vgr ON vgm.group_id = vgr.virtual_group_id "
            + "JOIN sys_roles r ON vgr.role_id = r.id "
            + "WHERE vgm.user_id = ?";
    private final JdbcTemplate jdbcTemplate;

    public List<String> findRoleCodes(String userId) {
        try {
            List<String> roles = jdbcTemplate.queryForList(SQL, String.class, userId);
            if (roles.isEmpty()) {
                log.info("No roles found for user {}", userId);
            }
            return roles;
        } catch (Exception e) {
            log.error("Error fetching roles for user {}: {}", userId, e.getMessage(), e);
            return List.of();
        }
    }
}
