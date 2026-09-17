package com.portal.component;

import com.platform.security.entity.BusinessUnit;
import com.platform.security.util.SecurityContextUtils;
import com.portal.client.WorkflowEngineClient;
import com.portal.dto.TaskInfo;
import com.portal.exception.PortalException;
import com.portal.repository.BusinessUnitRepository;
import com.portal.service.PortalWorkspaceAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Workspace (active business unit) scoping for task queries: filters FIXED_BU_ROLE task pools
 * against the JWT active BU and restricts candidate virtual groups to the active workspace.
 * Also owns the short-TTL per-user virtual-group membership cache.
 * Extracted from {@link TaskQueryComponent}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceTaskFilterComponent {

    private final WorkflowEngineClient workflowEngineClient;
    private final VirtualGroupAccessComponent virtualGroupAccessComponent;
    private final PortalWorkspaceAuthService portalWorkspaceAuthService;
    private final BusinessUnitRepository businessUnitRepository;

    /**
     * FIXED_BU_ROLE or BU_ROLE with an explicit businessUnitId in BPMN extensions:
     * the engine merges into taskCandidateUser, unrelated to candidate group filtering.
     * When the JWT contains {@code activeBusinessUnitId}, the pool's BU must match the current workspace.
     * <p>No longer relies on {@link PortalWorkspaceAuthService#listWorkspaceContexts} being non-empty:
     * in some environments, UBR data may be out of sync with the workspace switcher,
     * causing VG-only filtering to misclassify the situation as "non-workspace mode" and skip this filter.</p>
     */
    /**
     * Workspace BU filter applies to role-pool tasks only. Direct assignee/candidate tasks must remain visible
     * (e.g. rollback fallback assigned user-dev while BPMN still marks BU_ROLE + another BU id).
     */
    public List<TaskInfo> filterFixedBuRoleTasksForActiveWorkspace(List<TaskInfo> tasks, String userId) {
        Optional<String> activeBuOpt = SecurityContextUtils.getCurrentActiveBusinessUnitId();
        String portalUsername = SecurityContextUtils.getCurrentUsername().orElse(null);
        if (activeBuOpt.isEmpty()) {
            return tasks;
        }
        // The JWT activeBusinessUnitId is a BU *id*, but BPMN businessUnitId is now a *code*
        // (code-based assignment). Convert id -> code so FIXED_BU_ROLE pool tasks aren't all hidden.
        String activeBu = resolveActiveBusinessUnitCode(normalizeBuId(activeBuOpt.get()));
        // MI「按角色分派」任务是 workspace-scoped：仅当用户当前切换到的 role 恰是该任务分派的 role 时才可见。
        // 先把 JWT active roleId 反查成 roleCode（单人角色也走 setAssignee，但仍算 role 分派，不能靠 assignee 放行）。
        String activeRoleCode = resolveActiveRoleCode(userId);
        List<TaskInfo> out = new ArrayList<>();
        for (TaskInfo t : tasks) {
            if (t == null) {
                continue;
            }
            // MI role-scoped gate（优先于 assignee/candidate 放行）：
            // role 分派任务只在“切到该 role 的 workspace”时出现；active role 不匹配则隐藏，
            // 即便当前用户是该任务的 assignee/candidate（单持有者角色也遵循此规则）。
            if (isMiRoleScopedTask(t)) {
                if (activeRoleCode != null && activeRoleCode.equalsIgnoreCase(t.getMiRoleCode().trim())) {
                    out.add(t);
                }
                continue;
            }
            if (isDirectAssigneeForPortalUser(t, userId, portalUsername)
                    || isCandidateForPortalUser(t, userId, portalUsername)) {
                out.add(t);
                continue;
            }
            if (!isWorkspaceScopedBuPoolSemantics(t)) {
                out.add(t);
                continue;
            }
            String fixedBu = resolveFixedBusinessUnitForBpmnTask(t);
            if (fixedBu == null || fixedBu.isBlank()) {
                out.add(t);
                continue;
            }
            if (equalsNormalizedBuId(activeBu, fixedBu)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * MI「按角色分派」任务（引擎从 ExtendedTaskInfo 透出 {@code miAssigneeMode=role} + {@code miRoleCode}）。
     * 这类任务是 workspace-scoped：仅在用户切到该 role 的 workspace 时可见（区别于「按人分派」任务全 workspace 可见）。
     */
    private static boolean isMiRoleScopedTask(TaskInfo t) {
        return t != null
                && "role".equalsIgnoreCase(t.getMiAssigneeMode() == null ? null : t.getMiAssigneeMode().trim())
                && t.getMiRoleCode() != null && !t.getMiRoleCode().isBlank();
    }

    /**
     * 把 JWT active roleId 反查成 role code，用于和任务的 {@code miRoleCode} 比对。
     * 走 {@link PortalWorkspaceAuthService#listWorkspaceContexts}（用户的 UBR 上下文，含 roleId↔roleCode）。
     * 无 active role 或查不到对应 code 时返回 null（此时所有 MI role 任务都不匹配 → 一律隐藏，符合“未切到该 role 不可见”）。
     */
    private String resolveActiveRoleCode(String userId) {
        Optional<String> activeRoleIdOpt = SecurityContextUtils.getCurrentActiveRoleId();
        if (activeRoleIdOpt.isEmpty() || userId == null || userId.isBlank()) {
            return null;
        }
        String activeRoleId = activeRoleIdOpt.get().trim();
        for (PortalWorkspaceAuthService.WorkspaceContextRow row : portalWorkspaceAuthService.listWorkspaceContexts(userId)) {
            if (row.getRoleId() != null && activeRoleId.equals(row.getRoleId().trim())
                    && row.getRoleCode() != null && !row.getRoleCode().isBlank()) {
                return row.getRoleCode().trim();
            }
        }
        return null;
    }

    private static boolean isDirectAssigneeForPortalUser(TaskInfo t, String userId, String portalUsername) {
        if (t == null || userId == null || userId.isBlank()) {
            return false;
        }
        String assignee = t.getAssignee();
        if (assignee == null || assignee.isBlank()) {
            return false;
        }
        String a = assignee.trim();
        if (userId.trim().equals(a)) {
            return true;
        }
        return portalUsername != null && !portalUsername.isBlank()
                && portalUsername.trim().equalsIgnoreCase(a);
    }

    private static boolean isCandidateForPortalUser(TaskInfo t, String userId, String portalUsername) {
        if (t == null || userId == null || userId.isBlank() || t.getCandidateUserIds() == null) {
            return false;
        }
        for (String cid : t.getCandidateUserIds()) {
            if (cid == null || cid.isBlank()) {
                continue;
            }
            String c = cid.trim();
            if (userId.trim().equals(c)) {
                return true;
            }
            if (portalUsername != null && !portalUsername.isBlank()
                    && portalUsername.trim().equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeBuId(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    private static final long BU_CODE_CACHE_TTL_MS = 300_000L;
    private final Map<String, BuCodeCacheEntry> buCodeCache = new ConcurrentHashMap<>();

    private record BuCodeCacheEntry(String code, long timestampMs) {}

    /**
     * Resolve a BU <em>id</em> (from the JWT active workspace) to its <em>code</em> for comparison
     * against the code-based BPMN {@code businessUnitId}. BU id→code is effectively immutable, so it's
     * cached for a short TTL to avoid a DB hit on every To Do / dashboard refresh. When the id has no
     * resolvable code (e.g. it is already a code, or the BU is missing), the original value is returned,
     * preserving the legacy id-vs-id comparison.
     */
    private String resolveActiveBusinessUnitCode(String activeBuId) {
        if (activeBuId == null || activeBuId.isBlank()) {
            return activeBuId;
        }
        String key = activeBuId.trim();
        BuCodeCacheEntry hit = buCodeCache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.timestampMs() < BU_CODE_CACHE_TTL_MS) {
            return hit.code();
        }
        String resolved = businessUnitRepository.findById(key)
                .map(BusinessUnit::getCode)
                .filter(c -> c != null && !c.isBlank())
                .orElse(key);
        buCodeCache.put(key, new BuCodeCacheEntry(resolved, System.currentTimeMillis()));
        return resolved;
    }

    /**
     * FIXED_BU_ROLE, or BU_ROLE with an explicit businessUnitId in BPMN extensions
     * (aligned with engine {@code isWorkspaceScopedBuPoolSemantics}).
     * <p>BPMN extensions only; does not read process variables (to avoid stale cross-node variable spillover).</p>
     */
    private boolean isWorkspaceScopedBuPoolSemantics(TaskInfo t) {
        String bpmn = t.getBpmnAssigneeType();
        if (bpmn != null) {
            String u = bpmn.trim().toUpperCase(java.util.Locale.ROOT);
            if ("FIXED_BU_ROLE".equals(u)) {
                return true;
            }
            if ("BU_ROLE".equals(u) && t.getBpmnBusinessUnitId() != null && !t.getBpmnBusinessUnitId().isBlank()) {
                return true;
            }
        }
        return false;
    }

    public boolean workspacePairMatches(String buCode, String roleCode, String userId) {
        if (buCode == null || buCode.isBlank() || roleCode == null || roleCode.isBlank()) {
            return false;
        }
        String activeBu = SecurityContextUtils.getCurrentActiveBusinessUnitId()
                .map(WorkspaceTaskFilterComponent::normalizeBuId)
                .map(this::resolveActiveBusinessUnitCode)
                .orElse(null);
        String activeRole = resolveActiveRoleCode(userId);
        if (activeBu == null || activeRole == null) {
            return false;
        }
        return activeBu.equalsIgnoreCase(buCode.trim()) && activeRole.equalsIgnoreCase(roleCode.trim());
    }

    public Optional<String> activeWorkspaceBuCode() {
        return SecurityContextUtils.getCurrentActiveBusinessUnitId()
                .map(WorkspaceTaskFilterComponent::normalizeBuId)
                .map(this::resolveActiveBusinessUnitCode)
                .filter(code -> code != null && !code.isBlank());
    }

    public Optional<String> activeWorkspaceRoleCode(String userId) {
        String code = resolveActiveRoleCode(userId);
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(code);
    }

    private static boolean equalsNormalizedBuId(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.trim();
        String y = b.trim();
        if (x.equals(y)) {
            return true;
        }
        try {
            if (x.matches("^-?\\d+$") && y.matches("^-?\\d+$")) {
                return Long.parseLong(x) == Long.parseLong(y);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return false;
    }

    /**
     * Fixed business unit: BPMN extension {@code bpmnBusinessUnitId} only
     * (consistent with workflow-engine task list filtering semantics; does not use process variables).
     */
    private String resolveFixedBusinessUnitForBpmnTask(TaskInfo t) {
        String bu = t.getBpmnBusinessUnitId();
        if (bu != null && !bu.isBlank()) {
            return bu.trim();
        }
        return null;
    }

    /**
     * In workspace context: keep only virtual groups where the current user has a UBR
     * for the bound role within the active business unit, preventing users with multiple BUs
     * from seeing candidate group tasks in other BU workspaces (engine user-permissions
     * returns all virtualGroupIds).
     */
    public List<String> filterVirtualGroupsForActiveWorkspace(String userId, List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return groupIds == null ? Collections.emptyList() : groupIds;
        }
        Optional<String> activeBu = SecurityContextUtils.getCurrentActiveBusinessUnitId();
        if (activeBu.isEmpty()) {
            // 非 workspace 模式（JWT 无 activeBusinessUnitId）：设计语义，不做 BU 过滤。
            return groupIds;
        }
        if (portalWorkspaceAuthService.listWorkspaceContexts(userId).isEmpty()) {
            // FALLBACK(migration): 部分环境 UBR 数据与工作台切换器脱同步，UBR 为空时若仍过滤
            // 会把用户所有候选组任务全部隐藏。删除条件：全环境 UBR 数据与工作台来源统一后收紧。
            return groupIds;
        }
        List<String> kept = new ArrayList<>();
        for (String gid : groupIds) {
            Optional<String> boundRoleId = virtualGroupAccessComponent.getBoundRoleIdForVirtualGroup(gid);
            if (boundRoleId.isEmpty()) {
                log.debug("Workspace VG filter: group {} has no bound role; excluded from candidate-group query", gid);
                continue;
            }
            if (portalWorkspaceAuthService.hasContext(userId, activeBu.get(), boundRoleId.get())) {
                kept.add(gid);
            }
        }
        return kept;
    }

    private static final long VIRTUAL_GROUPS_CACHE_TTL_MS = 30_000L;
    private final Map<String, VirtualGroupsCacheEntry> virtualGroupsCache = new ConcurrentHashMap<>();

    private record VirtualGroupsCacheEntry(List<String> groupIds, long timestampMs) {}

    /**
     * A user's virtual-group membership changes rarely, but every To Do refresh resolves it via a
     * portal→engine→admin-center triple-hop ({@link WorkflowEngineClient#getUserTaskPermissions}) costing
     * ~0.3-1s. Cache the result per user for a short TTL so repeated list/statistics queries reuse it; an
     * admin membership change becomes effective within the TTL.
     *
     * <p>失败语义：查询失败<b>不写缓存</b>（否则一次抖动=用户 30 秒看不到候选组任务）。
     * 有过期缓存时降级复用（last-known-good：旧成员关系好于错误的"零候选组"）；
     * 连缓存都没有（冷启动+故障叠加）则抛 503——让用户知道服务异常，而不是误以为"没有待办"。</p>
     */
    public List<String> getUserVirtualGroups(String userId) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        VirtualGroupsCacheEntry hit = virtualGroupsCache.get(userId);
        if (hit != null && System.currentTimeMillis() - hit.timestampMs() < VIRTUAL_GROUPS_CACHE_TTL_MS) {
            return hit.groupIds();
        }
        Optional<List<String>> resolved = fetchUserVirtualGroups(userId);
        if (resolved.isPresent()) {
            virtualGroupsCache.put(userId, new VirtualGroupsCacheEntry(resolved.get(), System.currentTimeMillis()));
            return resolved.get();
        }
        if (hit != null) {
            // FALLBACK(external): 引擎/admin-center 暂不可用，复用过期的成员关系快照。
            log.warn("Virtual-group fetch failed for user {}; reusing stale cache from {} ms ago",
                    userId, System.currentTimeMillis() - hit.timestampMs());
            return hit.groupIds();
        }
        throw new PortalException("503",
                "Task permission service is temporarily unavailable, please retry");
    }

    /**
     * Get virtual groups the user belongs to (via workflow-engine → admin-center).
     *
     * @return present = 查询成功（可能是空列表 = 用户确实无组）；empty = 查询失败（引擎不可达或降级），
     *         调用方决定降级策略，<b>不得</b>把失败当"无组"。
     */
    @SuppressWarnings("unchecked")
    private Optional<List<String>> fetchUserVirtualGroups(String userId) {
        try {
            Optional<Map<String, Object>> result = workflowEngineClient.getUserTaskPermissions(userId);
            if (result.isPresent()) {
                List<String> groupIds = (List<String>) result.get().get("virtualGroupIds");
                return Optional.of(groupIds != null ? groupIds : Collections.emptyList());
            }
        } catch (Exception e) {
            log.warn("Failed to get user virtual groups from workflow engine: {}", e.getMessage());
        }
        // WorkflowEngineTaskClient 对传输故障降级为 Optional.empty，此处统一视为"查询失败"。
        return Optional.empty();
    }
}
