package com.admin.bi.service;

import com.admin.bi.dto.request.RbacMappingCreateRequest;
import com.admin.bi.dto.request.RbacMappingUpdateRequest;
import com.admin.bi.dto.response.RbacMappingResponse;
import com.admin.bi.dto.response.RoleOptionResponse;
import com.admin.bi.dto.response.SupersetRoleResponse;
import com.admin.bi.dto.response.SyncResultResponse;
import com.admin.bi.entity.BiSupersetRole;

import java.util.List;

/**
 * RBAC 映射 Service 接口
 */
public interface BiRbacMappingService {

    /**
     * 触发手动同步 Superset 角色
     */
    SyncResultResponse syncSupersetRoles();

    /**
     * 获取所有已同步的 Superset 角色列表
     */
    List<SupersetRoleResponse> listSupersetRoles();

    /**
     * 查询 RBAC 映射列表（支持 roleName/roleType 筛选）
     */
    List<RbacMappingResponse> listMappings(String roleName, String roleType);

    /**
     * 更新某个 Sys_Role 的 Superset_Role 映射（全量替换）
     */
    void updateMapping(String sysRoleId, RbacMappingUpdateRequest request);

    /**
     * 创建 RBAC 映射（为指定系统角色创建 Superset 角色映射）
     */
    void createMapping(RbacMappingCreateRequest request);

    /**
     * 删除某个 Sys_Role 的所有 RBAC 映射记录
     */
    void deleteMapping(String sysRoleId);

    /**
     * 查询所有未映射的活跃系统角色（用于创建映射时的下拉列表）
     */
    List<RoleOptionResponse> listUnmappedRoles();

    /**
     * 获取给定系统角色 ID 列表对应的有效（ACTIVE）Superset 角色 ID 列表（去重）
     */
    List<Integer> getEffectiveSupersetRoleIds(List<String> sysRoleIds);

    /**
     * 获取给定系统角色 ID 列表对应的有效（ACTIVE）Superset 角色名称列表（去重）。
     * 供 Superset 网关鉴权端点注入 X-Remote-Roles 头使用（Superset 按角色名解析）。
     */
    List<String> getEffectiveSupersetRoleNames(List<String> sysRoleIds);

    /**
     * 给定系统角色 ID 列表，返回其映射到的、状态为 ACTIVE 的 Superset 角色实体（去重）。
     * 嵌入链路按角色过滤 dashboard 时同时需要 ID（与 dashboard_roles 求交）和名称（识别 Admin）。
     */
    List<BiSupersetRole> getEffectiveSupersetRoles(List<String> sysRoleIds);
}
