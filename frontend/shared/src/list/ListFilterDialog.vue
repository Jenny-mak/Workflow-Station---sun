<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  isCompleteFilter,
  operatorLabelKey,
  operatorNeedsRange,
  operatorNeedsValue,
  type ListColumnFilter,
  type ListColumnMeta,
  type ListColumnOption,
} from './columnMeta'
import { resolveUserFilterValue } from './listFilterUserValue'

const props = defineProps<{
  visible: boolean
  column: ListColumnMeta | null
  filter: ListColumnFilter | null
  /**
   * Host-owned people search for USER columns (name or staff ID). The dialog never
   * calls an API itself — shared list chrome does not know users.
   */
  remoteSearch?: (query: string) => Promise<ListColumnOption[]>
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  apply: [filter: ListColumnFilter]
  clear: []
}>()

const { t } = useI18n()

const draft = ref<ListColumnFilter>({ operator: '', value: '', value2: '' })
const remoteHits = ref<ListColumnOption[]>([])
const remoteLoading = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | null = null
let searchGen = 0

const dialogVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

watch(
  () => props.visible,
  (open) => {
    if (!open) return
    const operators = props.column?.operators ?? []
    if (!props.column || operators.length === 0) {
      throw new Error(
        `ListFilterDialog opened for ${props.column?.field ?? 'unknown column'} without an operator whitelist — the column declaration is broken`,
      )
    }
    const closed = props.column.kind === 'ENUM' || props.column.kind === 'BOOLEAN'
    if (closed && (props.column.options?.length ?? 0) === 0) {
      throw new Error(
        `ListFilterDialog opened for ${props.column.field} (${props.column.kind}) without options — the column declaration is broken`,
      )
    }
    if (props.column.kind === 'USER' && (props.column.options?.length ?? 0) === 0 && !props.remoteSearch) {
      throw new Error(
        `ListFilterDialog opened for ${props.column.field} (USER) without a people search — the host must supply remoteSearch`,
      )
    }
    draft.value = {
      operator:
        props.filter && operators.includes(props.filter.operator)
          ? props.filter.operator
          : operators[0],
      value: props.filter?.value ?? '',
      value2: props.filter?.value2 ?? '',
    }
    remoteHits.value = []
    if (props.column.kind === 'USER' && props.remoteSearch && draft.value.value) {
      runUserSearch(draft.value.value)
    }
  },
  { immediate: true },
)

const needsValue = computed(() => operatorNeedsValue(draft.value.operator))
const needsRange = computed(() => operatorNeedsRange(draft.value.operator))
const hasOptions = computed(() => (props.column?.options?.length ?? 0) > 0)
const isUser = computed(() => props.column?.kind === 'USER')
const valueInputType = computed(() => {
  if (props.column?.kind === 'NUMBER') return 'number'
  return 'text'
})
const showOperator = computed(() => (props.column?.operators?.length ?? 0) > 1)
const isDate = computed(() => props.column?.kind === 'DATETIME')
const canApply = computed(() => {
  if (isUser.value && needsValue.value) {
    return resolveUserFilterValue(draft.value.value, remoteHits.value, {
      appliedValue: props.filter?.value,
      loading: remoteLoading.value,
    }) != null
  }
  return isCompleteFilter(draft.value)
})

function onUserSearch(query: string) {
  if (searchTimer != null) {
    clearTimeout(searchTimer)
  }
  searchTimer = setTimeout(() => {
    searchTimer = null
    runUserSearch(query)
  }, 300)
}

function runUserSearch(query: string) {
  if (!props.remoteSearch) return
  const trimmed = query.trim()
  if (!trimmed) {
    searchGen += 1
    remoteHits.value = []
    remoteLoading.value = false
    return
  }
  const gen = ++searchGen
  remoteLoading.value = true
  void props.remoteSearch(trimmed).then(
    (hits) => {
      if (gen === searchGen) {
        remoteHits.value = hits
      }
    },
  ).finally(() => {
    if (gen === searchGen) {
      remoteLoading.value = false
    }
  })
}

onBeforeUnmount(() => {
  if (searchTimer != null) {
    clearTimeout(searchTimer)
  }
})

function appliedUserValue(): string {
  const resolved = resolveUserFilterValue(draft.value.value, remoteHits.value, {
    appliedValue: props.filter?.value,
    loading: remoteLoading.value,
  })
  if (resolved == null) {
    throw new Error(
      `USER filter apply for ${props.column?.field ?? 'unknown'} without a selected person`,
    )
  }
  return resolved
}

function onApply() {
  const payload: ListColumnFilter = { operator: draft.value.operator, value: '' }
  if (needsValue.value) {
    payload.value = isUser.value ? appliedUserValue() : draft.value.value
    if (needsRange.value) {
      payload.value2 = draft.value.value2
    }
  }
  emit('apply', payload)
}

function onClear() {
  emit('clear')
}
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    :title="column ? `${t('sharedList.filterBy')}: ${column.label}` : t('sharedList.filterBy')"
    width="420px"
    append-to-body
    destroy-on-close
  >
    <el-form label-position="top">
      <el-form-item
        v-if="showOperator"
        :label="t('sharedList.filterOperator')"
      >
        <el-select
          v-model="draft.operator"
          class="list-filter-operator"
          style="width: 100%;"
        >
          <el-option
            v-for="op in column?.operators ?? []"
            :key="op"
            :label="t(operatorLabelKey(op))"
            :value="op"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="needsValue"
        :label="t('sharedList.filterValue')"
      >
        <el-select
          v-if="hasOptions"
          v-model="draft.value"
          class="list-filter-value"
          style="width: 100%;"
          clearable
          filterable
        >
          <el-option
            v-for="opt in column?.options ?? []"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-select
          v-else-if="isUser"
          v-model="draft.value"
          class="list-filter-value list-filter-user"
          style="width: 100%;"
          clearable
          filterable
          remote
          default-first-option
          :remote-method="onUserSearch"
          :loading="remoteLoading"
          :placeholder="t('sharedList.filterUserPlaceholder')"
        >
          <el-option
            v-for="opt in remoteHits"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-date-picker
          v-else-if="isDate"
          v-model="draft.value"
          class="list-filter-value"
          type="date"
          value-format="YYYY-MM-DD"
          style="width: 100%;"
        />
        <el-input
          v-else
          v-model="draft.value"
          class="list-filter-value"
          :type="valueInputType"
          :placeholder="t('sharedList.filterValuePlaceholder')"
          clearable
          @keyup.enter="canApply && onApply()"
        />
      </el-form-item>
      <el-form-item
        v-if="needsValue && needsRange"
        :label="t('sharedList.filterValueTo')"
      >
        <el-date-picker
          v-if="isDate"
          v-model="draft.value2"
          class="list-filter-value2"
          type="date"
          value-format="YYYY-MM-DD"
          style="width: 100%;"
        />
        <el-input
          v-else
          v-model="draft.value2"
          class="list-filter-value2"
          :type="valueInputType"
          :placeholder="t('sharedList.filterValuePlaceholder')"
          clearable
          @keyup.enter="canApply && onApply()"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="onClear">{{ t('sharedList.clear') }}</el-button>
      <el-button
        type="primary"
        :disabled="!canApply"
        @click="onApply"
      >
        {{ t('sharedList.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>
