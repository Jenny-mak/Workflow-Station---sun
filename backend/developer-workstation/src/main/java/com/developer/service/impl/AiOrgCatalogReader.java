package com.developer.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织目录（业务单元 + 其准入角色）读取器，供 AI Studio 的 VIEW_DESIGN 提案使用。
 *
 * <p>DW 与 Admin Center 共库：{@code sys_business_units} / {@code sys_roles} /
 * {@code sys_business_unit_roles} 就在本库里（{@code MainTableViewServiceImpl#loadAccessRuleDtos}
 * 已经这样 join 读名称），所以不走跨服务 client。"准入角色"与设计器的
 * {@code GET /business-units/{id}/roles} 同源——都是 {@code sys_business_unit_roles} 这张绑定表。</p>
 *
 * <p>两处消费：上下文序列化（模型只能引用这里的 id）与 Apply 前的引用校验（拒绝目录外的 id）。</p>
 */
@Slf4j
@Component
public class AiOrgCatalogReader {

    /** 进模型上下文的 BU 上限：目录过大时截断并打 truncated 标记，避免挤掉设计数据本身。 */
    static final int MAX_BUSINESS_UNITS_IN_CONTEXT = 200;

    private static final String CATALOG_SQL = """
            SELECT bu.id AS bu_id, bu.code AS bu_code, bu.name AS bu_name,
                   r.id AS role_id, r.code AS role_code, r.name AS role_name
            FROM sys_business_units bu
            LEFT JOIN sys_business_unit_roles bur ON bur.business_unit_id = bu.id
            LEFT JOIN sys_roles r ON r.id = bur.role_id AND r.status = 'ACTIVE'
            WHERE bu.status = 'ACTIVE'
            ORDER BY bu.sort_order NULLS LAST, bu.code, r.code
            """;

    public record RoleEntry(String id, String code, String name) {}

    public record BusinessUnitEntry(String id, String code, String name, List<RoleEntry> roles) {}

    private final JdbcTemplate jdbcTemplate;

    public AiOrgCatalogReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 全部 ACTIVE 业务单元及各自的 ACTIVE 准入角色（无绑定角色的 BU 也在列表里，roles 为空）。 */
    public List<BusinessUnitEntry> readActive() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(CATALOG_SQL);
        Map<String, BusinessUnitEntry> byId = new LinkedHashMap<>();
        Map<String, Set<String>> seenRoles = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String buId = String.valueOf(row.get("bu_id"));
            BusinessUnitEntry bu = byId.computeIfAbsent(buId, id -> new BusinessUnitEntry(
                    id, str(row.get("bu_code")), str(row.get("bu_name")), new ArrayList<>()));
            Object roleId = row.get("role_id");
            if (roleId != null && seenRoles.computeIfAbsent(buId, k -> new HashSet<>()).add(String.valueOf(roleId))) {
                bu.roles().add(new RoleEntry(String.valueOf(roleId), str(row.get("role_code")), str(row.get("role_name"))));
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** 上下文形态：{@code { businessUnits: [...], truncated: boolean }}。 */
    public Map<String, Object> readContext() {
        List<BusinessUnitEntry> all = readActive();
        boolean truncated = all.size() > MAX_BUSINESS_UNITS_IN_CONTEXT;
        List<BusinessUnitEntry> shown = truncated ? all.subList(0, MAX_BUSINESS_UNITS_IN_CONTEXT) : all;
        if (truncated) {
            log.info("orgCatalog truncated for AI context: {} business units, showing {}", all.size(), shown.size());
        }
        List<Map<String, Object>> units = new ArrayList<>(shown.size());
        for (BusinessUnitEntry bu : shown) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", bu.id());
            m.put("code", bu.code());
            m.put("name", bu.name());
            List<Map<String, Object>> roles = new ArrayList<>(bu.roles().size());
            for (RoleEntry r : bu.roles()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id", r.id());
                rm.put("code", r.code());
                rm.put("name", r.name());
                roles.add(rm);
            }
            m.put("roles", roles);
            units.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessUnits", units);
        out.put("truncated", truncated);
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
