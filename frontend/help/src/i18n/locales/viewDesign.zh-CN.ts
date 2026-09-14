export default {
  viewDesignGuide: {
    pageTitle: '视图设计',
    crumb: '开发工作站 · 功能单元 · 视图设计',
    intro:
      '每个视图是一张表的已发布列表。访问控制决定用户门户里谁能看见。列来自表设计。请先创建主表。',
    flowTitle: '操作顺序',
    flow1: '打开视图设计。必须已经有主表',
    flow2: '选中一个视图，或 Create view / Generate default views',
    flow3: '添加 Table columns（需要时再加 Lookup / Related columns）',
    flow4: '设 Name、Export、Detail form、Access control，再设 Sort / Filter',
    flow5: '保存。门户只给符合访问控制的人看这个视图',
    whatTitle: '视图设计是什么',
    whatBody:
      '功能单元页签「视图设计」。左侧：按表分组的 Views。Default 表示该表的默认视图。这不是表单设计，也不是表设计。',
    workspaceFigure: '视图设计。左：按表分组的视图。右：列、Name、Access control（Business Units 与 Roles）。',
    columnsTitle: '列',
    columnsBody:
      'Table columns 列出本表字段。Lookup columns 和 Related columns 来自关联表。Add selected 把勾选项加进网格。Clear all 清掉网格上所有列。',
    catViews: '左侧列表。点选要编辑的视图。尚未创建或生成默认视图时为空。',
    catDefaults: '给还没有视图的表各建一个初始视图。',
    catCreate: '在当前表上新增空白视图。',
    catTableColumns: '本表字段。搜索可过滤列表。主键字段有标记。',
    catAddSelected: '把目录里勾选的字段追加到视图网格。',
    propsTitle: '名称、工具栏、排序、过滤',
    propsBody:
      'Name 是门户上的名称。Portal toolbar 的 Show Export button 默认关闭。Detail form 用于 SUB 表；主表行打开申请详情页，使用 No detail page。',
    catName: '门户视图列表里的名称，必填。',
    catExport: '打开：门户用户看到 Export。关闭是默认。',
    catDetail: 'SUB 视图：打开一行用哪张表单。主表：No detail page。',
    catSort: 'Sort by，然后 Then sort by。点箭头切换升序或降序。',
    catFilter: 'Filter by。Edit filters 打开条件对话框（Equals、Contains 等）。',
    accessTitle: '访问控制',
    accessBody:
      'Business Units 和 Roles 都空时，只有 System Administrator 能看见此视图。若配置访问控制，至少选一个 Business Unit 和一个 Role。用户必须两边都匹配。System Administrator 始终能看见全部视图和全部行。',
    catAccess: '右侧这一段。标题下的说明写明留空和成对规则。',
    catBu: '按业务单元谁能看见。占位：Select BUs that can see this view。',
    catRoles: '在已选单元内按角色谁能看见。选项来自这些单元的准入角色，不是全局角色列表。',
    catRolesFirst: '未选 Business Unit 时 Roles 禁用。此时占位：Select a Business Unit first。',
    catInvolved:
      '关闭：通过 BU+Role 的人看见全部行。打开：仅发起人、办理人和 MI 参与者。System Administrator 仍看见全部行。',
    failTitle: '失败时',
    failPair:
      '只配了 BU 或只配了 Role 时无法保存。提示：Configure both BU and Role together, or leave both empty (System Administrator only)。',
    failEmpty: '两边都空：没有 System Administrator 的人在门户里看不见此视图。',
    failAnd: 'BU 和 Role 都配了：该人必须属于已选单元之一，并拥有已选角色之一。',
    failMainTable: '先在表设计里创建主表。列表显示 Create a Main Table in Table Design first。',
  },
}
