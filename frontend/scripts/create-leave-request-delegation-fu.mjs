/**
 * Business FU for delegation acceptance: employee submits leave, department
 * manager reviews (FIXED_BU_ROLE E2E_FINANCE / MANAGER), actions include DELEGATE.
 *
 *   node frontend/scripts/create-leave-request-delegation-fu.mjs
 *
 * Prints LEAVE_DELEGATION_PROCESS_KEY for the E2E script.
 */
const ORIGIN = (process.env.HELP_GUIDE_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const FU_NAME = process.env.LEAVE_DELEGATION_FU_NAME ?? 'Leave Request Delegation'
const USER = process.env.LOGIN_USER ?? 'developer'
const PASS = process.env.LOGIN_PASS ?? 'password'
const TABLE = 'leave_dlg'
const PROCESS_BPMN_ID = 'Process_LeaveDelegation'

function asRows(payload) {
  if (Array.isArray(payload)) return payload
  if (payload && Array.isArray(payload.records)) return payload.records
  if (payload && Array.isArray(payload.content)) return payload.content
  return []
}

function xmlAttr(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
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

function bpmnXml({ taskFormId, taskFormName, requestFormId, requestFormName, actionIds, actionNames }) {
  const actionIdsXml = xmlAttr(JSON.stringify(actionIds ?? []))
  const actionNamesXml = xmlAttr(JSON.stringify(actionNames ?? []))
  return `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:custom="http://workflow.platform/schema/custom" id="Definitions_LeaveDlg" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="${PROCESS_BPMN_ID}" name="Leave Request" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" name="Start">
      <bpmn:outgoing>Flow_StartToManager</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:userTask id="Task_ManagerReview" name="经理审批">
      <bpmn:extensionElements>
        <custom:properties>
          <custom:property name="assigneeType" value="FIXED_BU_ROLE"/>
          <custom:property name="assigneeLabel" value="Fixed BU Role: Department Manager"/>
          <custom:property name="businessUnitId" value="E2E_FINANCE"/>
          <custom:property name="roleId" value="MANAGER"/>
          <custom:property name="roleIds" value="MANAGER"/>
          <custom:property name="formId" value="${xmlAttr(taskFormId)}"/>
          <custom:property name="formName" value="${xmlAttr(taskFormName)}"/>
          <custom:property name="formReadOnly" value="false"/>
          <custom:property name="requestFormId" value="${xmlAttr(requestFormId)}"/>
          <custom:property name="requestFormName" value="${xmlAttr(requestFormName)}"/>
          <custom:property name="actionIds" value="${actionIdsXml}"/>
          <custom:property name="actionNames" value="${actionNamesXml}"/>
        </custom:properties>
      </bpmn:extensionElements>
      <bpmn:incoming>Flow_StartToManager</bpmn:incoming>
      <bpmn:outgoing>Flow_ManagerToEnd</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="EndEvent_1" name="Done">
      <bpmn:incoming>Flow_ManagerToEnd</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_StartToManager" sourceRef="StartEvent_1" targetRef="Task_ManagerReview"/>
    <bpmn:sequenceFlow id="Flow_ManagerToEnd" sourceRef="Task_ManagerReview" targetRef="EndEvent_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="${PROCESS_BPMN_ID}">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1">
        <dc:Bounds x="152" y="182" width="36" height="36"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_ManagerReview_di" bpmnElement="Task_ManagerReview">
        <dc:Bounds x="280" y="160" width="120" height="80"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="EndEvent_1_di" bpmnElement="EndEvent_1">
        <dc:Bounds x="500" y="182" width="36" height="36"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_StartToManager_di" bpmnElement="Flow_StartToManager">
        <di:waypoint x="188" y="200"/><di:waypoint x="280" y="200"/>
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_ManagerToEnd_di" bpmnElement="Flow_ManagerToEnd">
        <di:waypoint x="400" y="200"/><di:waypoint x="500" y="200"/>
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`
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
  const raw = typeof res.headers.getSetCookie === 'function'
    ? res.headers.getSetCookie()
    : [res.headers.get('set-cookie')].filter(Boolean)
  return {
    userId: user.userId,
    cookie: raw.map((c) => String(c).split(';')[0]).join('; '),
  }
}

function unwrapAdmin(json) {
  if (json && typeof json === 'object' && 'data' in json && json.data !== undefined) return json.data
  return json
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
  return unwrapAdmin(json)
}

async function grantPortalStartRoles(newCode, templateCode) {
  const admin = await adminLogin()
  const template = await adminApi(admin, 'GET', `/api/v1/admin/function-units/code/${encodeURIComponent(templateCode)}/active-for-start`)
  const catalog = await adminApi(admin, 'GET', `/api/v1/admin/function-units/code/${encodeURIComponent(newCode)}/latest`)
  const templateId = template?.id
  const catalogId = catalog?.id
  if (!templateId || !catalogId) {
    throw new Error(`Cannot resolve catalog ids template=${templateId} catalog=${catalogId}`)
  }
  const access = asRows(await adminApi(admin, 'GET', `/api/v1/admin/function-units/${templateId}/access`))
  const roleIds = [...new Set(access.map((row) => row.roleId || row.targetId).filter(Boolean))]
  if (roleIds.length === 0) {
    throw new Error(`Template ${templateCode} has no Portal access roles to copy`)
  }
  await adminApi(
    admin,
    'PUT',
    `/api/v1/admin/function-units/${catalogId}/access`,
    roleIds.map((roleId) => ({ roleId })),
  )
  console.log(`Granted Portal start roles (${roleIds.length}) from ${templateCode} → ${catalogId}`)
}

const session = await login()
const dw = (method, path, body) => api(session, method, path, body)

try {
  const groupsPayload = await dw('GET', '/api/v1/function-units/my-dev-groups')
  const groupId = groupsPayload?.groups?.[0]?.id || groupsPayload?.publicGroupId
  if (!groupId) throw new Error('No developer team for this user')
  session.groupId = groupId

  const listed = await dw('GET', '/api/v1/function-units?page=0&size=100')
  const records = listed?.records ?? listed?.content ?? (Array.isArray(listed) ? listed : [])
  const existing = records.find((fu) => fu.name === FU_NAME)
  let fu
  if (existing?.id) {
    fu = existing
    console.log(`Reusing FU id=${fu.id} code=${fu.code ?? ''}`)
  } else {
    fu = await dw('POST', '/api/v1/function-units', {
      name: FU_NAME,
      description:
        '请假审批：员工提交 → 部门经理审批。用于站立委托与单任务委托验收。',
      tags: ['delegation', 'e2e'],
      virtualGroupIds: [groupId],
    })
    console.log(`Created FU id=${fu.id} code=${fu.code ?? ''}`)
  }
  const fuId = fu.id

  const tableList = asRows(await dw('GET', `/api/v1/function-units/${fuId}/tables`))
  let main = tableList.find((t) => t.tableName === TABLE)
  if (!main) {
    main = await dw('POST', `/api/v1/function-units/${fuId}/tables`, {
      tableName: TABLE,
      tableDisplayName: 'Leave Request',
      tableType: 'MAIN',
      description: 'Leave header',
    })
  }

  const fields = [
    {
      fieldName: 'id',
      displayName: 'ID',
      dataType: 'VARCHAR',
      length: 64,
      nullable: false,
      isPrimaryKey: true,
      pkGeneration: { strategy: 'uuid', scope: 'perTable' },
      sortOrder: 0,
    },
    { fieldName: 'reason', displayName: 'Leave reason', dataType: 'VARCHAR', length: 200, nullable: true, sortOrder: 1 },
    { fieldName: 'start_date', displayName: 'Start date', dataType: 'DATE', nullable: true, sortOrder: 2 },
    { fieldName: 'end_date', displayName: 'End date', dataType: 'DATE', nullable: true, sortOrder: 3 },
    { fieldName: 'remark', displayName: 'Remark', dataType: 'VARCHAR', length: 500, nullable: true, sortOrder: 4 },
  ]
  await dw('PUT', `/api/v1/function-units/${fuId}/tables/${main.id}`, {
    tableName: TABLE,
    tableDisplayName: 'Leave Request',
    tableType: 'MAIN',
    fields,
  })

  const forms = asRows(await dw('GET', `/api/v1/function-units/${fuId}/forms`))
  let processForms = forms.filter((f) => f.formType === 'PROCESS')
  if (processForms.length === 0) {
    await dw('POST', `/api/v1/function-units/${fuId}/forms`, {
      formName: 'Leave Form',
      formType: 'PROCESS',
      scene: 'TASK',
      createBothScenes: true,
      boundTableId: main.id,
      configJson: { rule: [] },
      description: 'Submit leave request',
    })
    processForms = asRows(await dw('GET', `/api/v1/function-units/${fuId}/forms`)).filter(
      (f) => f.formType === 'PROCESS',
    )
  }

  const configJson = {
    rule: [
      { field: 'reason', title: 'Leave reason', type: 'input', props: { placeholder: 'Reason' } },
      { field: 'start_date', title: 'Start date', type: 'datePicker', props: { type: 'date', valueFormat: 'YYYY-MM-DD' } },
      { field: 'end_date', title: 'End date', type: 'datePicker', props: { type: 'date', valueFormat: 'YYYY-MM-DD' } },
      { field: 'remark', title: 'Remark', type: 'input', props: { type: 'textarea' } },
    ],
    options: { form: { labelPosition: 'left' } },
  }
  for (const form of processForms) {
    const bindings = asRows(await dw('GET', `/api/v1/function-units/${fuId}/forms/${form.id}/bindings`))
    if (!bindings.some((b) => b.bindingType === 'PRIMARY')) {
      await dw('POST', `/api/v1/function-units/${fuId}/forms/${form.id}/bindings`, {
        tableId: main.id,
        bindingType: 'PRIMARY',
        bindingMode: 'EDITABLE',
        sortOrder: 0,
      })
    }
    await dw('PUT', `/api/v1/function-units/${fuId}/forms/${form.id}`, {
      formName: form.formName,
      formType: form.formType || 'PROCESS',
      scene: form.scene || 'TASK',
      boundTableId: main.id,
      description: form.description || 'Leave request',
      configJson,
    })
  }

  async function findOrCreate(listPath, match, payload) {
    const rows = asRows(await dw('GET', listPath))
    const hit = rows.find(match)
    if (hit) return hit
    return dw('POST', listPath, payload)
  }

  const approve = await findOrCreate(
    `/api/v1/function-units/${fuId}/actions`,
    (a) => a.actionType === 'APPROVE',
    { actionName: 'Approve', actionType: 'APPROVE', configJson: {}, description: 'Approve leave' },
  )
  const reject = await findOrCreate(
    `/api/v1/function-units/${fuId}/actions`,
    (a) => a.actionType === 'REJECT',
    { actionName: 'Reject', actionType: 'REJECT', configJson: {}, description: 'Reject leave' },
  )
  const delegate = await findOrCreate(
    `/api/v1/function-units/${fuId}/actions`,
    (a) => a.actionType === 'DELEGATE',
    {
      actionName: 'Delegate',
      actionType: 'DELEGATE',
      configJson: { requireAssignee: true, requireComment: false },
      description: 'Delegate this review without changing assignee',
    },
  )

  const taskForm = processForms.find((f) => (f.scene || 'TASK') !== 'REQUEST') ?? processForms[0]
  const requestForm = processForms.find((f) => f.scene === 'REQUEST') ?? taskForm
  const bound = [approve, reject, delegate].filter((a) => a?.id != null)

  await dw('POST', `/api/v1/function-units/${fuId}/process`, {
    bpmnXml: bpmnXml({
      taskFormId: taskForm.id,
      taskFormName: taskForm.formName,
      requestFormId: requestForm.id,
      requestFormName: requestForm.formName,
      actionIds: bound.map((a) => a.id),
      actionNames: bound.map((a) => a.actionName),
    }),
  })

  const deploy = await dw('POST', `/api/v1/function-units/${fuId}/deploy`, {
    autoEnable: true,
    changeLog: 'Leave request FU for standing + single-task delegation acceptance',
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
  await grantPortalStartRoles(code, process.env.OWNER_DEMO_CODE ?? 'owner-demo-20260907-gehibh')
  console.log(`LEAVE_DELEGATION_FU_ID=${fuId}`)
  console.log(`LEAVE_DELEGATION_PROCESS_KEY=${code}`)
  console.log(`Deployed version ${status.versionNumber ?? ''} — start in Portal with process key ${code}`)
} catch (err) {
  console.error(err)
  process.exit(1)
}
