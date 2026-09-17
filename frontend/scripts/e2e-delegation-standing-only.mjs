/**
 * Standing-rule only (single-task delegate is blocked by missing wf_extended_task_info).
 * Reuses three sessions to stay under login rate limit.
 */
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const PROCESS_KEY = 'owner-demo-20260907-gehibh'
const findings = []
const log = (step, ok, detail) => {
  findings.push({ step, ok, detail })
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${step}${detail ? ` — ${JSON.stringify(detail)}` : ''}`)
}
const unwrap = (body) => (body && typeof body === 'object' && 'data' in body ? body.data : body)
async function api(page, method, path, data) {
  const opts = { headers: { Accept: 'application/json' } }
  if (data !== undefined) opts.data = data
  const res = await page.request[method](`${ORIGIN}/api/portal${path}`, opts)
  const body = await res.json().catch(() => ({}))
  return { status: res.status(), body, data: unwrap(body), message: body.message || body.msg }
}
function rows(pageData) {
  if (!pageData) return []
  if (Array.isArray(pageData)) return pageData
  return pageData.content || pageData.records || []
}
async function todo(page, extra = {}) {
  const r = await api(page, 'post', '/tasks/todo/query', { page: 0, size: 50, ...extra })
  return rows(r.data)
}
async function openSession(browser, who) {
  const page = await browser.newPage()
  await loginViaPortalPassword(page, who)
  return page
}

const waitMs = Number(process.env.LOGIN_WAIT_MS || 90000)
console.log(`waiting ${waitMs}ms for login rate limit…`)
await new Promise((r) => setTimeout(r, waitMs))

const browser = await chromium.launch({ headless: true, channel: 'chrome' })
try {
  const zhang = await openSession(browser, { user: 'e2e_zhangwei', pass: 'password', buCode: '共享财务中心', roleCode: 'HMDC_Operator_Role' })
  const lina = await openSession(browser, { user: 'e2e_lina', pass: 'password', buCode: '共享财务中心', roleCode: 'Department Manager' })
  const wang = await openSession(browser, { user: 'e2e_wangfang', pass: 'password', buCode: '共享财务中心', roleCode: 'HMDC_Operator_Role' })

  const ruleUser = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: 'user-e2e-wangfang',
    delegationType: 'ALL',
    reason: 'E2E standing USER 李娜→王芳',
  })
  log('创建站立 USER 规则', ruleUser.status < 300, { http: ruleUser.status, message: ruleUser.message, id: ruleUser.data?.id })

  const start = await api(zhang, 'post', `/processes/${PROCESS_KEY}/start`, {
    processDefinitionKey: PROCESS_KEY, formData: {}, priority: 'NORMAL', remark: `standing-user ${Date.now()}`,
  })
  const instance = start.data?.processInstanceId || start.data?.id
  log('张伟发起', start.status < 300, { http: start.status, instance })

  const linaTodo = (await todo(lina)).filter((t) => t.processInstanceId === instance)
  log('李娜 To Do 有 Review', linaTodo.length > 0, linaTodo.map((t) => ({ id: t.taskId, assignee: t.assignee, type: t.assignmentType })))
  const review = linaTodo[0]
  if (review?.taskId && !review.assignee) {
    const claim = await api(lina, 'post', `/tasks/${review.taskId}/claim`, {})
    log('李娜认领', claim.status < 300, { http: claim.status, assignee: claim.data?.assignee })
  }

  const wangDef = (await todo(wang)).filter((t) => t.processInstanceId === instance)
  const wangDel = (await todo(wang, { assignmentTypes: ['DELEGATED'] })).filter((t) => t.processInstanceId === instance)
  log('站立 USER：王芳默认 To Do 不合并', wangDef.length === 0, wangDef.map((t) => t.taskId))
  log('站立 USER：王芳筛委托任务能看见', wangDel.length > 0, wangDel.map((t) => ({ id: t.taskId, type: t.assignmentType, assignee: t.assignee, delegatorId: t.delegatorId })))

  const taskId = wangDel[0]?.taskId || review?.taskId
  if (taskId) {
    const complete = await api(wang, 'post', `/tasks/${taskId}/complete`, {
      taskId, action: 'APPROVE', comment: '站立USER代办',
    })
    log('站立 USER：王芳 complete', complete.status < 300, { http: complete.status, message: complete.message })
  }

  if (ruleUser.data?.id) {
    await api(lina, 'post', `/delegations/${ruleUser.data.id}/suspend`)
  }

  const ruleBu = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'BU_ROLE',
    delegateBuCode: 'hase-hmdc',
    delegateRoleCode: 'HMDC_Index_Role',
    delegationType: 'ALL',
    reason: 'E2E standing BU_ROLE → Index',
  })
  log('创建站立 BU_ROLE 规则', ruleBu.status < 300, { http: ruleBu.status, message: ruleBu.message, id: ruleBu.data?.id })

  const start2 = await api(zhang, 'post', `/processes/${PROCESS_KEY}/start`, {
    processDefinitionKey: PROCESS_KEY, formData: {}, priority: 'NORMAL', remark: `standing-bu ${Date.now()}`,
  })
  const instance2 = start2.data?.processInstanceId || start2.data?.id
  log('张伟再发起', start2.status < 300, { instance: instance2 })
  const linaTodo2 = (await todo(lina)).filter((t) => t.processInstanceId === instance2)
  if (linaTodo2[0]?.taskId && !linaTodo2[0].assignee) {
    await api(lina, 'post', `/tasks/${linaTodo2[0].taskId}/claim`, {})
  }
  const wangOpDel = (await todo(wang, { assignmentTypes: ['DELEGATED'] })).filter((t) => t.processInstanceId === instance2)
  log('站立 BU_ROLE：王芳停财务操作员工作台看不见', wangOpDel.length === 0, wangOpDel.map((t) => t.taskId))

  await wang.close()
  const wangIndex = await openSession(browser, { user: 'e2e_wangfang', pass: 'password', buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role' })
  const wangIdxDel = (await todo(wangIndex, { assignmentTypes: ['DELEGATED'] })).filter((t) => t.processInstanceId === instance2)
  log('站立 BU_ROLE：王芳切 Index 能看见', wangIdxDel.length > 0, wangIdxDel.map((t) => ({ id: t.taskId, assignee: t.assignee, type: t.assignmentType })))
  const task2 = wangIdxDel[0]?.taskId || linaTodo2[0]?.taskId
  if (task2) {
    const c2 = await api(wangIndex, 'post', `/tasks/${task2}/complete`, {
      taskId: task2, action: 'APPROVE', comment: '站立BU_ROLE代办',
    })
    log('站立 BU_ROLE：匹配工作台 complete', c2.status < 300, { http: c2.status, message: c2.message })
  }
  if (ruleBu.data?.id) await api(lina, 'post', `/delegations/${ruleBu.data.id}/suspend`)

  await zhang.close()
  await lina.close()
  await wangIndex.close()
} catch (e) {
  log('crashed', false, { error: String(e?.message || e) })
  console.error(e)
} finally {
  await browser.close()
  const failed = findings.filter((f) => !f.ok)
  console.log(`\n=== STANDING ${findings.length - failed.length}/${findings.length} pass ===`)
  process.exit(failed.length ? 1 : 0)
}
