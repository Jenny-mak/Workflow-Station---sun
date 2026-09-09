import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { createFieldExtractor } from '../useProcessStartFieldExtractor'

vi.mock('@/api/auth', () => ({
  getUser: () => ({ userId: 'u-hmdc', displayName: 'hase-hmdc', username: 'hase-hmdc' }),
}))

describe('process start Owner Creator prefill', () => {
  it('sets defaultValue on Creator and leaves Case Handler without a default', () => {
    const { extractFieldsRecursive } = createFieldExtractor({
      lookupDbConfigs: ref({}),
      relationViewConfigs: ref({}),
    })
    const fields = extractFieldsRecursive([
      {
        type: 'owner',
        field: 'case_creator',
        title: 'Creator',
        props: { ownerConfig: '{"source":"CREATOR"}' },
      },
      {
        type: 'owner',
        field: 'current_handler',
        title: 'Current Handler',
        props: { ownerConfig: '{"source":"CASE_HANDLER"}' },
      },
    ])
    const creator = fields.find((field) => field.key === 'case_creator')
    const handler = fields.find((field) => field.key === 'current_handler')
    expect(creator?.defaultValue).toBe('user:u-hmdc')
    expect(creator?._ownerPrefillDisplay).toBe('hase-hmdc')
    expect(handler?.defaultValue).toBeUndefined()
  })
})
