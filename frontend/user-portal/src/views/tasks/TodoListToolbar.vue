<template>
  <div class="list-toolbar">
    <span
      v-if="showAssignmentTypes"
      class="toolbar-label"
    >{{ t('task.assignmentType') }}</span>
    <el-select
      v-if="showAssignmentTypes"
      v-model="assignmentTypes"
      multiple
      clearable
      data-test="todo-assignment-types"
      :placeholder="t('common.all')"
      style="width: 200px;"
    >
      <el-option
        value="USER"
        :label="t('task.user')"
      />
      <el-option
        value="BU_ROLE"
        :label="t('task.buRole')"
      />
      <el-option
        value="DEPT_ROLE"
        :label="t('task.deptRole')"
      />
      <el-option
        value="DELEGATED"
        :label="t('task.delegated')"
      />
    </el-select>
    <el-input
      v-model="keyword"
      :placeholder="t('common.search')"
      clearable
      data-test="todo-search"
      style="width: 200px;"
      @keydown.enter.prevent="emit('search')"
      @clear="emit('search')"
    >
      <template #prefix>
        <el-icon><Search /></el-icon>
      </template>
    </el-input>
    <el-button
      type="primary"
      @click="emit('search')"
    >
      {{ t('common.search') }}
    </el-button>
    <el-button
      data-test="todo-reset-btn"
      @click="emit('reset')"
    >
      {{ t('common.reset') }}
    </el-button>
    <el-button
      :icon="Download"
      data-test="todo-export-btn"
      @click="emit('export')"
    >
      {{ t('common.export') }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Download, Search } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'

const props = withDefaults(defineProps<{
  assignmentTypes: string[]
  keyword: string
  /** Tasks to Claim is a single BU Role pool, so the assignment-type filter has nothing to choose. */
  showAssignmentTypes?: boolean
}>(), {
  showAssignmentTypes: true,
})

const emit = defineEmits<{
  'update:assignmentTypes': [value: string[]]
  'update:keyword': [value: string]
  search: []
  reset: []
  export: []
}>()

const assignmentTypes = computed({
  get: () => props.assignmentTypes,
  set: (value: string[] | null) => emit('update:assignmentTypes', value ?? []),
})
const keyword = computed({
  get: () => props.keyword,
  set: (value: string | null) => emit('update:keyword', value ?? ''),
})

const { t } = useI18n()
</script>

<style scoped lang="scss">
.list-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.toolbar-label {
  color: var(--el-text-color-regular);
  font-size: 14px;
  white-space: nowrap;
}
</style>
