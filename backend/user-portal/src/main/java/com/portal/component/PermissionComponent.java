package com.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portal.dto.PermissionRequestDto;
import com.portal.dto.PermissionRequestListItem;
import com.portal.dto.PageResponse;
import com.portal.entity.PermissionRequest;
import com.portal.enums.PermissionRequestStatus;
import com.portal.enums.PermissionRequestType;
import com.portal.repository.PermissionRequestRepository;
import com.portal.util.PermissionRequestListSpec;
import com.portal.util.PortalColumnFilterSupport;
import com.platform.common.i18n.I18nService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 权限申请组件
 * 支持角色申请和虚拟组加入申请
 *
 * <p>门面：保留全部 public 方法签名与构造签名不变，按职责委托同包协作类：
 * <ul>
 *   <li>{@link PermissionCatalogComponent} —— 只读目录与「可申请项」查询</li>
 *   <li>{@link PermissionRequestSubmissionComponent} —— 申请创建</li>
 *   <li>{@link PermissionApprovalComponent} —— 审批/拒绝/审批人判定（安全敏感）</li>
 *   <li>{@link PermissionRequestEnrichmentComponent} —— 展示字段填充与字符串工具</li>
 * </ul>
 * 新协作类经 {@code @Lazy @Autowired} 字段注入，构造签名保持原样（兼容测试直接 new）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionComponent {

    private final PermissionRequestRepository permissionRequestRepository;
    private final RoleAccessComponent roleAccessComponent;
    private final VirtualGroupAccessComponent virtualGroupAccessComponent;
    private final FunctionUnitAccessComponent functionUnitAccessComponent;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final I18nService i18nService;

    /** Lazy: 委托目录/可申请项查询，破除门面与协作类的构造环、保持构造签名不变。 */
    @Lazy
    @Autowired
    private PermissionCatalogComponent catalogComponent;

    /** Lazy: 委托申请创建。 */
    @Lazy
    @Autowired
    private PermissionRequestSubmissionComponent submissionComponent;

    /** Lazy: 委托审批/拒绝/审批人判定（安全敏感路径）。 */
    @Lazy
    @Autowired
    private PermissionApprovalComponent approvalComponent;

    /** Lazy: 委托展示字段填充与字符串工具。 */
    @Lazy
    @Autowired
    private PermissionRequestEnrichmentComponent enrichmentComponent;

    // ==================== 新的权限申请方法 ====================

    /**
     * 获取用户可申请的业务角色（排除已拥有的）
     */
    public List<Map<String, Object>> getAvailableRoles(String userId) {
        return catalogComponent.getAvailableRoles(userId);
    }

    /**
     * 获取用户可加入的虚拟组（排除已加入的）
     */
    public List<Map<String, Object>> getAvailableVirtualGroups(String userId) {
        return catalogComponent.getAvailableVirtualGroups(userId);
    }

    /**
     * 获取用户可加入的业务单元（排除已加入的）
     */
    public List<Map<String, Object>> getAvailableBusinessUnits(String userId) {
        return catalogComponent.getAvailableBusinessUnits(userId);
    }

    /**
     * 业务单元全量目录（扁平列表，供成员管理等场景下拉）
     */
    public List<Map<String, Object>> getBusinessUnitsCatalog() {
        return catalogComponent.getBusinessUnitsCatalog();
    }

    /**
     * 业务单元树（保留层级，供级联选择器）。
     */
    public List<Map<String, Object>> getBusinessUnitsTree() {
        return catalogComponent.getBusinessUnitsTree();
    }

    /**
     * 指定业务单元已绑定的业务角色
     */
    public List<Map<String, Object>> getBusinessUnitRoles(String businessUnitId) {
        return catalogComponent.getBusinessUnitRoles(businessUnitId);
    }

    /**
     * 申请角色分配（自动批准）
     */
    public PermissionRequest requestRoleAssignment(String userId, String roleId, String organizationUnitId, String reason) {
        return submissionComponent.requestRoleAssignment(userId, roleId, organizationUnitId, reason);
    }

    /**
     * 申请加入虚拟组（需要审批）
     */
    public PermissionRequest requestVirtualGroupJoin(String userId, String virtualGroupId, String reason) {
        return submissionComponent.requestVirtualGroupJoin(userId, virtualGroupId, reason);
    }

    /**
     * 申请加入业务单元（需要审批）
     */
    public PermissionRequest requestBusinessUnitJoin(String submittedByUserId, String beneficiaryUserId,
                                                     String businessUnitId, String reason) {
        return submissionComponent.requestBusinessUnitJoin(submittedByUserId, beneficiaryUserId, businessUnitId, reason);
    }

    /**
     * 申请加入业务单元，并指定该业务单元下的一条 Eligible Role（与 admin 中 BU 绑定角色一致）
     *
     * @param submittedByUserId 当前登录用户
     * @param beneficiaryUserId 受益人（为空则与提交人相同）
     */
    public PermissionRequest requestBusinessUnitJoinWithRole(String submittedByUserId, String beneficiaryUserId,
                                                             String businessUnitId, String roleId, String reason) {
        return submissionComponent.requestBusinessUnitJoinWithRole(submittedByUserId, beneficiaryUserId, businessUnitId, roleId, reason);
    }

    /**
     * 申请退出业务单元（成员）：审批通过后移除成员及该 BU 下全部 UBR
     */
    public PermissionRequest requestBusinessUnitExit(String submittedByUserId, String beneficiaryUserId,
                                                     String businessUnitId, String reason) {
        return submissionComponent.requestBusinessUnitExit(submittedByUserId, beneficiaryUserId, businessUnitId, reason);
    }

    /**
     * 申请移除在指定业务单元下的业务角色（需该业务单元审批人批准后生效）
     */
    public PermissionRequest requestBusinessUnitRoleRemoval(String submittedByUserId, String beneficiaryUserId,
                                                            String businessUnitId, String roleId, String reason) {
        return submissionComponent.requestBusinessUnitRoleRemoval(submittedByUserId, beneficiaryUserId, businessUnitId, roleId, reason);
    }

    /**
     * 按功能单元聚合受益人当前可发起「移除业务单元角色」申请的分配行：
     * 仅包含已在功能单元访问配置上绑定了业务角色的功能单元；未配置角色门槛的单元不在此聚合（避免重复罗列全部角色）。
     * 其余分配单独放在 otherAssignments。
     */
    public Map<String, Object> buildRoleRemovalOptionsByFunctionUnit(String beneficiaryUserId) {
        return catalogComponent.buildRoleRemovalOptionsByFunctionUnit(beneficiaryUserId);
    }

    /**
     * 获取用户当前的角色列表
     */
    public List<Map<String, Object>> getUserCurrentRoles(String userId) {
        return catalogComponent.getUserCurrentRoles(userId);
    }

    // ==================== 审批相关方法 ====================

    /**
     * 获取所有待审批的申请（审批人视图）
     * @deprecated 使用 getPendingApprovalsForUser 替代，只返回用户可以审批的申请
     */
    @Deprecated
    public Page<PermissionRequest> getPendingApprovals(Pageable pageable) {
        return approvalComponent.getPendingApprovals(pageable);
    }

    /**
     * 获取用户可以审批的待审批申请：业务单元相关（加入/移除角色/退出）+ 虚拟组加入（若该用户为对应 VG 审批人）。
     */
    public Page<PermissionRequest> getPendingApprovalsForUser(String userId, Pageable pageable) {
        return approvalComponent.getPendingApprovalsForUser(userId, pageable);
    }

    /** Pending approvals: SQL page + filters/sort/groupBy (true total, no fetch cap). */
    public PageResponse<PermissionRequest> pagePendingApprovals(
            String userId,
            int page,
            int size,
            Map<String, Map<String, Object>> filters,
            String sortField,
            String sortDirection,
            String groupBy) {
        return approvalComponent.pagePendingApprovals(
                userId, page, size, filters, sortField, sortDirection, groupBy);
    }

    /** Approval history: SQL page + filters/sort/groupBy (true total, no fetch cap). */
    public PageResponse<PermissionRequest> pageApprovalHistory(
            String userId,
            int page,
            int size,
            Map<String, Map<String, Object>> filters,
            String sortField,
            String sortDirection,
            String groupBy) {
        return approvalComponent.pageApprovalHistory(
                userId, page, size, filters, sortField, sortDirection, groupBy);
    }

    /**
     * 获取当前用户作为审批人处理过的记录（批准/拒绝；不含他人代批的同 BU 记录）。
     */
    public Page<PermissionRequest> getApprovalHistoryForUser(String userId, Pageable pageable) {
        return approvalComponent.getApprovalHistoryForUser(userId, pageable);
    }

    /**
     * 批准申请
     */
    public PermissionRequest approveRequest(Long requestId, String approverId, String comment) {
        return approvalComponent.approveRequest(requestId, approverId, comment);
    }

    /**
     * 拒绝申请
     */
    public PermissionRequest rejectRequest(Long requestId, String approverId, String comment) {
        return approvalComponent.rejectRequest(requestId, approverId, comment);
    }

    /**
     * 检查用户是否有审批权限（是否是任何VG或BU的审批人）
     */
    public boolean isApprover(String userId) {
        return approvalComponent.isApprover(userId);
    }

    /**
     * 检查用户是否可以审批特定的申请
     */
    public boolean canApproveRequest(String userId, PermissionRequest request) {
        return approvalComponent.canApproveRequest(userId, request);
    }

    /**
     * 获取申请人信息
     */
    public Map<String, Object> getApplicantInfo(String applicantId) {
        try {
            return roleAccessComponent.getUserById(applicantId);
        } catch (Exception e) {
            log.error("Failed to get applicant info for {}: {}", applicantId, e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("id", applicantId);
            fallback.put("username", applicantId);
            return fallback;
        }
    }

    /**
     * 获取用户当前的虚拟组成员身份
     */
    public List<Map<String, Object>> getUserCurrentVirtualGroups(String userId) {
        return catalogComponent.getUserCurrentVirtualGroups(userId);
    }

    // ==================== 旧的方法（保留兼容） ====================

    /**
     * 获取用户当前权限
     * @deprecated 使用 getUserCurrentRoles 和 getUserCurrentVirtualGroups 替代
     */
    @Deprecated
    public List<Map<String, Object>> getUserPermissions(String userId) {
        List<Map<String, Object>> permissions = new ArrayList<>();

        // 添加角色权限
        List<Map<String, Object>> roles = getUserCurrentRoles(userId);
        for (Map<String, Object> role : roles) {
            Map<String, Object> perm = new HashMap<>();
            perm.put("id", role.get("id"));
            perm.put("name", role.get("name"));
            perm.put("type", "ROLE");
            permissions.add(perm);
        }

        // 添加虚拟组权限
        List<Map<String, Object>> groups = getUserCurrentVirtualGroups(userId);
        for (Map<String, Object> group : groups) {
            Map<String, Object> perm = new HashMap<>();
            perm.put("id", group.get("groupId"));
            perm.put("name", group.get("groupName"));
            perm.put("type", "VIRTUAL_GROUP");
            permissions.add(perm);
        }

        return permissions;
    }

    /**
     * 提交权限申请
     * @deprecated 使用 requestRoleAssignment 或 requestVirtualGroupJoin 替代
     */
    @Deprecated
    public PermissionRequest submitRequest(String userId, PermissionRequestDto dto) {
        if (dto.getType() == null) {
            throw new IllegalArgumentException("Permission type cannot be empty");
        }
        if (dto.getPermissions() == null || dto.getPermissions().isEmpty()) {
            throw new IllegalArgumentException("Permission scope cannot be empty");
        }
        if (dto.getReason() == null || dto.getReason().isEmpty()) {
            throw new IllegalArgumentException("Request reason cannot be empty");
        }

        PermissionRequest request = new PermissionRequest();
        request.setApplicantId(userId);
        request.setSubmittedByUserId(userId);
        request.setRequestType(dto.getType());
        request.setPermissions(objectMapper.valueToTree(dto.getPermissions()));
        request.setReason(dto.getReason());
        request.setValidFrom(dto.getValidFrom() != null ? dto.getValidFrom() : LocalDateTime.now());
        request.setValidTo(dto.getValidTo());
        request.setStatus(PermissionRequestStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());

        return permissionRequestRepository.save(request);
    }

    /**
     * 获取用户的权限申请记录（JDBC 读取，避免 JPA 在枚举/JSONB 脏数据下加载失败导致 500）
     */
    public Page<PermissionRequestListItem> getMyRequests(String userId, PermissionRequestStatus status, Pageable pageable) {
        return getMyRequests(userId, status, false, pageable, null, null, null, null).page();
    }

    /**
     * 获取用户的权限申请记录。
     *
     * @param status         精确状态过滤；非 null 时优先于 excludePending
     * @param excludePending 为 true 且 status 为 null 时，使用 {@code status <> PENDING}，使历史分页 total 不含待审批
     */
    public Page<PermissionRequestListItem> getMyRequests(
            String userId,
            PermissionRequestStatus status,
            boolean excludePending,
            Pageable pageable) {
        return getMyRequests(userId, status, excludePending, pageable, null, null, null, null).page();
    }

    /**
     * My permission requests with optional column filters / sort / groupBy (SQL whitelist).
     */
    public PermissionRequestListResult getMyRequests(
            String userId,
            PermissionRequestStatus status,
            boolean excludePending,
            Pageable pageable,
            Map<String, Map<String, Object>> filters,
            String sortField,
            String sortDirection,
            String groupBy) {
        if (userId == null || userId.isBlank()) {
            throw new InsufficientAuthenticationException("User identity required");
        }
        StringBuilder where = new StringBuilder(
                " WHERE (applicant_id = ? OR submitted_by_user_id = ?) AND request_type <> 'VIRTUAL_GROUP_JOIN'");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(userId);
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        } else if (excludePending) {
            where.append(" AND status <> ?");
            args.add(PermissionRequestStatus.PENDING.name());
        }

        where.append(PermissionRequestListSpec.appendRequestTargetFilterSql(filters, args));
        var columnFilters = PermissionRequestListSpec.parseFilters(
                PermissionRequestListSpec.withoutRequestTarget(filters));
        where.append(PermissionRequestListSpec.appendFilterSql(columnFilters, args));

        String safeGroupBy = PermissionRequestListSpec.sanitizeGroupBy(groupBy);
        String orderBy = PermissionRequestListSpec.resolveOrderBy(sortField, sortDirection, safeGroupBy);

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM up_permission_request" + where, Long.class, args.toArray());
        long totalLong = total != null ? total : 0L;

        String dataSql = "SELECT id, applicant_id, submitted_by_user_id, request_type, role_id, role_name, "
                + "organization_unit_id, organization_unit_name, virtual_group_id, virtual_group_name, "
                + "business_unit_id, business_unit_name, status, reason, approver_id, approve_time, approve_comment, "
                + "created_at, updated_at FROM up_permission_request"
                + where
                + " ORDER BY " + orderBy
                + " LIMIT ? OFFSET ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add(pageable.getPageSize());
        dataArgs.add(pageable.getOffset());

        List<PermissionRequestListItem> content = jdbcTemplate.query(dataSql, this::mapPermissionRequestListRow, dataArgs.toArray());
        enrichmentComponent.enrichListItemUsernames(content);
        Page<PermissionRequestListItem> page = new PageImpl<>(content, pageable, totalLong);

        Map<String, Long> groupCounts = null;
        if (safeGroupBy != null) {
            groupCounts = queryPermissionGroupCounts(where.toString(), args, safeGroupBy);
        }
        return new PermissionRequestListResult(page, groupCounts);
    }

    private Map<String, Long> queryPermissionGroupCounts(String whereSql, List<Object> args, String groupColumn) {
        boolean ok = PermissionRequestListSpec.SQL_COLUMNS.contains(groupColumn)
                || PermissionRequestListSpec.REQUEST_TARGET_EXPR.equals(groupColumn);
        if (!ok) {
            return Map.of();
        }
        String sql = "SELECT COALESCE((" + groupColumn + ")::text, '') AS g, COUNT(*) AS c FROM up_permission_request"
                + whereSql + " GROUP BY 1 ORDER BY 1 ASC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object g = row.get("g");
            Object c = row.get("c");
            String label = PortalColumnFilterSupport.groupLabel(g != null && !String.valueOf(g).isBlank() ? g : null);
            long count = c instanceof Number n ? n.longValue() : 0L;
            out.merge(label, count, Long::sum);
        }
        return out;
    }

    public record PermissionRequestListResult(Page<PermissionRequestListItem> page, Map<String, Long> groupCounts) {
        public PageResponse<PermissionRequestListItem> toPageResponse() {
            PageResponse<PermissionRequestListItem> response = PageResponse.of(page);
            if (groupCounts != null) {
                response.setGroupCounts(groupCounts);
            }
            return response;
        }
    }

    private PermissionRequestListItem mapPermissionRequestListRow(ResultSet rs, int rowNum) throws SQLException {
        String buName = rs.getString("business_unit_name");
        String vgName = rs.getString("virtual_group_name");
        String ouName = rs.getString("organization_unit_name");
        String targetName = enrichmentComponent.firstNonBlank(
                enrichmentComponent.nonBlankString(buName),
                enrichmentComponent.nonBlankString(vgName),
                enrichmentComponent.nonBlankString(ouName));
        if (targetName == null) {
            targetName = "-";
        }
        String buId = rs.getString("business_unit_id");
        String vgId = rs.getString("virtual_group_id");
        String ouId = rs.getString("organization_unit_id");
        String targetId = enrichmentComponent.firstNonBlank(
                enrichmentComponent.nonBlankString(buId),
                enrichmentComponent.nonBlankString(vgId),
                enrichmentComponent.nonBlankString(ouId));
        if (targetId == null) {
            targetId = "";
        }
        String roleName = enrichmentComponent.nonBlankString(rs.getString("role_name"));
        List<String> roleNames = roleName != null ? List.of(roleName) : List.of();

        return PermissionRequestListItem.builder()
                .id(rs.getObject("id", Long.class))
                .applicantId(enrichmentComponent.nonBlankString(rs.getString("applicant_id")))
                .submittedByUserId(enrichmentComponent.nonBlankString(rs.getString("submitted_by_user_id")))
                .requestType(enrichmentComponent.nonBlankString(rs.getString("request_type")))
                .targetId(targetId)
                .targetName(targetName)
                .roleNames(roleNames)
                .reason(enrichmentComponent.nonBlankString(rs.getString("reason")))
                .status(enrichmentComponent.nonBlankString(rs.getString("status")))
                .approverId(enrichmentComponent.nonBlankString(rs.getString("approver_id")))
                .approverComment(enrichmentComponent.nonBlankString(rs.getString("approve_comment")))
                .approvedAt(formatTimestampUtc(rs.getTimestamp("approve_time")))
                .createdAt(formatTimestampUtc(rs.getTimestamp("created_at")))
                .updatedAt(formatTimestampUtc(rs.getTimestamp("updated_at")))
                .build();
    }

    private static String formatTimestampUtc(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        return ts.toInstant().atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * 获取申请详情（受益人或提交人可见；虚拟组类对门户隐藏）
     */
    public Optional<PermissionRequest> getRequestDetailForViewer(Long requestId, String viewerUserId) {
        Optional<PermissionRequest> opt = permissionRequestRepository.findById(requestId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        PermissionRequest r = opt.get();
        if (r.getRequestType() == PermissionRequestType.VIRTUAL_GROUP_JOIN) {
            return Optional.empty();
        }
        boolean beneficiary = viewerUserId != null && viewerUserId.equals(r.getApplicantId());
        boolean submitter = viewerUserId != null && viewerUserId.equals(r.getSubmittedByUserId());
        boolean legacySelf = r.getSubmittedByUserId() == null && beneficiary;
        if (!beneficiary && !submitter && !legacySelf) {
            return Optional.empty();
        }
        enrichmentComponent.enrichDisplayFields(List.of(r));
        return opt;
    }

    /**
     * @deprecated 使用 {@link #getRequestDetailForViewer(Long, String)}
     */
    @Deprecated
    public Optional<PermissionRequest> getRequestDetail(Long requestId) {
        return permissionRequestRepository.findById(requestId);
    }

    /**
     * 取消申请
     */
    public boolean cancelRequest(String userId, Long requestId) {
        Optional<PermissionRequest> requestOpt = permissionRequestRepository.findById(requestId);
        if (requestOpt.isEmpty()) {
            return false;
        }
        PermissionRequest request = requestOpt.get();
        boolean asApplicant = userId != null && userId.equals(request.getApplicantId());
        boolean asSubmitter = userId != null && request.getSubmittedByUserId() != null
                && userId.equals(request.getSubmittedByUserId());
        if (!asApplicant && !asSubmitter) {
            return false;
        }
        if (request.getStatus() != PermissionRequestStatus.PENDING) {
            return false;
        }
        // Keep request record for history view.
        request.setStatus(PermissionRequestStatus.CANCELLED);
        permissionRequestRepository.save(request);
        return true;
    }

    /**
     * 续期申请
     * @deprecated 新的权限模型不需要续期
     */
    @Deprecated
    public PermissionRequest renewPermission(String userId, String permissionId, LocalDateTime newValidTo, String reason) {
        PermissionRequest request = new PermissionRequest();
        request.setApplicantId(userId);
        request.setRequestType(PermissionRequestType.TEMPORARY);
        request.setPermissions(objectMapper.valueToTree(Collections.singletonList(permissionId)));
        request.setReason("续期申请: " + reason);
        request.setValidFrom(LocalDateTime.now());
        request.setValidTo(newValidTo);
        request.setStatus(PermissionRequestStatus.PENDING);
        request.setCreatedAt(LocalDateTime.now());

        return permissionRequestRepository.save(request);
    }

    /**
     * 检查权限是否即将过期
     * @deprecated 新的权限模型不需要过期检查
     */
    @Deprecated
    public List<Map<String, Object>> getExpiringPermissions(String userId, int daysBeforeExpiry) {
        // 新的权限模型不需要过期检查，返回空列表
        return Collections.emptyList();
    }
}
