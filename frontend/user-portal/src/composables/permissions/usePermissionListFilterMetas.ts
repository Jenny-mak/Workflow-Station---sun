import type { Ref } from 'vue'
import { permissionApi } from '@/api/permission'
import {
  usePortalListFilterMeta,
  unwrapPortalListColumns,
} from '@/composables/usePortalListFilterMeta'
import type { PortalListColumnState } from '@/utils/portalListGridRuntime'

export interface PermissionListFilterTab {
  state: PortalListColumnState
  openField: Ref<{ field: string; label: string } | null>
}

/**
 * Four permission lists share one `/permissions/requests/columns` declaration.
 * One in-flight fetch is reused so mounting all four tabs does not hit the endpoint four times.
 */
export function usePermissionListFilterMetas(
  tabs: {
    pending: PermissionListFilterTab
    history: PermissionListFilterTab
    apprPending: PermissionListFilterTab
    apprHistory: PermissionListFilterTab
  },
  enumLabel: (field: string, code: string) => string,
) {
  let inflight: Promise<ReturnType<typeof unwrapPortalListColumns>> | null = null
  const loadColumns = () => {
    if (!inflight) {
      inflight = permissionApi.getRequestColumns().then(unwrapPortalListColumns)
    }
    return inflight
  }

  const pending = usePortalListFilterMeta({
    loadColumns, state: tabs.pending.state, openField: tabs.pending.openField, enumLabel,
  })
  const history = usePortalListFilterMeta({
    loadColumns, state: tabs.history.state, openField: tabs.history.openField, enumLabel,
  })
  const apprPending = usePortalListFilterMeta({
    loadColumns, state: tabs.apprPending.state, openField: tabs.apprPending.openField, enumLabel,
  })
  const apprHistory = usePortalListFilterMeta({
    loadColumns, state: tabs.apprHistory.state, openField: tabs.apprHistory.openField, enumLabel,
  })

  async function ensureAll(): Promise<void> {
    await Promise.all([
      pending.ensureColumns(),
      history.ensureColumns(),
      apprPending.ensureColumns(),
      apprHistory.ensureColumns(),
    ])
  }

  function dispose(): void {
    pending.dispose()
    history.dispose()
    apprPending.dispose()
    apprHistory.dispose()
  }

  return { pending, history, apprPending, apprHistory, ensureAll, dispose }
}
