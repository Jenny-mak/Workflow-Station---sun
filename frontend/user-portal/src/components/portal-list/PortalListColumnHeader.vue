<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ArrowDown,
  CaretTop,
  CaretBottom,
  Filter,
  Close,
  Grid,
  DCaret,
  Back,
  Right,
} from '@element-plus/icons-vue'
import MainTableViewColumnResizeHandle from '@/components/mainTableView/MainTableViewColumnResizeHandle.vue'
import {
  COLUMN_WIDTH_MAX,
  COLUMN_WIDTH_MIN,
  type PortalListSortDirection,
} from '@/utils/portalListGridRuntime'

const props = withDefaults(
  defineProps<{
    label: string
    width: number
    hasFilter?: boolean
    sortDirection?: PortalListSortDirection | null
    isGrouped?: boolean
    canMoveLeft?: boolean
    canMoveRight?: boolean
    /** When false, hide sort menu items (e.g. actions column). */
    sortable?: boolean
    /** When false, hide filter menu items. */
    filterable?: boolean
    /** When false, hide group menu item. */
    groupable?: boolean
    /** When false, hide move left/right. */
    movable?: boolean
    /** When false, hide resize handle. */
    resizable?: boolean
    dateLike?: boolean
  }>(),
  {
    hasFilter: false,
    sortDirection: null,
    isGrouped: false,
    canMoveLeft: false,
    canMoveRight: false,
    sortable: true,
    filterable: true,
    groupable: true,
    movable: true,
    resizable: true,
    dateLike: false,
  },
)

const emit = defineEmits<{
  sortAsc: []
  sortDesc: []
  groupBy: []
  filter: []
  clearFilter: []
  moveLeft: []
  moveRight: []
  columnWidth: []
  resize: [width: number]
  resizeEnd: []
}>()

const { t } = useI18n()

const widthDialogVisible = ref(false)
const widthDraft = ref(props.width)

const hasActiveState = computed(
  () => props.hasFilter || props.isGrouped || props.sortDirection != null,
)

function onCommand(action: string) {
  if (action === 'sortAsc') emit('sortAsc')
  else if (action === 'sortDesc') emit('sortDesc')
  else if (action === 'groupBy') emit('groupBy')
  else if (action === 'filterBy') emit('filter')
  else if (action === 'clearFilter') emit('clearFilter')
  else if (action === 'moveLeft') emit('moveLeft')
  else if (action === 'moveRight') emit('moveRight')
  else if (action === 'columnWidth') {
    widthDraft.value = props.width
    widthDialogVisible.value = true
    emit('columnWidth')
  }
}

function applyWidthDialog() {
  emit('resize', widthDraft.value)
  emit('resizeEnd')
  widthDialogVisible.value = false
}
</script>

<template>
  <div class="portal-list-col-header">
    <el-dropdown
      class="portal-list-col-dropdown"
      trigger="click"
      @command="onCommand"
    >
      <span
        class="portal-list-col-trigger"
        :class="{ 'is-active-state': hasActiveState }"
        @click.stop
      >
        <span class="portal-list-col-label">{{ label }}</span>
        <span
          v-if="hasActiveState"
          class="portal-list-col-state"
          aria-hidden="true"
        >
          <el-icon
            v-if="sortDirection === 'ASC'"
            class="state-icon"
            :title="dateLike ? t('mainTableView.colSortOlder') : t('mainTableView.colSortAsc')"
          ><CaretTop /></el-icon>
          <el-icon
            v-else-if="sortDirection === 'DESC'"
            class="state-icon"
            :title="dateLike ? t('mainTableView.colSortNewer') : t('mainTableView.colSortDesc')"
          ><CaretBottom /></el-icon>
          <el-icon
            v-if="isGrouped"
            class="state-icon"
            :title="t('mainTableView.colGroupBy')"
          ><Grid /></el-icon>
          <el-icon
            v-if="hasFilter"
            class="state-icon is-filter"
            :title="t('mainTableView.colFilterBy')"
          ><Filter /></el-icon>
        </span>
        <el-icon class="portal-list-col-caret"><ArrowDown /></el-icon>
      </span>
      <template #dropdown>
        <el-dropdown-menu class="portal-list-col-menu">
          <template v-if="sortable">
            <el-dropdown-item command="sortAsc">
              <el-icon><CaretTop /></el-icon>
              <span>{{
                sortDirection === 'ASC'
                  ? t('mainTableView.colClearSort')
                  : (dateLike ? t('mainTableView.colSortOlder') : t('mainTableView.colSortAsc'))
              }}</span>
            </el-dropdown-item>
            <el-dropdown-item command="sortDesc">
              <el-icon><CaretBottom /></el-icon>
              <span>{{
                sortDirection === 'DESC'
                  ? t('mainTableView.colClearSort')
                  : (dateLike ? t('mainTableView.colSortNewer') : t('mainTableView.colSortDesc'))
              }}</span>
            </el-dropdown-item>
          </template>
          <el-dropdown-item
            v-if="groupable"
            :divided="sortable"
            command="groupBy"
          >
            <el-icon><Grid /></el-icon>
            <span>{{ isGrouped ? t('mainTableView.colUngroup') : t('mainTableView.colGroupBy') }}</span>
          </el-dropdown-item>
          <el-dropdown-item
            v-if="filterable"
            :divided="!groupable && sortable"
            command="filterBy"
          >
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
            v-if="filterable && hasFilter"
            command="clearFilter"
          >
            <el-icon><Close /></el-icon>
            <span>{{ t('mainTableView.colClearFilter') }}</span>
          </el-dropdown-item>
          <el-dropdown-item command="columnWidth">
            <el-icon><DCaret /></el-icon>
            <span>{{ t('mainTableView.colColumnWidth') }}</span>
          </el-dropdown-item>
          <template v-if="movable">
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
          </template>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <MainTableViewColumnResizeHandle
      v-if="resizable"
      :initial-width="width"
      @resize="(w) => emit('resize', w)"
      @resize-end="emit('resizeEnd')"
    />

    <el-dialog
      v-model="widthDialogVisible"
      :title="`${t('mainTableView.colColumnWidth')}: ${label}`"
      width="360px"
      append-to-body
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item :label="t('mainTableView.columnWidthPx')">
          <el-slider
            v-model="widthDraft"
            :min="COLUMN_WIDTH_MIN"
            :max="COLUMN_WIDTH_MAX"
            show-input
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="widthDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          @click="applyWidthDialog"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.portal-list-col-dropdown {
  display: block;
  flex: 1 1 0;
  width: 0;
  min-width: 0;
}

.portal-list-col-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  min-width: 0;
  cursor: pointer;
  user-select: none;

  &:hover {
    color: var(--el-color-primary);
  }

  &.is-active-state .portal-list-col-label {
    color: var(--hsbc-red, var(--el-color-primary));
    font-weight: 600;
  }
}

.portal-list-col-label {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.portal-list-col-state {
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

.portal-list-col-caret {
  font-size: 12px;
  flex-shrink: 0;
  opacity: 0.65;
}

.menu-active-tag {
  margin-left: auto;
  padding: 0 4px;
  height: 16px;
  line-height: 16px;
}

:deep(.el-dropdown-menu__item) {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 200px;
}
</style>
