/**
 * MCY Debit Card (FU 50006): create Email Monitor template on INBOUND connection,
 * bind StartEvent_1, deploy, and write a matching test email file.
 *
 * Usage (from frontend/):
 *   TEST_MAILBOX=you@example.com node scripts/setup-mcy-qq-email-monitor.mjs
 *
 * Env:
 *   TEST_MAILBOX              — sample from/to in test artifacts (default user@example.com)
 *   INBOUND_CONNECTION_UID    — optional; when unset, first INBOUND connection on the FU is used
 *   FU_ID, LOGIN_USER, LOGIN_PASS, HELP_GUIDE_ORIGIN
 */
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ORIGIN = (process.env.HELP_GUIDE_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const FU_ID = Number(process.env.FU_ID ?? '50006')
const INBOUND_CONNECTION_UID = process.env.INBOUND_CONNECTION_UID
const TEST_MAILBOX = process.env.TEST_MAILBOX ?? 'user@example.com'
const TEMPLATE_NAME = 'INBOX — Case intake (DIRECT attrs)'
const USER = process.env.LOGIN_USER ?? 'developer'
const PASS = process.env.LOGIN_PASS ?? 'password'
const __dirname = dirname(fileURLToPath(import.meta.url))

const TEST_SUBJECT = 'MCY Debit Card — 校验码测试 20260908'
const TEST_BODY = '您好，本次操作校验码为8844221。请处理借记卡案例。'
const TEST_MESSAGE_ID = '<mcy-intake-test-20260908@example.com>'

const extractionRules = {
  fields: [
    {
      target: 'case_number',
      source: 'TEXT',
      type: 'REGEX',
      pattern: '(?<=校验码为)\\d+',
      required: false,
    },
    {
      target: 'request_id',
      source: 'SUBJECT',
      type: 'DIRECT',
    },
    {
      target: 'calc',
      source: 'FROM',
      type: 'DIRECT',
    },
  ],
  subTables: [
    {
      bindingId: '271',
      tableIndex: 0,
      headerRow: true,
      columns: [
        { field: 'card_number', columnIndex: 0 },
        { field: 'case_type', columnIndex: 1 },
      ],
    },
    {
      bindingId: '273',
      tableIndex: 1,
      headerRow: true,
      columns: [{ field: 'case_id', columnIndex: 0 }],
    },
  ],
  sampleEmail: {
    subject: TEST_SUBJECT,
    from: TEST_MAILBOX,
    to: TEST_MAILBOX,
    text: TEST_BODY,
    messageId: TEST_MESSAGE_ID,
    date: '2026-09-08T10:30:00+08:00',
  },
}

async function login() {
  const res = await fetch(`${ORIGIN}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok || json.success === false) {
    throw new Error(`Login failed: ${json.message || res.status}`)
  }
  const user = json.user ?? json.data?.user
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

function asRows(payload) {
  if (Array.isArray(payload)) return payload
  if (payload?.records) return payload.records
  return []
}

function buildTestEml() {
  const date = new Date().toUTCString()
  return [
    `From: ${TEST_MAILBOX}`,
    `To: ${TEST_MAILBOX}`,
    `Subject: ${TEST_SUBJECT}`,
    `Date: ${date}`,
    `Message-ID: ${TEST_MESSAGE_ID}`,
    `MIME-Version: 1.0`,
    `Content-Type: text/plain; charset=UTF-8`,
    '',
    TEST_BODY,
    '',
  ].join('\r\n')
}

const session = await login()
const groupsPayload = await api(session, 'GET', '/api/v1/function-units/my-dev-groups')
session.groupId = groupsPayload?.groups?.[0]?.id || groupsPayload?.publicGroupId
if (!session.groupId) throw new Error('No developer team — open DW and pick a team first')

const dw = (method, path, body) => api(session, method, path, body)

const connections = asRows(await dw('GET', `/api/v1/function-units/${FU_ID}/connections`))
const inbound = INBOUND_CONNECTION_UID
  ? connections.find((c) => c.connectionUid === INBOUND_CONNECTION_UID)
  : connections.find((c) => String(c.direction).toUpperCase() === 'INBOUND')
if (!inbound) {
  const hint = INBOUND_CONNECTION_UID
    ? `INBOUND connection ${INBOUND_CONNECTION_UID} not found on FU ${FU_ID}`
    : `No INBOUND connection found on FU ${FU_ID} — set INBOUND_CONNECTION_UID or add one in DW`
  throw new Error(hint)
}
console.log(`Using INBOUND connection: ${inbound.name} (${inbound.connectionUid}) direction=${inbound.direction}`)

const templates = asRows(await dw('GET', `/api/v1/function-units/${FU_ID}/email-monitors/templates`))
let template = templates.find((t) => t.name === TEMPLATE_NAME)
if (!template) {
  template = await dw('POST', `/api/v1/function-units/${FU_ID}/email-monitors`, {
    name: TEMPLATE_NAME,
    enabled: true,
    connectionUid: inbound.connectionUid,
    actionType: 'START_PROCESS',
    folderLabel: 'INBOX',
    pollIntervalSeconds: 60,
    reviewOnMissing: true,
    systemInitiatorUserId: 'user-dev',
    extractionRules,
  })
  console.log(`Created monitor template id=${template.id}`)
} else {
  template = await dw('PUT', `/api/v1/function-units/${FU_ID}/email-monitors/${template.id}`, {
    name: TEMPLATE_NAME,
    enabled: true,
    connectionUid: inbound.connectionUid,
    actionType: 'START_PROCESS',
    folderLabel: 'INBOX',
    pollIntervalSeconds: 60,
    reviewOnMissing: true,
    systemInitiatorUserId: 'user-dev',
    extractionRules,
  })
  console.log(`Updated monitor template id=${template.id}`)
}

const binding = await dw('PUT', `/api/v1/function-units/${FU_ID}/email-monitors/start-event-bindings`, {
  templateRuleId: template.id,
  startEventId: 'StartEvent_1',
  processDefinitionKey: 'fu-20260505-thwmut',
  enabled: true,
})
console.log(`Bound StartEvent_1 → template (binding id=${binding.id}, name=${binding.name})`)

const deploy = await dw('POST', `/api/v1/function-units/${FU_ID}/deploy`, {
  environment: 'DEVELOPMENT',
  autoEnable: true,
  changeLog: 'Email monitor: INBOUND template with DIRECT attribute mapping',
})
console.log(`Deploy started: ${deploy.deploymentId} status=${deploy.status}`)

const outDir = resolve(__dirname, '../../deploy/environments/dev/test-emails')
mkdirSync(outDir, { recursive: true })
const emlPath = resolve(outDir, 'mcy-debit-card-intake-test.eml')
const txtPath = resolve(outDir, 'mcy-debit-card-intake-test.txt')
writeFileSync(emlPath, buildTestEml(), 'utf8')
writeFileSync(
  txtPath,
  [
    `=== 测试邮件（发到 ${TEST_MAILBOX} 的 INBOX）===`,
    '',
    `主题 / Subject: ${TEST_SUBJECT}`,
    `收件人 / To: ${TEST_MAILBOX}`,
    `发件人 / From: ${TEST_MAILBOX}（自发自收即可）`,
    '',
    '正文 / Body:',
    TEST_BODY,
    '',
    '字段映射预期:',
    '  case_number ← REGEX 正文「校验码为8844221」→ 8844221',
    '  request_id  ← DIRECT Subject',
    '  calc        ← DIRECT From',
    '',
    '注意: Monitor 使用 Connections 里 direction=INBOUND 的那条，不是 OUTBOUND。',
    'Monitor 只轮询 INBOX；请在收件箱发信，不要只放在「已发送」。',
    '',
    `Monitor template id=${template.id} | binding id=${binding.id}`,
  ].join('\n'),
  'utf8',
)

console.log(`\nTest email written:`)
console.log(`  ${emlPath}`)
console.log(`  ${txtPath}`)
console.log(`\nOpen DW → FU ${FU_ID} → Email Monitors to review the template.`)
