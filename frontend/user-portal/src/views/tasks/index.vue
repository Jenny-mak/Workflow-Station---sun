<template>
  <div class="tasks-page">
    <TodoPageHeader
      :auto-claim-on-open="preferenceStore.autoClaimOnOpen"
      :auto-preview-on-open="preferenceStore.autoPreviewOnOpen"
      :saving="preferenceStore.saving"
      :busy="claimAllBusy"
      @claim-all="handleClaimAll"
      @unclaim-all="handleUnclaimAll"
      @auto-claim-change="onAutoClaimChange"
      @auto-preview-change="onAutoPreviewChange"
    />

    <div
      v-loading="loading"
      class="portal-card"
    >
      <TodoListToolbar
        v-model:assignment-types="filterForm.assignmentTypes"
        v-model:priorities="filterForm.priorities"
        v-model:keyword="filterForm.keyword"
        @search="handleSearch"
        @reset="handleReset"
      />
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
            @selection-change="handleSelectionChange"
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
              <span v-else>{{ t('task.noTasks') }}</span>
            </template>
            <!-- Do not set `fixed`: EP's left overlay fills the viewport and hides the data columns. -->
            <el-table-column
              type="selection"
              width="50"
            />
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
                <span
                  v-else-if="col.field === 'functionUnitCode'"
                >
                  {{ row.functionUnitName || row.functionUnitCode || '-' }}
                </span>
                <el-tag
                  v-else-if="col.field === 'assignmentType'"
                  size="small"
                  class="assignment-tag"
                  :class="assignmentTagClass(row)"
                >
                  {{ t(`task.${assignmentDisplayKey(row)}`) }}
                </el-tag>
                <template v-else-if="col.field === 'assigneeName'">
                  <el-tag
                    v-if="row.claimPoolTask && row.assignee"
                    :type="row.claimedByCurrentUser ? 'success' : 'info'"
                    size="small"
                    data-test="todo-claimed-by"
                  >
                    {{ row.claimedByCurrentUser ? t('task.claimedByMe') : (row.assigneeName || row.assignee) }}
                  </el-tag>
                  <span v-else>-</span>
                </template>
                <span
                  v-else-if="col.field === 'priority'"
                  class="priority"
                  :class="getPriorityClass(row.priority)"
                >
                  {{ getPriorityLabel(row.priority) }}
                </span>
                <span
                  v-else-if="col.field === 'createTime' || col.field === 'dueDate'"
                  style="white-space: nowrap;"
                  :class="{ overdue: col.field === 'dueDate' && row.isOverdue }"
                >
                  {{ formatDate(row[col.field]) }}
                  <el-tag
                    v-if="col.field === 'dueDate' && row.isOverdue"
                    type="danger"
                    size="small"
                    style="margin-left: 4px;"
                  >
                    {{ t('task.overdue') }}
                  </el-tag>
                </span>
                <template v-else>
                  {{ row[col.field as keyof TaskInfo] || '-' }}
                </template>
              </template>
            </el-table-column>
            <el-table-column
              :width="CLAIM_ACTION_WIDTH"
              class-name="todo-claim-action-col"
            >
              <template #header>
                <span class="todo-claim-action-header">{{ t('task.action') }}</span>
              </template>
              <template #default="{ row }">
                <TodoClaimRowActions
                  :task="row"
                  :loading="actingTaskId === row.taskId"
                  @claim="handleClaim"
                  @unclaim="handleUnclaim"
                  @force-unclaim="handleForceUnclaim"
                  @reassign="handleReassign"
                />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <div
        v-if="selectedTasks.length > 0"
        class="batch-actions"
      >
        <span>{{ t('task.selected', { count: selectedTasks.length }) }}</span>
        <el-button
          type="primary"
          size="small"
          data-test="todo-claim-selected-btn"
          :disabled="selectedClaimableIds.length === 0"
          :loading="claimAllBusy"
          :title="selectedClaimableIds.length === 0 ? t('task.claimSelectedEmpty') : undefined"
          @click="handleClaimSelected"
        >
          {{ t('task.claim') }}
        </el-button>
        <el-button
          size="small"
          data-test="todo-unclaim-selected-btn"
          :disabled="selectedHeldIds.length === 0"
          :loading="claimAllBusy"
          :title="selectedHeldIds.length === 0 ? t('task.unclaimSelectedEmpty') : undefined"
          @click="handleUnclaimSelected"
        >
          {{ t('task.unclaim') }}
        </el-button>
        <el-button
          type="warning"
          size="small"
          @click="handleBatchUrge"
        >
          {{ t('task.batchUrge') }}
        </el-button>
      </div>

      <ListPagination
        v-model:page="pagination.page"
        v-model:size="pagination.size"
        :total="pagination.total"
        :loading="loading"
        @change="loadTasks"
      />
    </div>

    <ListFilterDialog
      v-model:visible="filterDialog.visible"
      :column="activeFilterColumn"
      :filter="activeFilter"
      @apply="onFilterApply"
      @clear="onFilterClear"
    />

    <el-dialog
      v-model="actionDialogVisible"
      :title="actionDialogTitle"
      width="500px"
      class="task-action-form"
    >
      <el-form
        :model="actionForm"
        label-width="100px"
      >
        <el-form-item
          v-if="currentAction !== 'urge' && currentAction !== 'batchUrge'"
          :label="t('task.targetUser')"
          required
        >
          <el-input
            v-model="actionForm.targetUserId"
            :placeholder="t('task.enterUserId')"
          />
        </el-form-item>
        <el-form-item :label="t('common.reason')">
          <el-input
            v-model="actionForm.reason"
            type="textarea"
            :rows="3"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          @click="submitAction"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <TaskReassignDialog
      v-model="reassignDialogVisible"
      :candidate-user-ids="reassignTask?.candidateUserIds ?? []"
      :current-holder-id="reassignTask?.assignee"
      :submitting="claimAllBusy"
      @confirm="confirmReassign"
    />
  </div>
</template>

<script setup lang="ts">
import { Loading } from '@element-plus/icons-vue'
import TodoListToolbar from './TodoListToolbar.vue'
import TodoPageHeader from './TodoPageHeader.vue'
import TodoClaimRowActions from '@/components/tasks/TodoClaimRowActions.vue'
import TaskReassignDialog from '@/components/tasks/TaskReassignDialog.vue'
import ListColumnHeader from '@platform-shared/list/ListColumnHeader.vue'
import ListFilterDialog from '@platform-shared/list/ListFilterDialog.vue'
import ListPagination from '@platform-shared/list/ListPagination.vue'
import type { TaskInfo } from '@/api/task'
import { CLAIM_ACTION_WIDTH, useTodoTasksPage } from '@/composables/tasks/useTodoTasksPage'
import { formatDate } from '@/utils/dateFormat'
import { assignmentDisplayKey, assignmentTagClass } from '@/utils/taskAssignmentDisplay'

defineOptions({ name: 'Tasks' })

const {
  t,
  preferenceStore,
  loading,
  selectedTasks,
  selectedClaimableIds,
  selectedHeldIds,
  filterForm,
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
  moveColumn,
  openFilter,
  actionDialogVisible,
  actionDialogTitle,
  actionForm,
  actingTaskId,
  claimAllBusy,
  loadTasks,
  handleSearch,
  handleReset,
  onSort,
  onClearSort,
  onClearFilter,
  onFilterApply,
  onFilterClear,
  handleSelectionChange,
  viewTask,
  handleClaim,
  handleUnclaim,
  handleForceUnclaim,
  handleReassign,
  confirmReassign,
  reassignDialogVisible,
  reassignTask,
  handleClaimAll,
  handleUnclaimAll,
  handleClaimSelected,
  handleUnclaimSelected,
  onAutoClaimChange,
  onAutoPreviewChange,
  handleBatchUrge,
  submitAction,
  getPriorityLabel,
  getPriorityClass,
} = useTodoTasksPage()
</script>

<style lang="scss">
@import '@/styles/listDataGrid.scss';
</style>

<style lang="scss" scoped>
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

.tasks-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;

  :deep(.portal-card) {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .batch-actions {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-top: 12px;
    padding: 12px 16px;
    background: #f5f7fa;
    border-radius: 4px;
    flex-shrink: 0;

    span {
      color: var(--text-secondary);
    }
  }

  .overdue {
    color: var(--error-red);
  }

  .todo-claim-action-header {
    font-weight: 500;
  }
}
</style>

<style lang="scss">
.task-action-form .el-form-item__label {
  white-space: nowrap;
}
</style>
