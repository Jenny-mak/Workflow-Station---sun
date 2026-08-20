package com.portal.service;

import com.platform.common.dto.RelationTableDTO;
import com.portal.dto.RelationTableDataPage;
import com.portal.dto.RelationTableQueryRequest;

import java.util.List;
import java.util.Map;

/**
 * Portal Relation Table 服务接口
 * 提供只读数据查看、权限过滤、CSV 导出和 Lookup 搜索
 */
public interface PortalRelationTableService {

    /**
     * 获取用户可见的 Relation Table 列表
     * 根据 portal_visible 和用户 Business Role 过滤
     */
    List<RelationTableDTO> getVisibleTables(String userId);

    /**
     * 只读分页查询表数据：真分页 + 列声明；筛选 / 排序推入 SQL。不支持分组。
     */
    RelationTableDataPage queryTableData(Long tableId, String userId, RelationTableQueryRequest request);

    /**
     * 导出 CSV
     */
    String exportCsv(Long tableId, String userId, int maxRows);

    /**
     * Lookup 搜索（limit+offset 分页；客户端按批翻页可拉全量）
     */
    List<Map<String, Object>> searchForLookup(Long tableId, String keyword,
                                               List<String> searchFields, String displayField,
                                               String filterConditions,
                                               int limit, int offset);

    /**
     * 获取表单的 Lookup 配置列表
     */
    List<Map<String, Object>> getLookupConfigs(Long formId);

    /**
     * 通过 tableId 获取 View 字段配置
     */
    List<Map<String, Object>> getViewFieldsByTableId(Long tableId);

    /**
     * 解析当前请求用户在某表上的权限级别（基于 JWT activeRoleId）。
     * @return READONLY | READ_WRITE | null（无访问）
     */
    String resolvePermissionLevel(Long tableId, String userId);

    /**
     * 获取表字段定义（供 Portal 编辑表单按类型渲染输入）。需要访问权限。
     */
    List<com.platform.common.dto.RelationFieldDTO> getFieldDefinitions(Long tableId, String userId);

    /**
     * 为主键字段按其生成策略分配值（add-row 时自动生成）。需要 READ_WRITE。
     */
    List<String> allocatePrimaryKeys(Long tableId, String userId, String fieldName, Integer count);

    /**
     * 新增一行数据（需要 READ_WRITE）。
     */
    Map<String, Object> addData(Long tableId, String userId, Map<String, Object> data);

    /**
     * 更新一行数据（需要 READ_WRITE）。
     */
    Map<String, Object> updateData(Long tableId, String userId, String rowId, Map<String, Object> data);

    /**
     * 修改一行数据状态 ACTIVE/INACTIVE（需要 READ_WRITE）。
     */
    Map<String, Object> changeStatus(Long tableId, String userId, String rowId, String status);

    /**
     * 生成导入模板（CSV / XLSX）。
     */
    byte[] generateTemplate(Long tableId, String userId, String format);

    /**
     * 导入数据（需要 READ_WRITE），返回 {inserted, failed, errors}。
     *
     * @param dryRun 为 true 时只校验、不写入；返回 {dryRun:true, validCount, failed, errors}，
     *               用于「先预览校验结果、再点击确认导入」的两步流程。
     */
    Map<String, Object> importData(Long tableId, String userId, byte[] fileBytes, String format, boolean dryRun);
}
