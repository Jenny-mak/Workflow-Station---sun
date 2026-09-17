import { computed, reactive, ref } from 'vue'
import type { ComputedRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import type { useFunctionUnitStore } from '@/stores/functionUnit'
import { normalizeTags, collectAvailableTags } from '@/utils/tagStorage'

type FunctionUnitStore = ReturnType<typeof useFunctionUnitStore>

interface UseFunctionUnitSettingsOptions {
  functionUnitId: ComputedRef<number>
  store: FunctionUnitStore
}

/**
 * Settings dialog: name/description/icon/tags form and persistence. The dialog also hosts the
 * Requirements / Design document tabs, so closing goes through the view's dirty-check instead of here.
 */
export function useFunctionUnitSettings(options: UseFunctionUnitSettingsOptions) {
  const { functionUnitId, store } = options
  const { t } = useI18n()

  const saving = ref(false)
  const showEditDialog = ref(false)
  const settingsTab = ref<'basic' | 'REQUIREMENTS' | 'DESIGN'>('basic')

  const editForm = reactive({
    name: '',
    description: '',
    iconId: undefined as number | null | undefined,
    tags: [] as string[]
  })

  const availableTags = computed(() => {
    const fromCurrent = store.current ? [store.current] : []
    const fromList = store.list.filter(item => item.id !== functionUnitId.value)
    const fromServer = (store.allTags ?? []).map((t: string) => ({ tags: [t] }))
    return collectAvailableTags([...fromServer, ...fromList, ...fromCurrent])
  })

  function openEditDialog() {
    editForm.name = store.current?.name || ''
    editForm.description = store.current?.description || ''
    editForm.iconId = store.current?.icon?.id ?? undefined
    editForm.tags = [...normalizeTags(store.current?.tags)]
    settingsTab.value = 'basic'
    showEditDialog.value = true
  }

  /** @returns whether the basic info was saved */
  async function handleSaveEdit(): Promise<boolean> {
    if (!editForm.name.trim()) {
      ElMessage.warning(t('functionUnit.enterName'))
      return false
    }
    saving.value = true
    try {
      const desc = editForm.description?.trim()
      await store.update(functionUnitId.value, {
        name: editForm.name.trim(),
        description: desc || undefined,
        iconId: editForm.iconId ?? undefined,
        tags: normalizeTags(editForm.tags),
      })
      ElMessage.success(t('functionUnit.saveSuccess'))
      await store.fetchById(functionUnitId.value)
      store.fetchAllTags()
      return true
    } catch (e: unknown) {
      const message = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
      ElMessage.error(message || t('functionUnit.saveFailed'))
      return false
    } finally {
      saving.value = false
    }
  }

  return { saving, showEditDialog, settingsTab, editForm, availableTags, openEditDialog, handleSaveEdit }
}
