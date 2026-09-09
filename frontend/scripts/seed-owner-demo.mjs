/**
 * Add Creator + Case Handler VARCHAR columns to an existing Function Unit
 * and mark those form controls as type:"owner". Also remaps leftover
 * CURRENT_ASSIGNEE / CURRENT_HANDLER sources to CASE_HANDLER.
 *
 * Login: developer / password. Origin: http://localhost:3000
 *
 *   OWNER_DEMO_FU='Owner Demo' node frontend/scripts/seed-owner-demo.mjs
 *
 * Then republish the FU and start a request as e2e_zhangwei (password) to demo
 * claim / delegate / complete.
 */
const ORIGIN = (process.env.OWNER_DEMO_ORIGIN ?? process.env.HELP_GUIDE_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const FU_NAME = process.env.OWNER_DEMO_FU ?? 'Owner Demo'
const USER = process.env.LOGIN_USER ?? 'developer'
const PASS = process.env.LOGIN_PASS ?? 'password'

const CREATOR_FIELD = 'case_owner'
const HANDLER_FIELD = 'current_handler'
const CREATOR_ALIASES = new Set([CREATOR_FIELD, 'case_creator'])
const HANDLER_ALIASES = new Set([HANDLER_FIELD, 'case_handler', 'case_assignee'])

function asRows(payload) {
  if (Array.isArray(payload)) return payload
  if (!payload || typeof payload !== 'object') return []
  if (Array.isArray(payload.records)) return payload.records
  if (Array.isArray(payload.content)) return payload.content
  if (Array.isArray(payload.items)) return payload.items
  if (Array.isArray(payload.functionUnits)) return payload.functionUnits
  if (payload.data) return asRows(payload.data)
  return []
}

function varcharCol(sortOrder, fieldName, displayName) {
  return {
    fieldName,
    displayName,
    dataType: 'VARCHAR',
    length: 255,
    nullable: true,
    sortOrder,
  }
}

function ownerRule(field, title, source) {
  return {
    type: 'owner',
    field,
    title,
    props: { ownerConfig: JSON.stringify({ source }) },
  }
}

// Migration helper: product code accepts CREATOR / CASE_HANDLER (+ legacy CURRENT_ASSIGNEE).
// CURRENT_HANDLER is rejected there, so this script is what rewrites any leftover dev JSON.
function normalizeOwnerSource(raw) {
  const value = String(raw || '').trim().toUpperCase()
  if (value === 'CASE_HANDLER' || value === 'CURRENT_ASSIGNEE' || value === 'CURRENT_HANDLER') {
    return 'CASE_HANDLER'
  }
  return 'CREATOR'
}

function rewriteOwnerConfig(node) {
  if (!node || typeof node !== 'object' || node.type !== 'owner') return
  let source = 'CREATOR'
  try {
    const parsed = JSON.parse(node.props?.ownerConfig || '{}')
    source = normalizeOwnerSource(parsed.source)
  } catch {
    source = 'CREATOR'
  }
  node.props = { ...(node.props || {}), ownerConfig: JSON.stringify({ source }) }
}

function walkRules(node, visit) {
  if (Array.isArray(node)) {
    node.forEach((child) => walkRules(child, visit))
    return
  }
  if (!node || typeof node !== 'object') return
  visit(node)
  if (Array.isArray(node.children)) walkRules(node.children, visit)
}

function remapOwnerSources(rules) {
  const list = Array.isArray(rules) ? rules : []
  walkRules(list, rewriteOwnerConfig)
  return list
}

function ensureOwnerOnRules(rules, tableFieldNames) {
  const list = remapOwnerSources(rules)
  const byField = new Map()
  walkRules(list, (node) => {
    if (typeof node.field === 'string') byField.set(node.field, node)
  })
  for (const [field, title, source] of [
    [CREATOR_FIELD, 'Creator', 'CREATOR'],
    [HANDLER_FIELD, 'Case Handler', 'CASE_HANDLER'],
  ]) {
    if (tableFieldNames && !tableFieldNames.has(field)) {
      continue
    }
    const existing = byField.get(field)
    if (existing) {
      existing.type = 'owner'
      existing.props = { ...(existing.props || {}), ownerConfig: JSON.stringify({ source }) }
    } else {
      list.push(ownerRule(field, title, source))
    }
  }
  return list
}

function rewriteConfigOwners(config, tableFieldNames) {
  config.rule = ensureOwnerOnRules(config.rule, tableFieldNames)
  if (config.subForms && typeof config.subForms === 'object') {
    for (const entry of Object.values(config.subForms)) {
      if (entry && typeof entry === 'object') {
        entry.rule = remapOwnerSources(entry.rule)
      }
    }
  }
  return config
}

async function login() {
  const res = await fetch(`${ORIGIN}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(`Login failed: ${json.message || `HTTP ${res.status}`}`)
  const user = json.user ?? json.data?.user
  if (!user?.userId) throw new Error('Login response missing user')
  const raw = typeof res.headers.getSetCookie === 'function'
    ? res.headers.getSetCookie()
    : [res.headers.get('set-cookie')].filter(Boolean)
  const cookie = raw.map((c) => String(c).split(';')[0]).join('; ')
  return { userId: user.userId, cookie }
}

async function api(session, method, path, body) {
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

const session = await login()
const dw = (method, path, body) => api(session, method, path, body)

const groupsPayload = await dw('GET', '/api/v1/function-units/my-dev-groups')
const listed = await dw('GET', '/api/v1/function-units?page=0&size=100')
const records = asRows(listed)
const wanted = FU_NAME.trim().toLowerCase()
const fu = records.find((row) => {
  const name = String(row.name || '').trim().toLowerCase()
  const code = String(row.code || '').trim().toLowerCase()
  return name === wanted || code === wanted || code.includes(wanted.replace(/\s+/g, '-'))
})
if (!fu?.id) {
  const available = records.map((row) => `${row.name} (${row.code})`).join(', ')
  throw new Error(`Function Unit "${FU_NAME}" not found. Available: ${available || '(empty list)'}`)
}
session.groupId = fu.devGroupId || fu.groupId || groupsPayload?.publicGroupId
  || groupsPayload?.groups?.find((g) => g.selectable)?.id

const tables = asRows(await dw('GET', `/api/v1/function-units/${fu.id}/tables`))
const main = tables.find((t) => t.tableType === 'MAIN') || tables[0]
if (!main?.id) throw new Error(`FU ${fu.id} has no table`)

const detail = await dw('GET', `/api/v1/function-units/${fu.id}/tables/${main.id}`)
const fields = Array.isArray(detail.fieldDefinitions)
  ? [...detail.fieldDefinitions]
  : (Array.isArray(detail.fields) ? [...detail.fields] : [])
if (fields.length === 0) {
  throw new Error(`Table ${main.tableName} returned no fieldDefinitions; refuse to PUT an empty column list`)
}
const names = new Set(fields.map((f) => f.fieldName))
const hasCreatorCol = [...names].some((name) => CREATOR_ALIASES.has(name))
const hasHandlerCol = [...names].some((name) => HANDLER_ALIASES.has(name))
let nextSort = fields.reduce((max, f) => Math.max(max, Number(f.sortOrder) || 0), 0) + 1
let added = []
if (!hasCreatorCol) {
  fields.push(varcharCol(nextSort++, CREATOR_FIELD, 'Creator'))
  added.push(CREATOR_FIELD)
}
if (!hasHandlerCol) {
  fields.push(varcharCol(nextSort++, HANDLER_FIELD, 'Case Handler'))
  added.push(HANDLER_FIELD)
}

if (added.length > 0) {
  await dw('PUT', `/api/v1/function-units/${fu.id}/tables/${main.id}`, {
    tableName: detail.tableName,
    tableDisplayName: detail.tableDisplayName,
    tableType: detail.tableType,
    description: detail.description,
    fields,
  })
}

const tableFieldNames = new Set(fields.map((f) => f.fieldName))
const forms = asRows(await dw('GET', `/api/v1/function-units/${fu.id}/forms`))
for (const form of forms) {
  const full = await dw('GET', `/api/v1/function-units/${fu.id}/forms/${form.id}`)
  const config = full.configJson && typeof full.configJson === 'object' ? { ...full.configJson } : { rule: [] }
  const boundToMain = full.boundTableId != null && Number(full.boundTableId) === Number(main.id)
  if (boundToMain) {
    rewriteConfigOwners(config, tableFieldNames)
  } else {
    config.rule = remapOwnerSources(config.rule)
    if (config.subForms && typeof config.subForms === 'object') {
      for (const entry of Object.values(config.subForms)) {
        if (entry && typeof entry === 'object') {
          entry.rule = remapOwnerSources(entry.rule)
        }
      }
    }
  }
  await dw('PUT', `/api/v1/function-units/${fu.id}/forms/${form.id}`, {
    formName: full.formName || full.name || form.formName || form.name,
    formType: full.formType || form.formType,
    scene: full.scene,
    description: full.description,
    boundTableId: full.boundTableId,
    lockVersion: full.lockVersion,
    configJson: config,
  })
}

try {
  await dw('POST', `/api/v1/function-units/${fu.id}/publish?changeLog=${encodeURIComponent('Owner Case Handler demo')}`)
  console.log(`Published FU "${fu.name}" id=${fu.id}`)
} catch (err) {
  console.log(`Forms updated on FU "${fu.name}" id=${fu.id}; publish skipped: ${err.message}`)
}
console.log(`Table ${main.tableName}; added columns: ${added.join(', ') || '(none, remapped existing Owner sources)'}`)
console.log('Start a request as e2e_zhangwei / password to demo claim / delegate / complete.')
