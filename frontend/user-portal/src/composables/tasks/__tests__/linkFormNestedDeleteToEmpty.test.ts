import { describe, expect, it } from 'vitest'
import { flattenNestedSubTableRowsIntoPayload } from '../miLinkChildScrub'

/**
 * Link Form 弹窗里把某个父行的嵌套子表**删到空**，该父行的行必须从顶层切片消失。
 *
 * <p><b>实测故障（2026-09-04，task a736e30f / ATM Transaction → ATM Correspondence）。</b>
 * 用户在 TRANS-000007 的 Details 里删掉 `Corr-000039`，删除**确实**写回了父行
 * （`tx[0].__subTables__['dw:atm_correspondence'] === []`），但顶层切片里那一行还在，
 * 刷新后又显示出来。
 *
 * <p>根因：`flattenNestedSubTableRowsIntoPayload` 遍历嵌套切片时
 * `if (!Array.isArray(childVal) || childVal.length === 0) continue` ——
 * **空数组被整个跳过**，于是「这个父行已经没有子行了」这条信息传不到顶层，
 * 顶层保留的旧行成了唯一真相。删到只剩一行时正常（非空会走合并），
 * 删到空才暴露 —— 这正是「删不掉」的那一步。
 */
describe('Link Form: deleting a parent row nested slice down to empty', () => {
  function build() {
    return {
      'dw:atm_transaction': [
        { row_id: 'TRANS-000007', __subTables__: { 'dw:atm_correspondence': [] } },
        {
          row_id: 'TRANS-000008',
          __subTables__: {
            'dw:atm_correspondence': [
              { correspondence_id: 'Corr-000041', related_transaction_id: 'TRANS-000008' },
            ],
          },
        },
      ],
      // 顶层还留着已删除的 Corr-000039
      'dw:atm_correspondence': [
        { correspondence_id: 'Corr-000039', related_transaction_id: 'TRANS-000007' },
        { correspondence_id: 'Corr-000041', related_transaction_id: 'TRANS-000008' },
      ],
    } as Record<string, unknown>
  }

  /**
   * 父表 `ATM_Transaction` 的设计器主键**就是** `row_id`（实测 `is_primary_key=true`,
   * `prefixedSequence`, 前缀 `ATM-DC-PW-TRANS-`）。调用方必须把这份配置传进来 ——
   * 从前这里靠 `['row_id','id_idw','id']` 猜名字，对主键叫 `correspondence_id`、
   * `case_number` 的表一律解析不出父行标识，「删到空」直接跳过 = 最后一行删不掉。
   */
  const PK_BY_SLICE = { 'dw:atm_transaction': ['row_id'] }

  it("drops the emptied parent's rows from the top-level slice", () => {
    const subTables = build()
    flattenNestedSubTableRowsIntoPayload(subTables, 8, PK_BY_SLICE)

    const ids = (subTables['dw:atm_correspondence'] as Array<Record<string, unknown>>)
      .map(r => String(r.correspondence_id))
    // 被删掉的行不能再出现
    expect(ids).not.toContain('Corr-000039')
    // 另一个父行的行必须原样保留
    expect(ids).toContain('Corr-000041')
  })

  /**
   * 这才是「读配置」真正买到的东西：父表主键叫 `case_number`——不在原来那份
   * `['row_id','id_idw','id']` 名单里。猜名字的实现在这里解析不出父行标识，
   * 「删到空」分支 `continue`，被删的行留在顶层、刷新后复活。
   */
  it('works when the parent key is named nothing like id/row_id', () => {
    const subTables = {
      'dw:atm_case': [
        { case_number: 'CASE-000007', __subTables__: { 'dw:atm_correspondence': [] } },
      ],
      'dw:atm_correspondence': [
        { correspondence_id: 'Corr-000039', related_case_id: 'CASE-000007' },
        { correspondence_id: 'Corr-000041', related_case_id: 'CASE-000008' },
      ],
    } as Record<string, unknown>

    flattenNestedSubTableRowsIntoPayload(subTables, 8, { 'dw:atm_case': ['case_number'] })

    const ids = (subTables['dw:atm_correspondence'] as Array<Record<string, unknown>>)
      .map(r => String(r.correspondence_id))
    expect(ids).not.toContain('Corr-000039')
    expect(ids).toContain('Corr-000041')
  })

  it('a non-empty nested slice still merges up as before', () => {
    const subTables = build()
    ;(subTables['dw:atm_transaction'] as Array<Record<string, unknown>>)[0].__subTables__ = {
      'dw:atm_correspondence': [
        { correspondence_id: 'Corr-000043', related_transaction_id: 'TRANS-000007' },
      ],
    }
    flattenNestedSubTableRowsIntoPayload(subTables)

    const ids = (subTables['dw:atm_correspondence'] as Array<Record<string, unknown>>)
      .map(r => String(r.correspondence_id))
    expect(ids).toContain('Corr-000043')
    expect(ids).toContain('Corr-000041')
  })
})
