<template>
  <div class="page-container">
    <PageHeader :title="t('bi.dataViewAssignment.pageTitle')">
      <template #actions>
        <el-button @click="exportAssignments">
          <el-icon><Download /></el-icon>{{ t('common.export') }}
        </el-button>
        <el-button
          type="primary"
          @click="openCreate"
        >
          <el-icon><Plus /></el-icon>{{ t('bi.dataViewAssignment.newAssignment') }}
        </el-button>
      </template>
    </PageHeader>

    <el-card class="search-card">
      <el-form
        :inline="true"
        :model="query"
        class="search-form"
      >
        <el-form-item :label="t('bi.dataViewAssignment.filterDashboard')">
          <el-input
            v-model="query.dashboardTitle"
            clearable
            :placeholder="t('bi.dataViewAssignment.placeholderDashboardTitle')"
            style="width: 220px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item :label="t('bi.dataViewAssignment.filterTarget')">
          <el-select
            v-model="query.functionUnitId"
            clearable
            filterable
            :placeholder="t('bi.dataViewAssignment.placeholderTarget')"
            style="width: 260px"
          >
            <el-option
              v-for="unit in functionUnits"
              :key="unit.id"
              :label="`${unit.name} (${unit.code})`"
              :value="unit.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            @click="search"
          >
            <el-icon><Search /></el-icon>{{ t('common.search') }}
          </el-button>
          <el-button @click="reset">
            <el-icon><Refresh /></el-icon>{{ t('common.reset') }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card
      v-loading="loading"
      class="table-card"
    >
      <el-table
        :data="rows"
        stripe
        class="list-data-grid"
        @selection-change="selectedRows = $event"
      >
        <el-table-column
          type="selection"
          width="48"
          fixed="left"
        />
        <el-table-column
          prop="dashboardTitle"
          :label="t('bi.dataViewAssignment.colDashboard')"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column
          prop="functionUnitName"
          :label="t('bi.dataViewAssignment.colTarget')"
          min-width="220"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <div>{{ row.functionUnitName }}</div>
            <small class="secondary-text">{{ row.functionUnitCode }}</small>
          </template>
        </el-table-column>
        <el-table-column
          prop="tableDisplayName"
          :label="t('bi.dataViewAssignment.colTable')"
          min-width="220"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <div class="table-name-cell">
              <span>{{ row.tableDisplayName || row.tableName }}</span>
              <el-tag
                size="small"
                type="info"
              >
                {{ t(`bi.dataViewAssignment.tableType${row.tableType}`) }}
              </el-tag>
            </div>
            <small
              v-if="row.tableDisplayName !== row.tableName"
              class="secondary-text"
            >{{ row.tableName }}</small>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('bi.dataViewAssignment.colActions')"
          width="140"
          fixed="right"
          align="center"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              @click="openEdit(row)"
            >
              {{ t('common.edit') }}
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              @click="remove(row)"
            >
              {{ t('common.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="load"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <DataViewAssignmentFormDialog
      v-model="dialogVisible"
      :mode="dialogMode"
      :initial-row="editingRow"
      @success="load"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Download, Plus, Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { biManagementApi, type DataViewAssignmentResponse, type DataViewFunctionUnitOption } from '@/api/biManagement'
import { notifyConfirm, notifyError, notifySuccess } from '@/utils/notify'
import DataViewAssignmentFormDialog from './components/DataViewAssignmentFormDialog.vue'
import { exportTableCsv } from '@platform-shared/list/tableExport'
import type { ListColumnMeta } from '@platform-shared/list/columnMeta'

const { t } = useI18n()
const loading = ref(false)
const rows = ref<DataViewAssignmentResponse[]>([])
const selectedRows = ref<DataViewAssignmentResponse[]>([])
const functionUnits = ref<DataViewFunctionUnitOption[]>([])
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingRow = ref<DataViewAssignmentResponse | null>(null)
const query = reactive({ dashboardTitle: '', functionUnitId: undefined as number | undefined })
const pagination = reactive({ page: 1, size: 20, total: 0 })
const exportColumns = computed<ListColumnMeta[]>(() => [
  { field: 'dashboardTitle', label: t('bi.dataViewAssignment.colDashboard'), kind: 'TEXT', filterable: false, sortable: false, operators: [] },
  { field: 'functionUnitName', label: t('bi.dataViewAssignment.colTarget'), kind: 'TEXT', filterable: false, sortable: false, operators: [] },
  { field: 'functionUnitCode', label: 'Function Unit Code', kind: 'TEXT', filterable: false, sortable: false, operators: [] },
  { field: 'tableDisplayName', label: t('bi.dataViewAssignment.colTable'), kind: 'TEXT', filterable: false, sortable: false, operators: [] },
  { field: 'tableName', label: 'Table Name', kind: 'TEXT', filterable: false, sortable: false, operators: [] },
])

function exportAssignments() {
  exportTableCsv({
    rows: selectedRows.value.length > 0 ? selectedRows.value : rows.value,
    columns: exportColumns.value,
    filename: 'bi-data-view-assignments',
  })
}

async function load() {
  loading.value = true
  try {
    const result = await biManagementApi.dataViewAssignment.list({
      page: pagination.page - 1,
      size: pagination.size,
      dashboardTitle: query.dashboardTitle.trim() || undefined,
      functionUnitId: query.functionUnitId,
    })
    rows.value = result.content || []
    pagination.total = result.totalElements || 0
  } catch {
    rows.value = []
    notifyError(t('bi.dataViewAssignment.queryFailed'))
  } finally {
    loading.value = false
  }
}

function search() {
  pagination.page = 1
  void load()
}

function reset() {
  query.dashboardTitle = ''
  query.functionUnitId = undefined
  search()
}

function handleSizeChange() {
  pagination.page = 1
  void load()
}

function openCreate() {
  dialogMode.value = 'create'
  editingRow.value = null
  dialogVisible.value = true
}

function openEdit(row: DataViewAssignmentResponse) {
  dialogMode.value = 'edit'
  editingRow.value = row
  dialogVisible.value = true
}

async function remove(row: DataViewAssignmentResponse) {
  try {
    await notifyConfirm(
      t('bi.dataViewAssignment.deleteConfirm', {
        dashboard: row.dashboardTitle,
        table: row.tableDisplayName || row.tableName,
      }),
      t('bi.dataViewAssignment.deleteConfirmTitle'),
      { type: 'warning' },
    )
    await biManagementApi.dataViewAssignment.delete(row.id)
    notifySuccess(t('bi.dataViewAssignment.deleteSuccess'))
    if (rows.value.length === 1 && pagination.page > 1) pagination.page -= 1
    await load()
  } catch (error) {
    if (error !== 'cancel') notifyError(t('bi.dataViewAssignment.deleteFailed'))
  }
}

onMounted(async () => {
  try {
    functionUnits.value = await biManagementApi.dataViewAssignment.listFunctionUnits()
  } catch {
    functionUnits.value = []
  }
  await load()
})
</script>

<style scoped lang="scss">
.secondary-text {
  color: var(--el-text-color-secondary);
}

.table-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
