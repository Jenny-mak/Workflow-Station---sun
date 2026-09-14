/**
 * Trim uniform background (typically dialog empty space) from a PNG.
 * Used after help-guide captures so Form Preview figures are form-sized, not overlay-tall.
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

function launchOpts() {
  const opts = { headless: true }
  if (process.env.PLAYWRIGHT_EXECUTABLE_PATH) {
    opts.executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
  } else if (process.env.PLAYWRIGHT_CHANNEL) {
    opts.channel = process.env.PLAYWRIGHT_CHANNEL
  }
  return opts
}

/**
 * @param {import('playwright').Page} page
 * @param {string} filePath
 * @param {{ pad?: number, threshold?: number }} [opts]
 * @returns {Promise<{ trimmed: boolean, width: number, height: number }>}
 */
export async function trimPngWhitespace(page, filePath, opts = {}) {
  const pad = opts.pad ?? 16
  const threshold = opts.threshold ?? 24
  const b64 = readFileSync(filePath).toString('base64')
  const result = await page.evaluate(
    async ({ b64: payload, pad: edge, threshold: delta }) => {
      const img = new Image()
      img.src = `data:image/png;base64,${payload}`
      await img.decode()
      const src = document.createElement('canvas')
      src.width = img.naturalWidth
      src.height = img.naturalHeight
      const ctx = src.getContext('2d', { willReadFrequently: true })
      ctx.drawImage(img, 0, 0)
      const { data, width, height } = ctx.getImageData(0, 0, src.width, src.height)
      const at = (x, y) => (y * width + x) * 4
      const br = at(width - 1, height - 1)
      const bgR = data[br]
      const bgG = data[br + 1]
      const bgB = data[br + 2]
      const isBg = (i) => {
        if (data[i + 3] < 8) return true
        return (
          Math.abs(data[i] - bgR) + Math.abs(data[i + 1] - bgG) + Math.abs(data[i + 2] - bgB) <=
          delta
        )
      }
      let minX = width
      let minY = height
      let maxX = 0
      let maxY = 0
      let found = false
      for (let y = 0; y < height; y += 1) {
        for (let x = 0; x < width; x += 1) {
          if (isBg(at(x, y))) continue
          found = true
          if (x < minX) minX = x
          if (y < minY) minY = y
          if (x > maxX) maxX = x
          if (y > maxY) maxY = y
        }
      }
      if (!found) {
        return { trimmed: false, width, height, png: null }
      }
      minX = Math.max(0, minX - edge)
      minY = Math.max(0, minY - edge)
      maxX = Math.min(width - 1, maxX + edge)
      maxY = Math.min(height - 1, maxY + edge)
      const w = maxX - minX + 1
      const h = maxY - minY + 1
      if (w >= width - 2 && h >= height - 2) {
        return { trimmed: false, width, height, png: null }
      }
      const out = document.createElement('canvas')
      out.width = w
      out.height = h
      out.getContext('2d').drawImage(src, minX, minY, w, h, 0, 0, w, h)
      const png = out.toDataURL('image/png').slice('data:image/png;base64,'.length)
      return { trimmed: true, width: w, height: h, png }
    },
    { b64, pad, threshold },
  )
  if (result.png) {
    writeFileSync(filePath, Buffer.from(result.png, 'base64'))
  }
  return { trimmed: result.trimmed, width: result.width, height: result.height }
}

export async function trimPngFiles(filePaths, opts = {}) {
  const browser = await chromium.launch(launchOpts())
  const page = await (await browser.newContext()).newPage()
  const reports = []
  try {
    for (const filePath of filePaths) {
      const report = await trimPngWhitespace(page, filePath, opts)
      reports.push({ filePath, ...report })
      console.log(
        `${report.trimmed ? 'trimmed' : 'unchanged'} ${filePath} → ${report.width}×${report.height}`,
      )
    }
  } finally {
    await browser.close()
  }
  return reports
}

const invokedAsCli = process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])

if (invokedAsCli) {
  const files = process.argv.slice(2)
  if (!files.length) {
    console.error('Usage: node trim-png-whitespace.mjs <png> [png...]')
    process.exit(1)
  }
  await trimPngFiles(files.map((f) => resolve(f)))
}
