/**
 * 从 BPMN XML 里读出每个 service task 的 Automation 绑定（不依赖 bpmn-js，纯 DOMParser）。
 *
 * 规则与后端 `BpmnServiceTaskScanner` / 引擎 `ProcessDeploymentManager` 一致：
 * 只看扩展属性的 `name`，不看命名空间；子流程内的 service task 一并计入；
 * legacy `ap:flowId` 单独暴露（设计器面板同样把它当作可回填的旧绑定）。
 *
 * AI Studio 的 Automation 阶段没有画布，`ServiceTaskFlowPanel` 那套读法（要 modeler + element）
 * 用不上，所以这里独立解析一次。
 */
export interface ServiceTaskBinding {
  id: string
  name: string | null
  serviceType: string | null
  flowKey: string | null
  legacyFlowId: string | null
}

const localName = (el: Element): string => el.localName || el.tagName

function extensionProperties(task: Element): Record<string, string> {
  const props: Record<string, string> = {}
  const ext = Array.from(task.children).find(c => localName(c) === 'extensionElements')
  if (!ext) return props
  for (const el of Array.from(ext.getElementsByTagNameNS('*', '*'))) {
    const local = localName(el)
    if ((local === 'property' || local === 'values') && el.hasAttribute('name')) {
      const name = el.getAttribute('name') as string
      if (!(name in props)) props[name] = el.getAttribute('value') ?? ''
    }
  }
  return props
}

const blankToNull = (s: string | undefined | null): string | null => (s && s.trim() ? s : null)

/** 解析失败（非 XML）抛错，由调用方决定如何提示。 */
export function parseServiceTaskBindings(bpmnXml: string): ServiceTaskBinding[] {
  if (!bpmnXml || !bpmnXml.trim()) return []
  const doc = new DOMParser().parseFromString(bpmnXml, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length > 0) {
    throw new Error('BPMN XML could not be parsed')
  }
  const tasks = Array.from(doc.getElementsByTagNameNS('*', 'serviceTask'))
  return tasks.map(task => {
    const props = extensionProperties(task)
    return {
      id: task.getAttribute('id') ?? '',
      name: blankToNull(task.getAttribute('name')),
      serviceType: blankToNull(props['serviceType']),
      flowKey: blankToNull(props['ap:flowKey']),
      legacyFlowId: blankToNull(props['ap:flowId'])
    }
  })
}
