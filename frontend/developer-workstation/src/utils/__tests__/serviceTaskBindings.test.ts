import { describe, expect, it } from 'vitest'
import { parseServiceTaskBindings } from '../serviceTaskBindings'

const wrap = (body: string) => `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:custom="http://workflow.platform/schema/custom"
                  xmlns:custom_1="http://custom.bpmn.io/schema">
  <bpmn:process id="p">${body}</bpmn:process>
</bpmn:definitions>`

describe('parseServiceTaskBindings', () => {
  it('returns an empty list for blank xml', () => {
    expect(parseServiceTaskBindings('')).toEqual([])
    expect(parseServiceTaskBindings('   ')).toEqual([])
  })

  it('reads ap:flowKey and serviceType from the platform container', () => {
    const xml = wrap(`
      <bpmn:serviceTask id="svc_sync" name="Sync invoice">
        <bpmn:extensionElements>
          <custom:properties>
            <custom:property name="serviceType" value="ap"/>
            <custom:property name="ap:flowKey" value="invoice-sync"/>
          </custom:properties>
        </bpmn:extensionElements>
      </bpmn:serviceTask>`)
    expect(parseServiceTaskBindings(xml)).toEqual([
      { id: 'svc_sync', name: 'Sync invoice', serviceType: 'ap', flowKey: 'invoice-sync', legacyFlowId: null }
    ])
  })

  it('reads the bpmn-js container with values entries and surfaces legacy flow ids', () => {
    const xml = wrap(`
      <bpmn:serviceTask id="svc_old">
        <bpmn:extensionElements>
          <custom_1:properties>
            <custom_1:values name="ap:flowId" value="src-env-id"/>
          </custom_1:properties>
        </bpmn:extensionElements>
      </bpmn:serviceTask>
      <bpmn:userTask id="u1"/>
      <bpmn:subProcess id="sp">
        <bpmn:serviceTask id="svc_nested"/>
      </bpmn:subProcess>`)
    expect(parseServiceTaskBindings(xml)).toEqual([
      { id: 'svc_old', name: null, serviceType: null, flowKey: null, legacyFlowId: 'src-env-id' },
      { id: 'svc_nested', name: null, serviceType: null, flowKey: null, legacyFlowId: null }
    ])
  })

  it('throws on malformed xml instead of returning an empty list', () => {
    expect(() => parseServiceTaskBindings('<definitions><process></definitions>')).toThrow()
  })
})
