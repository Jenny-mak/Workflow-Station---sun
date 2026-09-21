/**
 * Mix Current Assignee on Portal To Do so the column filter can be tried:
 * You / a colleague display name / -
 *
 * Direct USER tasks on your list are always you. Variety only exists on
 * BU Role claim-pool rows (unclaimed, held by you, held by another member).
 *
 *   node frontend/scripts/seed-todo-current-assignee.mjs
 *
 * Optional env: PORTAL_ORIGIN, LOGIN_USER, LOGIN_PASS.
 * With LOGIN_BU_CODE / LOGIN_ROLE_CODE, only that workspace is mixed.
 * Otherwise every developer workspace is mixed (unclaim / reassign pool holds).
 */
import { chromium } from 'playwright'
import { loginViaPortalPassword } from './playwright-login.mjs'

const ORIGIN = (process.env.PORTAL_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const OWNER_DEMO = process.env.OWNER_DEMO_CODE ?? 'owner-demo-20260907-gehibh'
const VIEWER = {
  user: process.env.LOGIN_USER ?? 'developer',
  pass: process.env.LOGIN_PASS ?? 'password',
  buCode: process.env.LOGIN_BU_CODE ?? '共享财务中心',
  roleCode: process.env.LOGIN_ROLE_CODE ?? 'MANAGER',
}
const INITIATOR = { user: 'e2e_zhangwei', pass: 'password' }
const COLLEAGUE = {
  user: 'e2e_lina',
  pass: 'password',
  buCode: '共享财务中心',
  roleCode: 'Department Manager',
  id: 'user-e2e-lina',
}
const WANT_EACH = 2

const browser = await chromium.launch({ channel: 'chrome', headless: true })

function unwrap(body) {
  return body && typeof body === 'object' && 'data' in body ? body.data : body
}

function rowsOf(pageData) {
  if (!pageData) return []
  if (Array.isArray(pageData)) return pageData
  return pageData.content || pageData.records || []
}

function taskIdOf(row) {
  return row?.taskId || row?.id
}

function painted(row) {
  if (row.claimedByCurrentUser) return 'You'
  const name = String(row.assigneeName || '').trim()
  if (name) return name
  if (String(row.assignee || '').trim()) return String(row.assignee)
  return '-'
}

function splitPool(list) {
  const you = []
  const other = []
  const dash = []
  const direct = []
  for (const row of list) {
    if (!row.claimPoolTask) {
      if (String(row.assignee || row.assigneeName || '').trim()) direct.push(row)
      continue
    }
    if (row.claimedByCurrentUser) you.push(row)
    else if (String(row.assignee || '').trim()) other.push(row)
    else dash.push(row)
  }
  return { you, other, dash, direct }
}

async function session(creds) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await ctx.newPage()
  const me = await loginViaPortalPassword(page, { ...creds, loginOrigin: ORIGIN })
  const api = async (method, path, data, params) => {
    const url = new URL(`${ORIGIN}/api/portal${path}`)
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        if (v != null) url.searchParams.set(k, String(v))
      }
    }
    const res = await page.request.fetch(url.toString(), { method, data })
    const body = await res.json().catch(() => ({}))
    return { status: res.status(), body, data: unwrap(body), message: body.message || body.msg }
  }
  return { ctx, page, api, userId: me.userId, username: me.username }
}

async function todo(api) {
  const r = await api('POST', '/tasks/todo/query', { page: 0, size: 100 })
  if (r.status !== 200) {
    throw new Error(`todo/query ${r.status} ${JSON.stringify(r.body).slice(0, 240)}`)
  }
  return rowsOf(r.data)
}

function otherCandidate(row, myId) {
  const ids = Array.isArray(row.candidateUserIds) ? row.candidateUserIds : []
  const hit = ids.map(String).find((id) => id && id !== myId && id !== String(row.assignee || ''))
  return hit || (COLLEAGUE.id !== myId ? COLLEAGUE.id : null)
}

async function startOwnerDemos(count) {
  const initiator = await session(INITIATOR)
  const ids = []
  try {
    for (let i = 0; i < count; i += 1) {
      const started = await initiator.api('POST', `/processes/${encodeURIComponent(OWNER_DEMO)}/start`, {
        processDefinitionKey: OWNER_DEMO,
        formData: { title: `Todo assignee seed ${Date.now()}-${i}` },
        priority: 'NORMAL',
      })
      const id = started.data?.id
      if (!id) {
        console.warn('[seed] start failed', started.status, JSON.stringify(started.body).slice(0, 240))
        break
      }
      ids.push(id)
    }
  } finally {
    await initiator.ctx.close()
  }
  return ids
}

async function listWorkspaces() {
  const ctx = await browser.newContext()
  const page = await ctx.newPage()
  await page.goto(`${ORIGIN}/portal/`, { waitUntil: 'commit' })
  const res = await page.request.post(`${ORIGIN}/api/portal/auth/login`, {
    data: { username: VIEWER.user, password: VIEWER.pass },
  })
  const body = await res.json().catch(() => ({}))
  await ctx.close()
  return body.workspaceContexts || []
}

async function mixSession(creds, label) {
  const viewer = await session(creds)
  try {
    let list = await todo(viewer.api)
    let pool = splitPool(list)
    console.log(`[seed] ${label} todo=${list.length}`, {
      you: pool.you.length,
      other: pool.other.length,
      dash: pool.dash.length,
      direct: pool.direct.length,
    })

    if (pool.you.length + pool.other.length + pool.dash.length < WANT_EACH * 3) {
      const need = WANT_EACH * 3 - (pool.you.length + pool.other.length + pool.dash.length)
      const started = await startOwnerDemos(Math.max(need, WANT_EACH * 3))
      console.log(`[seed] started ${started.length} ${OWNER_DEMO} instance(s)`)
      list = await todo(viewer.api)
      pool = splitPool(list)
    }

    const unclaimNeed = Math.max(0, WANT_EACH - pool.dash.length)
    const reassignNeed = Math.max(0, WANT_EACH - pool.other.length)
    const held = [...pool.you]
    for (let i = 0; i < unclaimNeed && held.length; i += 1) {
      const row = held.shift()
      const id = taskIdOf(row)
      const r = await viewer.api(
        'POST',
        `/tasks/${id}/unclaim`,
        null,
        { originalAssignmentType: row.assignmentType || 'BU_ROLE', originalAssignee: row.assignee || viewer.userId },
      )
      console.log(`[seed] unclaim ${id} -> ${r.status} ${r.message || ''}`)
    }
    for (let i = 0; i < reassignNeed && held.length; i += 1) {
      const row = held.shift()
      const id = taskIdOf(row)
      const target = otherCandidate(row, viewer.userId)
      if (!target) {
        console.warn(`[seed] no other candidate on ${id}`)
        continue
      }
      const r = await viewer.api('POST', `/tasks/${id}/reassign`, { targetUserId: target })
      console.log(`[seed] reassign ${id} -> ${target} ${r.status} ${r.message || ''}`)
    }

    list = await todo(viewer.api)
    pool = splitPool(list)
    const labels = new Map()
    for (const row of list) {
      const cell = painted(row)
      labels.set(cell, (labels.get(cell) || 0) + 1)
    }
    console.log(`[seed] ${label} Current Assignee`, Object.fromEntries(labels))
    const names = [...new Set(pool.other.map((r) => r.assigneeName || r.assignee))]
    console.log(`[seed] ${label} filter: You, 我, -, ${names.join(', ')}`)
    return { other: pool.other.length, dash: pool.dash.length, you: pool.you.length }
  } finally {
    await viewer.ctx.close()
  }
}

const workspaces = await listWorkspaces()
console.log('[seed] workspaces', workspaces.map((c) => `${c.businessUnitCode || c.businessUnitName}/${c.roleCode || c.roleName}`).join(', ') || '(none)')

if (process.env.LOGIN_BU_CODE || process.env.LOGIN_ROLE_CODE) {
  await mixSession(VIEWER, `${VIEWER.buCode}/${VIEWER.roleCode}`)
} else {
  const targets = workspaces.length
    ? workspaces.map((c) => ({
        user: VIEWER.user,
        pass: VIEWER.pass,
        buCode: c.businessUnitCode || c.businessUnitName,
        roleCode: c.roleCode || c.roleName,
      }))
    : [VIEWER]
  for (const creds of targets) {
    await mixSession(creds, `${creds.buCode}/${creds.roleCode}`)
  }
}

const colleague = await session(COLLEAGUE)
try {
  const theirs = await todo(colleague.api)
  const claimable = theirs.filter((row) => row.claimable || (row.claimPoolTask && !row.assignee))
  let claimed = 0
  for (const row of claimable) {
    if (claimed >= WANT_EACH) break
    const id = taskIdOf(row)
    const r = await colleague.api('POST', `/tasks/${id}/claim`)
    console.log(`[seed] ${COLLEAGUE.user} claim ${id} -> ${r.status} ${r.message || ''}`)
    if (r.status === 200) claimed += 1
  }
} finally {
  await colleague.ctx.close()
}

console.log(`[seed] open ${ORIGIN}/portal/tasks as ${VIEWER.user}`)
await browser.close()

