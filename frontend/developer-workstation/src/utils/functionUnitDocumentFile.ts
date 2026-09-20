import type { FunctionUnitDocument, FunctionUnitDocumentType } from '@/api/functionUnitDocument'
import { documentVersionLabel } from './functionUnitDocumentSource'

/**
 * Requirements / Function Unit Design 文档的文件导入与下载（Markdown 纯文本）。
 * 导入只把文件内容载入编辑器草稿，仍由用户预览后走同一条保存链路（版本比对、冲突处理不变）。
 */

/** 与后端 FunctionUnitDocumentService.MAX_CONTENT_BYTES 一致；后端仍是最终裁决 */
export const DOCUMENT_MAX_BYTES = 200 * 1024

export const DOCUMENT_FILE_ACCEPT = '.md,.markdown,.txt'

const ACCEPTED_EXTENSIONS = DOCUMENT_FILE_ACCEPT.split(',')

export type DocumentFileError = 'UNSUPPORTED_TYPE' | 'TOO_LARGE' | 'EMPTY'

export class DocumentFileRejected extends Error {
  constructor(public readonly reason: DocumentFileError) {
    super(reason)
  }
}

/** 读取用户选中的文件；扩展名不对、超限、空文件都显式拒绝，不做静默截断。 */
export async function readDocumentFile(file: File): Promise<string> {
  const name = file.name.toLowerCase()
  if (!ACCEPTED_EXTENSIONS.some(ext => name.endsWith(ext))) {
    throw new DocumentFileRejected('UNSUPPORTED_TYPE')
  }
  if (file.size > DOCUMENT_MAX_BYTES) throw new DocumentFileRejected('TOO_LARGE')
  // 去掉 UTF-8 BOM、统一换行：Windows 记事本保存的文件否则会在 diff 里整篇标红
  const text = (await readAsText(file)).replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n')
  if (!text.trim()) throw new DocumentFileRejected('EMPTY')
  return text
}

export function readAsText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob, 'utf-8')
  })
}

const TYPE_SLUG: Record<FunctionUnitDocumentType, string> = {
  REQUIREMENTS: 'requirements',
  DESIGN: 'design'
}

/** `<功能单元名>-requirements-v1.2.md`；名称里不能进文件名的字符换成 `_`，未保存过的文档不带版本段 */
export function documentFileName(
  functionUnitName: string | undefined,
  type: FunctionUnitDocumentType,
  saved: Pick<FunctionUnitDocument, 'version' | 'majorVersion' | 'minorVersion'> | null
): string {
  const safeName = (functionUnitName ?? '').trim().replace(/[\\/:*?"<>|\s]+/g, '_').replace(/^_+|_+$/g, '')
  return [safeName, TYPE_SLUG[type], documentVersionLabel(saved)].filter(Boolean).join('-') + '.md'
}

export function downloadDocumentFile(fileName: string, content: string): void {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  URL.revokeObjectURL(url)
}
