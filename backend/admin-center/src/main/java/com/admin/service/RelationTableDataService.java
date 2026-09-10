package com.admin.service;

import com.admin.dto.response.RelationTableResponse;
import com.platform.common.dto.RelationFieldDTO;
import com.platform.common.dto.RelationTableDataRowDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/**
 * Relation Table 表数据管理服务接口
 * 管理已部署 Relation Table 的业务数据（CRUD + 状态变更）
 */
public interface RelationTableDataService {

    /**
     * 获取已部署的表列表（仅返回 DEPLOYED 状态的表）
     */
    List<RelationTableResponse> getDeployedTables();

    /**
     * 已部署表按 Function Unit 分组的轻量清单（供左侧导航子菜单），不含字段定义。
     * 按 Function Unit code 去重：同一单元发布多个版本只出一条（否则每个版本一条重复项）；
     * 未关联 FU 的表不出现在此列表中。
     */
    List<com.admin.dto.response.FunctionUnitTableGroupResponse> getDeployedTableFunctionUnitGroups();

    /**
     * 分页查询表数据（根据已部署的最新表结构动态查询物理表数据，支持搜索过滤）
     */
    Page<RelationTableDataRowDTO> queryData(Long tableId, String search, Pageable pageable);

    /**
     * Deployed field snapshot used by the shared-list query (same source as {@link #queryData}).
     */
    List<RelationFieldDTO> loadDeployedFieldsForQuery(Long tableId);

    /**
     * Lookup 搜索：供 LOOKUP 字段下拉 + 派生带出。返回原始行 Map 列表（含全部列）。
     * 无按角色 rt_table_access 守卫（与 user-portal searchForLookup 一致）——lookup 是
     * 结构设计者配置的数据源，任何能打开该表单的人都需要读到完整行以做带出。
     */
    List<Map<String, Object>> searchForLookup(Long tableId, String keyword,
                                              List<String> searchFields, String displayField,
                                              String filterConditions, int limit, int offset);

    /**
     * 获取 Relation Table 的 View 字段配置（带出面板列）。
     */
    List<Map<String, Object>> getViewFields(Long tableId);

    /**
     * 新增数据
     */
    RelationTableDataRowDTO addData(Long tableId, Map<String, Object> data);

    /**
     * 修改数据
     */
    RelationTableDataRowDTO updateData(Long tableId, String rowId, Map<String, Object> data);

    /**
     * 删除数据
     */
    void deleteData(Long tableId, String rowId);

    /**
     * 变更数据状态（Active/Inactive）
     */
    RelationTableDataRowDTO changeStatus(Long tableId, String rowId, String status);

    /**
     * 导出表数据为 CSV
     */
    String exportCsv(Long tableId, int maxRows);

    /**
     * 生成导入模板（csv|xlsx）
     */
    byte[] generateTemplate(Long tableId, String format);

    /**
     * 导入数据（按公共校验器校验后插入），返回 {inserted, failed, errors}
     *
     * @param dryRun 为 true 时只校验、不写入；返回 {dryRun:true, validCount, failed, errors}，
     *               用于「先预览校验结果、再点击确认导入」的两步流程。
     */
    Map<String, Object> importData(Long tableId, byte[] fileBytes, String format, boolean dryRun);
}
