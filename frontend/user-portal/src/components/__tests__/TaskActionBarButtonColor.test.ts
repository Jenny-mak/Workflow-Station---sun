import { afterEach, describe, expect, it } from 'vitest'
import { h } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TaskActionBar from '@/components/tasks/TaskActionBar.vue'
import type { TaskActionInfo } from '@/api/task'
import { actionButtonStyle, isCustomButtonColor } from '@/utils/actionButtonColor'

let wrapper: VueWrapper | null = null

function action(overrides: Partial<TaskActionInfo> = {}): TaskActionInfo {
  return {
    actionId: '1',
    actionName: 'Approve',
    actionType: 'APPROVE',
    ...overrides,
  }
}

function mountActionBar(actions: TaskActionInfo[]) {
  wrapper = mount(TaskActionBar, {
    props: {
      isCompletedTask: false,
      showImplicitSaveAction: false,
      savingTaskForm: false,
      actions,
      canDelegate: true,
      getButtonType: () => 'primary',
      getIconComponent: () => h('span'),
      getActionLabel: (a: TaskActionInfo) => a.actionName,
      global: undefined,
    } as never,
    global: {
      plugins: [ElementPlus],
      mocks: { $t: (key: string) => key, $router: { back: () => {} } },
    },
  })
  return wrapper
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('actionButtonColor util', () => {
  it('accepts #RGB and #RRGGBB, rejects anything else', () => {
    expect(isCustomButtonColor('#1F5C4A')).toBe(true)
    expect(isCustomButtonColor('#abc')).toBe(true)
    expect(isCustomButtonColor('primary')).toBe(false)
    expect(isCustomButtonColor('')).toBe(false)
    expect(isCustomButtonColor(undefined)).toBe(false)
    expect(isCustomButtonColor('#12345')).toBe(false)
  })

  it('paints the picked colour and derives hover / active shades', () => {
    const style = actionButtonStyle('#1F5C4A')!
    expect(style['--el-button-bg-color']).toBe('#1F5C4A')
    expect(style['--el-button-border-color']).toBe('#1F5C4A')
    // dark colour -> white label
    expect(style['--el-button-text-color']).toBe('#ffffff')
    // hover lightens, active darkens
    expect(style['--el-button-hover-bg-color']).toBe('#628d80')
    expect(style['--el-button-active-bg-color']).toBe('#1c5343')
  })

  it('uses dark text on a light colour so the label stays readable', () => {
    expect(actionButtonStyle('#FFE082')!['--el-button-text-color']).toBe('#303133')
  })

  it('returns nothing for legacy named colours so the type mapping still applies', () => {
    expect(actionButtonStyle('primary')).toBeUndefined()
    expect(actionButtonStyle(null)).toBeUndefined()
  })
})

describe('TaskActionBar designer-picked button colour', () => {
  it('renders the Action button in the colour configured in the Action Designer', () => {
    const w = mountActionBar([action({ buttonColor: '#1F5C4A' })])
    const button = w.find('.right-actions button')
    expect(button.attributes('style')).toContain('--el-button-bg-color: #1F5C4A')
    expect(button.attributes('style')).toContain('--el-button-text-color: #ffffff')
  })

  it('leaves an Action with no colour on its built-in look', () => {
    const w = mountActionBar([action({ actionType: 'URGE', actionName: 'Urge' })])
    const button = w.find('.right-actions button')
    expect(button.attributes('style')).toBeUndefined()
    expect(button.classes()).toContain('el-button--warning')
  })
})
