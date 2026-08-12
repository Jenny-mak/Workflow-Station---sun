<script setup lang="ts">
import {
  PORTAL_LIST_PAGE_SIZES,
  PORTAL_LIST_PAGINATION_LAYOUT,
} from '@/constants/portalListPagination'

withDefaults(
  defineProps<{
    currentPage: number
    pageSize: number
    total: number
    disabled?: boolean
    /** When false, hide pager entirely (e.g. empty). Default: show when total > 0. */
    visible?: boolean
  }>(),
  {
    disabled: false,
    visible: undefined,
  },
)

const emit = defineEmits<{
  'update:currentPage': [page: number]
  'update:pageSize': [size: number]
  change: []
}>()

const pageSizes = [...PORTAL_LIST_PAGE_SIZES]

function onCurrentChange(page: number) {
  emit('update:currentPage', page)
  emit('change')
}

function onSizeChange(size: number) {
  emit('update:pageSize', size)
  emit('update:currentPage', 1)
  emit('change')
}
</script>

<template>
  <div
    v-if="visible !== false && (visible === true || total > 0)"
    class="portal-list-pagination"
  >
    <el-pagination
      background
      :current-page="currentPage"
      :page-size="pageSize"
      :total="total"
      :disabled="disabled"
      :page-sizes="pageSizes"
      :layout="PORTAL_LIST_PAGINATION_LAYOUT"
      @current-change="onCurrentChange"
      @size-change="onSizeChange"
    />
  </div>
</template>

<style scoped lang="scss">
.portal-list-pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
