/**
 * Action 按钮自定义配色。
 *
 * Developer Workstation 的 Action Designer 把按钮颜色存成 `#RRGGBB`
 * （dw_action_definitions.button_color）。User Portal 拿到后直接覆盖 el-button 的
 * CSS 变量，让按钮真的显示成设计时选的那个颜色。
 *
 * 历史遗留值可能是 Element Plus 的具名类型（primary/success/...），那类值不是
 * 合法十六进制，走原来的 `getButtonType` 映射，不进入这里。
 */

const HEX_PATTERN = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$/

/** 只有合法的 `#RGB` / `#RRGGBB` 才按自定义颜色渲染。 */
export function isCustomButtonColor(color?: string | null): boolean {
  return HEX_PATTERN.test(String(color || '').trim())
}

function toRgb(color: string): [number, number, number] {
  let hex = color.trim().slice(1)
  if (hex.length === 3) hex = hex.split('').map(c => c + c).join('')
  return [
    parseInt(hex.slice(0, 2), 16),
    parseInt(hex.slice(2, 4), 16),
    parseInt(hex.slice(4, 6), 16),
  ]
}

function toHex(rgb: [number, number, number]): string {
  return '#' + rgb.map(v => Math.round(Math.min(255, Math.max(0, v))).toString(16).padStart(2, '0')).join('')
}

/** 与白色混合，ratio 为白色占比（Element Plus 的 hover 态做法）。 */
function tint(color: string, ratio: number): string {
  const rgb = toRgb(color)
  return toHex(rgb.map(v => v + (255 - v) * ratio) as [number, number, number])
}

/** 与黑色混合，ratio 为黑色占比（Element Plus 的 active 态做法）。 */
function shade(color: string, ratio: number): string {
  const rgb = toRgb(color)
  return toHex(rgb.map(v => v * (1 - ratio)) as [number, number, number])
}

/** 相对亮度（WCAG），用来决定文字用白还是深灰。 */
function luminance(color: string): number {
  const [r, g, b] = toRgb(color).map(v => {
    const channel = v / 255
    return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * 生成覆盖 el-button 配色的内联 CSS 变量；颜色非法或未配置时返回 undefined，
 * 由调用方回落到原有的 `type` 语义配色。
 */
export function actionButtonStyle(color?: string | null): Record<string, string> | undefined {
  if (!isCustomButtonColor(color)) return undefined
  const base = String(color).trim()
  const text = luminance(base) > 0.6 ? '#303133' : '#ffffff'
  return {
    '--el-button-bg-color': base,
    '--el-button-border-color': base,
    '--el-button-text-color': text,
    '--el-button-hover-bg-color': tint(base, 0.3),
    '--el-button-hover-border-color': tint(base, 0.3),
    '--el-button-hover-text-color': text,
    '--el-button-active-bg-color': shade(base, 0.1),
    '--el-button-active-border-color': shade(base, 0.1),
    '--el-button-active-text-color': text,
    '--el-button-disabled-bg-color': tint(base, 0.5),
    '--el-button-disabled-border-color': tint(base, 0.5),
    '--el-button-disabled-text-color': text,
  }
}
