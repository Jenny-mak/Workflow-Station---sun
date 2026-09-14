import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function shapeIds(): string[] {
  const src = readFileSync(new URL('./formCtlBasic.ts', import.meta.url), 'utf8')
  return [...src.matchAll(/shape\('(\w+)'/g)].map((match) => match[1])
}

function localeSrc(name: string): string {
  return readFileSync(new URL(`./i18n/locales/${name}`, import.meta.url), 'utf8')
}

test('every shapeSample has eventShapeHint in en / zh-CN / zh-TW', () => {
  const ids = shapeIds()
  assert.ok(ids.length > 0)
  const files = [
    ['en', localeSrc('formCtl.en.ts')],
    ['zh-CN', localeSrc('formCtl.zh-CN.ts')],
    ['zh-TW', localeSrc('formCtl.zh-TW.ts')],
  ] as const
  for (const id of ids) {
    const needle = new RegExp(`${id}:\\s*\\{[\\s\\S]*?eventShapeHint:`)
    for (const [locale, src] of files) {
      assert.ok(needle.test(src), `${locale} formCtl.${id}.eventShapeHint`)
    }
  }
})

test('home-card summaries do not contain wiki tokens', () => {
  for (const name of ['formCtl.en.ts', 'formCtl.zh-CN.ts', 'formCtl.zh-TW.ts'] as const) {
    const src = localeSrc(name)
    for (const match of src.matchAll(/summary:\s*'([^']*)'/g)) {
      assert.equal(
        match[1].includes('[['),
        false,
        `${name} summary must stay plain text for HelpHome: ${match[1]}`,
      )
    }
  }
})
