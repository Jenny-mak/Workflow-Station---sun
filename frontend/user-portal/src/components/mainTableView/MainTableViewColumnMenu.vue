<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CaretTop,
  CaretBottom,
  Grid,
  Filter,
  Close,
  DCaret,
  Back,
  Right,
  ArrowDown,
} from '@element-plus/icons-vue'
import type { MainTableViewFieldColumn } from '@/api/mainTableView'
import type { GridSortDirection } from '@/utils/mainTableViewGridRuntime'

const props = defineProps<{
  column: MainTableViewFieldColumn
  canMoveLeft: boolean
  canMoveRight: boolean
  isGrouped: boolean
  hasFilter: boolean
  /** ASC / DESC when this column is the active sort; otherwise null. */
  sortDirection: GridSortDirection | null
}>()

const emit = defineEmits<{
  command: [action: string]
}>()

const { t } = useI18n()

const isDateLike = computed(() => {
  const name = props.column.fieldName.toLowerCase()
  return name.includes('time') || name.includes('date') || props.column.systemField
})

const hasActiveState = computed(
  () => props.hasFilter || props.isGrouped || props.sortDirection != null,
)

function onCommand(action: string) {
  emit('command', action)
}
</script>

<template>
  <el-dropdown
    class="col-header-dropdown"
    trigger="click"
    @command="onCommand"
  >
    <span
      class="col-header-trigger"
      :class="{ 'is-active-state': hasActiveState }"
      @click.stop
    >
      <span class="col-header-label">{{ column.displayLabel }}</span>
      <span
        v-if="hasActiveState"
        class="col-header-state"
        aria-hidden="true"
      >
        <el-icon
          v-if="sortDirection === 'ASC'"
          class="state-icon"
          :title="isDateLike ? t('mainTableView.colSortOlder') : t('mainTableView.colSortAsc')"
        >
          <CaretTop />
        </el-icon>
        <el-icon
          v-else-if="sortDirection === 'DESC'"
          class="state-icon"
          :title="isDateLike ? t('mainTableView.colSortNewer') : t('mainTableView.colSortDesc')"
        >
          <CaretBottom />
        </el-icon>
        <el-icon
          v-if="isGrouped"
          class="state-icon"
          :title="t('mainTableView.colGroupBy')"
        >
          <Grid />
        </el-icon>
        <el-icon
          v-if="hasFilter"
          class="state-icon is-filter"
          :title="t('mainTableView.colFilterBy')"
        >
          <Filter />
        </el-icon>
      </span>
      <el-icon class="col-header-caret"><ArrowDown /></el-icon>
    </span>
    <template #dropdown>
      <el-dropdown-menu class="col-header-menu">
        <el-dropdown-item command="sortAsc">
          <el-icon><CaretTop /></el-icon>
          <span>{{
            sortDirection === 'ASC'
              ? t('mainTableView.colClearSort')
              : (isDateLike ? t('mainTableView.colSortOlder') : t('mainTableView.colSortAsc'))
          }}</span>
        </el-dropdown-item>
        <el-dropdown-item command="sortDesc">
          <el-icon><CaretBottom /></el-icon>
          <span>{{
            sortDirection === 'DESC'
              ? t('mainTableView.colClearSort')
              : (isDateLike ? t('mainTableView.colSortNewer') : t('mainTableView.colSortDesc'))
          }}</span>
        </el-dropdown-item>
        <el-dropdown-item
          divided
          command="groupBy"
        >
          <el-icon><Grid /></el-icon>
          <span>{{ isGrouped ? t('mainTableView.colUngroup') : t('mainTableView.colGroupBy') }}</span>
        </el-dropdown-item>
        <el-dropdown-item command="filterBy">
          <el-icon><Filter /></el-icon>
          <span>{{ t('mainTableView.colFilterBy') }}</span>
          <el-tag
            v-if="hasFilter"
            size="small"
            type="danger"
            class="menu-active-tag"
          >
            ●
          </el-tag>
        </el-dropdown-item>
        <el-dropdown-item
          v-if="hasFilter"
          command="clearFilter"
        >
          <el-icon><Close /></el-icon>
          <span>{{ t('mainTableView.colClearFilter') }}</span>
        </el-dropdown-item>
        <el-dropdown-item command="columnWidth">
          <el-icon><DCaret /></el-icon>
          <span>{{ t('mainTableView.colColumnWidth') }}</span>
        </el-dropdown-item>
        <el-dropdown-item
          divided
          command="moveLeft"
          :disabled="!canMoveLeft"
        >
          <el-icon><Back /></el-icon>
          <span>{{ t('mainTableView.colMoveLeft') }}</span>
        </el-dropdown-item>
        <el-dropdown-item
          command="moveRight"
          :disabled="!canMoveRight"
        >
          <el-icon><Right /></el-icon>
          <span>{{ t('mainTableView.colMoveRight') }}</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped lang="scss">
.col-header-dropdown {
  display: block;
  flex: 1 1 0;
  width: 0;
  min-width: 0;
}

.col-header-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  cursor: pointer;
  user-select: none;

  &:hover {
    color: var(--el-color-primary);
  }

  &.is-active-state .col-header-label {
    color: var(--hsbc-red, var(--el-color-primary));
    font-weight: 600;
  }
}

.col-header-label {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-header-state {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.state-icon {
  font-size: 13px;
  color: var(--hsbc-red, var(--el-color-primary));

  &.is-filter {
    font-size: 14px;
  }
}

.col-header-caret {
  font-size: 12px;
  flex-shrink: 0;
  opacity: 0.65;
}

:deep(.el-dropdown-menu__item) {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 200px;
}

.menu-active-tag {
  margin-left: auto;
  padding: 0 4px;
  height: 16px;
  line-height: 16px;
}
</style>
