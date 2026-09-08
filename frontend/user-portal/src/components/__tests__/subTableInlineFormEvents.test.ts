import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import SubTableInlineForm from '../SubTableInlineForm.vue'
import type { FormField } from '../formRendererHelpers'

/**
 * Form-below-table Event gap: SubTableInlineForm used to render PortalFormFields
 * without the Add/Edit dialog's form-create Event runtime, so Y/N hide-show
 * (and designer Hide seed) never ran under the table.
 */

vi.mock('vue-i18n', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-i18n')>()
  return { ...actual, useI18n: () => ({ t: (key: string) => key }) }
})

vi.mock('@/api/user', () => ({
  userApi: { searchUsers: vi.fn().mockResolvedValue([]) },
}))

vi.mock('@/api/admin', () => ({
  getBusinessUnitTree: vi.fn().mockResolvedValue([]),
  getRolesByBusinessUnit: vi.fn().mockResolvedValue([]),
}))

const FieldRendererStub = defineComponent({
  name: 'FieldRenderer',
  props: {
    field: { type: Object, required: true },
    modelValue: { type: [String, Number, Boolean, Object, Array], default: null },
  },
  emits: ['update:modelValue', 'field-blur'],
  setup(props, { emit, expose }) {
    expose({
      setValue(v: unknown) {
        emit('update:modelValue', v)
      },
    })
    return () =>
      h(
        'button',
        {
          class: 'stub-field',
          type: 'button',
          'data-field': (props.field as { key?: string }).key,
          onClick: () => emit('update:modelValue', 'Y'),
        },
        String((props.field as { label?: string }).label ?? ''),
      )
  },
})

const FLAG_ON_CHANGE =
  '$FNX:\n'
  + 'var v = $inject.field === "__bootstrap__" ? $inject.api.getValue("merchant_credit") : $inject.value\n'
  + 'if (v === "Y") { $inject.api.hidden(true, "merchant_credit_date"); $inject.api.hidden(false, "temporary_refund_date") }\n'
  + 'else { $inject.api.hidden(false, "merchant_credit_date"); $inject.api.hidden(true, "temporary_refund_date") }\n'

function financialFields(): FormField[] {
  return [
    {
      key: '__card_fin',
      label: 'Financial adjustment',
      type: 'card',
      children: [
        {
          key: 'merchant_credit',
          label: 'Merchant Credit',
          type: 'select',
          sourceRule: { type: 'select', field: 'merchant_credit' },
        },
        {
          key: 'merchant_credit_date',
          label: 'Merchant Credit Date',
          type: 'date',
          hidden: true,
          sourceRule: { type: 'datePicker', field: 'merchant_credit_date', hidden: true },
        },
        {
          key: 'temporary_refund_date',
          label: 'Temporary Refund Date',
          type: 'date',
          hidden: true,
          sourceRule: { type: 'datePicker', field: 'temporary_refund_date', hidden: true },
        },
      ],
    },
  ]
}

async function mountInline(props: Record<string, unknown> = {}) {
  const wrapper = mount(SubTableInlineForm, {
    props: {
      fields: financialFields(),
      // These fixtures identify rows by `id`, so the table must SAY that `id` is its primary key.
      // Without it the rows are anonymous by design: `id` is a business column on 13 tables in dev
      // and the primary key on none of them, so a bare `id` no longer counts as identity.
      primaryKeyFields: ['id'],
      currentRow: { id: 'row-1', merchant_credit: 'N' },
      ...props,
    } as never,
    global: {
      stubs: { FieldRenderer: FieldRendererStub, teleport: true },
    },
  })
  await nextTick()
  await nextTick()
  await flushPromises()
  return wrapper
}

function clickSave(wrapper: Awaited<ReturnType<typeof mountInline>>) {
  return wrapper.find('.inline-form-actions el-button').trigger('click')
}

describe('SubTableInlineForm — form-below-table Events', () => {
  it('keeps designer-hidden dates out of the DOM on first row bind', async () => {
    const wrapper = await mountInline()
    const html = wrapper.html()
    expect(html).toContain('Merchant Credit')
    expect(html).not.toContain('Merchant Credit Date')
    expect(html).not.toContain('Temporary Refund Date')
    wrapper.unmount()
  })

  it('runs Form onChange so Y hides merchant date and reveals temporary refund date', async () => {
    const wrapper = await mountInline({ formOptions: { onChange: FLAG_ON_CHANGE } })
    expect(wrapper.html()).toContain('Merchant Credit Date')
    expect(wrapper.html()).not.toContain('Temporary Refund Date')
    const flag = wrapper.find('[data-field="merchant_credit"]')
    expect(flag.exists()).toBe(true)
    await flag.trigger('click')
    await nextTick()
    await nextTick()
    const html = wrapper.html()
    expect(html).not.toContain('Merchant Credit Date')
    expect(html).toContain('Temporary Refund Date')
    wrapper.unmount()
  })

  it('lets onMounted reveal a statically hidden field', async () => {
    const wrapper = await mountInline({
      formOptions: { onMounted: '$FNX:\napi.hidden(false, "merchant_credit_date")' },
    })
    expect(wrapper.html()).toContain('Merchant Credit Date')
    expect(wrapper.html()).not.toContain('Temporary Refund Date')
    wrapper.unmount()
  })

  it('does not leak the previous row\'s visibility when switching rows', async () => {
    const wrapper = await mountInline({ formOptions: { onChange: FLAG_ON_CHANGE } })
    await wrapper.find('[data-field="merchant_credit"]').trigger('click')
    await nextTick()
    await nextTick()
    expect(wrapper.html()).toContain('Temporary Refund Date')

    await wrapper.setProps({
      currentRow: { id: 'row-2', merchant_credit: 'N' },
    } as never)
    await nextTick()
    await nextTick()
    expect(wrapper.html()).not.toContain('Temporary Refund Date')
    expect(wrapper.html()).toContain('Merchant Credit')
    wrapper.unmount()
  })

  it('does not emit update:row on bootstrap (would autosave the page-load N)', async () => {
    const wrapper = await mountInline()
    expect(wrapper.emitted('update:row')).toBeUndefined()
    wrapper.unmount()
  })

  it('keeps Y when parent re-renders the same row with a stale N snapshot', async () => {
    const wrapper = await mountInline({ formOptions: { onChange: FLAG_ON_CHANGE } })
    await wrapper.find('[data-field="merchant_credit"]').trigger('click')
    await nextTick()
    const last = wrapper.emitted('update:row')?.at(-1)?.[0] as Record<string, unknown>
    expect(last?.merchant_credit).toBe('Y')

    await wrapper.setProps({
      currentRow: { id: 'row-1', merchant_credit: 'N' },
    } as never)
    await nextTick()
    await nextTick()
    expect(wrapper.html()).toContain('Temporary Refund Date')
    await clickSave(wrapper)
    const saved = wrapper.emitted('update:row')?.at(-1)?.[0] as Record<string, unknown>
    expect(saved?.merchant_credit).toBe('Y')
    wrapper.unmount()
  })

  it('aborts Save when Form beforeSubmit returns false', async () => {
    const wrapper = await mountInline({
      formOptions: { beforeSubmit: '$FNX:\nreturn false' },
    })
    await clickSave(wrapper)
    expect(wrapper.emitted('save')).toBeUndefined()
    wrapper.unmount()
  })

  it('emits save when beforeSubmit is absent', async () => {
    const wrapper = await mountInline({ formOptions: {} })
    await clickSave(wrapper)
    expect(wrapper.emitted('save')).toHaveLength(1)
    wrapper.unmount()
  })

  it('keeps field-gated onChange visibility when a PK id appears on a row that had none', async () => {
    const fieldGated =
      '$FNX:\n'
      + 'if ($inject.field === "merchant_credit") {\n'
      + '  if ($inject.value === "Y") { $inject.api.hidden(false, "merchant_credit_date") }\n'
      + '  else { $inject.api.hidden(true, "merchant_credit_date") }\n'
      + '}\n'
    const wrapper = await mountInline({
      formOptions: { onChange: fieldGated },
      currentRow: { merchant_credit: 'N' },
    })
    expect(wrapper.html()).not.toContain('Merchant Credit Date')
    await wrapper.find('[data-field="merchant_credit"]').trigger('click')
    await nextTick()
    await nextTick()
    expect(wrapper.html()).toContain('Merchant Credit Date')

    await wrapper.setProps({
      currentRow: { id: 'pk-allocated', merchant_credit: 'N' },
    } as never)
    await nextTick()
    await nextTick()
    expect(wrapper.html()).toContain('Merchant Credit Date')
    wrapper.unmount()
  })

  it('does not rebootstrap when PK id appears on the same row_id', async () => {
    const wrapper = await mountInline({
      formOptions: { onChange: FLAG_ON_CHANGE },
      currentRow: { row_id: 'TRANS-1', merchant_credit: 'N' },
    })
    await wrapper.find('[data-field="merchant_credit"]').trigger('click')
    await nextTick()
    await nextTick()
    expect(wrapper.html()).toContain('Temporary Refund Date')

    await wrapper.setProps({
      currentRow: { row_id: 'TRANS-1', id: 'pk-new', merchant_credit: 'N' },
    } as never)
    await nextTick()
    await nextTick()
    expect(wrapper.html()).toContain('Temporary Refund Date')
    await clickSave(wrapper)
    const saved = wrapper.emitted('update:row')?.at(-1)?.[0] as Record<string, unknown>
    expect(saved?.merchant_credit).toBe('Y')
    wrapper.unmount()
  })

  it('runs Form events when formOptions arrive after the row is bound', async () => {
    const wrapper = await mountInline()
    expect(wrapper.html()).not.toContain('Merchant Credit Date')
    await wrapper.setProps({
      formOptions: { onMounted: '$FNX:\napi.hidden(false, "merchant_credit_date")' },
    } as never)
    await nextTick()
    await nextTick()
    expect(wrapper.html()).toContain('Merchant Credit Date')
    wrapper.unmount()
  })
})
