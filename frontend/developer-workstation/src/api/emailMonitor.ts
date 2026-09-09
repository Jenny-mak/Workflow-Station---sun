import { functionUnitAxios } from './functionUnit'

/** One main-table field extraction rule (mirrors backend EmailExtractionSpec.FieldRule). */
export interface ExtractionFieldRule {
  target: string
  source:
    | 'SUBJECT'
    | 'FROM'
    | 'TO'
    | 'CC'
    | 'REPLY_TO'
    | 'DATE'
    | 'MESSAGE_ID'
    | 'TEXT'
    | 'HTML'
    | 'TEXT_AND_HTML'
    | 'ATTACHMENTS'
    | 'HEADER'
    | 'CONST'
  type: 'DIRECT' | 'CONST' | 'LABEL' | 'BETWEEN' | 'REGEX' | 'HEADER'
  value?: string
  label?: string
  before?: string
  after?: string
  pattern?: string
  group?: number
  header?: string
  required?: boolean
  postProcess?: Array<'TRIM' | 'DIGITS_ONLY' | 'STRIP_CURRENCY' | 'UPPER' | 'LOWER'>
}

/** One HTML table column -> sub-table field mapping. */
export interface ExtractionColumnRule {
  field: string
  columnIndex?: number
  constValue?: string
  postProcess?: Array<'TRIM' | 'DIGITS_ONLY' | 'STRIP_CURRENCY' | 'UPPER' | 'LOWER'>
}

export interface ExtractionSubTableRule {
  bindingId: string
  tableSelector?: string
  tableIndex?: number
  headerRow?: boolean
  columns: ExtractionColumnRule[]
}

/** Design-time sample email for the wizard preview; ignored by the runtime extractor. */
export interface ExtractionSampleEmail {
  subject?: string
  from?: string
  to?: string
  cc?: string
  replyTo?: string
  date?: string
  messageId?: string
  text?: string
  html?: string
  /** Read-only filename preview for ATTACHMENTS mappings; not sent to the runtime. */
  attachmentNames?: string
}

export interface ExtractionRules {
  fields?: ExtractionFieldRule[]
  subTables?: ExtractionSubTableRule[]
  sampleEmail?: ExtractionSampleEmail
}

export interface EmailMonitorRule {
  id: number
  ruleUid: string
  name: string
  enabled: boolean
  connectionUid: string
  sourceRuleId?: number
  processDefinitionKey?: string
  startEventId?: string
  folderLabel?: string
  filterFrom?: string
  filterSubject?: string
  actionType: 'START_PROCESS' | 'APPEND_SUB_TABLE'
  targetFormId?: number
  targetBindingId?: string
  systemInitiatorUserId?: string
  extractionRules?: ExtractionRules
  correlation?: Record<string, unknown>
  pollIntervalSeconds?: number
  reviewOnMissing?: boolean
  lastSyncedAt?: string
}

export interface EmailMonitorRuleRequest {
  name: string
  enabled?: boolean
  connectionUid: string
  processDefinitionKey?: string
  startEventId?: string
  folderLabel?: string
  filterFrom?: string
  filterSubject?: string
  actionType?: 'START_PROCESS' | 'APPEND_SUB_TABLE'
  targetFormId?: number
  targetBindingId?: string
  systemInitiatorUserId?: string
  extractionRules?: ExtractionRules
  correlation?: Record<string, unknown>
  pollIntervalSeconds?: number
  reviewOnMissing?: boolean
}

export interface EmailMonitorStartEventBindRequest {
  templateRuleId: number
  startEventId: string
  processDefinitionKey: string
  filterFrom?: string
  filterSubject?: string
  enabled?: boolean
}

const base = (functionUnitId: number) =>
  `/api/v1/function-units/${functionUnitId}/email-monitors`

export const emailMonitorApi = {
  listTemplates(functionUnitId: number) {
    return functionUnitAxios.get<any, { data: EmailMonitorRule[] }>(`${base(functionUnitId)}/templates`)
  },
  list(functionUnitId: number) {
    return functionUnitAxios.get<any, { data: EmailMonitorRule[] }>(base(functionUnitId))
  },
  get(functionUnitId: number, ruleId: number) {
    return functionUnitAxios.get<any, { data: EmailMonitorRule }>(`${base(functionUnitId)}/${ruleId}`)
  },
  getByStartEventId(functionUnitId: number, startEventId: string) {
    return functionUnitAxios.get<any, { data: EmailMonitorRule }>(
      `${base(functionUnitId)}/by-start-event/${encodeURIComponent(startEventId)}`
    )
  },
  create(functionUnitId: number, data: EmailMonitorRuleRequest) {
    return functionUnitAxios.post<any, { data: EmailMonitorRule }>(base(functionUnitId), data)
  },
  update(functionUnitId: number, ruleId: number, data: EmailMonitorRuleRequest) {
    return functionUnitAxios.put<any, { data: EmailMonitorRule }>(`${base(functionUnitId)}/${ruleId}`, data)
  },
  delete(functionUnitId: number, ruleId: number) {
    return functionUnitAxios.delete(`${base(functionUnitId)}/${ruleId}`)
  },
  bindStartEvent(functionUnitId: number, data: EmailMonitorStartEventBindRequest) {
    return functionUnitAxios.put<any, { data: EmailMonitorRule }>(
      `${base(functionUnitId)}/start-event-bindings`,
      data
    )
  },
  unbindStartEvent(functionUnitId: number, startEventId: string) {
    return functionUnitAxios.delete(`${base(functionUnitId)}/start-event-bindings/${encodeURIComponent(startEventId)}`)
  }
}
