/**
 * Live portal walkthrough: standing rules + single-task delegate, USER and BU+Role.
 * Usage: node scripts/e2e-delegation-walkthrough.mjs
 */
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const PROCESS_KEY = process.env.DELEGATION_PROCESS_KEY ?? 'owner-demo-20260907-gehibh'

const CAST = {
  zhangwei: { user: 'e2e_zhangwei', pass: 'password', buCode: '共享财务中心', roleCode: 'HMDC_Operator_Role', id: 'user-e2e-zhangwei', label: '张伟/操作员' },
  lina: { user: 'e2e_lina', pass: 'password', buCode: '共享财务中心', roleCode: 'Department Manager', id: 'user-e2e-lina', label: '李娜/部门经理' },
  wangfangFinance: { user: 'e2e_wangfang', pass: 'password', buCode: '共享财务中心', roleCode: 'HMDC_Operator_Role', id: 'user-e2e-wangfang', label: '王芳/财务操作员' },
  wangfangIndex: { user: 'e2e_wangfang', pass: 'password', buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role', id: 'user-e2e-wangfang', label: '王芳/HMDC Index' },
  zhangweiIndex: { user: 'e2e_zhangwei', pass: 'password', buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role', id: 'user-e2e-zhangwei', label: '张伟/HMDC Index' },
  linaAssign: { user: 'e2e_lina', pass: 'password', buCode: 'hase-hmdc', roleCode: 'HMDC_Assign_Role', id: 'user-e2e-lina', label: '李娜/HMDC Assign' },
}

const findings = []
const log = (step, ok, detail) => {
  const row = { step, ok, detail }
  findings.push(row)
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${step}${detail ? ` — ${JSON.stringify(detail)}` : ''}`)
}

function unwrap(body) {
  if (body && typeof body === 'object' && 'data' in body) return body.data
  return body
}

async function api(page, method, path, data) {
  const opts = { headers: { Accept: 'application/json' } }
  if (data !== undefined) opts.data = data
  const res = await page.request[method](`${ORIGIN}/api/portal${path}`, opts)
  const body = await res.json().catch(() => ({}))
  return { status: res.status(), body, data: unwrap(body), message: body.message || body.msg }
}

async function openSession(browser, who) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  await loginViaPortalPassword(page, {
    user: who.user,
    pass: who.pass,
    buCode: who.buCode,
    roleCode: who.roleCode,
  })
  const me = await api(page, 'get', '/auth/me')
  return { page, who, me: me.data }
}

function rowsOf(pageData) {
  if (!pageData) return []
  if (Array.isArray(pageData)) return pageData
  return pageData.content || pageData.records || []
}

function summarizeTask(t) {
  if (!t) return null
  return {
    taskId: t.taskId || t.id,
    name: t.taskName || t.name,
    assignee: t.assignee,
    assignmentType: t.assignmentType,
    delegated: t.delegated,
    delegatedTo: t.delegatedTo,
    delegatedTargetType: t.delegatedTargetType,
    delegatedBuCode: t.delegatedBuCode,
    delegatedRoleCode: t.delegatedRoleCode,
    delegatorId: t.delegatorId,
    processInstanceId: t.processInstanceId,
    processDefinitionKey: t.processDefinitionKey,
    actions: (t.actions || []).map((a) => a.actionType || a.actionName),
  }
}

async function todo(page, extra = {}) {
  return api(page, 'post', '/tasks/todo/query', { page: 0, size: 50, ...extra })
}

async function taskDetail(page, taskId) {
  return api(page, 'get', `/tasks/${taskId}`)
}

async function startOwnerDemo(page, remark) {
  const startable = await api(page, 'get', '/processes/startable')
  const list = Array.isArray(startable.data) ? startable.data : []
  const hit = list.find((p) => (p.processKey || p.key || p.processDefinitionKey) === PROCESS_KEY)
  const started = await api(page, 'post', `/processes/${PROCESS_KEY}/start`, {
    processDefinitionKey: PROCESS_KEY,
    formData: {},
    priority: 'NORMAL',
    remark: remark || `delegation-walkthrough ${Date.now()}`,
  })
  return { startableStatus: startable.status, startableCount: list.length, foundInStartable: !!hit, started }
}

async function findReviewFor(page, processInstanceId) {
  const all = await todo(page)
  const delegated = await todo(page, { assignmentTypes: ['DELEGATED'] })
  const mine = rowsOf(all.data).filter((t) => !processInstanceId || t.processInstanceId === processInstanceId)
  const del = rowsOf(delegated.data).filter((t) => !processInstanceId || t.processInstanceId === processInstanceId)
  return { defaultTodo: mine.map(summarizeTask), delegatedTodo: del.map(summarizeTask), defaultStatus: all.status, delegatedStatus: delegated.status }
}

async function claimIfNeeded(page, task) {
  if (!task?.taskId) return { claimed: false }
  const type = String(task.assignmentType || '').toUpperCase()
  if (task.assignee && String(task.assignee).trim()) {
    return { claimed: false, alreadyAssigned: task.assignee }
  }
  if (type.includes('BU_ROLE') || type.includes('CANDIDATE') || type.includes('POOL') || !task.assignee) {
    const res = await api(page, 'post', `/tasks/${task.taskId}/claim`, {})
    return { claimed: res.status < 300, status: res.status, message: res.message, data: res.data }
  }
  return { claimed: false }
}

const launchOpts = { headless: true, channel: 'chrome' }
const browser = await chromium.launch(launchOpts)

try {
  console.log(`\n=== CAST ===`)
  console.log(JSON.stringify(Object.fromEntries(Object.entries(CAST).map(([k, v]) => [k, v.label])), null, 2))
  console.log(`process=${PROCESS_KEY}\n`)

  const zhang = await openSession(browser, CAST.zhangwei)
  log('张伟 login E2E_FINANCE/Operator', zhang.me?.userId === CAST.zhangwei.id || zhang.me?.username === 'e2e_zhangwei', {
    userId: zhang.me?.userId || zhang.me?.id,
    bu: zhang.me?.activeBusinessUnitCode || zhang.me?.activeBusinessUnitId,
    role: zhang.me?.activeRoleCode || zhang.me?.activeRoleId,
  })

  const start1 = await startOwnerDemo(zhang.page, 'single-delegate-user')
  log('张伟发起 Owner Demo (1)', start1.started.status < 300, {
    http: start1.started.status,
    message: start1.started.message,
    foundInStartable: start1.foundInStartable,
    startableCount: start1.startableCount,
    instance: start1.started.data?.processInstanceId || start1.started.data?.id,
  })
  const instance1 = start1.started.data?.processInstanceId || start1.started.data?.id

  const lina = await openSession(browser, CAST.lina)
  const linaList1 = await findReviewFor(lina.page, instance1)
  log('李娜默认 To Do 能看到 Review（节点 BU+Role=E2E_FINANCE/MANAGER）', linaList1.defaultTodo.length > 0, linaList1.defaultTodo)
  const review1 = linaList1.defaultTodo[0]
  const claim1 = await claimIfNeeded(lina.page, review1)
  log('李娜认领（若是池任务）', claim1.claimed || !!claim1.alreadyAssigned, claim1)
  const afterClaim = review1?.taskId ? (await taskDetail(lina.page, review1.taskId)).data : null
  log('认领后任务详情', !!afterClaim, summarizeTask(afterClaim))
  const canDelegate = (afterClaim?.actions || []).some((a) => String(a.actionType || a).toUpperCase() === 'DELEGATE')
  log('李娜详情有「委托」动作', canDelegate, (afterClaim?.actions || []).map((a) => a.actionType || a.actionName || a))

  const wangOp = await openSession(browser, CAST.wangfangFinance)
  const wangBefore = await findReviewFor(wangOp.page, instance1)
  log('委托前：王芳(操作员)默认 To Do 不应有这单', wangBefore.defaultTodo.length === 0, wangBefore.defaultTodo)
  log('委托前：王芳筛委托任务也不应有', wangBefore.delegatedTodo.length === 0, wangBefore.delegatedTodo)

  if (afterClaim?.taskId) {
    const outsiderTry = await api(wangOp.page, 'post', `/tasks/${afterClaim.taskId}/complete`, {
      taskId: afterClaim.taskId,
      action: 'APPROVE',
      comment: 'should-403-before-delegate',
    })
    log('委托前王芳办理应失败', outsiderTry.status >= 400, { http: outsiderTry.status, message: outsiderTry.message })

    const delUser = await api(lina.page, 'post', `/tasks/${afterClaim.taskId}/delegate`, {
      delegatedTargetType: 'USER',
      delegatedTo: CAST.wangfangFinance.id,
      reason: 'E2E 单任务委托给王芳',
    })
    log('李娜单任务委托给王芳', delUser.status < 300, { http: delUser.status, message: delUser.message })
    const afterDel = (await taskDetail(lina.page, afterClaim.taskId)).data
    log('委托后 assignee 仍是李娜', String(afterDel?.assignee || '') === CAST.lina.id || String(afterDel?.assignee || '').includes('lina'), summarizeTask(afterDel))

    const linaTodoAfter = await findReviewFor(lina.page, instance1)
    log('委托后李娜默认 To Do 仍有这单（自己还能办）', linaTodoAfter.defaultTodo.length > 0, linaTodoAfter.defaultTodo)

    const wangDef = await findReviewFor(wangOp.page, instance1)
    log('委托后王芳默认 To Do 仍不应混入', wangDef.defaultTodo.length === 0, wangDef.defaultTodo)
    log('委托后王芳筛委托任务能看见', wangDef.delegatedTodo.length > 0, wangDef.delegatedTodo)

    const wangDetail = wangDef.delegatedTodo[0]?.taskId
      ? (await taskDetail(wangOp.page, wangDef.delegatedTodo[0].taskId)).data
      : (await taskDetail(wangOp.page, afterClaim.taskId)).data
    log('王芳打开详情（代办）', !!wangDetail, summarizeTask(wangDetail))
    const wangComplete = await api(wangOp.page, 'post', `/tasks/${afterClaim.taskId}/complete`, {
      taskId: afterClaim.taskId,
      action: 'APPROVE',
      comment: '王芳代李娜办理-单任务USER',
    })
    log('王芳代办 complete（节点本不是她的 BU+Role）', wangComplete.status < 300, { http: wangComplete.status, message: wangComplete.message })
  } else {
    log('没有李娜可委托的 Review 任务，跳过单任务 USER', false, { linaTodo: linaList1 })
  }

  // --- instance 2: single-task BU+Role ---
  const start2 = await startOwnerDemo(zhang.page, 'single-delegate-bu-role')
  log('张伟发起 Owner Demo (2) BU+Role 委托', start2.started.status < 300, { http: start2.started.status, message: start2.started.message, instance: start2.started.data?.processInstanceId || start2.started.data?.id })
  const instance2 = start2.started.data?.processInstanceId || start2.started.data?.id
  const linaList2 = await findReviewFor(lina.page, instance2)
  const review2 = linaList2.defaultTodo[0]
  await claimIfNeeded(lina.page, review2)
  const detail2 = review2?.taskId ? (await taskDetail(lina.page, review2.taskId)).data : null
  if (detail2?.taskId) {
    const delBu = await api(lina.page, 'post', `/tasks/${detail2.taskId}/delegate`, {
      delegatedTargetType: 'BU_ROLE',
      delegatedBuCode: 'hase-hmdc',
      delegatedRoleCode: 'HMDC_Index_Role',
      reason: 'E2E 单任务委托给 hase-hmdc / Index',
    })
    log('李娜单任务委托给 hase-hmdc/HMDC_Index_Role', delBu.status < 300, { http: delBu.status, message: delBu.message })

    const wangWrongWs = await findReviewFor(wangOp.page, instance2)
    log('王芳停在财务操作员工作台：委托任务不应看见 BU+Role 委托', wangWrongWs.delegatedTodo.length === 0, wangWrongWs.delegatedTodo)

    const wangIndex = await openSession(browser, CAST.wangfangIndex)
    const wangRightWs = await findReviewFor(wangIndex.page, instance2)
    log('王芳切到 hase-hmdc/Index：委托任务应看见', wangRightWs.delegatedTodo.length > 0, wangRightWs.delegatedTodo)

    const zhangIndex = await openSession(browser, CAST.zhangweiIndex)
    const zhangRight = await findReviewFor(zhangIndex.page, instance2)
    log('张伟切到 Index：同样应看见 BU+Role 委托任务', zhangRight.delegatedTodo.length > 0, zhangRight.delegatedTodo)

    const linaWrongWs = await openSession(browser, CAST.linaAssign)
    const linaIndexView = await findReviewFor(linaWrongWs.page, instance2)
    log('李娜切到 Assign（不是 Index）：委托任务不应因自己是办理人以外的叠加而看见 Index 目标', true, {
      default: linaIndexView.defaultTodo,
      delegated: linaIndexView.delegatedTodo,
    })

    const completeBu = await api(wangIndex.page, 'post', `/tasks/${detail2.taskId}/complete`, {
      taskId: detail2.taskId,
      action: 'APPROVE',
      comment: '王芳Index工作台代办-单任务BU_ROLE',
    })
    log('王芳在匹配工作台代办 complete', completeBu.status < 300, { http: completeBu.status, message: completeBu.message })
    await wangIndex.page.close()
    await zhangIndex.page.close()
    await linaWrongWs.page.close()
  } else {
    log('没有第 2 单 Review，跳过单任务 BU+Role', false, linaList2)
  }

  // --- standing USER rule ---
  const ruleUser = await api(lina.page, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: CAST.wangfangFinance.id,
    delegationType: 'ALL',
    reason: 'E2E 站立规则：李娜→王芳',
  })
  log('李娜创建站立规则 USER→王芳', ruleUser.status < 300, { http: ruleUser.status, message: ruleUser.message, id: ruleUser.data?.id })
  const start3 = await startOwnerDemo(zhang.page, 'standing-user')
  const instance3 = start3.started.data?.processInstanceId || start3.started.data?.id
  log('张伟发起 Owner Demo (3) 站立 USER', start3.started.status < 300, { http: start3.started.status, instance: instance3 })
  const linaList3 = await findReviewFor(lina.page, instance3)
  await claimIfNeeded(lina.page, linaList3.defaultTodo[0])
  const wangStanding = await findReviewFor(wangOp.page, instance3)
  log('站立 USER：王芳默认 To Do 不自动合并', wangStanding.defaultTodo.length === 0, wangStanding.defaultTodo)
  log('站立 USER：王芳筛委托任务能看见', wangStanding.delegatedTodo.length > 0, wangStanding.delegatedTodo)
  const standingTaskId = wangStanding.delegatedTodo[0]?.taskId || linaList3.defaultTodo[0]?.taskId
  if (standingTaskId) {
    const standingComplete = await api(wangOp.page, 'post', `/tasks/${standingTaskId}/complete`, {
      taskId: standingTaskId,
      action: 'APPROVE',
      comment: '王芳按站立规则代办',
    })
    log('站立 USER：王芳 complete', standingComplete.status < 300, { http: standingComplete.status, message: standingComplete.message })
  }

  if (ruleUser.data?.id) {
    const sus = await api(lina.page, 'post', `/delegations/${ruleUser.data.id}/suspend`)
    log('暂停 USER 站立规则', sus.status < 300, { http: sus.status, message: sus.message })
  }

  // --- standing BU_ROLE ---
  const ruleBu = await api(lina.page, 'post', '/delegations', {
    delegateTargetType: 'BU_ROLE',
    delegateBuCode: 'hase-hmdc',
    delegateRoleCode: 'HMDC_Index_Role',
    delegationType: 'ALL',
    reason: 'E2E 站立规则：李娜→hase-hmdc/Index',
  })
  log('李娜创建站立规则 BU_ROLE→Index', ruleBu.status < 300, { http: ruleBu.status, message: ruleBu.message, id: ruleBu.data?.id })
  const start4 = await startOwnerDemo(zhang.page, 'standing-bu-role')
  const instance4 = start4.started.data?.processInstanceId || start4.started.data?.id
  log('张伟发起 Owner Demo (4) 站立 BU+Role', start4.started.status < 300, { http: start4.started.status, instance: instance4 })
  const linaList4 = await findReviewFor(lina.page, instance4)
  await claimIfNeeded(lina.page, linaList4.defaultTodo[0])
  const wangOpStandingBu = await findReviewFor(wangOp.page, instance4)
  log('站立 BU+Role：王芳停操作员工作台看不见', wangOpStandingBu.delegatedTodo.length === 0, wangOpStandingBu.delegatedTodo)
  const wangIndex2 = await openSession(browser, CAST.wangfangIndex)
  const wangIdxStanding = await findReviewFor(wangIndex2.page, instance4)
  log('站立 BU+Role：王芳切 Index 能看见', wangIdxStanding.delegatedTodo.length > 0, wangIdxStanding.delegatedTodo)
  const standingBuTaskId = wangIdxStanding.delegatedTodo[0]?.taskId || linaList4.defaultTodo[0]?.taskId
  if (standingBuTaskId) {
    const complete4 = await api(wangIndex2.page, 'post', `/tasks/${standingBuTaskId}/complete`, {
      taskId: standingBuTaskId,
      action: 'APPROVE',
      comment: '王芳Index按站立BU_ROLE代办',
    })
    log('站立 BU+Role：匹配工作台 complete', complete4.status < 300, { http: complete4.status, message: complete4.message })
  }
  if (ruleBu.data?.id) {
    await api(lina.page, 'post', `/delegations/${ruleBu.data.id}/suspend`)
  }

  await zhang.page.close()
  await lina.page.close()
  await wangOp.page.close()
  await wangIndex2.page.close()
} catch (e) {
  log('walkthrough crashed', false, { error: String(e?.message || e) })
  console.error(e)
} finally {
  await browser.close()
  const failed = findings.filter((f) => !f.ok)
  console.log(`\n=== SUMMARY ${findings.length - failed.length}/${findings.length} pass, ${failed.length} fail ===`)
  process.exit(failed.length ? 1 : 0)
}
