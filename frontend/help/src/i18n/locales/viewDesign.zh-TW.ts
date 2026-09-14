export default {
  viewDesignGuide: {
    pageTitle: '檢視設計',
    crumb: '開發工作站 · 功能單元 · 檢視設計',
    intro:
      '每個檢視是一張表的已發布清單。存取控制決定使用者入口裡誰能看見。欄來自表設計。請先建立主表。',
    flowTitle: '操作順序',
    flow1: '開啟檢視設計。必須已經有主表',
    flow2: '選取一個檢視，或 Create view / Generate default views',
    flow3: '新增 Table columns（需要時再加 Lookup / Related columns）',
    flow4: '設 Name、Export、Detail form、Access control，再設 Sort / Filter',
    flow5: '儲存。入口只給符合存取控制的人看這個檢視',
    whatTitle: '檢視設計是什麼',
    whatBody:
      '功能單元頁簽「檢視設計」。左側：按表分組的 Views。Default 表示該表的預設檢視。這不是表單設計，也不是表設計。',
    workspaceFigure: '檢視設計。左：按表分組的檢視。右：欄、Name、Access control（Business Units 與 Roles）。',
    columnsTitle: '欄',
    columnsBody:
      'Table columns 列出本表欄位。Lookup columns 和 Related columns 來自關聯表。Add selected 把勾選項加進網格。Clear all 清掉網格上所有欄。',
    catViews: '左側清單。點選要編輯的檢視。尚未建立或產生預設檢視時為空。',
    catDefaults: '給還沒有檢視的表各建一個初始檢視。',
    catCreate: '在目前表上新增空白檢視。',
    catTableColumns: '本表欄位。搜尋可過濾清單。主鍵欄位有標記。',
    catAddSelected: '把目錄裡勾選的欄位追加到檢視網格。',
    propsTitle: '名稱、工具列、排序、過濾',
    propsBody:
      'Name 是入口上的名稱。Portal toolbar 的 Show Export button 預設關閉。Detail form 用於 SUB 表；主表列開啟申請詳情頁，使用 No detail page。',
    catName: '入口檢視清單裡的名稱，必填。',
    catExport: '開啟：入口使用者看到 Export。關閉是預設。',
    catDetail: 'SUB 檢視：開啟一列用哪張表單。主表：No detail page。',
    catSort: 'Sort by，然後 Then sort by。點箭頭切換升序或降序。',
    catFilter: 'Filter by。Edit filters 開啟條件對話框（Equals、Contains 等）。',
    accessTitle: '存取控制',
    accessBody:
      'Business Units 和 Roles 都空時，只有 System Administrator 能看見此檢視。若設定存取控制，至少選一個 Business Unit 和一個 Role。使用者必須兩邊都符合。System Administrator 始終能看見全部檢視和全部列。',
    catAccess: '右側這一段。標題下的說明寫明留空和成對規則。',
    catBu: '按業務單元誰能看見。占位：Select BUs that can see this view。',
    catRoles: '在已選單元內按角色誰能看見。選項來自這些單元的准入角色，不是全域角色清單。',
    catRolesFirst: '未選 Business Unit 時 Roles 停用。此時占位：Select a Business Unit first。',
    catInvolved:
      '關閉：通過 BU+Role 的人看見全部列。開啟：僅發起人、辦理人和 MI 參與者。System Administrator 仍看見全部列。',
    failTitle: '失敗時',
    failPair:
      '只配了 BU 或只配了 Role 時無法儲存。提示：Configure both BU and Role together, or leave both empty (System Administrator only)。',
    failEmpty: '兩邊都空：沒有 System Administrator 的人在入口裡看不見此檢視。',
    failAnd: 'BU 和 Role 都配了：該人必須屬於已選單元之一，並擁有已選角色之一。',
    failMainTable: '先在表設計裡建立主表。清單顯示 Create a Main Table in Table Design first。',
  },
}
