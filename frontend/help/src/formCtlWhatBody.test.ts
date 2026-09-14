import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const DRAG_OPEN =
  /^(Drag \w[\w-]* from (?:Basic|Extend)\.|从 (?:Basic|Extend) 拖入|從 (?:Basic|Extend) 拖入)/

function localeSrc(name: string): string {
  return readFileSync(new URL(`./i18n/locales/${name}`, import.meta.url), 'utf8')
}

function whatBodies(src: string): string[] {
  return [...src.matchAll(/whatBody:\s*\n\s*'([^']*)'/g)].map((match) => match[1])
}

const FILES = [
  'formCtl.en.ts',
  'formCtl.zh-CN.ts',
  'formCtl.zh-TW.ts',
  'formCtlExtend.en.ts',
  'formCtlExtend.zh-CN.ts',
  'formCtlExtend.zh-TW.ts',
] as const

test('formCtl whatBody explains the control, not drag-from-palette', () => {
  for (const name of FILES) {
    const bodies = whatBodies(localeSrc(name))
    assert.ok(bodies.length > 0, `${name} has whatBody`)
    for (const body of bodies) {
      assert.equal(
        DRAG_OPEN.test(body),
        false,
        `${name} whatBody must not open with drag-from-palette: ${body.slice(0, 80)}`,
      )
    }
  }
})
