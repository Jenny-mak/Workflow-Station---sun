package com.developer.service.impl;

import com.developer.entity.FunctionUnitDevGroupAssignment;
import com.developer.repository.FunctionUnitDevGroupAssignmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Automation flow 目录读取器，供 AI Studio 的 AUTOMATION 提案使用。
 *
 * <p>AP 的 {@code flow} / {@code flow_version} / {@code project} 与平台同库（dev compose 里
 * {@code AP_POSTGRES_DATABASE} 就是 {@code POSTGRES_DB}），admin-center 的
 * {@code AutomationFlowServiceImpl} 也是这样用普通 JdbcTemplate 直读，所以这里不走服务间 client
 * （admin-center 的 flow 列表端点只认 system-admin 会话，不收 X-Service-Token）。</p>
 *
 * <p>workspace 范围与 Automation 隔离（DECISIONS D14）一致：Public 组 → {@code hermes-main}，
 * 团队组 {@code <id>} → {@code hermes-dg-<id>}。本 FU 可引用的 flow = Public + FU 所属各开发组的 project；
 * 只列带业务键（{@code metadata.hermesFlowKey}）的 flow——service task 只能按键引用。</p>
 */
@Slf4j
@Component
public class AiAutomationFlowCatalogReader {

    /** 与 admin-center {@code service-task.managed.project-external-id} 默认值一致 */
    public static final String PUBLIC_PROJECT_EXTERNAL_ID = "hermes-main";
    /** 与 admin-center {@code service-task.managed.public-group-id} 默认值一致 */
    public static final String PUBLIC_GROUP_ID = "vg-dev-public";
    /** 与 admin-center {@code service-task.managed.team-project-external-id-prefix} 默认值一致 */
    public static final String TEAM_PROJECT_EXTERNAL_ID_PREFIX = "hermes-dg-";

    private static final String SELECT = """
            SELECT f.id AS flow_id,
                   f.metadata->>'hermesFlowKey' AS flow_key,
                   fv."displayName" AS display_name,
                   (f."publishedVersionId" IS NOT NULL) AS published,
                   p."externalId" AS project_external_id,
                   COALESCE(vg.name, p."displayName") AS workspace
            FROM flow f
            JOIN project p ON p.id = f."projectId"
            JOIN LATERAL (SELECT "displayName" FROM flow_version v
                          WHERE v."flowId" = f.id ORDER BY v.created DESC LIMIT 1) fv ON true
            LEFT JOIN sys_virtual_groups vg ON vg.id = CASE
                WHEN p."externalId" = ? THEN ?
                WHEN p."externalId" LIKE ? THEN substr(p."externalId", ?)
            END
            WHERE f.metadata->>'hermesFlowKey' IS NOT NULL
            """;

    public record FlowEntry(String flowId, String flowKey, String displayName, boolean published,
                            String projectExternalId, String workspace) {}

    private final JdbcTemplate jdbcTemplate;
    private final FunctionUnitDevGroupAssignmentRepository devGroupAssignmentRepository;

    public AiAutomationFlowCatalogReader(JdbcTemplate jdbcTemplate,
                                         FunctionUnitDevGroupAssignmentRepository devGroupAssignmentRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.devGroupAssignmentRepository = devGroupAssignmentRepository;
    }

    /** 本 FU 可见的 AP project externalId：Public 恒在，其余按 FU 的开发组推导。 */
    public Set<String> workspaceExternalIds(Long functionUnitId) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(PUBLIC_PROJECT_EXTERNAL_ID);
        for (FunctionUnitDevGroupAssignment a : devGroupAssignmentRepository.findByFunctionUnitId(functionUnitId)) {
            String groupId = a.getVirtualGroupId();
            if (groupId == null || groupId.isBlank() || PUBLIC_GROUP_ID.equals(groupId)) continue;
            ids.add(TEAM_PROJECT_EXTERNAL_ID_PREFIX + groupId);
        }
        return ids;
    }

    /** 本 FU 可引用的带键 flow（Public + 所属开发组 project），最近更新在前。 */
    public List<FlowEntry> readForFunctionUnit(Long functionUnitId) {
        Set<String> externalIds = workspaceExternalIds(functionUnitId);
        List<Object> params = new ArrayList<>(joinParams());
        StringBuilder sql = new StringBuilder(SELECT).append(" AND p.\"externalId\" IN (");
        int i = 0;
        for (String id : externalIds) {
            sql.append(i++ == 0 ? "?" : ", ?");
            params.add(id);
        }
        sql.append(") ORDER BY f.updated DESC");
        return jdbcTemplate.query(sql.toString(), (rs, n) -> new FlowEntry(
                rs.getString("flow_id"), rs.getString("flow_key"), rs.getString("display_name"),
                rs.getBoolean("published"), rs.getString("project_external_id"), rs.getString("workspace")),
                params.toArray());
    }

    /** 全局按业务键查（跨 project，与引擎部署期解析同一把尺）；同键多条取最近更新。 */
    public Optional<FlowEntry> findByKey(String flowKey) {
        if (flowKey == null || flowKey.isBlank()) return Optional.empty();
        List<Object> params = new ArrayList<>(joinParams());
        params.add(flowKey.trim());
        return jdbcTemplate.query(SELECT + " AND f.metadata->>'hermesFlowKey' = ? ORDER BY f.updated DESC LIMIT 1",
                (rs, n) -> new FlowEntry(
                        rs.getString("flow_id"), rs.getString("flow_key"), rs.getString("display_name"),
                        rs.getBoolean("published"), rs.getString("project_external_id"), rs.getString("workspace")),
                params.toArray()).stream().findFirst();
    }

    /** 上下文形态：只带模型需要的四个字段。 */
    public List<Map<String, Object>> toContext(List<FlowEntry> flows) {
        List<Map<String, Object>> out = new ArrayList<>(flows.size());
        for (FlowEntry f : flows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flowKey", f.flowKey());
            m.put("displayName", f.displayName());
            m.put("published", f.published());
            m.put("workspace", f.workspace());
            out.add(m);
        }
        return out;
    }

    private static List<Object> joinParams() {
        return List.of(PUBLIC_PROJECT_EXTERNAL_ID, PUBLIC_GROUP_ID,
                TEAM_PROJECT_EXTERNAL_ID_PREFIX + "%", TEAM_PROJECT_EXTERNAL_ID_PREFIX.length() + 1);
    }
}
