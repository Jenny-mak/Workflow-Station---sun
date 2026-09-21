<template>
  <div
    v-loading="loading"
    class="portal-card list-tab-card"
  >
    <div
      ref="gridScrollRef"
      class="list-data-grid-scroll"
    >
      <div
        class="list-data-grid-inner"
        :style="gridInnerStyle"
      >
        <el-table
          :data="displayRows"
          stripe
          :fit="false"
          table-layout="fixed"
          style="width: 100%;"
          class="list-data-grid"
          :class="{ 'list-data-grid--fit': gridFits }"
          scrollbar-always-on
          :height="gridTableHeight || '100%'"
        >
          <template #empty>
            <div
              v-if="loading"
              class="table-empty-loading"
            >
              <el-icon class="table-empty-loading__icon is-loading">
                <Loading />
              </el-icon>
              <span>{{ t('common.loading') }}</span>
            </div>
            <span v-else>{{ t('delegation.noProxyTasks') }}</span>
          </template>
          <el-table-column
            v-for="(col, colIndex) in displayColumns"
            :key="col.field"
            :prop="col.field"
            :width="widthOf(col.field)"
            show-overflow-tooltip
          >
            <template #header>
              <ListColumnHeader
                :column="col"
                :sort="sort.field === col.field ? sort.direction : null"
                :filtered="!!columnFilters[col.field]"
                :width="widthOf(col.field)"
                :show-move="displayColumns.length > 1"
                :can-move-left="colIndex > 0"
                :can-move-right="colIndex < displayColumns.length - 1"
                @sort-change="(direction: 'ASC' | 'DESC') => onSort(col.field, direction)"
                @clear-sort="onClearSort"
                @filter-open="openFilter(col.field)"
                @clear-filter="onClearFilter(col.field)"
                @move="(direction: 'left' | 'right') => moveColumn(col.field, direction)"
                @width-change="(width: number) => setWidth(col.field, width)"
                @width-commit="persistWidths"
              />
            </template>
            <template #default="{ row }">
              <el-link
                v-if="col.field === 'requestId'"
                type="primary"
                @click="viewTask(row)"
              >
                {{ row.requestId || '-' }}
              </el-link>
              <span v-else-if="col.field === 'functionUnitCode'">
                {{ row.functionUnitName || row.functionUnitCode || '-' }}
              </span>
              <span v-else-if="col.field === 'delegatorId'">
                {{ row.delegatorName || row.delegatorId || '-' }}
              </span>
              <span v-else-if="col.field === 'delegatedTargetType'">
                {{ delegatedTargetLabel(row) }}
              </span>
              <el-tag
                v-else-if="col.field === 'assignmentType'"
                size="small"
                class="assignment-tag"
                :class="assignmentTagClass(row)"
              >
                {{ t(`task.${assignmentDisplayKey(row)}`) }}
              </el-tag>
              <span
                v-else-if="col.field === 'createTime'"
                style="white-space: nowrap;"
              >{{ formatDate(row.createTime) }}</span>
              <template v-else>
                {{ row[col.field] || '-' }}
              </template>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <ListPagination
      v-model:page="pagination.page"
      v-model:size="pagination.size"
      :total="pagination.total"
      :loading="loading"
      @change="load"
    />
    <ListFilterDialog
      v-model:visible="filterDialog.visible"
      :column="activeFilterColumn"
      :filter="activeFilter"
      :remote-search="searchListFilterUsers"
      @apply="onFilterApply"
      @clear="onFilterClear"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { ListColumnFilter } from '@platform-shared/list/columnMeta'
import { queryTodoTasks, type TaskInfo } from '@/api/task'
import { usePortalListGrid } from '@/composables/list/usePortalListGrid'
import { searchListFilterUsers } from '@/composables/list/searchListFilterUsers'
import { formatDate } from '@/utils/dateFormat'
import { assignmentDisplayKey, assignmentTagClass } from '@/utils/taskAssignmentDisplay'

const PROXY_VISIBLE_FIELDS = [
  'requestId',
  'functionUnitCode',
  'taskName',
  'delegatorId',
  'delegatedTargetType',
  'assignmentType',
  'createTime',
] as const

const { t } = useI18n()
const router = useRouter()
const loading = ref(false)
const loaded = ref(false)

const {
  displayColumns,
  displayRows,
  columnFilters,
  sort,
  filterDialog,
  pagination,
  activeFilterColumn,
  activeFilter,
  gridScrollRef,
  gridFits,
  gridTableHeight,
  gridInnerStyle,
  widthOf,
  setWidth,
  persistWidths,
  beginQuery,
  isCurrentQuery,
  applyPage,
  buildQuery,
  moveColumn,
  openFilter,
  applyFilter,
  clearFilter,
  applySort,
  clearSort,
} = usePortalListGrid<TaskInfo>({
  storageKey: 'portal-list-layout:delegation-proxy-tasks',
  visibleFields: PROXY_VISIBLE_FIELDS,
})

async function load() {
  const seq = beginQuery()
  loading.value = true
  try {
    const res = await queryTodoTasks({
      ...buildQuery(),
      assignmentTypes: ['DELEGATED'],
    })
    if (!isCurrentQuery(seq)) return
    applyPage(res.data, 'tasks/todo/query delegated response is missing its column declaration')
    loaded.value = true
  } catch (error) {
    if (!isCurrentQuery(seq)) return
    if (!(error as { response?: unknown })?.response) {
      ElMessage.error(error instanceof Error ? error.message : t('delegation.loadFailed'))
    }
  } finally {
    if (isCurrentQuery(seq)) loading.value = false
  }
}

function ensureLoaded() {
  if (!loaded.value) {
    void load()
  }
}

function viewTask(task: TaskInfo) {
  if (!task.taskId) return
  router.push(`/tasks/${task.taskId}`)
}

function delegatedTargetLabel(row: TaskInfo): string {
  if (row.delegatedTargetType === 'BU_ROLE') {
    const pair = [row.delegatedBuCode, row.delegatedRoleCode].filter(Boolean).join(' / ')
    const kind = t('delegation.specifyBuRole')
    return pair ? `${kind} (${pair})` : kind
  }
  if (row.delegatedTargetType === 'USER') {
    return t('delegation.specifyUser')
  }
  return row.delegatedTargetType || '-'
}

function onSort(field: string, direction: 'ASC' | 'DESC') {
  applySort(field, direction)
  load()
}

function onClearSort() {
  clearSort()
  load()
}

function onClearFilter(field: string) {
  clearFilter(field)
  load()
}

function onFilterApply(filter: ListColumnFilter) {
  applyFilter(filter)
  load()
}

function onFilterClear() {
  onClearFilter(filterDialog.field)
}

defineExpose({ ensureLoaded, reload: load })
</script>

<style lang="scss" scoped>
@import '@/styles/listDataGrid.scss';

.table-empty-loading {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  padding: 24px 0;

  &__icon {
    font-size: 18px;
  }
}

.list-tab-card {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
