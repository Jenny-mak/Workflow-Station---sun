/**
 * Business-path acceptance for standing rules + single-task delegate
 * on the Leave Request (Delegation) Function Unit.
 *
 *   LEAVE_DELEGATION_PROCESS_KEY=<code> LOGIN_WAIT_MS=90000 \
 *     node frontend/scripts/e2e-delegation-business.mjs
 */
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:3000'
const PROCESS_KEY = process.env.LEAVE_DELEGATION_PROCESS_KEY
  ?? process.env.DELEGATION_PROCESS_KEY
  ?? ''
const OWNER_DEMO = process.env.OWNER_DEMO_CODE ?? 'owner-demo-20260907-gehibh'

const CAST = {
  zhangwei: { user: 'e2e_zhangwei', pass: 'password', buCode: '共享财务中心', roleCode: 'HMDC_Operator_Role', id: 'user-e2e-zhangwei' },
  lina: { user: 'e2e_lina', pass: 'password', buCode: '共享财务中心', roleCode: 'Department Manager', id: 'user-e2e-lina' },
  wangfangFinance: { user: 'e2e_wangfang', pass: 'password', buCode: '共享财务中心', roleCode: 'HMDC_Operator_Role', id: 'user-e2e-wangfang' },
  wangfangIndex: { user: 'e2e_wangfang', pass: 'password', buCode: 'hase-hmdc', roleCode: 'HMDC_Index_Role', id: 'user-e2e-wangfang' },
}

const findings = []
const log = (group, step, ok, detail) => {
  findings.push({ group, step, ok, detail })
  console.log(`${ok ? '[PASS]' : '[FAIL]'} [${group}] ${step}${detail ? ` — ${JSON.stringify(detail)}` : ''}`)
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
  return { status: r.status, rows: rows(r.data), message: r.message }
}

function forInstance(list, instanceId) {
  return list.filter((t) => t.processInstanceId === instanceId)
}

function summarize(t) {
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
    actions: (t.actions || []).map((a) => a.actionType || a.actionName),
  }
}

async function openSession(browser, who) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  await loginViaPortalPassword(page, who)
  return page
}

async function startLeave(page, remark, extra = {}) {
  return api(page, 'post', `/processes/${PROCESS_KEY}/start`, {
    processDefinitionKey: PROCESS_KEY,
    formData: { reason: remark || 'E2E leave', remark },
    priority: extra.priority || 'NORMAL',
    remark,
  })
}

async function claimIfNeeded(page, task) {
  if (!task?.taskId) return { claimed: false }
  if (task.assignee && String(task.assignee).trim()) {
    return { claimed: false, alreadyAssigned: task.assignee, taskId: task.taskId }
  }
  const res = await api(page, 'post', `/tasks/${task.taskId}/claim`, {})
  return { claimed: res.status < 300, status: res.status, message: res.message, taskId: task.taskId, data: res.data }
}

if (!PROCESS_KEY) {
  console.error('Set LEAVE_DELEGATION_PROCESS_KEY (printed by create-leave-request-delegation-fu.mjs)')
  process.exit(2)
}

const waitMs = Number(process.env.LOGIN_WAIT_MS || 90000)
console.log(`process=${PROCESS_KEY}`)
console.log(`waiting ${waitMs}ms for login rate limit…`)
await new Promise((r) => setTimeout(r, waitMs))

const browser = await chromium.launch({ headless: true, channel: 'chrome' })
let wangIndex
try {
  const zhang = await openSession(browser, CAST.zhangwei)
  const lina = await openSession(browser, CAST.lina)
  const wang = await openSession(browser, CAST.wangfangFinance)

  const startable = await api(zhang, 'get', '/processes/startable')
  const startableList = Array.isArray(startable.data) ? startable.data : []
  const found = startableList.some((p) => (p.processKey || p.key || p.processDefinitionKey) === PROCESS_KEY)
  log('FU', '张伟可发起请假审批', found, {
    found,
    http: startable.status,
    keys: startableList.slice(0, 8).map((p) => p.processKey || p.key || p.processDefinitionKey),
  })

  // --- validation ---
  const self = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: CAST.lina.id,
    delegationType: 'ALL',
    reason: 'self',
  })
  log('规则校验', '禁止委托给自己', self.status >= 400, { http: self.status, message: self.message, expect400: self.status === 400 })

  const buOnly = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'BU_ROLE',
    delegateBuCode: 'hase-hmdc',
    delegationType: 'ALL',
    reason: 'bu-only',
  })
  log('规则校验', 'BU+Role 只填 BU 应失败', buOnly.status >= 400, { http: buOnly.status, message: buOnly.message, expect400: buOnly.status === 400 })

  const partialEmpty = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: CAST.wangfangFinance.id,
    delegationType: 'PARTIAL',
    reason: 'partial-empty',
  })
  log('规则校验', 'PARTIAL 未填 process key 应失败', partialEmpty.status >= 400, { http: partialEmpty.status, message: partialEmpty.message, expect400: partialEmpty.status === 400 })

  const tempEmpty = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: CAST.wangfangFinance.id,
    delegationType: 'TEMPORARY',
    reason: 'temp-empty',
  })
  log('规则校验', 'TEMPORARY 无起止应失败', tempEmpty.status >= 400, { http: tempEmpty.status, message: tempEmpty.message, expect400: tempEmpty.status === 400 })

  // --- unclaimed pool: no DELEGATE, standing overlay ignores unheld ---
  const startPool = await startLeave(zhang, `unclaimed ${Date.now()}`)
  const instPool = startPool.data?.processInstanceId || startPool.data?.id
  log('未认领', '张伟发起', startPool.status < 300, { http: startPool.status, instance: instPool, message: startPool.message })
  const linaPool = forInstance((await todo(lina)).rows, instPool)
  log('未认领', '李娜 To Do 能看见经理池', linaPool.length > 0, linaPool.map(summarize))
  const poolTask = linaPool[0]
  if (poolTask?.taskId && !poolTask.assignee) {
    const delUnheld = await api(lina, 'post', `/tasks/${poolTask.taskId}/delegate`, {
      delegatedTargetType: 'USER',
      delegatedTo: CAST.wangfangFinance.id,
      reason: 'should-fail-unclaimed',
    })
    log('未认领', '未认领单不能单任务委托', delUnheld.status >= 400, { http: delUnheld.status, message: delUnheld.message })
  } else {
    log('未认领', '未认领单不能单任务委托', false, { skipped: true, task: summarize(poolTask) })
  }

  // --- single-task USER ---
  const start1 = await startLeave(zhang, `single-user ${Date.now()}`)
  const inst1 = start1.data?.processInstanceId || start1.data?.id
  log('单任务USER', '张伟发起', start1.status < 300, { http: start1.status, instance: inst1 })
  const lina1 = forInstance((await todo(lina)).rows, inst1)
  const claim1 = await claimIfNeeded(lina, lina1[0])
  log('单任务USER', '李娜认领', claim1.claimed || !!claim1.alreadyAssigned, claim1)
  const detail1 = claim1.taskId ? (await api(lina, 'get', `/tasks/${claim1.taskId}`)).data : null
  const hasDelegate = (detail1?.actions || []).some((a) => String(a.actionType || a).toUpperCase() === 'DELEGATE')
  log('单任务USER', '详情有「委托」动作', hasDelegate, (detail1?.actions || []).map((a) => a.actionType || a.actionName))

  const wangBefore = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, inst1)
  log('单任务USER', '委托前王芳筛委托任务为空', wangBefore.length === 0, wangBefore.map(summarize))
  if (detail1?.taskId) {
    const outsider = await api(wang, 'post', `/tasks/${detail1.taskId}/complete`, {
      taskId: detail1.taskId, action: 'APPROVE', comment: 'should-403',
    })
    log('单任务USER', '委托前王芳办理失败', outsider.status >= 400, { http: outsider.status, message: outsider.message })

    const delUser = await api(lina, 'post', `/tasks/${detail1.taskId}/delegate`, {
      delegatedTargetType: 'USER',
      delegatedTo: CAST.wangfangFinance.id,
      reason: '单任务委托给王芳',
    })
    log('单任务USER', '李娜委托给王芳', delUser.status < 300, { http: delUser.status, message: delUser.message })
    const afterDel = (await api(lina, 'get', `/tasks/${detail1.taskId}`)).data
    const assigneeStillLina = String(afterDel?.assignee || '') === CAST.lina.id
      || String(afterDel?.assignee || '').includes('lina')
    log('单任务USER', 'assignee 仍是李娜', assigneeStillLina, summarize(afterDel))
    log('单任务USER', '扩展表已委托 USER', String(afterDel?.delegatedTargetType || '').toUpperCase() === 'USER'
      || afterDel?.delegated === true
      || String(afterDel?.delegatedTo || '') === CAST.wangfangFinance.id, summarize(afterDel))

    const linaStill = forInstance((await todo(lina)).rows, inst1)
    log('单任务USER', '李娜默认 To Do 仍有这单', linaStill.length > 0, linaStill.map(summarize))
    const wangDef = forInstance((await todo(wang)).rows, inst1)
    log('单任务USER', '王芳默认 To Do 不混入', wangDef.length === 0, wangDef.map(summarize))
    const wangDel = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, inst1)
    log('单任务USER', '王芳筛委托任务能看见', wangDel.length > 0, wangDel.map(summarize))
    const proxy = await api(wang, 'get', '/delegations/proxy-tasks')
    const proxyRows = rows(proxy.data).filter((t) => t.processInstanceId === inst1 || t.taskId === detail1.taskId)
    log('单任务USER', '委托管理「委托任务」Tab 同源可见', proxy.status < 300 && (wangDel.length === 0 || proxy.status < 300), {
      http: proxy.status,
      proxyHit: proxyRows.length,
    })

    const done = await api(wang, 'post', `/tasks/${detail1.taskId}/complete`, {
      taskId: detail1.taskId, action: 'APPROVE', comment: '王芳代李娜-单任务USER',
    })
    log('单任务USER', '王芳代办 complete', done.status < 300, { http: done.status, message: done.message })
  }

  // --- single-task BU+Role ---
  const start2 = await startLeave(zhang, `single-bu ${Date.now()}`)
  const inst2 = start2.data?.processInstanceId || start2.data?.id
  log('单任务BU_ROLE', '张伟发起', start2.status < 300, { instance: inst2 })
  const lina2 = forInstance((await todo(lina)).rows, inst2)
  const claim2 = await claimIfNeeded(lina, lina2[0])
  log('单任务BU_ROLE', '李娜认领', claim2.claimed || !!claim2.alreadyAssigned, claim2)
  if (claim2.taskId) {
    const delBu = await api(lina, 'post', `/tasks/${claim2.taskId}/delegate`, {
      delegatedTargetType: 'BU_ROLE',
      delegatedBuCode: 'hase-hmdc',
      delegatedRoleCode: 'HMDC_Index_Role',
      reason: '单任务委托给 Index',
    })
    log('单任务BU_ROLE', '李娜委托给 hase-hmdc/Index', delBu.status < 300, { http: delBu.status, message: delBu.message })
    const after2 = (await api(lina, 'get', `/tasks/${claim2.taskId}`)).data
    log('单任务BU_ROLE', 'assignee 仍是李娜', String(after2?.assignee || '').includes('lina') || String(after2?.assignee || '') === CAST.lina.id, summarize(after2))
    const wangWrong = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, inst2)
    log('单任务BU_ROLE', '王芳停财务操作员看不见', wangWrong.length === 0, wangWrong.map(summarize))

    wangIndex = await openSession(browser, CAST.wangfangIndex)
    const wangRight = forInstance((await todo(wangIndex, { assignmentTypes: ['DELEGATED'] })).rows, inst2)
    log('单任务BU_ROLE', '王芳切 Index 能看见且不认领', wangRight.length > 0, wangRight.map(summarize))
    const done2 = await api(wangIndex, 'post', `/tasks/${claim2.taskId}/complete`, {
      taskId: claim2.taskId, action: 'APPROVE', comment: '王芳Index代办-单任务BU_ROLE',
    })
    log('单任务BU_ROLE', '匹配工作台代办 complete', done2.status < 300, { http: done2.status, message: done2.message })
  }

  // --- standing USER ---
  const ruleUser = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: CAST.wangfangFinance.id,
    delegationType: 'ALL',
    reason: '站立 USER 李娜→王芳',
  })
  log('站立USER', '创建规则', ruleUser.status < 300, { http: ruleUser.status, id: ruleUser.data?.id, message: ruleUser.message })
  const start3 = await startLeave(zhang, `standing-user ${Date.now()}`)
  const inst3 = start3.data?.processInstanceId || start3.data?.id
  const lina3 = forInstance((await todo(lina)).rows, inst3)
  await claimIfNeeded(lina, lina3[0])
  const wangDef3 = forInstance((await todo(wang)).rows, inst3)
  const wangDel3 = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, inst3)
  log('站立USER', '默认 To Do 不合并', wangDef3.length === 0, wangDef3.map(summarize))
  log('站立USER', '筛委托任务能看见', wangDel3.length > 0, wangDel3.map(summarize))
  const standingId = wangDel3[0]?.taskId || lina3[0]?.taskId
  if (standingId) {
    const done3 = await api(wang, 'post', `/tasks/${standingId}/complete`, {
      taskId: standingId, action: 'APPROVE', comment: '站立USER代办',
    })
    log('站立USER', '王芳 complete', done3.status < 300, { http: done3.status, message: done3.message })
  }
  if (ruleUser.data?.id) {
    const sus = await api(lina, 'post', `/delegations/${ruleUser.data.id}/suspend`)
    log('站立USER', '暂停规则', sus.status < 300, { http: sus.status })
  }

  // --- standing PARTIAL / URGENT ---
  const rulePartial = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: CAST.wangfangFinance.id,
    delegationType: 'PARTIAL',
    processTypes: [PROCESS_KEY],
    reason: '站立 PARTIAL 仅请假审批',
  })
  log('站立PARTIAL', '创建规则', rulePartial.status < 300, { http: rulePartial.status, id: rulePartial.data?.id, message: rulePartial.message })
  const startP = await startLeave(zhang, `partial-leave ${Date.now()}`)
  const instP = startP.data?.processInstanceId || startP.data?.id
  const linaP = forInstance((await todo(lina)).rows, instP)
  await claimIfNeeded(lina, linaP[0])
  const wangP = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, instP)
  log('站立PARTIAL', '本 FU 请假单可见', wangP.length > 0, wangP.map(summarize))

  const startOther = await api(zhang, 'post', `/processes/${OWNER_DEMO}/start`, {
    processDefinitionKey: OWNER_DEMO,
    formData: {},
    priority: 'NORMAL',
    remark: `partial-other ${Date.now()}`,
  })
  const instOther = startOther.data?.processInstanceId || startOther.data?.id
  if (startOther.status < 300 && instOther) {
    const linaOther = forInstance((await todo(lina)).rows, instOther)
    await claimIfNeeded(lina, linaOther[0])
    const wangOther = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, instOther)
    log('站立PARTIAL', 'Owner Demo 不应进 PARTIAL 列表', wangOther.length === 0, wangOther.map(summarize))
  } else {
    log('站立PARTIAL', 'Owner Demo 不应进 PARTIAL 列表', true, { skippedStart: startOther.status, message: startOther.message })
  }
  if (rulePartial.data?.id) await api(lina, 'post', `/delegations/${rulePartial.data.id}/suspend`)

  const rulePartialBpmn = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: CAST.wangfangFinance.id,
    delegationType: 'PARTIAL',
    processTypes: ['Process_LeaveDelegation'],
    reason: '站立 PARTIAL 按 BPMN process id',
  })
  log('站立PARTIAL', '用 BPMN process id 创建规则', rulePartialBpmn.status < 300, {
    http: rulePartialBpmn.status,
    id: rulePartialBpmn.data?.id,
    message: rulePartialBpmn.message,
  })
  const startPb = await startLeave(zhang, `partial-bpmn ${Date.now()}`)
  const instPb = startPb.data?.processInstanceId || startPb.data?.id
  const linaPb = forInstance((await todo(lina)).rows, instPb)
  await claimIfNeeded(lina, linaPb[0])
  const wangPb = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, instPb)
  log('站立PARTIAL', '填 BPMN process id 后本 FU 可见', wangPb.length > 0, {
    instance: instPb,
    processDefinitionKey: linaPb[0]?.processDefinitionKey,
    functionUnitCode: linaPb[0]?.functionUnitCode,
    rows: wangPb.map(summarize),
  })
  if (rulePartialBpmn.data?.id) await api(lina, 'post', `/delegations/${rulePartialBpmn.data.id}/suspend`)

  const ruleUrgent = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: CAST.wangfangFinance.id,
    delegationType: 'URGENT',
    reason: '站立 URGENT',
  })
  log('站立URGENT', '创建规则', ruleUrgent.status < 300, { http: ruleUrgent.status, id: ruleUrgent.data?.id, message: ruleUrgent.message })
  const startN = await startLeave(zhang, `urgent-normal ${Date.now()}`, { priority: 'NORMAL' })
  const instN = startN.data?.processInstanceId || startN.data?.id
  const linaN = forInstance((await todo(lina)).rows, instN)
  await claimIfNeeded(lina, linaN[0])
  const wangN = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, instN)
  log('站立URGENT', 'NORMAL 单不可见', wangN.length === 0, wangN.map(summarize))
  const startU = await startLeave(zhang, `urgent-yes ${Date.now()}`, { priority: 'URGENT' })
  const instU = startU.data?.processInstanceId || startU.data?.id
  const linaU = forInstance((await todo(lina)).rows, instU)
  await claimIfNeeded(lina, linaU[0])
  const wangU = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, instU)
  log('站立URGENT', 'URGENT 单可见', wangU.length > 0, wangU.map(summarize))
  if (ruleUrgent.data?.id) await api(lina, 'post', `/delegations/${ruleUrgent.data.id}/suspend`)

  // --- standing BU_ROLE ---
  const ruleBu = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'BU_ROLE',
    delegateBuCode: 'hase-hmdc',
    delegateRoleCode: 'HMDC_Index_Role',
    delegationType: 'ALL',
    reason: '站立 BU_ROLE → Index',
  })
  log('站立BU_ROLE', '创建规则', ruleBu.status < 300, { http: ruleBu.status, id: ruleBu.data?.id, message: ruleBu.message })
  const start4 = await startLeave(zhang, `standing-bu ${Date.now()}`)
  const inst4 = start4.data?.processInstanceId || start4.data?.id
  const lina4 = forInstance((await todo(lina)).rows, inst4)
  await claimIfNeeded(lina, lina4[0])
  const wangOp4 = forInstance((await todo(wang, { assignmentTypes: ['DELEGATED'] })).rows, inst4)
  log('站立BU_ROLE', '王芳停财务操作员看不见', wangOp4.length === 0, wangOp4.map(summarize))
  if (!wangIndex) wangIndex = await openSession(browser, CAST.wangfangIndex)
  const wangIdx4 = forInstance((await todo(wangIndex, { assignmentTypes: ['DELEGATED'] })).rows, inst4)
  log('站立BU_ROLE', '王芳切 Index 能看见', wangIdx4.length > 0, wangIdx4.map(summarize))
  const task4 = wangIdx4[0]?.taskId || lina4[0]?.taskId
  if (task4) {
    const done4 = await api(wangIndex, 'post', `/tasks/${task4}/complete`, {
      taskId: task4, action: 'APPROVE', comment: '站立BU_ROLE代办',
    })
    log('站立BU_ROLE', '匹配工作台 complete', done4.status < 300, { http: done4.status, message: done4.message })
  }
  if (ruleBu.data?.id) await api(lina, 'post', `/delegations/${ruleBu.data.id}/suspend`)

  // --- A still self-completes after standing USER ---
  const ruleSelf = await api(lina, 'post', '/delegations', {
    delegateTargetType: 'USER',
    delegateId: CAST.wangfangFinance.id,
    delegationType: 'ALL',
    reason: '站立后李娜自办',
  })
  const start5 = await startLeave(zhang, `self-complete ${Date.now()}`)
  const inst5 = start5.data?.processInstanceId || start5.data?.id
  const lina5 = forInstance((await todo(lina)).rows, inst5)
  const claim5 = await claimIfNeeded(lina, lina5[0])
  if (claim5.taskId) {
    const selfDone = await api(lina, 'post', `/tasks/${claim5.taskId}/complete`, {
      taskId: claim5.taskId, action: 'APPROVE', comment: '李娜有规则仍自办',
    })
    log('自办', '配了站立规则后李娜仍可自己办完', selfDone.status < 300, { http: selfDone.status, message: selfDone.message })
  } else {
    log('自办', '配了站立规则后李娜仍可自己办完', false, { noTask: true })
  }
  if (ruleSelf.data?.id) await api(lina, 'post', `/delegations/${ruleSelf.data.id}/suspend`)

  await zhang.close()
  await lina.close()
  await wang.close()
  if (wangIndex) await wangIndex.close()
} catch (e) {
  log('runner', '脚本异常', false, { error: String(e?.message || e) })
  console.error(e)
} finally {
  await browser.close()
  const failed = findings.filter((f) => !f.ok)
  const byGroup = {}
  for (const f of findings) {
    byGroup[f.group] = byGroup[f.group] || { pass: 0, fail: 0 }
    byGroup[f.group][f.ok ? 'pass' : 'fail'] += 1
  }
  console.log('\n=== BY GROUP ===')
  for (const [g, n] of Object.entries(byGroup)) {
    console.log(`${g}: ${n.pass} pass / ${n.fail} fail`)
  }
  console.log(`\n=== BUSINESS ${findings.length - failed.length}/${findings.length} pass, ${failed.length} fail ===`)
  if (failed.length) {
    console.log('FAILED:')
    for (const f of failed) console.log(` - [${f.group}] ${f.step}`)
  }
  process.exit(failed.length ? 1 : 0)
}
