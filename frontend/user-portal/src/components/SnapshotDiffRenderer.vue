<template>
  <div class="snapshot-diff-renderer">
    <SnapshotDiffTable
      :rows="diffRows"
      :show-live-values="showLiveValues"
      :format-value="formatValue"
    />
    <div
      v-for="section in subTableSections"
      :key="section.storeKey"
      class="snapshot-sub-table"
    >
      <div class="snapshot-sub-table-title">
        {{ t('changeHistory.subTable') }}: {{ section.tableLabel || t('changeHistory.subTable') }}
      </div>
      <el-table
        :data="section.snapshotRows"
        border
        stripe
        size="small"
        :empty-text="t('snapshotDiff.noSubTableRows')"
      >
        <el-table-column
          v-for="col in section.columns"
          :key="col.field"
          :label="col.label"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ formatSubTableCell(row, col) }}
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormField, FormTab } from './formRendererHelpers'
import SnapshotDiffTable from './SnapshotDiffTable.vue'
import {
  collectSnapshotDiffFields,
  computeDiffRows,
  formatSnapshotDisplayValue,
  type DiffRow,
} from './snapshotDiffHelpers'
import {
  buildSnapshotSubTableSections,
  formatSnapshotSubTableCell,
  type SnapshotSubTableBindingSource,
  type SnapshotSubTableColumn,
} from './snapshotDiffSubTables'
import {
  applySensitiveMask,
  isSensitiveMaskActive,
} from '@/utils/sensitiveMask'

const { t } = useI18n()

interface Props {
  snapshotValues: Record<string, unknown>
  liveValues: Record<string, unknown>
  fields: FormField[]
  tabs?: FormTab[]
  fieldsAfterTabs?: FormField[]
  subTableBindings?: SnapshotSubTableBindingSource[]
  showLiveValues: boolean
}

const props = withDefaults(defineProps<Props>(), {
  showLiveValues: true,
  subTableBindings: () => [],
})

const comparableFields = computed(() =>
  collectSnapshotDiffFields(props.fields, props.tabs, props.fieldsAfterTabs)
)

const diffRows = computed<DiffRow[]>(() =>
  computeDiffRows(
    props.snapshotValues,
    props.liveValues,
    props.fields,
    props.tabs,
    props.fieldsAfterTabs,
  )
)

const subTableSections = computed(() =>
  buildSnapshotSubTableSections(
    props.fields,
    props.snapshotValues,
    props.subTableBindings,
    props.tabs,
    props.fieldsAfterTabs,
  )
)

const fieldByKey = computed(() => {
  const map = new Map<string, FormField>()
  for (const f of comparableFields.value) {
    map.set(f.key, f)
  }
  return map
})

const maskByKey = computed(() => {
  const map = new Map<string, NonNullable<FormField['sensitiveMask']>>()
  for (const f of comparableFields.value) {
    if (f.sensitiveMask?.enabled) map.set(f.key, f.sensitiveMask)
  }
  return map
})

function formatValue(value: unknown, fieldKey?: string): string {
  const field = fieldKey ? fieldByKey.value.get(fieldKey) : undefined
  const s = formatSnapshotDisplayValue(value, field)
  const cfg = fieldKey ? maskByKey.value.get(fieldKey) : undefined
  if (isSensitiveMaskActive(cfg) && s !== '-') return applySensitiveMask(s, cfg!)
  return s
}

function formatSubTableCell(row: Record<string, unknown>, col: SnapshotSubTableColumn): string {
  return formatSnapshotSubTableCell(row, col.field, col.type)
}
</script>

<style scoped lang="scss">
.snapshot-diff-renderer {
  width: 100%;

  .snapshot-sub-table {
    margin-top: 16px;
  }

  .snapshot-sub-table-title {
    margin-bottom: 8px;
    font-weight: 500;
    color: var(--text-primary, #303133);
  }
}
</style>
