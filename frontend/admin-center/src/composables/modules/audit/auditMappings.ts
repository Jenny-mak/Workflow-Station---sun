/**
 * 审计日志 - 动作/资源类型映射与时间格式化（纯函数）
 *
 * 从 useAudit.ts 抽出，行为逐字不变。
 * 依赖 i18n 的函数以 `t` 作为入参，便于在 composable 外复用且不引入 Vue 上下文。
 */

/** i18n 翻译函数（支持可选 fallback 参数） */
export type AuditTranslate = (key: string, fallback?: string) => string

export const actionType = (action: string): 'success' | 'warning' | 'info' | 'primary' | 'danger' => {
  switch ((action || '').toUpperCase()) {
    case 'CREATE': return 'primary'
    case 'UPDATE': return 'warning'
    case 'DELETE': return 'danger'
    case 'QUERY':  return 'info'
    default:       return 'info'
  }
}

export const actionText = (t: AuditTranslate, action: string) => {
  switch ((action || '').toUpperCase()) {
    case 'CREATE': return t('audit.actionCREATE')
    case 'UPDATE': return t('audit.actionUPDATE')
    case 'DELETE': return t('audit.actionDELETE')
    case 'QUERY':  return t('audit.actionQUERY')
    default:       return action || '-'
  }
}

export const actionCategory = (action: string): 'create' | 'update' | 'delete' | 'query' | 'other' => {
  switch ((action || '').toUpperCase()) {
    case 'CREATE': return 'create'
    case 'UPDATE': return 'update'
    case 'DELETE': return 'delete'
    case 'QUERY':  return 'query'
    default:       return 'other'
  }
}

export const resourceTypeText = (t: AuditTranslate, rt: string | null | undefined): string => {
  const sep = ' - '
  const EM  = t('menu.entitlementManagement')
  const RT  = t('menu.relationTables')
  switch ((rt || '').toUpperCase()) {
    case 'USER':               return [t('menu.userManagement'), t('menu.userList')].join(sep)
    case 'ROLE':               return [EM, t('menu.roleManagement')].join(sep)
    case 'VIRTUAL_GROUP':      return [EM, t('menu.virtualGroup')].join(sep)
    case 'TASK':               return [EM, t('menu.virtualGroup')].join(sep) // table rows only; not in filter dropdown
    case 'BUSINESS_UNIT':      return [EM, t('menu.organization')].join(sep)
    case 'RELATION_TABLE':     return [RT, t('menu.tableStructure')].join(sep)
    case 'RELATION_TABLE_ROW': return [RT, t('menu.tableData')].join(sep)
    case 'AUTH':               return t('common.auth', 'Auth')
    case 'BI_DASHBOARD':       return [t('menu.biManagement'), t('menu.biDashboardRegistry')].join(sep)
    case 'BI_ASSIGNMENT':      return [t('menu.biManagement'), t('menu.biAudienceAssignment')].join(sep)
    case 'BI_DATA_VIEW_ASSIGNMENT': return [t('menu.biManagement'), t('menu.biDataViewAssignment')].join(sep)
    case 'BI_RBAC':            return [t('menu.biManagement'), t('menu.biRbacMapping')].join(sep)
    // 自动化两页是顶级菜单项，无父级分组，故不拼 sep
    case 'AUTOMATION_FLOW':    return t('menu.automationFlows')
    case 'AUTOMATION_PIECE':   return t('menu.automationPieces')
    default:                   return rt || ''
  }
}

export const formatTime = (isoStr: string | null | undefined): string => {
  if (!isoStr) return '-'
  try {
    const date = new Date(isoStr)
    if (isNaN(date.getTime())) return isoStr
    const yyyy = date.getFullYear()
    const MM = String(date.getMonth() + 1).padStart(2, '0')
    const dd = String(date.getDate()).padStart(2, '0')
    const HH = String(date.getHours()).padStart(2, '0')
    const mm = String(date.getMinutes()).padStart(2, '0')
    const ss = String(date.getSeconds()).padStart(2, '0')
    const SSS = String(date.getMilliseconds()).padStart(3, '0')
    return `${yyyy}-${MM}-${dd} ${HH}:${mm}:${ss}.${SSS}`
  } catch {
    return isoStr
  }
}
