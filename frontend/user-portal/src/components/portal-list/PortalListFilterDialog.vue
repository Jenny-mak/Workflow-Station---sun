<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type {
  PortalListColumnFilter,
  PortalListFilterOperator,
} from '@/utils/portalListGridRuntime'

const props = defineProps<{
  modelValue: boolean
  title: string
  /** Current column filter; operator/value shape shared with Views runtime. */
  initial?: { operator: string; value: string } | null
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  apply: [filter: PortalListColumnFilter]
  clear: []
}>()

const { t } = useI18n()

const draft = reactive<{ operator: PortalListFilterOperator; value: string }>({
  operator: 'contains',
  value: '',
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    const op = props.initial?.operator
    draft.operator = (op as PortalListFilterOperator) || 'contains'
    draft.value = props.initial?.value || ''
  },
)

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
          v-model="draft.operator"
          style="width: 100%;"
        >
          <el-option
            :label="t('mainTableView.filterOpContains')"
            value="contains"
          />
          <el-option
            :label="t('mainTableView.filterOpEquals')"
            value="eq"
          />
          <el-option
            :label="t('mainTableView.filterOpNotEquals')"
            value="ne"
          />
          <el-option
            :label="t('mainTableView.filterOpStartsWith')"
            value="startsWith"
          />
          <el-option
            :label="t('mainTableView.filterOpEndsWith')"
            value="endsWith"
          />
          <el-option
            :label="t('mainTableView.filterOpNotContains')"
            value="notContains"
          />
          <el-option
            :label="t('mainTableView.filterOpHasData')"
            value="isNotNull"
          />
          <el-option
            :label="t('mainTableView.filterOpNoData')"
            value="isNull"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="draft.operator !== 'isNull' && draft.operator !== 'isNotNull'"
        :label="t('mainTableView.filterValue')"
      >
        <el-input v-model="draft.value" />
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
