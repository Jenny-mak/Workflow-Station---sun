# 需求文档：BI Management 模块

## 简介

在低代码开发平台的 Admin Center 中新增 BI Management 模块，用于管理 Superset Dashboard 的同步、分配和访问控制。该模块从 Superset 数据库自动同步已发布且启用嵌入的 Dashboard 元数据，使任何已登录 Admin Center 的用户能够将 Dashboard 按 User、Role 或 Business Unit 维度分配给不同用户，并在 User Portal 的 Landing Page 上根据分配结果渲染对应的嵌入式 Dashboard。

## 术语表

- **Admin_Center**: 低代码平台的管理后台服务（Spring Boot），负责用户、角色、权限、组织架构等管理功能
- **Admin_Center_Authenticated_User**: 已通过 Admin Center 登录认证的用户，拥有 BI Management 模块的完整操作权限（查看、同步、编辑本地字段、删除、分配）
- **User_Portal**: 低代码平台的用户门户，Landing Page 展示分配给当前用户的 Superset Dashboard
- **Superset**: Apache Superset BI 平台，提供 Dashboard 创建和 Embedded SDK 嵌入能力
- **Superset_Database**: Superset 使用的 PostgreSQL 数据库（与 Admin Center 同一实例，数据库名 workflow_platform_dev，public schema），包含 dashboards 和 embedded_dashboards 表
- **Dashboards_Table**: Superset 数据库中的 dashboards 表，存储 Dashboard 的核心元数据，包含 id、dashboard_title、published、uuid、description 等字段
- **Embedded_Dashboards_Table**: Superset 数据库中的 embedded_dashboards 表，存储已启用嵌入的 Dashboard 记录，通过 dashboard_id 关联 Dashboards_Table，其 uuid 字段即为前端 Embedded SDK 使用的 Embed_ID
- **Dashboard_Status**: Dashboard 在 Dashboard_Registry 中的状态，取值为 ACTIVE（有效）、AUTO_INACTIVE（自动失效，同步时发现 Superset 端不再满足条件）、MANUAL_INACTIVE（手动失效，用户在 Admin Center 主动标记）
- **Dashboard_Registry**: BI Management 子模块，负责从 Superset_Database 自动同步 Dashboard 元数据，并维护 Tags、Is_Default_Landing、Dashboard_Status 等本地扩展字段
- **Dashboard_Assignment**: BI Management 子模块，负责将已同步的 Dashboard 分配给 User、Role 或 Business Unit
- **Embed_ID**: 从 Embedded_Dashboards_Table 的 uuid 字段自动获取的唯一标识符（UUID 格式），用于通过 Embedded SDK 渲染 Dashboard
- **Superset_Dashboard_UUID**: Dashboards_Table 的 uuid 字段，用于唯一标识 Superset 中的 Dashboard
- **Guest_Token**: Superset 颁发的临时访问令牌，前端通过该令牌认证并渲染嵌入式 Dashboard
- **Assignment_Target_Type**: Dashboard 分配的目标维度，取值为 USER、ROLE 或 BUSINESS_UNIT
- **Layout_Mode**: User Portal Landing Page 的布局模式，取值为 SINGLE、MULTI 或 WIDGET
- **Sync_Operation**: Dashboard 同步操作，从 Superset_Database 拉取符合条件的 Dashboard 数据并更新 Dashboard_Registry 的过程
- **Superset_Role**: Superset 内部的角色，存储在 AB_Role_Table 中，用于控制 Superset 内部的权限（如 Admin、Public、Alpha、Gamma、sql_lab 等）
- **AB_Role_Table**: Superset 数据库中的 ab_role 表，存储 Superset 角色信息，包含 id（integer, PK）和 name（varchar(64), unique）字段
- **Sys_Role**: 低代码平台自身的角色，存储在 sys_roles 表中，包含 id、code、name、type（ADMIN/DEVELOPER/BU_BOUNDED/BU_UNBOUNDED）、status 等字段
- **RBAC_Mapping**: BI Management 子模块，负责管理 Sys_Role 与 Superset_Role 之间的映射关系，一个 Sys_Role 可映射多个 Superset_Role，一个 Superset_Role 也可被多个 Sys_Role 映射（多对多）
- **RBAC_Mapping_Registry**: 存储 Sys_Role 与 Superset_Role 映射关系的本地注册表
- **Superset_Role_Sync_Operation**: 从 AB_Role_Table 自动同步 Superset 角色列表到本地的操作，类似 Dashboard 同步机制

## 需求

### 需求 1：Dashboard 同步管理

**用户故事：** 作为 Admin Center 已认证用户，我希望系统自动从 Superset 数据库同步已发布且启用嵌入的 Dashboard 元数据，以便后续将 Dashboard 分配给用户。

#### 验收标准

1. WHEN Sync_Operation 执行时，THE Dashboard_Registry SHALL 查询 Superset_Database 中 Dashboards_Table 与 Embedded_Dashboards_Table 的关联数据，仅拉取 published 为 true 且在 Embedded_Dashboards_Table 中存在记录的 Dashboard
2. THE Dashboard_Registry SHALL 对每条同步记录存储以下字段：Dashboard_Title（来自 Dashboards_Table.dashboard_title）、Description（来自 Dashboards_Table.description）、Embed_ID（来自 Embedded_Dashboards_Table.uuid）、Superset_Dashboard_UUID（来自 Dashboards_Table.uuid）、Superset_Dashboard_ID（来自 Dashboards_Table.id）、Tags（本地扩展字段，选填，逗号分隔）、Is_Default_Landing（本地扩展字段，布尔值，默认 false）、Dashboard_Status（本地扩展字段，取值 ACTIVE/AUTO_INACTIVE/MANUAL_INACTIVE，默认 ACTIVE）、Last_Synced_At（最近同步时间戳）
3. WHEN Sync_Operation 发现 Superset_Database 中存在新的符合条件的 Dashboard（Superset_Dashboard_ID 不在 Dashboard_Registry 中），THE Dashboard_Registry SHALL 自动创建对应的注册记录
4. WHEN Sync_Operation 发现 Superset_Database 中已同步的 Dashboard 的 Dashboard_Title、Description 或 Embed_ID 发生变化，THE Dashboard_Registry SHALL 更新对应字段，同时保留 Tags 和 Is_Default_Landing 等本地扩展字段不变
5. WHEN Sync_Operation 发现 Dashboard_Registry 中已有记录对应的 Dashboard 在 Superset_Database 中不再满足同步条件（published 变为 false 或 Embedded_Dashboards_Table 中记录被删除），THE Dashboard_Registry SHALL 将该记录的 Dashboard_Status 设为 AUTO_INACTIVE（自动失效），而非直接删除
6. WHEN Admin_Center_Authenticated_User 点击手动同步按钮，THE Dashboard_Registry SHALL 立即执行一次 Sync_Operation 并返回同步结果摘要（新增数量、更新数量、自动失效数量）并刷新表格列表
7. THE Dashboard_Registry SHALL 支持定时自动执行 Sync_Operation，同步周期通过系统配置项设定
8. IF Sync_Operation 执行过程中 Superset_Database 连接失败或查询异常，THEN THE Dashboard_Registry SHALL 记录错误日志并返回同步失败的错误信息，已有注册数据保持不变
9. WHEN Sync_Operation 发现 Superset_Database 中某条 Dashboard 仍满足同步条件（published 为 true 且 Embedded_Dashboards_Table 中存在记录），但该 Dashboard 在 Dashboard_Registry 中的 Dashboard_Status 为 MANUAL_INACTIVE，THE Dashboard_Registry SHALL 保持该记录的 MANUAL_INACTIVE 状态不变，不自动恢复为 ACTIVE
10. WHEN Sync_Operation 发现 Superset_Database 中某条 Dashboard 仍满足同步条件，且该 Dashboard 在 Dashboard_Registry 中的 Dashboard_Status 为 AUTO_INACTIVE，THE Dashboard_Registry SHALL 将该记录的 Dashboard_Status 恢复为 ACTIVE
11. WHEN Admin_Center_Authenticated_User 对一条 Dashboard_Status 为 ACTIVE 的 Dashboard 执行禁用操作，THE Dashboard_Registry SHALL 将该记录的 Dashboard_Status 设为 MANUAL_INACTIVE
12. WHEN Admin_Center_Authenticated_User 对一条 Dashboard_Status 为 MANUAL_INACTIVE 的 Dashboard 执行启用操作，THE Dashboard_Registry SHALL 将该记录的 Dashboard_Status 恢复为 ACTIVE
13. WHEN Admin_Center_Authenticated_User 请求 Dashboard 列表，THE Dashboard_Registry SHALL 返回所有已同步 Dashboard 的分页列表，支持按 Dashboard_Title、Tags 和 Dashboard_Status（ACTIVE/AUTO_INACTIVE/MANUAL_INACTIVE）筛选
14. WHEN Admin_Center_Authenticated_User 编辑一条已同步 Dashboard 的本地扩展字段（Tags、Is_Default_Landing），THE Dashboard_Registry SHALL 保存更新后的字段值并返回更新后的完整记录
15. WHEN Admin_Center_Authenticated_User 删除一条已同步 Dashboard 记录，THE Dashboard_Registry SHALL 检查该 Dashboard 是否存在关联的 Assignment 记录
16. IF 被删除的 Dashboard 存在关联的 Assignment 记录，THEN THE Dashboard_Registry SHALL 拒绝删除并返回存在关联分配的错误信息

### 需求 2：Dashboard 分配管理

**用户故事：** 作为 Admin Center 已认证用户，我希望将已同步的 Dashboard 按 User、Role 或 Business Unit 维度分配，以便控制不同用户在 User Portal 上看到的 Dashboard。

#### 验收标准

1. WHEN Admin_Center_Authenticated_User 提交分配请求，THE Dashboard_Assignment SHALL 创建一条分配记录，包含以下字段：Dashboard ID（必填，引用已同步且状态为有效的 Dashboard）、Assignment_Target_Type（必填，取值 USER/ROLE/BUSINESS_UNIT）、Target ID（必填，引用对应的 User/Role/Business Unit）、Layout_Mode（选填，默认 SINGLE）、Display Order（选填，整数，默认 0）、Is_Default（布尔值，默认 false）
2. WHEN Admin_Center_Authenticated_User 提交的 Dashboard ID 不存在于 Dashboard_Registry 中或该 Dashboard 状态为已失效，THE Dashboard_Assignment SHALL 拒绝创建并返回 Dashboard 不存在或已失效的错误信息
3. WHEN Admin_Center_Authenticated_User 提交的 Target ID 在对应的 Assignment_Target_Type 维度中不存在，THE Dashboard_Assignment SHALL 拒绝创建并返回目标不存在的错误信息
4. WHEN 同一 Dashboard 和同一 Target（相同 Type + Target ID）的分配记录已存在，THE Dashboard_Assignment SHALL 拒绝创建并返回重复分配的错误信息
5. WHEN Admin_Center_Authenticated_User 查询某个 User 的有效 Dashboard 列表，THE Dashboard_Assignment SHALL 合并该 User 的直接分配、该 User 所属 Role 的分配、以及该 User 所属 Business Unit 的分配，仅包含状态为有效的 Dashboard，并按 Display Order 升序排列后返回去重结果
6. WHEN 同一 Dashboard 通过多个维度（User、Role、Business Unit）同时分配给同一 User，THE Dashboard_Assignment SHALL 在合并结果中仅保留一条记录，优先级为 USER > ROLE > BUSINESS_UNIT
7. WHEN Admin_Center_Authenticated_User 删除一条分配记录，THE Dashboard_Assignment SHALL 移除该分配记录并返回成功响应
8. WHEN Admin_Center_Authenticated_User 查询分配列表，THE Dashboard_Assignment SHALL 返回分页结果，支持按 Assignment_Target_Type 和 Dashboard_Title 筛选

### 需求 3：访问控制

**用户故事：** 作为系统管理员，我希望确保只有已登录 Admin Center 的用户才能访问 BI Management 模块，以防止未授权的访问。

#### 验收标准

1. WHEN 未认证用户访问 BI Management 相关 API，THE Admin_Center SHALL 返回 401 Unauthorized 响应
2. WHEN Admin_Center_Authenticated_User 访问 BI Management 模块，THE Admin_Center SHALL 允许该用户执行所有操作（查看、同步、编辑本地扩展字段（Tags、Is_Default_Landing）、切换 Dashboard 状态（启用/禁用）、删除、分配）
3. WHEN Admin_Center_Authenticated_User 执行同步、更新或删除操作，THE Admin_Center SHALL 记录一条审计日志，包含操作者、操作类型、目标资源和时间戳

### 需求 4：Guest Token 认证接口

**用户故事：** 作为前端开发者，我希望通过后端 API 获取 Superset Guest Token，以便在 User Portal 中安全地渲染嵌入式 Dashboard。

#### 验收标准

1. WHEN 已认证用户请求某个 Dashboard 的 Guest Token，THE Admin_Center SHALL 使用 Superset Admin 凭据调用 Superset REST API 获取 Guest Token 并返回给前端
2. WHEN 请求 Guest Token 的用户未被分配该 Dashboard，THE Admin_Center SHALL 返回 403 Forbidden 响应
3. IF Superset REST API 调用失败或超时，THEN THE Admin_Center SHALL 返回 502 Bad Gateway 响应并记录错误日志
4. THE Admin_Center SHALL 从环境变量或配置文件中读取 Superset 连接配置（Host、Port、Admin Username、Admin Password）

### 需求 5：User Portal Landing Page 渲染

**用户故事：** 作为终端用户，我希望在 User Portal 的 Landing Page 上看到分配给我的 Superset Dashboard，以便快速访问 BI 数据。

#### 验收标准

1. WHEN 用户登录 User Portal，THE User_Portal SHALL 调用 Admin_Center API 获取当前用户的有效 Dashboard 列表
2. WHEN 用户的有效 Dashboard 列表包含一条记录且 Layout_Mode 为 SINGLE，THE User_Portal SHALL 以全屏模式渲染该 Dashboard
3. WHEN 用户的有效 Dashboard 列表包含多条记录且 Layout_Mode 为 MULTI，THE User_Portal SHALL 以标签页模式渲染所有 Dashboard，默认显示 Is_Default 为 true 的 Dashboard
4. WHEN 用户的有效 Dashboard 列表包含多条记录且 Layout_Mode 为 WIDGET，THE User_Portal SHALL 以网格卡片模式渲染所有 Dashboard 的缩略图，点击后展开为全屏
5. WHEN 用户的有效 Dashboard 列表为空，THE User_Portal SHALL 显示空状态提示信息
6. THE User_Portal SHALL 使用 @superset-ui/embedded-sdk 的 embedDashboard 方法，传入 Embed_ID 和 Guest Token 渲染 Dashboard
7. WHEN Guest Token 过期或无效，THE User_Portal SHALL 自动重新请求 Guest Token 并刷新 Dashboard 渲染

### 需求 6：Admin Center 前端 BI Management 页面

**用户故事：** 作为 Admin Center 已认证用户，我希望在 Admin Center 前端有一个专门的 BI Management 导航入口和管理页面，以便方便地管理 Dashboard 同步和分配。

#### 验收标准

1. THE Admin_Center SHALL 在左侧导航菜单中新增 "BI Management" 一级菜单项，包含 "Dashboard Registry"、"Dashboard Assignment" 和 "RBAC Mapping" 三个子菜单项
2. WHEN Admin_Center_Authenticated_User 访问 Dashboard Registry 页面，THE Admin_Center SHALL 展示已同步 Dashboard 的表格列表，包含 Dashboard_Title、Embed_ID、Superset_Dashboard_UUID、Tags、Is_Default_Landing、Dashboard_Status（显示为"有效"、"手动失效"、"自动失效"）、Last_Synced_At 列
3. THE Admin_Center SHALL 在 Dashboard Registry 页面顶部提供"同步 Dashboard"按钮，点击后触发手动 Sync_Operation
4. WHEN 手动 Sync_Operation 完成，THE Admin_Center SHALL 展示同步结果摘要（新增数量、更新数量、自动失效数量）并刷新表格列表
5. WHEN Admin_Center_Authenticated_User 点击某条 Dashboard 记录的编辑按钮，THE Admin_Center SHALL 仅允许编辑 Tags 和 Is_Default_Landing 等本地扩展字段，Dashboard_Title、Description、Embed_ID 等来自 Superset 的字段展示为只读
6. WHEN Admin_Center_Authenticated_User 点击某条 Dashboard_Status 为 ACTIVE 的 Dashboard 记录的"禁用"按钮，THE Admin_Center SHALL 调用后端接口将该记录的 Dashboard_Status 设为 MANUAL_INACTIVE 并刷新表格
7. WHEN Admin_Center_Authenticated_User 点击某条 Dashboard_Status 为 MANUAL_INACTIVE 的 Dashboard 记录的"启用"按钮，THE Admin_Center SHALL 调用后端接口将该记录的 Dashboard_Status 恢复为 ACTIVE 并刷新表格
8. WHEN Dashboard_Status 为 AUTO_INACTIVE 时，THE Admin_Center SHALL 禁用该记录的"启用"按钮，仅在 Superset 端重新满足同步条件并完成同步后自动恢复
9. WHEN Admin_Center_Authenticated_User 访问 Dashboard Assignment 页面，THE Admin_Center SHALL 展示分配记录的表格列表，包含 Dashboard_Title、Assignment_Target_Type、Target Name、Layout_Mode、Display Order 列，并提供新增、编辑、删除操作按钮
10. WHEN Admin_Center_Authenticated_User 点击新增 Dashboard Assignment，THE Admin_Center SHALL 展示表单，其中 Dashboard 下拉列表仅显示 Dashboard_Status 为 ACTIVE 的已同步 Dashboard，Assignment_Target_Type 选择后动态加载对应维度（User/Role/Business Unit）的下拉选项
11. THE Admin_Center SHALL 对 BI Management 页面的所有路由要求用户已通过 Admin Center 登录认证
12. WHEN Admin_Center_Authenticated_User 访问 RBAC Mapping 页面，THE Admin_Center SHALL 展示映射关系的表格列表，包含 Sys_Role Name、Sys_Role Code、Sys_Role Type、已映射的 Superset_Role 列表、Last_Updated_At 列
13. THE Admin_Center SHALL 在 RBAC Mapping 页面顶部提供"同步 Superset Role"按钮，点击后触发 Superset_Role_Sync_Operation
14. WHEN Admin_Center_Authenticated_User 点击某条 Sys_Role 记录的"编辑映射"按钮，THE Admin_Center SHALL 展示映射编辑表单，其中 Superset_Role 以多选复选框或穿梭框形式展示所有已同步的 Superset_Role，已映射的 Superset_Role 默认选中
15. WHEN Admin_Center_Authenticated_User 提交映射编辑表单，THE Admin_Center SHALL 调用后端接口保存映射关系并刷新表格列表


### 需求 7：RBAC Mapping 管理

**用户故事：** 作为 Admin Center 已认证用户，我希望配置系统角色（Sys_Role）与 Superset 角色（Superset_Role）之间的映射关系，以便在请求 Guest Token 时自动传递用户对应的 Superset 角色信息，实现灵活的 BI 权限控制。

#### 验收标准

1. WHEN Superset_Role_Sync_Operation 执行时，THE RBAC_Mapping_Registry SHALL 查询 Superset_Database 中 AB_Role_Table 的所有记录，拉取 id 和 name 字段并存储到本地 Superset_Role 注册表
2. WHEN Superset_Role_Sync_Operation 发现 AB_Role_Table 中存在新的角色（id 不在本地注册表中），THE RBAC_Mapping_Registry SHALL 自动创建对应的本地 Superset_Role 记录
3. WHEN Superset_Role_Sync_Operation 发现 AB_Role_Table 中已同步角色的 name 发生变化，THE RBAC_Mapping_Registry SHALL 更新本地记录的 name 字段
4. WHEN Superset_Role_Sync_Operation 发现本地注册表中的 Superset_Role 在 AB_Role_Table 中已不存在，THE RBAC_Mapping_Registry SHALL 将该记录标记为 INACTIVE，而非直接删除
5. WHEN 本地注册表中标记为 INACTIVE 的 Superset_Role 在 AB_Role_Table 中重新出现，THE RBAC_Mapping_Registry SHALL 将该记录恢复为 ACTIVE
6. IF Superset_Role_Sync_Operation 执行过程中 Superset_Database 连接失败或查询异常，THEN THE RBAC_Mapping_Registry SHALL 记录错误日志并返回同步失败的错误信息，已有注册数据保持不变
7. WHEN Admin_Center_Authenticated_User 点击手动同步按钮，THE RBAC_Mapping_Registry SHALL 立即执行一次 Superset_Role_Sync_Operation 并返回同步结果摘要（新增数量、更新数量、失效数量）
8. THE RBAC_Mapping_Registry SHALL 支持定时自动执行 Superset_Role_Sync_Operation，同步周期通过系统配置项设定
9. WHEN Admin_Center_Authenticated_User 为某个 Sys_Role 配置 Superset_Role 映射，THE RBAC_Mapping_Registry SHALL 保存该 Sys_Role 与所选 Superset_Role 列表的映射关系（多对多）
10. WHEN Admin_Center_Authenticated_User 更新某个 Sys_Role 的映射关系，THE RBAC_Mapping_Registry SHALL 以全量替换方式保存新的映射列表（删除旧映射，创建新映射）
11. WHEN Admin_Center_Authenticated_User 查询 RBAC Mapping 列表，THE RBAC_Mapping_Registry SHALL 返回所有 Sys_Role 及其对应的 Superset_Role 映射信息，支持按 Sys_Role Name、Sys_Role Type 筛选
12. THE RBAC_Mapping_Registry SHALL 仅允许映射状态为 ACTIVE 的 Superset_Role，状态为 INACTIVE 的 Superset_Role 在映射编辑界面中展示为不可选
13. WHEN 已映射的 Superset_Role 被标记为 INACTIVE，THE RBAC_Mapping_Registry SHALL 保留该映射记录但在查询有效映射时排除该 Superset_Role
14. WHEN Dashboard Sync_Operation 执行时，THE Admin_Center SHALL 同步 Superset `dashboard_roles`（Dashboard 级 RBAC，需 Superset 开启 `DASHBOARD_RBAC`）到本地注册表；WHEN 用户查询有效 Dashboard 列表或请求 Guest Token 时，对于在 Superset 上设置了角色的 Dashboard，THE Admin_Center SHALL 仅当该用户的 Sys_Role 经 RBAC_Mapping_Registry 解析出的 ACTIVE Superset_Role 与 Dashboard 角色有交集、或包含 Superset 管理员角色（`bi.superset.admin-role-name`，默认 `Admin`）时才将其视为可见；未设置角色的 Dashboard 不受此限制
    > 修订说明（2026-09）：原条款要求把映射角色作为 `rls` 参数传给 Guest Token API。Superset 的 `/api/v1/security/guest_token/` 不接受角色列表（`rls` 只接受行级过滤子句），guest 身份固定为 `GUEST_ROLE_NAME`；映射角色实际从未生效。现改为在 Admin_Center 侧按角色过滤 Dashboard 分配。
15. WHEN 某个用户拥有多个 Sys_Role，THE Admin_Center SHALL 合并所有 Sys_Role 对应的 Superset_Role 映射，去重后参与上述交集判定
