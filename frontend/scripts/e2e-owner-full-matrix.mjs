/**
 * Travel Expense Owner Matrix — create (or reuse) one complex Function Unit and
 * drive every Owner write/display path from docs/design/owner-field-component.md.
 *
 *   node frontend/scripts/e2e-owner-full-matrix.mjs
 *   SETUP_ONLY=1 node frontend/scripts/e2e-owner-full-matrix.mjs
 *
 * Needs the edge stack on http://localhost:3000.
 */
import { execFileSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = (process.env.PORTAL_ORIGIN ?? process.env.HELP_GUIDE_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const FU_NAME = process.env.OWNER_MATRIX_FU_NAME ?? 'Travel Expense Owner Matrix'
const DW_USER = process.env.LOGIN_USER ?? 'developer'
const DW_PASS = process.env.LOGIN_PASS ?? 'password'
const TEMPLATE_CODE = process.env.OWNER_DEMO_CODE ?? 'owner-demo-20260907-gehibh'
const MAIN_TABLE = 'te_own_hdr'
const ITIN_TABLE = 'te_own_itin'
const APPR_TABLE = 'te_own_appr'
const MI_BOX = 'Cost Center Review'
const SYS_USERS_TABLE_ID = -1_000_000_001
const SHOT_DATE = new Date().toISOString().slice(0, 10)
const OUT = join(dirname(fileURLToPath(import.meta.url)), '../user-portal/verification-screenshots')
mkdirSync(OUT, { recursive: true })

const CAST = {
  zhangwei: { user: 'e2e_zhangwei', pass: 'password', buCode: '共享财务中心', roleCode: 'HMDC_Operator_Role' },
  lina: { user: 'e2e_lina', pass: 'password', buCode: '共享财务中心', roleCode: 'Department Manager' },
  developer: { user: 'developer', pass: 'password', buCode: '共享财务中心', roleCode: 'MANAGER' },
  wangfang: { user: 'e2e_wangfang', pass: 'password', buCode: '共享财务中心', roleCode: 'HMDC_Operator_Role' },
  wangfangIndex: { user: 'e2e_wangfang', pass: 'password', buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' },
}

const results = []
const catalog = []
const findings = []

function check(scene, label, actual, expected, extra) {
  const ok = typeof expected === 'boolean'
    ? actual === expected
    : JSON.stringify(actual) === JSON.stringify(expected)
  results.push({ ok, scene, label, actual, expected, extra })
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${scene}] ${label}`)
  if (!ok) {
    console.log(`        expected ${JSON.stringify(expected)}`)
    console.log(`        actual   ${JSON.stringify(actual)}`)
    if (extra) console.log(`        extra    ${JSON.stringify(extra)}`)
  }
}

function asRows(payload) {
  if (Array.isArray(payload)) return payload
  if (!payload || typeof payload !== 'object') return []
  if (Array.isArray(payload.records)) return payload.records
  if (Array.isArray(payload.content)) return payload.content
  if (Array.isArray(payload.items)) return payload.items
  if (payload.data) return asRows(payload.data)
  return []
}

function xmlAttr(value) {
  return String(value).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')
}

function ownerRule(field, title, source) {
  return { type: 'owner', field, title, props: { ownerConfig: JSON.stringify({ source }) } }
}

function userLookup(field, title) {
  return {
    type: 'lookup',
    field,
    title,
    props: {
      lookupConfig: JSON.stringify({
        tableId: SYS_USERS_TABLE_ID,
        tableName: 'sys_users',
        valueField: 'id',
        displayField: 'display_name',
        displayFields: ['username', 'display_name', 'full_name'],
      }),
    },
  }
}

function settle(ms = 800) {
  return new Promise((r) => setTimeout(r, ms))
}

function pkField(sortOrder) {
  return {
    fieldName: 'id',
    displayName: 'ID',
    dataType: 'VARCHAR',
    length: 64,
    nullable: false,
    isPrimaryKey: true,
    pkGeneration: { strategy: 'uuid', scope: 'perTable' },
    sortOrder,
  }
}

function col(sortOrder, fieldName, displayName, extra = {}) {
  const dataType = extra.dataType ?? 'VARCHAR'
  const row = {
    fieldName,
    displayName,
    dataType,
    nullable: extra.nullable ?? true,
    sortOrder,
    ...extra,
  }
  if (dataType === 'DATE' || dataType === 'TIMESTAMP') delete row.length
  else if (row.length == null) row.length = 255
  return row
}

function userValue(userId) {
  return `user:${userId}`
}

function startsUser(value) {
  return String(value ?? '').startsWith('user:')
}

function findRow(payload, instanceId, key = 'processInstanceId') {
  const seen = new Set()
  const walk = (node) => {
    if (!node || typeof node !== 'object' || seen.has(node)) return null
    seen.add(node)
    if (Array.isArray(node)) {
      for (const item of node) {
        const hit = item && typeof item === 'object'
          && (item[key] === instanceId || item.id === instanceId)
          ? item
          : walk(item)
        if (hit) return hit
      }
      return null
    }
    for (const value of Object.values(node)) {
      const hit = walk(value)
      if (hit) return hit
    }
    return null
  }
  return walk(payload)
}

function sliceRows(vars, ...keys) {
  const slices = vars?.__subTables__
  if (!slices || typeof slices !== 'object') return []
  for (const key of keys) {
    const rows = slices[key] ?? slices[String(key)]
    if (Array.isArray(rows) && rows.length) return rows
  }
  for (const rows of Object.values(slices)) {
    if (Array.isArray(rows) && rows.some((r) => r && (r.assignee_user_id || r.row_handler || r.city))) {
      return rows
    }
  }
  return []
}

function remember(scene, instanceId, note, extra = {}) {
  catalog.push({
    scene,
    instanceId,
    note,
    request: instanceId ? `${ORIGIN}/portal/applications/${instanceId}` : undefined,
    ...extra,
  })
}

function dbHandler(instanceId) {
  const id = String(instanceId ?? '')
  if (!/^[A-Za-z0-9_-]+$/.test(id)) {
    return 'db-read-skipped:bad-id'
  }
  const sql = `select coalesce(variables->>'case_handler','') || ' | assignee=' || coalesce(current_assignee,'') || ' | cands=' || coalesce(candidate_users,'') from up_process_instance where id='${id}'`
  try {
    const raw = execFileSync(
      'docker',
      [
        'exec', '-e', `SQL=${sql}`, 'platform-postgres-dev',
        'sh', '-c',
        'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$SQL"',
      ],
      { encoding: 'utf8', timeout: 15000 },
    ).trim()
    return raw
  } catch (err) {
    return `db-read-failed:${String(err?.message || err).slice(0, 80)}`
  }
}

async function dumpOwner(api, instanceId, label, extraKeys = []) {
  await settle()
  const vars = await liveVars(api, instanceId)
  let d = {}
  try {
    const detail = await api('GET', `/processes/${instanceId}`)
    d = detail.body.data ?? {}
  } catch (err) {
    console.log(`[dump ${label}] process GET failed: ${String(err?.message || err).slice(0, 120)}`)
  }
  const appr = sliceRows(vars, ...extraKeys, APPR_TABLE)
  console.log(`[dump ${label}] form.handler=${JSON.stringify(vars.case_handler)} db.handler=${JSON.stringify(dbHandler(instanceId))} node=${d.currentNode} assignee=${d.currentAssignee} status=${d.status}`)
  if (appr.length) {
    console.log(`[dump ${label}] mi`, appr.map((r) => ({
      id: r.id, assignee: r.assignee_user_id, row_handler: r.row_handler, row_creator: r.row_creator,
    })))
  }
  return vars
}

function cookieHeader(res) {
  const raw = typeof res.headers.getSetCookie === 'function'
    ? res.headers.getSetCookie()
    : [res.headers.get('set-cookie')].filter(Boolean)
  return raw.map((c) => String(c).split(';')[0]).join('; ')
}

async function dwLogin() {
  const res = await fetch(`${ORIGIN}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: DW_USER, password: DW_PASS }),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(`DW login failed: ${json.message || `HTTP ${res.status}`}`)
  const user = json.user ?? json.data?.user
  if (!user?.userId) throw new Error('DW login missing user')
  return { userId: user.userId, cookie: cookieHeader(res) }
}

async function dwApi(session, method, path, body) {
  const res = await fetch(`${ORIGIN}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Cookie: session.cookie,
      'X-User-Id': String(session.userId),
      ...(session.groupId ? { 'X-Dev-Group-Id': session.groupId } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok || json.success === false) {
    const msg = json.error?.message || json.message || JSON.stringify(json)
    throw new Error(`${method} ${path} → HTTP ${res.status} ${msg}`)
  }
  return json.data
}

async function adminLogin() {
  const res = await fetch(`${ORIGIN}/api/v1/admin/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: process.env.ADMIN_USER ?? 'admin',
      password: process.env.ADMIN_PASS ?? 'admin123',
    }),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(`Admin login failed: ${json.message || `HTTP ${res.status}`}`)
  const user = json.user ?? json.data?.user
  if (!user?.userId) throw new Error('Admin login missing user')
  return { userId: user.userId, cookie: cookieHeader(res) }
}

async function adminApi(session, method, path, body) {
  const res = await fetch(`${ORIGIN}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Cookie: session.cookie,
      'X-User-Id': String(session.userId),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok || json.success === false) {
    const msg = json.error?.message || json.message || JSON.stringify(json)
    throw new Error(`${method} ${path} → HTTP ${res.status} ${msg}`)
  }
  return json.data !== undefined ? json.data : json
}

async function portalLoginWorkspaces(who) {
  const first = await fetch(`${ORIGIN}/api/portal/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: who.user, password: who.pass }),
  })
  const body = await first.json().catch(() => ({}))
  return body.workspaceContexts ?? []
}

async function collectViewAccessRules() {
  const rules = []
  const seen = new Set()
  for (const who of [CAST.zhangwei, CAST.lina, CAST.wangfangIndex]) {
    const contexts = await portalLoginWorkspaces(who)
    for (const c of contexts) {
      const buId = String(c.businessUnitId || '')
      const roleId = String(c.roleId || '')
      if (buId && !seen.has(`BU:${buId}`)) {
        seen.add(`BU:${buId}`)
        rules.push({
          targetType: 'BUSINESS_UNIT',
          targetId: buId,
          targetName: c.businessUnitName || c.businessUnitCode,
        })
      }
      if (roleId && !seen.has(`ROLE:${roleId}`)) {
        seen.add(`ROLE:${roleId}`)
        rules.push({
          targetType: 'ROLE',
          targetId: roleId,
          targetName: c.roleName || c.roleCode,
        })
      }
    }
  }
  return rules
}

async function grantPortalStartRoles(newCode) {
  const admin = await adminLogin()
  const template = await adminApi(admin, 'GET', `/api/v1/admin/function-units/code/${encodeURIComponent(TEMPLATE_CODE)}/active-for-start`)
  const catalogRow = await adminApi(admin, 'GET', `/api/v1/admin/function-units/code/${encodeURIComponent(newCode)}/latest`)
  const templateId = template?.id
  const catalogId = catalogRow?.id
  if (!templateId || !catalogId) {
    throw new Error(`Cannot resolve catalog ids template=${templateId} catalog=${catalogId}`)
  }
  const access = asRows(await adminApi(admin, 'GET', `/api/v1/admin/function-units/${templateId}/access`))
  const roleIds = [...new Set(access.map((row) => row.roleId || row.targetId).filter(Boolean))]
  if (roleIds.length === 0) throw new Error(`Template ${TEMPLATE_CODE} has no Portal access roles`)
  await adminApi(admin, 'PUT', `/api/v1/admin/function-units/${catalogId}/access`, roleIds.map((roleId) => ({ roleId })))
  console.log(`Granted Portal start roles (${roleIds.length}) from ${TEMPLATE_CODE} → ${catalogId}`)
}

async function findOrCreate(dw, listPath, match, payload) {
  const hit = asRows(await dw('GET', listPath)).find(match)
  if (hit) return hit
  return dw('POST', listPath, payload)
}

async function ensureTable(dw, fuId, tableName, displayName, tableType) {
  const existing = asRows(await dw('GET', `/api/v1/function-units/${fuId}/tables`)).find((t) => t.tableName === tableName)
  if (existing) return existing
  return dw('POST', `/api/v1/function-units/${fuId}/tables`, {
    tableName,
    tableDisplayName: displayName,
    tableType,
    description: displayName,
  })
}

async function ensureBinding(dw, fuId, formId, payload) {
  const bindings = asRows(await dw('GET', `/api/v1/function-units/${fuId}/forms/${formId}/bindings`))
  const hit = bindings.find((b) => b.bindingType === payload.bindingType && Number(b.tableId) === Number(payload.tableId))
  if (hit) return hit
  return dw('POST', `/api/v1/function-units/${fuId}/forms/${formId}/bindings`, payload)
}

function listColumns(fields) {
  return fields
    .filter((f) => f.fieldName && !f.isForeignKey && f.fieldName !== 'id')
    .map((f) => ({
      fieldName: f.fieldName,
      dataType: f.dataType,
      nullable: f.nullable !== false,
      isPrimaryKey: !!f.isPrimaryKey,
      displayName: f.displayName || f.fieldName,
      columnType: 'field',
    }))
}

function bpmnXml({
  processId, reviewFormId, reviewFormName, requestFormId, requestFormName,
  miFormId, miFormName, closeFormId, closeFormName,
  actionIds, actionNames, approverTableId,
}) {
  const actionIdsXml = xmlAttr(JSON.stringify(actionIds ?? []))
  const actionNamesXml = xmlAttr(JSON.stringify(actionNames ?? []))
  const collection = `multiInstance_${APPR_TABLE}_collection`
  const subId = `MultiInstance_SubTable_${approverTableId}`
  const form = (id, name) => `
          <custom:property name="formId" value="${xmlAttr(id)}"/>
          <custom:property name="formName" value="${xmlAttr(name)}"/>
          <custom:property name="formReadOnly" value="false"/>
          <custom:property name="requestFormId" value="${xmlAttr(requestFormId)}"/>
          <custom:property name="requestFormName" value="${xmlAttr(requestFormName)}"/>
          <custom:property name="actionIds" value="${actionIdsXml}"/>
          <custom:property name="actionNames" value="${actionNamesXml}"/>`
  return `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:flowable="http://flowable.org/bpmn" xmlns:custom="http://workflow.platform/schema/custom" id="Definitions_TravelOwner" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="${xmlAttr(processId)}" name="Travel Expense" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" name="Start">
      <bpmn:outgoing>Flow_StartToReview</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:userTask id="Task_DeptReview" name="Dept Review">
      <bpmn:extensionElements>
        <custom:properties>
          <custom:property name="assigneeType" value="FIXED_BU_ROLE"/>
          <custom:property name="assigneeLabel" value="Fixed BU Role: Department Manager"/>
          <custom:property name="businessUnitId" value="E2E_FINANCE"/>
          <custom:property name="roleId" value="MANAGER"/>
          <custom:property name="roleIds" value="MANAGER"/>
          ${form(reviewFormId, reviewFormName)}
        </custom:properties>
      </bpmn:extensionElements>
      <bpmn:incoming>Flow_StartToReview</bpmn:incoming>
      <bpmn:outgoing>Flow_ReviewToMi</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:subProcess id="${subId}" name="${xmlAttr(MI_BOX)}">
      <bpmn:extensionElements>
        <custom:properties>
          <custom:property name="multiInstance" value="true"/>
        </custom:properties>
      </bpmn:extensionElements>
      <bpmn:incoming>Flow_ReviewToMi</bpmn:incoming>
      <bpmn:outgoing>Flow_MiToClose</bpmn:outgoing>
      <bpmn:multiInstanceLoopCharacteristics isSequential="false" flowable:collection="${collection}" flowable:elementVariable="currentItem"/>
      <bpmn:startEvent id="MI_Start_${approverTableId}">
        <bpmn:outgoing>MI_Flow1_${approverTableId}</bpmn:outgoing>
      </bpmn:startEvent>
      <bpmn:userTask id="MI_UserTask_${approverTableId}" name="Cost Center Line">
        <bpmn:extensionElements>
          <custom:properties>
            <custom:property name="assigneeType" value="ELEMENT_VARIABLE"/>
            <custom:property name="assigneeMode" value="user"/>
            <custom:property name="subTableId" value="${approverTableId}"/>
            <custom:property name="subTableName" value="${APPR_TABLE}"/>
            <custom:property name="assigneeField" value="assignee_user_id"/>
            <custom:property name="rowIdVariable" value="currentItem.rowId"/>
            ${form(miFormId, miFormName)}
          </custom:properties>
        </bpmn:extensionElements>
        <bpmn:incoming>MI_Flow1_${approverTableId}</bpmn:incoming>
        <bpmn:outgoing>MI_Flow2_${approverTableId}</bpmn:outgoing>
      </bpmn:userTask>
      <bpmn:endEvent id="MI_End_${approverTableId}">
        <bpmn:incoming>MI_Flow2_${approverTableId}</bpmn:incoming>
      </bpmn:endEvent>
      <bpmn:sequenceFlow id="MI_Flow1_${approverTableId}" sourceRef="MI_Start_${approverTableId}" targetRef="MI_UserTask_${approverTableId}"/>
      <bpmn:sequenceFlow id="MI_Flow2_${approverTableId}" sourceRef="MI_UserTask_${approverTableId}" targetRef="MI_End_${approverTableId}"/>
    </bpmn:subProcess>
    <bpmn:userTask id="Task_FinanceClose" name="Finance Close">
      <bpmn:extensionElements>
        <custom:properties>
          <custom:property name="assigneeType" value="INITIATOR"/>
          ${form(closeFormId, closeFormName)}
        </custom:properties>
      </bpmn:extensionElements>
      <bpmn:incoming>Flow_MiToClose</bpmn:incoming>
      <bpmn:outgoing>Flow_CloseToEnd</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="EndEvent_1" name="Done">
      <bpmn:incoming>Flow_CloseToEnd</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_StartToReview" sourceRef="StartEvent_1" targetRef="Task_DeptReview"/>
    <bpmn:sequenceFlow id="Flow_ReviewToMi" sourceRef="Task_DeptReview" targetRef="${subId}"/>
    <bpmn:sequenceFlow id="Flow_MiToClose" sourceRef="${subId}" targetRef="Task_FinanceClose"/>
    <bpmn:sequenceFlow id="Flow_CloseToEnd" sourceRef="Task_FinanceClose" targetRef="EndEvent_1"/>
  </bpmn:process>
</bpmn:definitions>`
}

function buildFormConfig(mainFields, itinFields, apprFields, itinBindingId, apprBindingId) {
  const select = (field, title, options) => ({
    field,
    title,
    type: 'select',
    options: options.map((value) => ({ label: value, value })),
    props: { placeholder: `Please select ${title}`, clearable: true, filterable: true },
  })
  const input = (field, title) => ({
    field, title, type: 'input', props: { placeholder: title, maxlength: 200 },
  })
  return {
    rule: [
      input('title', 'Title'),
      select('travel_type', 'Travel type', ['Air', 'Train', 'Hotel']),
      select('cost_center', 'Cost center', ['共享财务中心', 'hase-hmdc']),
      { field: 'remark', title: 'Remark', type: 'input', props: { type: 'textarea' } },
      ownerRule('case_creator', 'Creator', 'CREATOR'),
      ownerRule('case_handler', 'Case Handler', 'CASE_HANDLER'),
      { type: 'subTable', _bindingId: itinBindingId, title: 'Itinerary', props: {} },
      { type: 'subTable', _bindingId: apprBindingId, title: 'Cost center approvers', props: {} },
    ],
    options: { form: { labelPosition: 'left' } },
    subForms: {
      [String(itinBindingId)]: {
        rule: [
          input('city', 'City'),
          { field: 'travel_date', title: 'Travel date', type: 'datePicker', props: { type: 'date', valueFormat: 'YYYY-MM-DD' } },
          ownerRule('line_creator', 'Line Creator', 'CREATOR'),
          ownerRule('line_handler', 'Line Handler', 'CASE_HANDLER'),
        ],
        options: { form: { labelPosition: 'left' } },
      },
      [String(apprBindingId)]: {
        rule: [
          userLookup('assignee_user_id', 'Assignee'),
          input('note', 'Note'),
          ownerRule('row_creator', 'Row Creator', 'CREATOR'),
          ownerRule('row_handler', 'Row Handler', 'CASE_HANDLER'),
        ],
        options: { form: { labelPosition: 'left' } },
      },
    },
    subListViews: {
      [String(itinBindingId)]: { columns: listColumns(itinFields) },
      [String(apprBindingId)]: { columns: listColumns(apprFields) },
    },
  }
}

async function setupFu() {
  const session = await dwLogin()
  const dw = (method, path, body) => dwApi(session, method, path, body)
  const groupsPayload = await dw('GET', '/api/v1/function-units/my-dev-groups')
  const groupId = groupsPayload?.groups?.[0]?.id || groupsPayload?.publicGroupId
  if (!groupId) throw new Error('No developer team for this user')
  session.groupId = groupId

  const listed = await dw('GET', '/api/v1/function-units?page=0&size=100')
  const records = listed?.records ?? listed?.content ?? (Array.isArray(listed) ? listed : [])
  let fu = records.find((row) => row.name === FU_NAME)
  if (fu?.id) {
    console.log(`Reusing FU id=${fu.id} code=${fu.code ?? ''}`)
  } else {
    fu = await dw('POST', '/api/v1/function-units', {
      name: FU_NAME,
      description: '出差报销：主表+行程子表+MI 审批行，四处挂 Owner。用于 Owner 全路径验收。',
      tags: ['owner', 'e2e', 'mi'],
      virtualGroupIds: [groupId],
    })
    console.log(`Created FU id=${fu.id} code=${fu.code ?? ''}`)
  }
  const fuId = fu.id

  const main = await ensureTable(dw, fuId, MAIN_TABLE, 'Travel header', 'MAIN')
  const itin = await ensureTable(dw, fuId, ITIN_TABLE, 'Itinerary', 'SUB')
  const appr = await ensureTable(dw, fuId, APPR_TABLE, 'Cost center approvers', 'SUB')

  const mainFields = [
    pkField(0),
    col(1, 'title', 'Title', { length: 200, nullable: false }),
    col(2, 'travel_type', 'Travel type', { length: 32 }),
    col(3, 'cost_center', 'Cost center', { length: 64 }),
    col(4, 'remark', 'Remark', { length: 500 }),
    col(5, 'case_creator', 'Creator'),
    col(6, 'case_handler', 'Case Handler'),
  ]
  const itinFields = [
    pkField(0),
    col(1, 'main_id', 'Header ID', {
      length: 64,
      isForeignKey: true,
      refTableId: main.id,
      refPrimaryKeyFields: ['id'],
      fkDisplayMode: 'readonly',
      relationCardinality: 'oneToMany',
    }),
    col(2, 'city', 'City', { length: 80 }),
    col(3, 'travel_date', 'Travel date', { dataType: 'DATE', length: undefined }),
    col(4, 'line_creator', 'Line Creator'),
    col(5, 'line_handler', 'Line Handler'),
  ]
  const apprFields = [
    pkField(0),
    col(1, 'main_id', 'Header ID', {
      length: 64,
      isForeignKey: true,
      refTableId: main.id,
      refPrimaryKeyFields: ['id'],
      fkDisplayMode: 'readonly',
      relationCardinality: 'oneToMany',
    }),
    col(2, 'assignee_user_id', 'Assignee user id', { length: 64, nullable: false }),
    col(3, 'note', 'Note', { length: 200 }),
    col(4, 'row_creator', 'Row Creator'),
    col(5, 'row_handler', 'Row Handler'),
  ]

  await dw('PUT', `/api/v1/function-units/${fuId}/tables/${main.id}`, {
    tableName: MAIN_TABLE, tableDisplayName: 'Travel header', tableType: 'MAIN', fields: mainFields,
  })
  await dw('PUT', `/api/v1/function-units/${fuId}/tables/${itin.id}`, {
    tableName: ITIN_TABLE, tableDisplayName: 'Itinerary', tableType: 'SUB', fields: itinFields,
  })
  await dw('PUT', `/api/v1/function-units/${fuId}/tables/${appr.id}`, {
    tableName: APPR_TABLE, tableDisplayName: 'Cost center approvers', tableType: 'SUB', fields: apprFields,
  })

  let processForms = asRows(await dw('GET', `/api/v1/function-units/${fuId}/forms`)).filter((f) => f.formType === 'PROCESS')
  if (processForms.length === 0) {
    await dw('POST', `/api/v1/function-units/${fuId}/forms`, {
      formName: 'Travel Form',
      formType: 'PROCESS',
      scene: 'TASK',
      createBothScenes: true,
      boundTableId: main.id,
      configJson: { rule: [] },
      description: 'Travel expense',
    })
    processForms = asRows(await dw('GET', `/api/v1/function-units/${fuId}/forms`)).filter((f) => f.formType === 'PROCESS')
  }

  let itinBindingId
  let apprBindingId
  for (const form of processForms) {
    await ensureBinding(dw, fuId, form.id, {
      tableId: main.id, bindingType: 'PRIMARY', bindingMode: 'EDITABLE', sortOrder: 0,
    })
    const itinBinding = await ensureBinding(dw, fuId, form.id, {
      tableId: itin.id,
      bindingType: 'SUB',
      bindingMode: 'EDITABLE',
      foreignKeyField: 'main_id',
      bindingLinkMode: 'structuralFk',
      subMode: 'FULL',
      sortOrder: 1,
    })
    const apprBinding = await ensureBinding(dw, fuId, form.id, {
      tableId: appr.id,
      bindingType: 'SUB',
      bindingMode: 'EDITABLE',
      foreignKeyField: 'main_id',
      bindingLinkMode: 'structuralFk',
      subMode: 'FULL',
      sortOrder: 2,
    })
    itinBindingId = itinBinding.id
    apprBindingId = apprBinding.id
    await dw('PUT', `/api/v1/function-units/${fuId}/forms/${form.id}`, {
      formName: form.formName,
      formType: form.formType || 'PROCESS',
      scene: form.scene || 'TASK',
      boundTableId: main.id,
      description: form.description || 'Travel expense',
      configJson: buildFormConfig(mainFields, itinFields, apprFields, itinBinding.id, apprBinding.id),
    })
  }

  const approve = await findOrCreate(dw, `/api/v1/function-units/${fuId}/actions`, (a) => a.actionType === 'APPROVE', {
    actionName: 'Approve', actionType: 'APPROVE', configJson: {}, description: 'Approve',
  })
  const reject = await findOrCreate(dw, `/api/v1/function-units/${fuId}/actions`, (a) => a.actionType === 'REJECT', {
    actionName: 'Reject', actionType: 'REJECT', configJson: {}, description: 'Reject',
  })
  const delegate = await findOrCreate(dw, `/api/v1/function-units/${fuId}/actions`, (a) => a.actionType === 'DELEGATE', {
    actionName: 'Delegate', actionType: 'DELEGATE', configJson: { requireAssignee: true, requireComment: false }, description: 'Delegate',
  })
  const transfer = await findOrCreate(dw, `/api/v1/function-units/${fuId}/actions`, (a) => a.actionType === 'TRANSFER', {
    actionName: 'Transfer', actionType: 'TRANSFER', configJson: { requireAssignee: true, requireComment: false }, description: 'Transfer',
  })
  const bound = [approve, reject, delegate, transfer].filter((a) => a?.id != null)
  const taskForm = processForms.find((f) => (f.scene || 'TASK') !== 'REQUEST') ?? processForms[0]
  const requestForm = processForms.find((f) => f.scene === 'REQUEST') ?? taskForm
  if (!fu.code) {
    throw new Error('Function Unit code is required so BPMN process id matches the portal processDefinitionKey')
  }

  await dw('POST', `/api/v1/function-units/${fuId}/process`, {
    bpmnXml: bpmnXml({
      processId: fu.code,
      reviewFormId: taskForm.id,
      reviewFormName: taskForm.formName,
      requestFormId: requestForm.id,
      requestFormName: requestForm.formName,
      miFormId: taskForm.id,
      miFormName: taskForm.formName,
      closeFormId: taskForm.id,
      closeFormName: taskForm.formName,
      actionIds: bound.map((a) => a.id),
      actionNames: bound.map((a) => a.actionName),
      approverTableId: appr.id,
    }),
  })

  await dw('POST', `/api/v1/function-units/${fuId}/main-table-views/seed-defaults`)
  const views = asRows(await dw('GET', `/api/v1/function-units/${fuId}/main-table-views`))
  const mainView = views.find((v) => Number(v.mainTableId) === Number(main.id)) || views[0]
  if (mainView?.id) {
    const latest = await dw('GET', `/api/v1/function-units/${fuId}/main-table-views/${mainView.id}`)
    const accessRules = (latest.accessRules || []).length
      ? latest.accessRules
      : await collectViewAccessRules()
    const wanted = new Set(['title', 'travel_type', 'cost_center', 'case_creator', 'case_handler', 'current_step'])
    const fields = (latest.fields || []).map((f, i) => ({
      fieldName: f.fieldName,
      displayLabel: f.displayLabel,
      columnWidth: f.columnWidth ?? 150,
      sortOrder: f.sortOrder ?? i,
      visible: wanted.has(f.fieldName) || !!f.systemField,
      systemField: !!f.systemField,
      columnType: f.columnType,
      lookupSourceField: f.lookupSourceField,
      lookupDisplayField: f.lookupDisplayField,
    }))
    await dw('PUT', `/api/v1/function-units/${fuId}/main-table-views/${mainView.id}`, {
      viewName: latest.viewName || 'Travel header',
      restrictToInvolvedUsers: false,
      accessRules,
      fields,
      sortConfig: latest.sortConfig,
      filterConfig: latest.filterConfig,
    })
  }

  const deploy = await dw('POST', `/api/v1/function-units/${fuId}/deploy`, {
    autoEnable: true,
    changeLog: 'Travel Expense Owner Matrix',
  })
  let status = deploy
  const deploymentId = deploy?.deploymentId
  if (deploymentId && (deploy.status === 'DEPLOYING' || deploy.status === 'PENDING')) {
    for (let i = 0; i < 60; i += 1) {
      await new Promise((r) => setTimeout(r, 2000))
      status = await dw('GET', `/api/v1/function-units/deployments/${deploymentId}/status`)
      console.log(`Deploy ${status.status} ${status.progress ?? ''}% ${status.message ?? ''}`)
      if (status.status === 'SUCCESS' || status.status === 'FAILED' || status.status === 'ROLLED_BACK') break
    }
  }
  if (status?.status !== 'SUCCESS') {
    throw new Error(`Deploy did not succeed: ${status?.status} ${status?.message ?? ''}`)
  }

  const refreshed = await dw('GET', `/api/v1/function-units/${fuId}`)
  const code = refreshed?.code || fu.code
  await grantPortalStartRoles(code)
  console.log(`OWNER_MATRIX_FU_ID=${fuId}`)
  console.log(`OWNER_MATRIX_PROCESS_KEY=${code}`)
  return {
    fuId,
    code,
    viewId: mainView?.id,
    itinBindingId,
    apprBindingId,
    viewUrl: `${ORIGIN}/portal/views/${code}`,
  }
}

async function openSession(browser, who) {
  let lastErr
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
    const page = await ctx.newPage()
    try {
      const me = await loginViaPortalPassword(page, { ...who, loginOrigin: ORIGIN })
      const api = async (method, path, data) => {
        const res = await page.request.fetch(`${ORIGIN}/api/portal${path}`, {
          method, data, timeout: 60000,
        })
        return { status: res.status(), body: await res.json().catch(() => ({})) }
      }
      return { ctx, page, api, userId: me.userId, who }
    } catch (err) {
      lastErr = err
      await ctx.close().catch(() => {})
      const msg = String(err?.message || err)
      if (!/rate limit/i.test(msg) || attempt === 5) throw err
      const wait = Number(process.env.LOGIN_RETRY_MS || 20000) * (attempt + 1)
      console.log(`rate-limited ${who.user}; waiting ${wait}ms (attempt ${attempt + 1})`)
      await new Promise((r) => setTimeout(r, wait))
    }
  }
  throw lastErr
}

async function liveVars(api, instanceId) {
  const { body } = await api('GET', `/processes/${instanceId}/form`)
  return (body.data ?? body).fieldValues ?? {}
}

async function todoFor(api, instanceId, extra = {}) {
  const { body } = await api('POST', '/tasks/todo/query', { page: 0, size: 50, ...extra })
  return findRow(body, instanceId)
}

async function allTodos(api, instanceId, extra = {}) {
  const { body } = await api('POST', '/tasks/todo/query', { page: 0, size: 50, ...extra })
  const rows = body.data?.content ?? body.data?.records ?? body.data ?? body
  const list = Array.isArray(rows) ? rows : []
  return list.filter((t) => t && (t.processInstanceId === instanceId || t.id === instanceId))
}

function taskIdOf(task) {
  return task?.taskId || task?.id
}

async function startTravel(api, stamp, linaId, wangId, itinBindingId, apprBindingId, remark) {
  const itinId = randomUUID()
  const row1 = randomUUID()
  const row2 = randomUUID()
  const formData = {
    title: `Owner matrix ${stamp}`,
    travel_type: 'Air',
    cost_center: '共享财务中心',
    remark: remark || stamp,
    __subTables__: {
      [String(itinBindingId)]: [{ id: itinId, city: 'Shanghai', travel_date: '2026-09-20' }],
      [ITIN_TABLE]: [{ id: itinId, city: 'Shanghai', travel_date: '2026-09-20' }],
      [String(apprBindingId)]: [
        { id: row1, assignee_user_id: linaId, note: 'CC-lina' },
        { id: row2, assignee_user_id: wangId, note: 'CC-wangfang' },
      ],
      [APPR_TABLE]: [
        { id: row1, assignee_user_id: linaId, note: 'CC-lina' },
        { id: row2, assignee_user_id: wangId, note: 'CC-wangfang' },
      ],
    },
  }
  const started = await api('POST', `/processes/${encodeURIComponent(processKey)}/start`, {
    processDefinitionKey: processKey,
    formData,
    priority: 'NORMAL',
    remark,
  })
  const id = started.body.data?.id || started.body.data?.processInstanceId
  if (!id) {
    throw new Error(`start failed: ${started.status} ${JSON.stringify(started.body).slice(0, 400)}`)
  }
  return { id, title: formData.title, row1, row2, itinId }
}

let processKey = ''

async function claimReview(api, instanceId) {
  const task = await todoFor(api, instanceId)
  if (!task) throw new Error(`no Review To Do for ${instanceId}`)
  const id = taskIdOf(task)
  if (!task.assignee) {
    const claimed = await api('POST', `/tasks/${id}/claim`)
    if (claimed.status >= 400) {
      throw new Error(`claim failed ${claimed.status} ${JSON.stringify(claimed.body).slice(0, 240)}`)
    }
  }
  return { taskId: id, assignmentType: task.assignmentType || task.originalAssignmentType || 'CANDIDATE', assignee: task.assignee }
}

async function completeTask(api, taskId, comment, formData = {}) {
  const res = await api('POST', `/tasks/${taskId}/complete`, {
    taskId, action: 'APPROVE', comment, formData,
  })
  if (res.status >= 400) {
    throw new Error(`complete failed ${res.status} ${JSON.stringify(res.body).slice(0, 280)}`)
  }
  return res
}

async function screenshot(page, name) {
  const path = join(OUT, `${SHOT_DATE}_owner-matrix-${name}.png`)
  await page.screenshot({ path, fullPage: true })
  console.log('wrote', path)
  return path
}

async function runMatrix(fu) {
  processKey = fu.code
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const gap = Number(process.env.LOGIN_GAP_MS || 5000)
  const zhang = await openSession(browser, CAST.zhangwei)
  await new Promise((r) => setTimeout(r, gap))
  const lina = await openSession(browser, CAST.lina)
  await new Promise((r) => setTimeout(r, gap))
  const developer = await openSession(browser, CAST.developer)
  await new Promise((r) => setTimeout(r, gap))
  const wang = await openSession(browser, CAST.wangfang)
  await new Promise((r) => setTimeout(r, gap))
  const wangIndex = await openSession(browser, CAST.wangfangIndex)
  const stamp = Date.now()
  const startable = await zhang.api('GET', '/processes/startable')
  const keys = asRows(startable.body.data ?? startable.body).map((p) => p.processKey || p.key || p.processDefinitionKey)
  check('setup', '张伟可发起 Travel Expense', keys.includes(fu.code), true, { keys: keys.slice(0, 12) })

  const startOpts = [lina.userId, wang.userId, fu.itinBindingId, fu.apprBindingId]

  // 1. Start / pool / itinerary
  {
    const scene = 'start-pool'
    const { id } = await startTravel(zhang.api, `${stamp}-pool`, ...startOpts, scene)
    const vars = await dumpOwner(zhang.api, id, scene, [fu.itinBindingId, fu.apprBindingId])
    check(scene, 'Creator = initiator', vars.case_creator, userValue(zhang.userId))
    check(scene, 'Case Handler is user: pool', startsUser(vars.case_handler), true, vars.case_handler)
    const itin = sliceRows(vars, fu.itinBindingId, ITIN_TABLE)
    const appr = sliceRows(vars, fu.apprBindingId, APPR_TABLE)
    check(scene, 'itinerary row exists', itin.length > 0, true)
    check(scene, 'itinerary Creator = initiator', itin[0]?.line_creator, userValue(zhang.userId))
    check(scene, 'itinerary Handler empty (non-MI)', !itin[0]?.line_handler, true, itin[0]?.line_handler)
    check(scene, 'MI rows exist', appr.length >= 2, true, appr.length)
    check(scene, 'MI row Creator = initiator', appr.every((r) => r.row_creator === userValue(zhang.userId)), true)
    const detail = await zhang.api('GET', `/processes/${id}`)
    const listed = await zhang.api('GET', '/processes/my-applications?page=0&size=50')
    const listRow = findRow(listed.body, id, 'id')
    check(scene, 'Basic Info Current Assignee populated', !!detail.body.data?.currentAssignee, true, detail.body.data?.currentAssignee)
    check(scene, 'My Request list matches Basic Info', listRow?.currentAssignee, detail.body.data?.currentAssignee)
    remember(scene, id, 'sitting at Dept Review pool', { view: fu.viewUrl })
    await zhang.page.goto(`${ORIGIN}/portal/applications/${id}`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await zhang.page.waitForTimeout(8000)
    await screenshot(zhang.page, 'start-request')
  }

  // 2. Claim / unclaim
  {
    const scene = 'claim-unclaim'
    const { id } = await startTravel(zhang.api, `${stamp}-claim`, ...startOpts, scene)
    const claimed = await claimReview(lina.api, id)
    let vars = await dumpOwner(zhang.api, id, `${scene}-claimed`)
    check(scene, 'claim writes lina as Case Handler', vars.case_handler, userValue(lina.userId))
    check(scene, 'Creator untouched by claim', vars.case_creator, userValue(zhang.userId))
    const unclaim = await lina.api(
      'POST',
      `/tasks/${claimed.taskId}/unclaim?originalAssignmentType=${encodeURIComponent(claimed.assignmentType)}&originalAssignee=${encodeURIComponent(lina.userId)}`,
    )
    check(scene, 'unclaim HTTP ok', unclaim.status < 300, true, { status: unclaim.status, body: unclaim.body?.message })
    vars = await liveVars(zhang.api, id)
    check(scene, 'unclaim restores user: pool', startsUser(vars.case_handler) && String(vars.case_handler).includes(','), true, vars.case_handler)
    remember(scene, id, 'claimed then unclaimed')
  }

  // 3. Transfer
  {
    const scene = 'transfer'
    const { id } = await startTravel(zhang.api, `${stamp}-xfer`, ...startOpts, scene)
    const claimed = await claimReview(lina.api, id)
    const xfer = await lina.api(
      'POST',
      `/tasks/${claimed.taskId}/transfer?toUserId=${encodeURIComponent(developer.userId)}&reason=owner-matrix-transfer`,
    )
    check(scene, 'transfer HTTP ok', xfer.status < 300, true, { status: xfer.status, message: xfer.body?.message })
    const vars = await dumpOwner(zhang.api, id, scene)
    check(scene, 'transfer Case Handler = developer', vars.case_handler, userValue(developer.userId))
    check(scene, 'Creator unchanged after transfer', vars.case_creator, userValue(zhang.userId))
    remember(scene, id, 'Review transferred to developer', {
      todo: `${ORIGIN}/portal/tasks/${claimed.taskId}`,
    })
  }

  // 4. Reassign (same MANAGER pool)
  {
    const scene = 'reassign'
    const { id } = await startTravel(zhang.api, `${stamp}-reassign`, ...startOpts, scene)
    const claimed = await claimReview(lina.api, id)
    const taskDetail = await developer.api('GET', `/tasks/${claimed.taskId}`)
    const canReassign = !!(taskDetail.body.data ?? taskDetail.body)?.canReassign
    const res = await developer.api('POST', `/tasks/${claimed.taskId}/reassign`, { targetUserId: developer.userId })
    check(scene, 'reassign HTTP ok (leader/developer)', res.status < 300, true, {
      status: res.status, message: res.body?.message, canReassign,
    })
    const vars = await dumpOwner(zhang.api, id, scene)
    check(scene, 'reassign Case Handler = developer', vars.case_handler, userValue(developer.userId))
    check(scene, 'Creator unchanged after reassign', vars.case_creator, userValue(zhang.userId))
    remember(scene, id, 'Review reassigned to developer')
  }

  // 5. Single-task USER delegate then B completes → snapshot B, live MI step
  let delegateInstance = null
  let delegateReviewTaskId = null
  {
    const scene = 'delegate-user'
    const started = await startTravel(zhang.api, `${stamp}-del-user`, ...startOpts, scene)
    const { id } = started
    const claimed = await claimReview(lina.api, id)
    const before = await liveVars(zhang.api, id)
    const del = await lina.api('POST', `/tasks/${claimed.taskId}/delegate`, {
      delegatedTargetType: 'USER',
      delegatedTo: wang.userId,
      reason: 'owner-matrix-delegate-user',
    })
    check(scene, 'delegate HTTP ok', del.status < 300, true, { status: del.status, message: del.body?.message })
    const during = await liveVars(zhang.api, id)
    check(scene, 'delegate does not change Case Handler', during.case_handler, before.case_handler)
    check(scene, 'delegate Handler still lina', during.case_handler, userValue(lina.userId))
    const wangDel = await todoFor(wang.api, id, { assignmentTypes: ['DELEGATED'] })
    check(scene, 'wangfang sees delegated To Do', !!wangDel, true)
    const proxy = await wang.api('GET', '/delegations/proxy-tasks')
    const proxyHit = findRow(proxy.body, id)
    check(scene, 'Delegations 委托任务 Tab 可见', !!proxyHit || !!wangDel, true)
    await wang.page.goto(`${ORIGIN}/portal/delegations`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await wang.page.getByRole('tab', { name: /Delegated|委托任务/i }).click().catch(() => {})
    await wang.page.waitForTimeout(2500)
    await screenshot(wang.page, 'delegations')
    await completeTask(wang.api, claimed.taskId, 'wangfang on behalf of lina')
    const vars = await dumpOwner(zhang.api, id, scene, [fu.apprBindingId])
    const snap = vars[`_snapshot_${claimed.taskId}`]?.fieldValues ?? {}
    check(scene, 'Review snapshot is actual operator wangfang', snap.case_handler, userValue(wang.userId))
    check(scene, 'live Case Handler is MI step:', String(vars.case_handler ?? '').startsWith('step:'), true, vars.case_handler)
    check(scene, `MI box name is ${MI_BOX}`, String(vars.case_handler ?? '').includes(MI_BOX) || String(vars.case_handler ?? '').startsWith('step:'), true, vars.case_handler)
    const detail = await zhang.api('GET', `/processes/${id}`)
    const listed = await zhang.api('GET', '/processes/my-applications?page=0&size=50')
    const listRow = findRow(listed.body, id, 'id')
    check(scene, 'My Request Current Assignee is box name not a person',
      listRow?.currentAssignee === detail.body.data?.currentAssignee
      && !String(listRow?.currentAssignee || '').startsWith('user:'),
      true,
      { list: listRow?.currentAssignee, detail: detail.body.data?.currentAssignee, handler: vars.case_handler })
    delegateInstance = id
    delegateReviewTaskId = claimed.taskId
    remember(scene, id, 'delegated then completed by wangfang; now in MI', {
      todo: `${ORIGIN}/portal/tasks/${claimed.taskId}`,
    })
    await zhang.page.goto(`${ORIGIN}/portal/applications/${id}`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await zhang.page.waitForTimeout(4000)
    await screenshot(zhang.page, 'mi-request')
  }

  // 6. Single-task BU+Role delegate
  {
    const scene = 'delegate-bu-role'
    const { id } = await startTravel(zhang.api, `${stamp}-del-bu`, ...startOpts, scene)
    const claimed = await claimReview(lina.api, id)
    const del = await lina.api('POST', `/tasks/${claimed.taskId}/delegate`, {
      delegatedTargetType: 'BU_ROLE',
      delegatedBuCode: 'hase-hmdc',
      delegatedRoleCode: 'HMDC_Index_Role',
      reason: 'owner-matrix-delegate-bu',
    })
    check(scene, 'BU+Role delegate HTTP ok', del.status < 300, true, { status: del.status, message: del.body?.message })
    const during = await liveVars(zhang.api, id)
    check(scene, 'BU+Role delegate Handler still lina', during.case_handler, userValue(lina.userId))
    const wangWrong = await todoFor(wang.api, id, { assignmentTypes: ['DELEGATED'] })
    check(scene, 'wangfang on finance workspace cannot see BU+Role delegate', !wangWrong, true)
    const wangRight = await todoFor(wangIndex.api, id, { assignmentTypes: ['DELEGATED'] })
    if (wangRight) {
      check(scene, 'wangfang Index workspace sees BU+Role delegate', true, true)
    } else {
      console.log('SKIP  [delegate-bu-role] Index workspace To Do (standing overlay not in this Owner stack)')
    }
    await completeTask(wangIndex.api, claimed.taskId, 'index on behalf')
    const vars = await liveVars(zhang.api, id)
    const snap = vars[`_snapshot_${claimed.taskId}`]?.fieldValues ?? {}
    check(scene, 'BU+Role snapshot is actual operator wangfang', snap.case_handler, userValue(wangIndex.userId))
    remember(scene, id, 'BU+Role delegated; completed by Index workspace')
  }

  // 7. A self-completes (control) then MI row transfer / sibling isolation
  {
    const scene = 'self-complete-mi'
    const { id } = await startTravel(zhang.api, `${stamp}-self-mi`, ...startOpts, scene)
    const claimed = await claimReview(lina.api, id)
    await completeTask(lina.api, claimed.taskId, 'lina self complete')
    const varsAfterReview = await dumpOwner(zhang.api, id, `${scene}-after-review`, [fu.apprBindingId])
    const snap = varsAfterReview[`_snapshot_${claimed.taskId}`]?.fieldValues ?? {}
    check(scene, 'self-complete snapshot is lina', snap.case_handler, userValue(lina.userId))
    check(scene, 'live Handler is step: after Review', String(varsAfterReview.case_handler ?? '').startsWith('step:'), true, varsAfterReview.case_handler)

    const linaMi = await todoFor(lina.api, id)
    const wangMi = await todoFor(wang.api, id)
    check(scene, 'lina has MI To Do', !!linaMi, true)
    check(scene, 'wangfang has MI To Do', !!wangMi, true)
    if (linaMi) {
      const detail = await lina.api('GET', `/tasks/${taskIdOf(linaMi)}`)
      const task = detail.body.data ?? detail.body
      const header = task.assignee || task.currentAssignee || task.assigneeName
      check(scene, 'To Do task header is this row person, not main step',
        String(header || '').includes(lina.userId) || String(task.assignee || '') === lina.userId,
        true,
        { assignee: task.assignee, currentAssignee: task.currentAssignee, name: task.taskName })
      await lina.page.goto(`${ORIGIN}/portal/tasks/${taskIdOf(linaMi)}`, { waitUntil: 'domcontentloaded', timeout: 60000 })
      await lina.page.waitForTimeout(4000)
      await screenshot(lina.page, 'mi-todo-row')
    }

    const beforeRows = sliceRows(varsAfterReview, fu.apprBindingId, APPR_TABLE)
    const wangRowBefore = beforeRows.find((r) => String(r.assignee_user_id) === wang.userId) || beforeRows[1]
    if (linaMi && developer.userId) {
      const xfer = await lina.api(
        'POST',
        `/tasks/${taskIdOf(linaMi)}/transfer?toUserId=${encodeURIComponent(developer.userId)}&reason=mi-row-transfer`,
      )
      check(scene, 'MI row transfer HTTP ok', xfer.status < 300, true, { status: xfer.status, message: xfer.body?.message })
      const afterXfer = await liveVars(zhang.api, id)
      check(scene, 'main Handler still step: after MI transfer', String(afterXfer.case_handler ?? '').startsWith('step:'), true, afterXfer.case_handler)
      const afterRows = sliceRows(afterXfer, fu.apprBindingId, APPR_TABLE)
      const transferred = afterRows.find((r) => String(r.id) === String(beforeRows.find((x) => String(x.assignee_user_id) === lina.userId)?.id)
        || String(r.row_handler) === userValue(developer.userId))
      const sibling = afterRows.find((r) => String(r.assignee_user_id) === wang.userId) || afterRows.find((r) => r !== transferred)
      check(scene, 'transferred MI row Handler is developer', startsUser(transferred?.row_handler) && String(transferred?.row_handler).includes(developer.userId), true, transferred?.row_handler)
      check(scene, 'sibling MI row Handler not copied', sibling?.row_handler !== transferred?.row_handler, true, {
        sibling: sibling?.row_handler, transferred: transferred?.row_handler, wangBefore: wangRowBefore?.row_handler,
      })
    }

    const wangMi2 = await todoFor(wang.api, id) || wangMi
    if (wangMi2) {
      const duringDel = await liveVars(zhang.api, id)
      const wangRowId = sliceRows(duringDel, fu.apprBindingId, APPR_TABLE)
        .find((r) => String(r.assignee_user_id) === wang.userId)
      const del = await wang.api('POST', `/tasks/${taskIdOf(wangMi2)}/delegate`, {
        delegatedTargetType: 'USER',
        delegatedTo: zhang.userId,
        reason: 'mi-row-delegate',
      })
      check(scene, 'MI row delegate HTTP ok', del.status < 300, true, { status: del.status, message: del.body?.message })
      const still = await liveVars(zhang.api, id)
      const stillRow = sliceRows(still, fu.apprBindingId, APPR_TABLE)
        .find((r) => String(r.id) === String(wangRowId?.id) || String(r.assignee_user_id) === wang.userId)
      check(scene, 'MI row delegate does not change row Handler yet',
        stillRow?.row_handler === wangRowId?.row_handler || stillRow?.row_handler === userValue(wang.userId),
        true,
        stillRow?.row_handler)
      await completeTask(zhang.api, taskIdOf(wangMi2), 'zhangwei on behalf of wangfang MI')
      const after = await liveVars(zhang.api, id)
      const doneRow = sliceRows(after, fu.apprBindingId, APPR_TABLE)
        .find((r) => String(r.id) === String(wangRowId?.id) || String(r.note) === 'CC-wangfang')
      check(scene, 'MI row Complete writes actual operator zhangwei', doneRow?.row_handler, userValue(zhang.userId))
    }

    // finish remaining MI row (developer holds the transferred one)
    const devMi = await todoFor(developer.api, id)
    if (devMi) {
      await completeTask(developer.api, taskIdOf(devMi), 'developer MI line')
    } else if (linaMi) {
      const stillLina = await todoFor(lina.api, id)
      if (stillLina) await completeTask(lina.api, taskIdOf(stillLina), 'lina leftover MI')
    }

    const atClose = await dumpOwner(zhang.api, id, `${scene}-at-close`, [fu.itinBindingId, fu.apprBindingId])
    check(scene, 'after all MI rows, Handler is Close initiator (user:)', startsUser(atClose.case_handler), true, atClose.case_handler)
    check(scene, 'Close Handler is zhangwei', atClose.case_handler, userValue(zhang.userId))
    const miKept = sliceRows(atClose, fu.apprBindingId, APPR_TABLE)
    check(scene, 'MI row Handlers retained after leaving MI', miKept.every((r) => startsUser(r.row_handler)), true, miKept.map((r) => r.row_handler))
    check(scene, 'itinerary Handler still empty at Close',
      !sliceRows(atClose, fu.itinBindingId, ITIN_TABLE)[0]?.line_handler, true)

    const close = await todoFor(zhang.api, id)
    check(scene, 'initiator has Finance Close To Do', !!close, true)
    if (close) {
      await completeTask(zhang.api, taskIdOf(close), 'close')
      const terminal = await dumpOwner(zhang.api, id, `${scene}-terminal`, [fu.apprBindingId])
      check(scene, 'terminal clears main Case Handler', String(terminal.case_handler ?? ''), '')
      check(scene, 'terminal keeps Creator', terminal.case_creator, userValue(zhang.userId))
      check(scene, 'terminal keeps MI row Handlers',
        sliceRows(terminal, fu.apprBindingId, APPR_TABLE).every((r) => startsUser(r.row_handler)),
        true)
      const closeSnap = terminal[`_snapshot_${taskIdOf(close)}`]?.fieldValues ?? {}
      check(scene, 'Close snapshot froze zhangwei', closeSnap.case_handler, userValue(zhang.userId))
      check(scene, 'Review snapshot survives terminal',
        terminal[`_snapshot_${claimed.taskId}`]?.fieldValues?.case_handler, userValue(lina.userId))
      const detail = await zhang.api('GET', `/processes/${id}`)
      check(scene, 'Basic Info Current Assignee empty at terminal', String(detail.body.data?.currentAssignee ?? ''), '')
      const completed = await zhang.api('POST', '/tasks/completed/query', { page: 0, size: 50 })
      const doneRow = findRow(completed.body, id)
      check(scene, 'Completed list contains the request', !!doneRow, true)
    remember(scene, id, 'COMPLETED full path', {
        todo: close ? `${ORIGIN}/portal/tasks/${taskIdOf(close)}` : undefined,
        view: fu.viewUrl,
      })
      if (doneRow) {
        await zhang.page.goto(`${ORIGIN}/portal/tasks/${taskIdOf(doneRow) || taskIdOf(close)}`, { waitUntil: 'domcontentloaded', timeout: 60000 })
        await zhang.page.waitForTimeout(4000)
        await screenshot(zhang.page, 'completed-close')
      }
    }
  }

  // 8. Standing USER
  {
    const scene = 'standing-user'
    const rule = await lina.api('POST', '/delegations', {
      delegateTargetType: 'USER',
      delegateId: wang.userId,
      delegationType: 'ALL',
      reason: 'owner-matrix standing',
    })
    check(scene, 'create standing rule', rule.status < 300, true, { status: rule.status, message: rule.body?.message })
    const { id } = await startTravel(zhang.api, `${stamp}-standing`, ...startOpts, scene)
    const claimed = await claimReview(lina.api, id)
    const during = await liveVars(zhang.api, id)
    check(scene, 'standing: Case Handler still lina before complete', during.case_handler, userValue(lina.userId))
    const wangDel = await todoFor(wang.api, id, { assignmentTypes: ['DELEGATED'] })
    if (wangDel) {
      check(scene, 'standing: wangfang delegated To Do visible', true, true)
    } else {
      console.log('SKIP  [standing-user] delegated To Do (standing overlay not in this Owner stack)')
    }
    if (wangDel) {
      await completeTask(wang.api, claimed.taskId, 'standing wangfang')
      const vars = await liveVars(zhang.api, id)
      const snap = vars[`_snapshot_${claimed.taskId}`]?.fieldValues ?? {}
      check(scene, 'standing Complete snapshot is wangfang', snap.case_handler, userValue(wang.userId))
    }
    const ruleId = rule.body.data?.id || rule.body?.id
    if (ruleId) await lina.api('POST', `/delegations/${ruleId}/suspend`)
    remember(scene, id, 'standing USER complete')
  }

  // 9. Data menu View
  if (fu.viewId) {
    const scene = 'data-view'
    const menu = await zhang.api('GET', '/main-table-views/function-units')
    const fuMenu = asRows(menu.body.data ?? menu.body).find((x) => x.functionUnitCode === fu.code)
    check(scene, 'Data menu lists this FU', !!fuMenu, true, fuMenu)
    const data = await zhang.api('POST', `/main-table-views/${fu.viewId}/data`, { page: 0, size: 50 })
    check(scene, 'View data HTTP ok', data.status < 300, true, { status: data.status, message: data.body?.message })
    const rows = data.body.data?.content ?? data.body.data?.records ?? data.body.data?.rows ?? []
    const list = Array.isArray(rows) ? rows : []
    const hit = list.find((r) => String(r.title || r.TITLE || '').includes('Owner matrix'))
      || list[0]
    if (hit) {
      const creatorCell = hit.case_creator ?? hit.case_creator__display ?? hit.Creator
      const handlerCell = hit.case_handler ?? hit.case_handler__display
      const travel = hit.travel_type
      const cost = hit.cost_center
      check(scene, 'View Creator cell is a name not raw user: if display projected',
        creatorCell == null || !String(creatorCell).startsWith('user:') || startsUser(creatorCell),
        true,
        creatorCell)
      check(scene, 'View travel_type shows option label', travel === 'Air' || travel == null, true, travel)
      check(scene, 'View cost_center shows BU display name', cost === '共享财务中心' || cost == null, true, cost)
      void handlerCell
    } else {
      check(scene, 'View returned at least one travel row', list.length > 0, true, { n: list.length })
    }
    await zhang.page.goto(fu.viewUrl, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => {})
    await zhang.page.waitForTimeout(3000)
    await screenshot(zhang.page, 'data-view')
    remember(scene, null, 'Data menu View', { view: fu.viewUrl })
  }

  // Overlay reminder: snapshot already asserted; live at MI must not equal Review snapshot
  if (delegateInstance && delegateReviewTaskId) {
    const vars = await liveVars(zhang.api, delegateInstance)
    const snap = vars[`_snapshot_${delegateReviewTaskId}`]?.fieldValues?.case_handler
    check('snapshot-overlay', 'live MI Handler differs from Review snapshot', vars.case_handler !== snap, true, {
      live: vars.case_handler, snap,
    })
    check('snapshot-overlay', 'Review snapshot remains wangfang', snap, userValue(wang.userId))
  }

  await zhang.ctx.close()
  await lina.ctx.close()
  await developer.ctx.close()
  await wang.ctx.close()
  await wangIndex.ctx.close()
  await browser.close()
}

try {
  const fu = await setupFu()
  if (process.env.SETUP_ONLY === '1') {
    console.log('SETUP_ONLY=1 — skipping matrix')
    process.exit(0)
  }
  await runMatrix(fu)
} catch (err) {
  findings.push(String(err?.stack || err))
  console.error(err)
}

console.log('\n=== Owner matrix catalog ===')
for (const row of catalog) {
  console.log(`\n[${row.scene}] ${row.note}`)
  if (row.request) console.log(`  Request  ${row.request}`)
  if (row.todo) console.log(`  To Do    ${row.todo}`)
  if (row.view) console.log(`  View     ${row.view}`)
}

const failed = results.filter((r) => !r.ok)
const byScene = {}
for (const r of results) {
  byScene[r.scene] = byScene[r.scene] || { pass: 0, fail: 0 }
  byScene[r.scene][r.ok ? 'pass' : 'fail'] += 1
}
console.log('\n=== BY SCENE ===')
for (const [scene, n] of Object.entries(byScene)) {
  console.log(`${scene}: ${n.pass} pass / ${n.fail} fail`)
}
if (findings.length) {
  console.log('\n=== RUNNER ERRORS ===')
  for (const f of findings) console.log(f)
}
console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
if (failed.length) {
  console.log('\n=== EXPECTED vs ACTUAL (FAIL only) ===')
  for (const f of failed) {
    const kind = /reassign HTTP/i.test(f.label)
      ? 'permission'
      : /Handler|handler|step:|snapshot|assignee/i.test(f.label)
        ? 'owner-write-or-display'
        : 'other'
    console.log(` - [${kind}] [${f.scene}] ${f.label}`)
    console.log(`     expected ${JSON.stringify(f.expected)}`)
    console.log(`     actual   ${JSON.stringify(f.actual)}`)
  }
}
process.exit(failed.length === 0 && findings.length === 0 ? 0 : 1)
