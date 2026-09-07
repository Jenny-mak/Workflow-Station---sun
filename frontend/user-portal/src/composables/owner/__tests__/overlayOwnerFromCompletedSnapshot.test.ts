import { describe, expect, it } from 'vitest'
import {
  lastCompletedTaskIdForNode,
  overlayCompletedNodeOwner,
  overlayOwnerMainValues,
  snapshotFieldValuesOf,
} from '../overlayOwnerFromCompletedSnapshot'

describe('overlayOwnerFromCompletedSnapshot', () => {
  it('picks the last completed task for the BPMN node', () => {
    expect(lastCompletedTaskIdForNode([
      { id: 'h1', taskId: 't1', nodeId: 'Review', status: 'completed' },
      { id: 'h2', taskId: 't2', nodeId: 'Review', status: 'completed' },
      { id: 'h3', taskId: 't3', nodeId: 'Close', status: 'completed' },
    ], 'Review')).toBe('t2')
  })

  it('reads fieldValues from _snapshot_{taskId}', () => {
    const vars = {
      current_handler: 'step:multi',
      _snapshot_t2: { fieldValues: { current_handler: 'user:u-b', current_handler__display: 'B' } },
    }
    expect(snapshotFieldValuesOf(vars, 't2')).toEqual({
      current_handler: 'user:u-b',
      current_handler__display: 'B',
    })
    expect(snapshotFieldValuesOf(vars, 'missing')).toBeNull()
  })

  it('uses snapshot Owner values and clears when the snapshot is missing', () => {
    const live = { title: 'x', current_handler: 'step:multi', current_handler__display: 'multi' }
    expect(overlayOwnerMainValues(live, ['current_handler'], {
      current_handler: 'user:u-b',
      current_handler__display: 'B',
    }).current_handler).toBe('user:u-b')
    const cleared = overlayOwnerMainValues(live, ['current_handler'], null)
    expect(cleared.current_handler).toBe('')
    expect(cleared.current_handler__display).toBeUndefined()
    expect(cleared.title).toBe('x')
  })

  it('never falls back to live Owner values when the node has no snapshot', () => {
    const live = { current_handler: 'step:multi' }
    const result = overlayCompletedNodeOwner({
      values: live,
      fields: [{ type: 'owner', key: 'current_handler' }],
      bindings: [],
      nodeId: 'Review',
      variables: { current_handler: 'step:multi' },
      history: [],
    })
    expect(result.values.current_handler).toBe('')
  })

  it('treats a rejected node as finished but a claimed one as still running', () => {
    expect(lastCompletedTaskIdForNode(
      [{ id: 'h1', taskId: 't4', nodeId: 'Review', status: 'rejected' }],
      'Review',
    )).toBe('t4')
    expect(lastCompletedTaskIdForNode(
      [{ id: 'h2', taskId: 't5', nodeId: 'Review', status: 'current' }],
      'Review',
    )).toBeNull()
  })

  it('ignores the synthetic history row id — only a real taskId addresses a snapshot', () => {
    expect(lastCompletedTaskIdForNode(
      [{ id: 'history_1', nodeId: 'Review', status: 'completed' }],
      'Review',
    )).toBeNull()
  })

  it('matches history by BPMN activity name when activityId is missing', () => {
    expect(lastCompletedTaskIdForNode(
      [{ id: 'history_0', taskId: 't9', nodeId: 'node_0', nodeName: 'assignment', status: 'completed' }],
      'Activity_0hwtl8v',
      'assignment',
    )).toBe('t9')
  })

  it('reads row Owner fields from the snapshot collection row, not only the snapshot root', () => {
    const result = overlayCompletedNodeOwner({
      values: { main_id: 'Test-000019', row_owner: '', row_case_handler: '' },
      fields: [
        { type: 'owner', key: 'row_owner' },
        { type: 'owner', key: 'row_case_handler' },
      ],
      bindings: [],
      nodeId: 'Activity_0j8mz1c',
      nodeName: 'sub form1',
      variables: {
        case_handler: 'step:multi',
        _snapshot_t8: {
          fieldValues: {
            case_handler: 'step:multi',
            __subTables__: {
              'dw:subtable': [{
                id_idw: 'Test-000019',
                main_id: 'Test-000019',
                row_owner: 'user:user-e2e-lina',
                row_owner__display: '李娜',
                row_case_handler: 'user:user-e2e-zhangwei',
                row_case_handler__display: '张伟',
              }],
            },
          },
        },
      },
      history: [
        { id: 'history_2', taskId: 't8', nodeId: 'Activity_0j8mz1c', nodeName: 'sub form1', status: 'completed' },
      ],
    })
    expect(result.values.row_owner).toBe('user:user-e2e-lina')
    expect(result.values.row_owner__display).toBe('李娜')
    expect(result.values.row_case_handler).toBe('user:user-e2e-zhangwei')
    expect(result.values.case_handler).toBeUndefined()
  })

  it('keeps MAIN Case Handler on the snapshot root when a collection row is also present', () => {
    const result = overlayCompletedNodeOwner({
      values: { case_handler: 'step:multi', row_owner: '' },
      fields: [
        { type: 'owner', key: 'case_handler' },
        { type: 'owner', key: 'row_owner' },
      ],
      bindings: [],
      nodeId: 'Activity_0j8mz1c',
      nodeName: 'sub form1',
      variables: {
        _snapshot_t8: {
          fieldValues: {
            case_handler: 'step:multi',
            case_handler__display: 'multi',
            __subTables__: {
              'dw:subtable': [{
                id_idw: 'Test-000019',
                row_owner: 'user:user-e2e-lina',
                row_owner__display: '李娜',
              }],
            },
          },
        },
      },
      history: [
        { id: 'history_2', taskId: 't8', nodeId: 'Activity_0j8mz1c', status: 'completed' },
      ],
    })
    expect(result.values.case_handler).toBe('step:multi')
    expect(result.values.row_owner).toBe('user:user-e2e-lina')
  })

  it('overlays sub-table Owner columns when the snapshot slice is keyed by dw:table', () => {
    const result = overlayCompletedNodeOwner({
      values: {},
      fields: [],
      bindings: [{
        bindingId: 50627,
        primaryKeyFields: ['id_idw'],
        columns: [{ field: 'row_owner', type: 'owner' }],
        data: [{ id_idw: 'Test-000019', row_owner: '' }],
      }],
      nodeId: 'Activity_0j8mz1c',
      variables: {
        _snapshot_t8: {
          fieldValues: {
            __subTables__: {
              'dw:subtable': [{
                id_idw: 'Test-000019',
                row_owner: 'user:user-e2e-lina',
                row_owner__display: '李娜',
              }],
            },
          },
        },
      },
      history: [
        { id: 'history_2', taskId: 't8', nodeId: 'Activity_0j8mz1c', status: 'completed' },
      ],
    })
    const row = result.bindings[0].data?.[0] as Record<string, unknown>
    expect(row.row_owner).toBe('user:user-e2e-lina')
    expect(row.row_owner__display).toBe('李娜')
  })

  it('reads the frozen handler for a completed node while live data already moved on', () => {
    const result = overlayCompletedNodeOwner({
      values: { case_handler: 'step:multi', case_handler__display: 'multi' },
      fields: [{ type: 'owner', key: 'case_handler' }],
      bindings: [],
      nodeId: 'Activity_0hwtl8v',
      nodeName: 'assignment',
      variables: {
        case_handler: 'step:multi',
        _snapshot_t7: {
          fieldValues: { case_handler: 'user:user-e2e-zhangwei', case_handler__display: '张伟' },
        },
      },
      history: [
        { id: 'history_0', taskId: 't6', nodeId: 'Activity_0z1px4l', status: 'completed' },
        { id: 'history_1', taskId: 't7', nodeId: 'Activity_0hwtl8v', status: 'completed' },
      ],
    })
    expect(result.values.case_handler).toBe('user:user-e2e-zhangwei')
    expect(result.values.case_handler__display).toBe('张伟')
  })
})
