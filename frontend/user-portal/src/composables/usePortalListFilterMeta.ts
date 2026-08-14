import { computed, ref, type Ref } from 'vue'
import { debounce } from 'lodash-es'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { userApi } from '@/api/user'
import { resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'
import {
  pruneUnsupportedFilters,
  type PortalListColumnMeta,
  type PortalListColumnState,
  type PortalListFilterOption,
} from '@/utils/portalListGridRuntime'

const USER_SEARCH_DEBOUNCE_MS = 300

export interface PortalListFilterMetaOptions {
  /** The list's own `/columns` endpoint. */
  loadColumns: () => Promise<PortalListColumnMeta[]>
  /** Column state whose persisted filters must stay compatible with the declarations. */
  state: PortalListColumnState
  /** Column whose filter dialog is currently open. */
  openField: Ref<{ field: string; label: string } | null>
  /** Localized label for an ENUM code; return the code itself when there is no translation. */
  enumLabel: (field: string, code: string) => string
}

/**
 * Backs a list's header filter dialog with the backend's column declarations: which kind
 * each column is, which operators it accepts, and the choices to offer for ENUM / USER
 * columns. Keeping this on the server side is what stops the dialog from offering a filter
 * the query would reject.
 */
export function usePortalListFilterMeta(options: PortalListFilterMetaOptions) {
  const { t } = useI18n()
  const columns = ref<PortalListColumnMeta[]>([])
  const userOptions = ref<PortalListFilterOption[]>([])
  const optionsLoading = ref(false)
  let loaded = false

  /**
   * Fetch the declarations once and drop persisted filters they no longer allow.
   * Callers await this before their first query, so the dropped filter never reaches it.
   *
   * FALLBACK(ux): a failed fetch is surfaced to the user and leaves the dialog on its
   * plain-text operator set — the list stays usable with fewer filter types rather than
   * blocking the page. A later call retries.
   */
  async function ensureColumns(): Promise<void> {
    if (loaded) return
    try {
      columns.value = await options.loadColumns()
      loaded = true
      pruneUnsupportedFilters(options.state, columns.value)
    } catch (error) {
      ElMessage.error(resolveUserFacingHttpMessage(error, t))
    }
  }

  function metaFor(field: string): PortalListColumnMeta | null {
    return columns.value.find(c => c.field === field) ?? null
  }

  function isDateColumn(field: string): boolean {
    return metaFor(field)?.kind === 'DATETIME'
  }

  const openColumn = computed<PortalListColumnMeta | null>(() => {
    const field = options.openField.value?.field
    return field ? metaFor(field) : null
  })

  const filterOptions = computed<PortalListFilterOption[]>(() => {
    const column = openColumn.value
    if (!column) return []
    if (column.kind === 'ENUM') {
      return column.options.map(code => ({ value: code, label: options.enumLabel(column.field, code) }))
    }
    return column.kind === 'USER' ? userOptions.value : []
  })

  async function runUserSearch(keyword: string): Promise<void> {
    const field = options.openField.value?.field
    const selected = field ? options.state.filters[field]?.value ?? '' : ''
    optionsLoading.value = true
    try {
      const found = await userApi.searchUsers(keyword || '')
      const picked = found.map(u => ({ value: u.id, label: u.name || u.username || u.id }))
      // Keep the person the filter is already set to visible, even outside the search hits.
      if (selected && !picked.some(o => o.value === selected)) {
        const current = await userApi.getUserSummary(selected)
        picked.unshift({ value: selected, label: current?.name || selected })
      }
      userOptions.value = picked
    } catch (error) {
      userOptions.value = []
      ElMessage.error(resolveUserFacingHttpMessage(error, t) || t('task.searchUserFailed'))
    } finally {
      optionsLoading.value = false
    }
  }

  const onSearch = debounce((keyword: string) => {
    if (openColumn.value?.kind !== 'USER') return
    void runUserSearch(keyword)
  }, USER_SEARCH_DEBOUNCE_MS)

  function dispose() {
    onSearch.cancel()
  }

  return { columns, ensureColumns, metaFor, isDateColumn, openColumn, filterOptions, optionsLoading, onSearch, dispose }
}
