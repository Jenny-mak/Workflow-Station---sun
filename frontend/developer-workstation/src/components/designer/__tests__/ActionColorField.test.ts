import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import ActionColorField from '@/components/designer/action-designer/ActionColorField.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: { en: { action: { buttonColorPlaceholder: 'RRGGBB' } } },
})

let wrapper: VueWrapper | null = null

function mountField(modelValue?: string | null) {
  wrapper = mount(ActionColorField, {
    props: { modelValue },
    global: { plugins: [ElementPlus, i18n] },
  })
  return wrapper
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('ActionColorField', () => {
  it('shows the stored hex without the # prefix (the prefix is the input addon)', () => {
    const w = mountField('#1f5c4a')
    expect((w.find('input.el-input__inner').element as HTMLInputElement).value).toBe('1F5C4A')
  })

  it('emits a #RRGGBB value when hex digits are typed', async () => {
    const w = mountField('')
    await w.find('input.el-input__inner').setValue('1f5c4a')
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual(['#1F5C4A'])
  })

  it('drops non-hex characters and a pasted # instead of storing junk', async () => {
    const w = mountField('')
    await w.find('input.el-input__inner').setValue('#zz1f5c4a99')
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual(['#1F5C4A'])
  })

  /**
   * 回归：输入框是受控的，若只按「完整合法 hex」回显，每敲一个字符都会被清空，
   * 最终只存下第一个字符（实测存成了 `#8`）。中间值必须能留在框里。
   */
  it('keeps a partially typed value visible so the hex can be typed character by character', async () => {
    const w = mountField('')
    const input = w.find('input.el-input__inner')
    let current = ''
    for (const char of '7B4EA8') {
      // 模拟父组件把上一次 emit 的值写回 v-model
      await w.setProps({ modelValue: current })
      const shown = (input.element as HTMLInputElement).value
      await input.setValue(shown + char)
      current = (w.emitted('update:modelValue')?.at(-1) as string[])[0]
    }
    expect(current).toBe('#7B4EA8')
    await w.setProps({ modelValue: current })
    expect((input.element as HTMLInputElement).value).toBe('7B4EA8')
  })

  it('drops a half-typed value on blur instead of storing a colour the portal cannot read', async () => {
    const w = mountField('#7B4E')
    await w.find('input.el-input__inner').trigger('blur')
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([''])
  })

  it('keeps a complete value on blur', async () => {
    const w = mountField('#7B4EA8')
    await w.find('input.el-input__inner').trigger('blur')
    expect(w.emitted('update:modelValue')).toBeUndefined()
  })

  it('emits an empty value when the field is cleared', async () => {
    const w = mountField('#1F5C4A')
    await w.find('input.el-input__inner').setValue('')
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([''])
  })

  /**
   * Legacy rows may hold an Element Plus type name (e.g. `primary`). It is not a hex
   * colour, so the input stays empty — but mounting must not emit and overwrite it.
   */
  it('does not rewrite a legacy named colour on load', () => {
    const w = mountField('primary')
    expect((w.find('input.el-input__inner').element as HTMLInputElement).value).toBe('')
    expect(w.emitted('update:modelValue')).toBeUndefined()
  })

  it('opens a palette button next to the input', () => {
    const w = mountField('#1F5C4A')
    expect(w.find('.el-color-picker').exists()).toBe(true)
  })
})
