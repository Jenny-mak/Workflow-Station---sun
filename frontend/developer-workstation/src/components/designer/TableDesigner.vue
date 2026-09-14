<template>
  <div class="table-designer">
    <div class="designer-toolbar">
      <el-button
        type="primary"
        @click="openCreateDialog"
      >
        {{ t('table.title') }}
      </el-button>
      <el-button
        :loading="loading"
        @click="loadTables"
      >
        <el-icon><Refresh /></el-icon> {{ t('common.refresh') }}
      </el-button>
      <el-button
        :disabled="store.tables.length < 2"
        @click="showRelationDialog = true"
      >
        {{ t('table.relations') }}
      </el-button>
      <el-button @click="handleValidate">
        {{ t('table.validateTables') }}
      </el-button>
      <el-button
        :loading="importing"
        @click="handleImportClick"
      >
        {{ t('table.importTemplate') }}
      </el-button>
      <DesignerHelpLink
        path="/table-design"
        :aria-label="t('table.guideLinkAria')"
        test-id="table-design-guide-link"
      />
      <input
        ref="fileInputRef"
        type="file"
        accept=".json"
        style="display: none"
        @change="handleImportFile"
      />
    </div>
    
    <div
      v-if="!selectedTable"
      class="table-list table-scroll-wrap"
    >
      <DesignerListTable
        :loading="loading"
        :storage-key="`${functionUnitId}:tables`"
        :columns="listColumns"
        :rows="() => orderedTables"
        @row-click="handleSelectTable"
      >
        <template #cell-tableType="{ row }">
          <el-tag :type="row.tableType === 'MAIN' ? 'primary' : 'info'">
            {{ tableTypeLabel(row.tableType) }}
          </el-tag>
        </template>
        <template #cell-fieldCount="{ row }">
          {{ row.fieldDefinitions?.length || 0 }}
        </template>
        <template #cell-relations="{ row }">
          <el-tag
            v-if="getTableRelations(row.id).length"
            type="success"
            size="small"
          >
            {{ getTableRelations(row.id).length }}
          </el-tag>
          <span
            v-else
            class="text-muted"
          >-</span>
        </template>
        <template #actions="{ row }">
          <div class="table-row-actions">
            <el-button
              link
              type="primary"
              @click.stop="handleSelectTable(row)"
            >
              {{ t('common.edit') }}
            </el-button>
            <el-button
              link
              type="success"
              @click.stop="handleExportTable(row)"
            >
              {{ t('table.exportTemplate') }}
            </el-button>
            <el-button
              link
              type="danger"
              @click.stop="handleDeleteTable(row)"
            >
              {{ t('common.delete') }}
            </el-button>
          </div>
        </template>
      </DesignerListTable>
    </div>

    <div
      v-else
      class="table-editor"
    >
      <div class="editor-header">
        <div class="editor-header-left">
          <el-button
            text
            class="back-btn"
            @click="handleBackToList"
          >
            <el-icon><ArrowLeft /></el-icon> {{ t('table.backToList') }}
          </el-button>
          <span class="table-name">{{ selectedTable.tableDisplayName || selectedTable.tableName }}</span>
          <el-tag
            size="small"
            type="info"
            effect="plain"
          >
            {{ tableTypeLabel(selectedTable.tableType) }}
          </el-tag>
        </div>
        <div class="editor-header-actions">
          <el-button
            size="small"
            @click="handleGenerateDDL"
          >
            {{ t('table.ddlPreview') }}
          </el-button>
          <el-button
            type="primary"
            size="small"
            @click="handleSaveTable"
          >
            {{ t('table.save') }}
          </el-button>
        </div>
      </div>

      <el-card
        shadow="never"
        class="table-meta-card"
      >
        <div class="table-meta-grid">
          <div class="meta-field">
            <label class="meta-label">{{ t('table.tableDisplayName') }}</label>
            <el-input
              v-model="selectedTable.tableDisplayName"
              @input="onTableDisplayNameInput"
            />
          </div>
          <div class="meta-field">
            <label class="meta-label">{{ t('table.tableName') }}</label>
            <el-input
              v-model="selectedTable.tableName"
              @input="onTableNameManualInput"
            />
          </div>
          <div class="meta-field meta-field--type">
            <label class="meta-label">{{ t('table.tableType') }}</label>
            <el-select
              v-model="selectedTable.tableType"
              class="table-type-select"
            >
              <el-option
                :label="t('table.mainTable')"
                value="MAIN"
              />
              <el-option
                :label="t('table.subTable')"
                value="SUB"
              />
              <el-option
                :label="t('table.actionTable')"
                value="ACTION"
              />
              <el-option
                :label="t('table.relationTable')"
                value="RELATION"
              />
            </el-select>
          </div>
          <div class="meta-field meta-field--description">
            <label class="meta-label">{{ t('table.description') }}</label>
            <el-input
              v-model="selectedTable.description"
              type="textarea"
              :rows="2"
              autosize
            />
          </div>
        </div>
      </el-card>

      <el-card
        shadow="never"
        class="table-fields-card"
      >
        <div class="fields-card-header">
          <div class="fields-header-left">
            <h4 class="fields-title">
              {{ t('table.fields') }}
            </h4>
            <el-tag
              size="small"
              round
              type="info"
              effect="plain"
            >
              {{ selectedTable.fieldDefinitions.length }}
            </el-tag>
            <el-tooltip
              :content="t('table.fieldsHint')"
              placement="top"
            >
              <el-icon class="fields-info-icon">
                <InfoFilled />
              </el-icon>
            </el-tooltip>
          </div>
          <el-button
            type="primary"
            plain
            size="small"
            @click="handleAddField"
          >
            {{ t('table.addField') }}
          </el-button>
        </div>
        <div class="table-scroll-wrap table-fields-wrap">
          <el-table
            :data="selectedTable.fieldDefinitions"
            size="small"
            stripe
            row-key="__uid"
            class="table-fields-grid"
            :row-class-name="auditFieldRowClassName"
          >
            <el-table-column
              width="36"
              align="center"
              class-name="col-order"
            >
              <template #default="{ row, $index }">
                <div class="field-order-btns">
                  <el-button
                    v-if="$index > 0 && !isTableAuditField(row.fieldName)"
                    link
                    size="small"
                    class="order-btn"
                    @click="moveFieldUp($index)"
                  >
                    <el-icon><CaretTop /></el-icon>
                  </el-button>
                  <el-button
                    v-if="$index < selectedTable.fieldDefinitions.length - 1 && !isTableAuditField(row.fieldName) && !isTableAuditField(selectedTable.fieldDefinitions[$index + 1]?.fieldName)"
                    link
                    size="small"
                    class="order-btn"
                    @click="moveFieldDown($index)"
                  >
                    <el-icon><CaretBottom /></el-icon>
                  </el-button>
                </div>
              </template>
            </el-table-column>
            <el-table-column
              prop="displayName"
              :label="t('table.fieldDisplayName')"
              min-width="128"
            >
              <template #default="{ row, $index }">
                <div class="field-display-cell">
                  <el-input
                    v-model="row.displayName"
                    size="small"
                    :disabled="isTableAuditField(row.fieldName)"
                    @update:model-value="onFieldDisplayNameInput(row, $index)"
                  />
                  <el-tag
                    v-if="isTableAuditField(row.fieldName)"
                    size="small"
                    type="info"
                    effect="plain"
                    round
                    class="audit-field-tag"
                  >
                    {{ t('table.systemField') }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column
              prop="fieldName"
              :label="t('table.fieldName')"
              min-width="100"
            >
              <template #default="{ row }">
                <el-input
                  v-model="row.fieldName"
                  size="small"
                  :disabled="isTableAuditField(row.fieldName)"
                  @input="onFieldNameManualInput(row)"
                />
              </template>
            </el-table-column>
            <el-table-column
              prop="dataType"
              :label="t('table.dataType')"
              width="100"
            >
              <template #default="{ row }">
                <el-select
                  v-model="row.dataType"
                  size="small"
                  :disabled="isTableAuditField(row.fieldName)"
                >
                  <el-option
                    label="VARCHAR"
                    value="VARCHAR"
                  />
                  <el-option
                    label="INTEGER"
                    value="INTEGER"
                  />
                  <el-option
                    label="BIGINT"
                    value="BIGINT"
                  />
                  <el-option
                    label="DECIMAL"
                    value="DECIMAL"
                  />
                  <el-option
                    label="BOOLEAN"
                    value="BOOLEAN"
                  />
                  <el-option
                    label="DATE"
                    value="DATE"
                  />
                  <el-option
                    label="TIMESTAMP"
                    value="TIMESTAMP"
                  />
                  <el-option
                    label="TEXT"
                    value="TEXT"
                  />
                  <el-option
                    label="FILE"
                    value="FILE"
                  />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column
              prop="length"
              :label="t('table.length')"
              width="88"
            >
              <template #default="{ row }">
                <el-input-number
                  v-model="row.length"
                  size="small"
                  :min="0"
                  :disabled="isTableAuditField(row.fieldName)"
                  controls-position="right"
                  class="compact-number length-number"
                />
              </template>
            </el-table-column>
            <el-table-column
              :label="t('table.nullable')"
              width="72"
              align="center"
            >
              <template #default="{ row }">
                <el-checkbox
                  v-model="row.nullable"
                  :disabled="isTableAuditField(row.fieldName)"
                />
              </template>
            </el-table-column>
            <el-table-column
              :label="t('table.primaryKey')"
              min-width="120"
              align="center"
              class-name="col-pk"
            >
              <template #default="{ row }">
                <div class="constraint-cell">
                  <el-checkbox
                    v-model="row.isPrimaryKey"
                    :disabled="isTableAuditField(row.fieldName) || row.isComputed"
                    @change="(val: boolean) => onPrimaryKeyChange(row, val)"
                  />
                  <PkGenerationEditor
                    v-if="row.isPrimaryKey && !isTableAuditField(row.fieldName) && !row.isComputed"
                    v-model="row.pkGeneration"
                    :enabled="true"
                    variant="popover"
                  />
                </div>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('table.foreignKey')"
              min-width="100"
              align="center"
            >
              <template #default="{ row }">
                <FieldForeignKeyEditor
                  v-if="!isTableAuditField(row.fieldName) && !row.isComputed"
                  :is-foreign-key="row.isForeignKey"
                  :ref-table-id="row.refTableId"
                  :ref-primary-key-fields="row.refPrimaryKeyFields"
                  :ref-tables="otherTables"
                  :ref-pk-field-options="getTableFields(row.refTableId).filter(f => f.isPrimaryKey)"
                  @update:is-foreign-key="row.isForeignKey = $event"
                  @update:ref-table-id="row.refTableId = $event"
                  @update:ref-primary-key-fields="row.refPrimaryKeyFields = $event"
                />
                <span
                  v-else-if="isTableAuditField(row.fieldName)"
                  class="text-muted"
                >—</span>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('table.computedField.column')"
              min-width="96"
              align="center"
            >
              <template #default="{ row }">
                <ComputedFieldEditor
                  v-if="!isTableAuditField(row.fieldName)"
                  :is-computed="row.isComputed"
                  :computed-field="row.computedField"
                  :computed-field-json="row.computedFieldJson"
                  :table-type="selectedTable?.tableType"
                  :disabled="row.isPrimaryKey || row.isForeignKey"
                  @update:is-computed="onComputedFieldChange(row, $event)"
                  @update:computed-field="row.computedField = $event"
                />
                <span
                  v-else
                  class="text-muted"
                >—</span>
              </template>
            </el-table-column>
            <el-table-column
              prop="defaultValue"
              :label="t('table.defaultValue')"
              min-width="88"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <el-input
                  v-model="row.defaultValue"
                  size="small"
                  :disabled="isTableAuditField(row.fieldName) || row.isComputed"
                  :placeholder="t('common.inputPlaceholder')"
                />
              </template>
            </el-table-column>
            <el-table-column
              v-if="hasDecimalFields"
              prop="precision"
              :label="t('table.precision')"
              width="76"
            >
              <template #default="{ row }">
                <el-input-number
                  v-if="row.dataType === 'DECIMAL'"
                  v-model="row.precision"
                  size="small"
                  :min="1"
                  :max="38"
                  :disabled="isTableAuditField(row.fieldName)"
                  controls-position="right"
                  class="compact-number"
                />
                <span
                  v-else
                  class="text-muted"
                >—</span>
              </template>
            </el-table-column>
            <el-table-column
              v-if="hasDecimalFields"
              prop="scale"
              :label="t('table.scale')"
              width="76"
            >
              <template #default="{ row }">
                <el-input-number
                  v-if="row.dataType === 'DECIMAL'"
                  v-model="row.scale"
                  size="small"
                  :min="0"
                  :max="20"
                  :disabled="isTableAuditField(row.fieldName)"
                  controls-position="right"
                  class="compact-number"
                />
                <span
                  v-else
                  class="text-muted"
                >—</span>
              </template>
            </el-table-column>
            <el-table-column
              width="48"
              align="center"
              fixed="right"
            >
              <template #header>
                <span class="col-header-short">&nbsp;</span>
              </template>
              <template #default="{ row, $index }">
                <el-tooltip
                  v-if="!isTableAuditField(row.fieldName)"
                  :content="t('table.delete')"
                  placement="top"
                >
                  <el-button
                    link
                    type="danger"
                    class="delete-btn"
                    @click="handleRemoveField($index)"
                  >
                    <el-icon><Delete /></el-icon>
                  </el-button>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
          <!-- Request ID: reads as the last row of the MAIN table fields grid -->
          <div
            v-if="selectedTable.tableType === 'MAIN'"
            class="request-id-field-row"
          >
            <div class="request-id-field-main">
              <span class="request-id-field-name">{{ t('table.requestId.label') }}</span>
              <el-tag
                size="small"
                type="info"
                effect="plain"
                round
                class="request-id-field-badge"
              >
                {{ t('form.virtualField') }}
              </el-tag>
              <code
                v-if="hasRequestIdConfigured"
                class="request-id-field-preview"
              >{{ requestIdPreview }}</code>
              <span
                v-else
                class="request-id-field-hint"
              >
                <el-icon><WarningFilled /></el-icon>
                {{ t('table.requestId.notConfigured') }}
              </span>
            </div>
            <el-button
              link
              type="primary"
              size="small"
              @click="showRequestIdDialog = true"
            >
              {{ t('table.requestId.configure') }}
            </el-button>
          </div>
        </div>
      </el-card>
    </div>

    <!-- Create Table Dialog -->
    <el-dialog
      v-model="showCreateDialog"
      :title="t('table.title')"
      width="500px"
    >
      <el-form
        :model="createForm"
        label-width="auto"
        label-position="left"
      >
        <el-form-item
          :label="t('table.tableDisplayName')"
          required
        >
          <el-input
            v-model="createForm.tableDisplayName"
            @input="onCreateTableDisplayNameInput"
          />
        </el-form-item>
        <el-form-item
          :label="t('table.tableName')"
          required
        >
          <el-input
            v-model="createForm.tableName"
            @input="onCreateTableNameManualInput"
          />
        </el-form-item>
        <el-form-item :label="t('table.tableType')">
          <el-select v-model="createForm.tableType">
            <el-option
              :label="t('table.mainTable')"
              value="MAIN"
            />
            <el-option
              :label="t('table.subTable')"
              value="SUB"
            />
            <el-option
              :label="t('table.actionTable')"
              value="ACTION"
            />
            <el-option
              :label="t('table.relationTable')"
              value="RELATION"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('table.description')">
          <el-input
            v-model="createForm.description"
            type="textarea"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          @click="handleCreateTable"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- DDL Dialog -->
    <el-dialog
      v-model="showDDLDialog"
      :title="t('table.ddlPreview')"
      width="700px"
    >
      <el-select
        v-model="ddlDialect"
        style="margin-bottom: 16px;"
      >
        <el-option
          label="PostgreSQL"
          value="POSTGRESQL"
        />
        <el-option
          label="MySQL"
          value="MYSQL"
        />
        <el-option
          label="Oracle"
          value="ORACLE"
        />
      </el-select>
      <el-input
        v-model="ddlContent"
        type="textarea"
        :rows="15"
        readonly
      />
      <template #footer>
        <el-button @click="handleCopyDDL">
          {{ t('table.copy') }}
        </el-button>
        <el-button @click="showDDLDialog = false">
          {{ t('table.close') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Relation Config Dialog -->
    <el-dialog
      v-model="showRelationDialog"
      :title="t('table.relationConfig')"
      width="80%"
      top="6vh"
      class="relation-diagram-dialog"
    >
      <RelationDiagramEditor
        v-if="showRelationDialog"
        v-model="relations"
        :tables="store.tables"
      />
      <template #footer>
        <el-button @click="showRelationDialog = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          @click="handleSaveRelations"
        >
          {{ t('table.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Request ID Config Dialog (MAIN tables only) -->
    <RequestIdConfigDialog
      v-if="selectedTable"
      v-model="showRequestIdDialog"
      :fields="selectedTable.fieldDefinitions"
      :config="selectedTable.requestIdConfig"
      @confirm="onRequestIdConfirm"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowLeft, Refresh, InfoFilled, WarningFilled, CaretTop, CaretBottom, Delete } from '@element-plus/icons-vue'
import { useFunctionUnitStore } from '@/stores/functionUnit'
import { type TableDefinition, type FieldDefinition, type ForeignKeyDTO, type RequestIdConfig } from '@/api/functionUnit'
import RelationDiagramEditor from '@/components/designer/RelationDiagramEditor.vue'
import RequestIdConfigDialog from '@/components/designer/RequestIdConfigDialog.vue'
import PkGenerationEditor from '@/components/designer/PkGenerationEditor.vue'
import FieldForeignKeyEditor from '@/components/designer/FieldForeignKeyEditor.vue'
import ComputedFieldEditor from '@/components/designer/ComputedFieldEditor.vue'
import { hasRequestIdConfig } from '@/utils/formFieldMeta'
import { useTableNaming } from '@/composables/tableDesigner/useTableNaming'
import { useTableList } from '@/composables/tableDesigner/useTableList'
import { useTableEditor } from '@/composables/tableDesigner/useTableEditor'
import { useTableCreate } from '@/composables/tableDesigner/useTableCreate'
import { useTableTemplate } from '@/composables/tableDesigner/useTableTemplate'
import { useTableTools } from '@/composables/tableDesigner/useTableTools'
import { isTableAuditField } from '@/utils/tableAuditFields'
import DesignerListTable from '@/components/designer-list/DesignerListTable.vue'
import DesignerHelpLink from '@/components/designer/DesignerHelpLink.vue'
import type { DesignerListTableColumn } from '@/composables/useDesignerListGrid'

interface TableRelation {
  id?: number
  sourceTableId: number | null
  sourceFieldName: string
  relationType: string
  targetTableId: number | null
  targetFieldName: string
}

const { t } = useI18n()

const props = defineProps<{ functionUnitId: number }>()

const store = useFunctionUnitStore()

// Shared state owned by the orchestrator and threaded into the composables.
const selectedTable = ref<TableDefinition | null>(null)
const tableNameTouched = ref(false)
const relations = ref<TableRelation[]>([])
const foreignKeys = ref<ForeignKeyDTO[]>([])
const showRequestIdDialog = ref(false)

// table-meta-card 的 Request ID 只读预览:用已选字段的 displayName 占位拼出形态
const requestIdPreview = computed(() => {
  const cfg = selectedTable.value?.requestIdConfig
  const fieldNames = cfg?.fieldNames
  if (!fieldNames || !fieldNames.length) return t('table.requestId.notConfigured')
  const fields = selectedTable.value?.fieldDefinitions ?? []
  const labelOf = (name: string) =>
    fields.find((f) => f.fieldName === name)?.displayName || name
  return fieldNames.map((n) => `[${labelOf(n)}]`).join(cfg?.separator ?? '-')
})

/** MAIN 表是否已配置 Request ID(决定 Save 是否放行 + 行的高亮态)。 */
const hasRequestIdConfigured = computed(() =>
  hasRequestIdConfig(selectedTable.value?.requestIdConfig),
)

function onRequestIdConfirm(cfg: RequestIdConfig | null) {
  if (selectedTable.value) {
    selectedTable.value.requestIdConfig = cfg
  }
}

function auditFieldRowClassName({ row }: { row: FieldDefinition }) {
  return isTableAuditField(row.fieldName) ? 'audit-field-row' : ''
}

// Shared technical-name primitives (validation + availability check).
const { validateName, existingTableNames, assertTableNameAvailable } = useTableNaming({
  functionUnitId: props.functionUnitId,
  store,
  t,
})

// loadTables / normalizeFieldRow form a cycle between the list and editor
// composables; break it with wrapper closures (see FormDesigner.vue).
let loadTablesImpl: () => Promise<void> | void = () => {}
const loadTables = () => loadTablesImpl()

const editor = useTableEditor({
  functionUnitId: props.functionUnitId,
  store,
  selectedTable,
  tableNameTouched,
  validateName,
  existingTableNames,
  assertTableNameAvailable,
  loadTables,
  t,
})
const {
  hasDecimalFields,
  normalizeFieldRow,
  onTableDisplayNameInput,
  onTableNameManualInput,
  handleSelectTable,
  onPrimaryKeyChange,
  onComputedFieldChange,
  onFieldDisplayNameInput,
  onFieldNameManualInput,
  handleBackToList,
  handleAddField,
  handleRemoveField,
  moveFieldUp,
  moveFieldDown,
  handleSaveTable,
} = editor

const list = useTableList({
  functionUnitId: props.functionUnitId,
  store,
  selectedTable,
  relations,
  foreignKeys,
  normalizeFieldRow,
  t,
})
const {
  loading,
  otherTables,
  tableTypeLabel,
  getTableFields,
  getTableRelations,
  handleDeleteTable,
} = list
loadTablesImpl = list.loadTables

/**
 * Table list order: MAIN first, then SUB, RELATION, ACTION.
 *
 * <p>The API returns creation order, which scatters the main table among the others — it is
 * the one readers look for first, so it leads. Ties keep the server order (stable sort), so
 * tables of the same type stay in the sequence the author created them.
 */
const TABLE_TYPE_ORDER: Record<string, number> = { MAIN: 0, SUB: 1, RELATION: 2, ACTION: 3 }

const orderedTables = computed(() => {
  const rank = (t: TableDefinition) => TABLE_TYPE_ORDER[String(t.tableType)] ?? 99
  return [...store.tables].sort((a, b) => rank(a) - rank(b))
})

const listColumns = computed<DesignerListTableColumn<TableDefinition>[]>(() => [
  {
    key: 'tableDisplayName',
    prop: 'tableDisplayName',
    label: t('table.tableDisplayName'),
    defaultWidth: 180,
    showOverflowTooltip: true,
  },
  {
    key: 'tableName',
    prop: 'tableName',
    label: t('table.tableName'),
    defaultWidth: 150,
    showOverflowTooltip: true,
  },
  {
    key: 'tableType',
    prop: 'tableType',
    label: t('table.tableType'),
    defaultWidth: 120,
    getValue: (row) => tableTypeLabel(row.tableType),
  },
  {
    key: 'description',
    prop: 'description',
    label: t('table.description'),
    defaultWidth: 200,
    showOverflowTooltip: true,
  },
  {
    key: 'fieldCount',
    label: t('table.fieldCount'),
    defaultWidth: 110,
    getValue: (row) => String(row.fieldDefinitions?.length || 0),
  },
  {
    key: 'relations',
    label: t('table.relations'),
    defaultWidth: 110,
    getValue: (row) => String(getTableRelations(row.id).length || 0),
  },
])

const {
  showCreateDialog,
  createForm,
  openCreateDialog,
  onCreateTableDisplayNameInput,
  onCreateTableNameManualInput,
  handleCreateTable,
} = useTableCreate({
  functionUnitId: props.functionUnitId,
  store,
  validateName,
  existingTableNames,
  assertTableNameAvailable,
  loadTables,
  t,
})

const {
  importing,
  fileInputRef,
  handleExportTable,
  handleImportClick,
  handleImportFile,
} = useTableTemplate({
  functionUnitId: props.functionUnitId,
  store,
  validateName,
  assertTableNameAvailable,
  loadTables,
  t,
})

const {
  showDDLDialog,
  showRelationDialog,
  ddlDialect,
  ddlContent,
  handleGenerateDDL,
  handleValidate,
  handleCopyDDL,
  handleSaveRelations,
} = useTableTools({
  functionUnitId: props.functionUnitId,
  selectedTable,
  relations,
  loadTables,
  t,
})

onMounted(loadTables)
</script>

<style lang="scss" scoped>
.table-designer {
  min-height: 400px;
  width: 100%;
  min-width: 0;
}

.table-meta-card {
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  :deep(.el-card__body) {
    padding: 16px 20px;
  }
}

.table-meta-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr) minmax(180px, 220px);
  gap: 14px 20px;
  align-items: start;
}

.meta-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.meta-field--description {
  grid-column: 1 / -1;
}

.meta-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
  line-height: 1.2;
}

.table-type-select {
  width: 100%;
}

@media (max-width: 960px) {
  .table-meta-grid {
    grid-template-columns: 1fr 1fr;
  }

  .meta-field--type {
    grid-column: 1 / -1;
    max-width: 280px;
  }
}

@media (max-width: 640px) {
  .table-meta-grid {
    grid-template-columns: 1fr;
  }

  .meta-field--type {
    max-width: none;
  }
}

.designer-toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: 8px;
}

.editor-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.back-btn {
  padding-left: 4px;
  padding-right: 8px;
  flex-shrink: 0;
}

.editor-header-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.table-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-editor {
  min-width: 0;
}

.table-fields-wrap {
  margin-top: 0;
}

/* Request ID virtual row — reads as the last row of the fields grid */
.request-id-field-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  /* match el-table small cell horizontal padding so it lines up with the grid */
  padding: 8px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-light);
  font-size: var(--el-font-size-small);
}

.request-id-field-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.request-id-field-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
  white-space: nowrap;
}

.request-id-field-badge {
  flex-shrink: 0;
}

.request-id-field-preview {
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.request-id-field-hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-color-warning);
}

.request-id-field-hint .el-icon {
  font-size: 14px;
}

.table-fields-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  :deep(.el-card__body) {
    padding: 16px;
  }
}

.fields-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.fields-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.fields-info-icon {
  font-size: 14px;
  color: var(--el-text-color-secondary);
  cursor: help;
}

.fields-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.col-header-short {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.table-fields-grid {
  :deep(.el-table__header th) {
    background: var(--el-fill-color-light);
    font-size: 12px;
    padding: 6px 0;
  }

  :deep(.el-table__cell) {
    vertical-align: middle;
    padding: 6px 0;
  }

  :deep(.col-pk .cell),
  :deep(.col-order .cell) {
    padding-left: 4px;
    padding-right: 4px;
  }

  :deep(.el-input-number.compact-number) {
    width: 100%;
  }

  :deep(.el-input-number.length-number) {
    width: 72px;

    .el-input__wrapper {
      padding-left: 6px;
      padding-right: 24px;
    }
  }

  :deep(tr.audit-field-row) {
    background-color: var(--el-fill-color-lighter);
  }

  :deep(tr.audit-field-row:hover > td.el-table__cell) {
    background-color: var(--el-fill-color-light);
  }
}

.field-display-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.audit-field-tag {
  flex-shrink: 0;
}

.constraint-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  flex-wrap: nowrap;
}

.field-order-btns {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0;
}

.order-btn {
  height: 16px;
  padding: 0;
  margin: 0;

  .el-icon {
    font-size: 12px;
  }
}

.delete-btn {
  padding: 4px;
}

.text-muted {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.table-row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
