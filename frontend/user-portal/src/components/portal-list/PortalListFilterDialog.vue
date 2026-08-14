<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  formatPortalListFilterDays,
  parsePortalListFilterDays,
  type PortalListColumnFilter,
  type PortalListColumnMeta,
  type PortalListFilterOperator,
  type PortalListFilterOption,
} from '@/utils/portalListGridRuntime'

const props = defineProps<{
  modelValue: boolean
  title: string
  /** Current column filter; operator/value shape shared with Views runtime. */
  initial?: { operator: string; value: string } | null
  /** Column capability from the list's `/columns` endpoint; omitted → plain text column. */
  column?: PortalListColumnMeta | null
  /** Choices for an ENUM column, or the current search results for a USER column. */
  options?: PortalListFilterOption[]
  optionsLoading?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  apply: [filter: PortalListColumnFilter]
  clear: []
  /** USER columns search people remotely; the owning view runs the query. */
  search: [keyword: string]
}>()

const { t } = useI18n()

const OPERATOR_LABEL_KEY: Record<PortalListFilterOperator, string> = {
  contains: 'mainTableView.filterOpContains',
  eq: 'mainTableView.filterOpEquals',
  ne: 'mainTableView.filterOpNotEquals',
  startsWith: 'mainTableView.filterOpStartsWith',
  endsWith: 'mainTableView.filterOpEndsWith',
  notContains: 'mainTableView.filterOpNotContains',
  isNotNull: 'mainTableView.filterOpHasData',
  isNull: 'mainTableView.filterOpNoData',
  on: 'mainTableView.filterOpOn',
  before: 'mainTableView.filterOpBefore',
  after: 'mainTableView.filterOpAfter',
  between: 'mainTableView.filterOpBetween',
}

const TEXT_OPERATORS: PortalListFilterOperator[] = [
  'contains', 'eq', 'ne', 'startsWith', 'endsWith', 'notContains', 'isNotNull', 'isNull',
]

const draft = reactive<{ operator: PortalListFilterOperator; value: string }>({
  operator: 'contains',
  value: '',
})

const kind = computed(() => props.column?.kind ?? 'TEXT')

const operators = computed<PortalListFilterOperator[]>(() =>
  props.column?.operators?.length ? props.column.operators : TEXT_OPERATORS,
)

const needsValue = computed(() => draft.operator !== 'isNull' && draft.operator !== 'isNotNull')
const isPickList = computed(() => kind.value === 'ENUM' || kind.value === 'USER')
const isDateRange = computed(() => draft.operator === 'between')

/** el-date-picker works in days; the filter value stays the shared `operator/value` string. */
const dayValue = computed<string | null>({
  get: () => parsePortalListFilterDays(draft.operator, draft.value)[0] ?? null,
  set: (day) => {
    draft.value = day || ''
  },
})

const dayRangeValue = computed<string[] | null>({
  get: () => {
    const days = parsePortalListFilterDays('between', draft.value)
    return days.length === 2 ? days : null
  },
  set: (range) => {
    draft.value = range?.length === 2 ? formatPortalListFilterDays(range) : ''
  },
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    const allowed = operators.value
    const requested = props.initial?.operator as PortalListFilterOperator | undefined
    draft.operator = requested && allowed.includes(requested) ? requested : allowed[0]
    draft.value = requested === draft.operator ? props.initial?.value || '' : ''
    if (kind.value === 'USER') emit('search', '')
  },
)

/** Switching between day / range / free text makes the previously picked value meaningless. */
function onOperatorChange(next: PortalListFilterOperator) {
  const prev = draft.operator
  draft.operator = next
  const dayLike = (op: PortalListFilterOperator) => op === 'on' || op === 'before' || op === 'after'
  if (!(dayLike(next) && dayLike(prev))) draft.value = ''
}

function close() {
  emit('update:modelValue', false)
}

function onApply() {
  emit('apply', { operator: draft.operator, value: draft.value })
  close()
}

function onClear() {
  emit('clear')
  close()
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="420px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-form-item :label="t('mainTableView.filterOperator')">
        <el-select
          :model-value="draft.operator"
          style="width: 100%;"
          @change="onOperatorChange"
        >
          <el-option
            v-for="op in operators"
            :key="op"
            :label="t(OPERATOR_LABEL_KEY[op])"
            :value="op"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="needsValue"
        :label="t('mainTableView.filterValue')"
      >
        <el-date-picker
          v-if="kind === 'DATETIME' && isDateRange"
          v-model="dayRangeValue"
          type="daterange"
          value-format="YYYY-MM-DD"
          :start-placeholder="t('mainTableView.filterDateStart')"
          :end-placeholder="t('mainTableView.filterDateEnd')"
          style="width: 100%;"
        />
        <el-date-picker
          v-else-if="kind === 'DATETIME'"
          v-model="dayValue"
          type="date"
          value-format="YYYY-MM-DD"
          :placeholder="t('mainTableView.filterPickDate')"
          style="width: 100%;"
        />
        <el-select
          v-else-if="isPickList"
          v-model="draft.value"
          filterable
          clearable
          :remote="kind === 'USER'"
          :remote-method="kind === 'USER' ? (q: string) => emit('search', q) : undefined"
          :loading="optionsLoading"
          :placeholder="t('mainTableView.filterPickValue')"
          style="width: 100%;"
        >
          <el-option
            v-for="option in options || []"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-input
          v-else
          v-model="draft.value"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="onClear">
        {{ t('common.clear') }}
      </el-button>
      <el-button
        type="primary"
        @click="onApply"
      >
        {{ t('common.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>
