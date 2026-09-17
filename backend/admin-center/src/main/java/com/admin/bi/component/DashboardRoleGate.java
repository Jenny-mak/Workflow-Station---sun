package com.admin.bi.component;

import com.admin.bi.config.BiProperties;
import com.admin.bi.entity.BiDashboardRegistry;
import com.admin.bi.entity.BiSupersetRole;
import com.admin.bi.service.BiRbacMappingService;
import com.admin.bi.support.SupersetRoleIdCsv;
import com.admin.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Embed-path role gate shared by every way a portal user can reach a dashboard (Audience
 * Assignment landing page, Data View binding, guest token).
 * <p>
 * A dashboard that carries Superset roles (Superset {@code dashboard_roles}, synced into
 * {@code bi_dashboard_registry.superset_role_ids}) is visible only when the user's sys roles map
 * (RBAC Mapping) to at least one of them, or to Superset's admin role
 * ({@code bi.superset.admin-role-name}). A dashboard with no Superset roles is unrestricted, so
 * registries keep working until authors start granting roles in Superset.
 * <p>
 * Obtain a {@link Check} per request; it resolves the user's mapped roles at most once and only
 * when the first restricted dashboard is met.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardRoleGate {

    private final BiRbacMappingService rbacMappingService;
    private final BiProperties biProperties;
    private final UserRoleRepository userRoleRepository;

    /** Gate for a user whose sys role IDs are looked up lazily (including virtual-group roles). */
    public Check forUser(String userId) {
        return new Check(userId, () -> userRoleRepository.findAllRoleIdsByUserId(userId));
    }

    /** Gate for a user whose sys role IDs the caller already holds. */
    public Check forSysRoles(String userId, List<String> sysRoleIds) {
        return new Check(userId, () -> sysRoleIds);
    }

    public final class Check {
        private final String userId;
        private final Supplier<List<String>> sysRoleIds;
        private Set<Integer> mappedRoleIds;
        private boolean admin;

        private Check(String userId, Supplier<List<String>> sysRoleIds) {
            this.userId = userId;
            this.sysRoleIds = sysRoleIds;
        }

        public boolean allows(BiDashboardRegistry dashboard) {
            List<Integer> required = SupersetRoleIdCsv.parse(dashboard.getSupersetRoleIds());
            if (required.isEmpty()) {
                return true;
            }
            resolve();
            if (admin) {
                return true;
            }
            for (Integer roleId : required) {
                if (mappedRoleIds.contains(roleId)) {
                    return true;
                }
            }
            log.debug("Dashboard {} hidden from user {}: Superset roles {} not covered by RBAC mapping",
                    dashboard.getId(), userId, dashboard.getSupersetRoleIds());
            return false;
        }

        private void resolve() {
            if (mappedRoleIds != null) {
                return;
            }
            List<String> roleIds = sysRoleIds.get();
            List<BiSupersetRole> roles = roleIds == null || roleIds.isEmpty()
                    ? List.of()
                    : rbacMappingService.getEffectiveSupersetRoles(roleIds);
            String adminRoleName = biProperties.getSuperset().getAdminRoleName();
            mappedRoleIds = new HashSet<>();
            for (BiSupersetRole role : roles) {
                mappedRoleIds.add(role.getSupersetRoleId());
                if (adminRoleName != null && adminRoleName.equals(role.getName())) {
                    admin = true;
                }
            }
        }
    }
}
