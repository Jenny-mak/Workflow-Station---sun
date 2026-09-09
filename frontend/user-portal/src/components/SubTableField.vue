<template>
  <div class="sub-table-field">
    <div class="sub-table-header">
      <span class="title">{{ title }}</span>
      <div class="actions">
        <el-button
          v-if="editable"
          size="small"
          @click="handleExport"
        >
          <el-icon><Download /></el-icon> {{ t('subTable.exportWithData') }}
        </el-button>
        <el-button
          v-if="canAdd && !hasFileColumn"
          size="small"
          @click="triggerImport"
        >
          <el-icon><Upload /></el-icon> {{ t('subTable.import') }}
        </el-button>
        <el-button
          v-if="canAdd"
          type="primary"
          size="small"
          @click="handleAdd"
        >
          <el-icon><Plus /></el-icon> {{ t('subTable.add') }}
        </el-button>
      </div>
      <input
        ref="importInputRef"
        type="file"
        accept=".csv,.xlsx,.xls"
        style="display:none"
        @change="handleImportFile"
      >
    </div>

    <div class="sub-table-scroll-wrapper">
      <el-table
        v-loading="loading === true"
        :data="rows"
        size="small"
        border
        :max-height="400"
        style="width: 100%"
        :show-summary="hasSummary"
        :summary-method="getSummaryMethod"
      >
        <el-table-column
          v-for="col in columns"
          :key="col.field"
          :prop="col.field"
          :label="col.label"
          :min-width="columnMinWidth(col)"
          :show-overflow-tooltip="false"
        >
          <template #default="scope">
            <!-- Read-only display -->
            <!-- 状态列名来自 Sub-Task Config（miTaskStatusField），不写死 task_status：
                 列名不叫 task_status 的 FU 此前会退化成纯文本、拿不到状态标签 -->
            <template v-if="isMiStatusColumnField(col.field)">
              <el-tag
                :type="scope.row[col.field] === 'COMPLETED' ? 'success' : 'warning'"
                size="small"
              >
                {{ formatTaskStatus(scope.row[col.field]) }}
              </el-tag>
            </template>
            <template v-else-if="isUploadColumn(col, scope.row[col.field])">
              <span
                v-if="uploadCellLabel(scope.row[col.field], uploadNames[scope.$index + '_' + col.field])"
                class="file-download-link"
                @click="previewStoredFile(scope.row[col.field], uploadNames[scope.$index + '_' + col.field], col)"
              >
                <el-icon><Document /></el-icon>
                {{ uploadCellLabel(scope.row[col.field], uploadNames[scope.$index + '_' + col.field]) }}
              </span>
              <span
                v-else
                class="no-file"
              >-</span>
            </template>
            <template v-else-if="col.type === 'colorPicker'">
              <span
                v-if="scope.row[col.field]"
                class="color-swatch"
                :style="{ backgroundColor: scope.row[col.field] }"
                :title="scope.row[col.field]"
              />
              <span v-else>-</span>
            </template>
            <template v-else-if="col.type === 'editor'">
              <span
                v-if="scope.row[col.field]"
                class="editor-preview"
                v-html="sanitizeHtml(scope.row[col.field])"
              />
              <span v-else>-</span>
            </template>
            <template v-else-if="col.type === 'signature'">
              <img
                v-if="scope.row[col.field]"
                :src="scope.row[col.field]"
                class="signature-preview"
                alt="Signature"
              >
              <span v-else>-</span>
            </template>
            <template v-else-if="col.type === 'slider'">
              <el-slider
                v-if="scope.row[col.field] != null"
                :model-value="Number(scope.row[col.field])"
                :min="col.props?.min ?? 0"
                :max="col.props?.max ?? 100"
                disabled
                style="width: 100%; padding: 0 10px;"
              />
              <span v-else>-</span>
            </template>
            <template v-else-if="col.type === 'password'">
              <span>••••••</span>
            </template>
            <template v-else-if="col.type === 'switch'">
              <el-switch
                :model-value="scope.row[col.field] === true || scope.row[col.field] === 'true' || scope.row[col.field] === 1 || scope.row[col.field] === '1'"
                disabled
              />
            </template>
            <template v-else-if="col.type === 'rate'">
              <el-rate
                v-if="scope.row[col.field] != null"
                :model-value="Number(scope.row[col.field])"
                :max="col.props?.max || 5"
                disabled
                style="display: inline-flex;"
              />
              <span v-else>-</span>
            </template>
            <template v-else-if="col.type === 'lookup'">
              <div class="lookup-preview-wrapper sub-table-lookup-preview">
                <div class="lookup-form-item">
                  <label class="lookup-label-text">
                    <el-icon class="lookup-label-icon"><Search /></el-icon>
                  </label>
                  <div class="lookup-field readonly">
                    <div
                      v-if="effectiveLookupRowForCell(col, scope.row[col.field])"
                      class="lookup-selected-wrapper"
                    >
                      <span class="lookup-selected-tag">
                        <span class="lookup-selected-text">{{ lookupTagDisplayText(col, scope.row[col.field]) }}</span>
                      </span>
                    </div>
                    <el-input
                      v-else
                      model-value=""
                      placeholder="-"
                      class="lookup-input"
                      disabled
                    />
                  </div>
                </div>
                <div
                  v-if="shouldShowLookupBackfill(col) && lookupSelectedRow(col, scope.row[col.field]) && effectiveLookupViewFields(col, scope.row[col.field]).length > 0"
                  class="lookup-view-display"
                >
                  <el-descriptions
                    :column="1"
                    border
                    size="small"
                    direction="horizontal"
                  >
                    <el-descriptions-item
                      v-for="field in effectiveLookupViewFields(col, scope.row[col.field])"
                      :key="field.fieldName"
                      :label="field.displayLabel || field.fieldName"
                      label-class-name="lookup-view-label"
                      class-name="lookup-view-value"
                    >
                      {{ formatUserSnapshotCellValue(effectiveLookupRowForCell(col, scope.row[col.field])?.[field.fieldName]) }}
                    </el-descriptions-item>
                  </el-descriptions>
                </div>
              </div>
            </template>
            <template v-else-if="isUserSnapshotLikeObject(scope.row[col.field])">
              <div class="lookup-preview-wrapper sub-table-lookup-preview">
                <div class="lookup-form-item">
                  <label class="lookup-label-text">
                    <el-icon class="lookup-label-icon"><Search /></el-icon>
                  </label>
                  <div class="lookup-field readonly">
                    <div class="lookup-selected-wrapper">
                      <span class="lookup-selected-tag">
                        <span class="lookup-selected-text">{{ userObjectTagDisplayString(scope.row[col.field]) }}</span>
                      </span>
                    </div>
                  </div>
                </div>
                <div
                  v-if="shouldShowLookupBackfill(col) && userSnapshotViewFieldsFromRow(scope.row[col.field]).length > 0"
                  class="lookup-view-display"
                >
                  <el-descriptions
                    :column="1"
                    border
                    size="small"
                    direction="horizontal"
                  >
                    <el-descriptions-item
                      v-for="field in userSnapshotViewFieldsFromRow(scope.row[col.field])"
                      :key="field.key"
                      :label="field.label"
                      label-class-name="lookup-view-label"
                      class-name="lookup-view-value"
                    >
                      {{ formatUserSnapshotCellValue(getSnapshotField(scope.row[col.field], field.key)) }}
                    </el-descriptions-item>
                  </el-descriptions>
                </div>
              </div>
            </template>
            <template v-else-if="col.type === 'linkForm'">
              <el-link
                type="primary"
                :underline="false"
                @click.prevent="handleLinkFormClick(col, scope.row, scope.$index)"
              >
                {{ col.props?.linkText || t('linkForm.defaultLinkText') }}
              </el-link>
            </template>
            <template v-else-if="col.type === 'owner'">
              <template v-if="ownerChipsForRow(col, scope.row).length">
                <OwnerChip
                  v-for="(chip, chipIdx) in ownerChipsForRow(col, scope.row)"
                  :key="`${col.field}-${chipIdx}-${chip.label}`"
                  :label="chip.label"
                  :size="22"
                />
              </template>
              <span v-else>-</span>
            </template>
            <span v-else>{{ resolveDisplayValue(col, scope.row[col.field]) }}</span>
          </template>
        </el-table-column>

        <!-- Task status column (multi-instance subtask completion) -->
        <el-table-column
          v-if="effectiveShowTaskStatus"
          :label="t('subTable.taskStatus')"
          width="120"
          align="center"
        >
          <template #default="scope">
            <el-tag
              :type="scope.row[miStatusField] === 'COMPLETED' ? 'success' : 'warning'"
              size="small"
            >
              {{ formatTaskStatus(scope.row[miStatusField]) }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- fixed=right: a nested sub-table sits in a narrow row dialog, where the last
             column falls outside the visible area (Element Plus hides the horizontal
             scrollbar until hover) — pinning keeps Edit/Delete reachable. -->
        <el-table-column
          v-if="canEdit || canDelete"
          :label="t('common.operation')"
          width="120"
          fixed="right"
        >
          <template #default="scope">
            <el-button
              v-if="canEdit"
              link
              type="primary"
              size="small"
              @click="openEditDialog(scope.$index)"
            >
              {{ t('subTable.edit') }}
            </el-button>
            <el-button
              v-if="canDelete"
              link
              type="danger"
              size="small"
              @click="deleteRowAndSyncNested(scope.$index, scope.row)"
            >
              {{ t('subTable.delete') }}
            </el-button>
          </template>
        </el-table-column>

        <!-- View subtask detail button (read-only mode) -->
        <el-table-column
          v-if="effectiveShowViewDetail"
          :label="t('subTable.actions')"
          width="100"
          align="center"
        >
          <template #default="scope">
            <el-button
              link
              type="primary"
              size="small"
              :disabled="scope.row[miStatusField] !== 'COMPLETED'"
              @click="emit('viewDetail', scope.row, scope.$index)"
            >
              {{ t('subTable.viewDetail') }}
            </el-button>
          </template>
        </el-table-column>

        <!-- Fill form button for multi-instance subtask (todo mode) -->
        <el-table-column
          v-if="showFillButton"
          :label="t('subTable.actions')"
          :min-width="fillButtonLabel ? 200 : 100"
          align="center"
        >
          <template #default="scope">
            <el-button
              link
              type="primary"
              size="small"
              @click="emit('fillForm', scope.row, scope.$index)"
            >
              {{ fillButtonLabel || t('subTable.add') }}
            </el-button>
          </template>
        </el-table-column>

        <!-- Multi-instance assignment column -->
        <el-table-column
          v-if="showAssigneeColumn"
          :label="t('subTable.assignee')"
          width="180"
        >
          <template #default="scope">
            <div class="assignee-cell">
              <span
                v-if="scope.row.assignee_display_name"
                class="assignee-name"
              >
                {{ formatAssigneeDisplayLabel(scope.row.assignee_display_name) }}
              </span>
              <span
                v-else-if="resolveRowAssigneeCell(scope.row)"
                class="assignee-name"
              >
                {{ getUserDisplayName(resolveRowAssigneeCell(scope.row)) }}
              </span>
              <!-- Configured role-assignment row: show its shared pool before a user claims it. -->
              <span
                v-else-if="rowRoleCode(scope.row)"
                class="assignee-role-pool"
              >{{ t('subTable.sharedRole', { role: rowRoleCode(scope.row) }) }}</span>
              <span
                v-else
                class="text-muted"
              >{{ t('subTable.unassigned') }}</span>
              <!--
                No inline Assign/Reassign button here by design: assignment is driven solely by the
                row's Edit dialog (assignee field), so the Assignee column stays a pure display cell.
                `canAssign` still gates the Edit-dialog assignment call in useSubTableRowDialog.
              -->
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty
            :description="t('subTable.noData')"
            :image-size="40"
          />
        </template>
      </el-table>
    </div>

    <SubTableAddDialog
      :visible="dialogVisible"
      :columns="subTableDialogColumns"
      :audit-columns="listViewColumnsForAudit"
      :form-fields="formFields"
      :form-options="formOptions"
      :assignment-config="assignmentConfig"
      :mode="dialogMode"
      :initial-data="dialogInitialData"
      :row-formulas="rowFormulas"
      :field-definitions="fieldDefinitions"
      :column-validation-rules="validationConfig?.columnRules"
      :upload-url="uploadUrl"
      :nested-sub-tables="nestedSubTableDescriptors"
      :host-table-id="tableId ?? null"
      :host-field-definitions="fieldDefinitions"
      :host-function-unit-id="functionUnitId"
      :host-task-id="taskId"
      :host-primary-form-data="primaryFormData"
      :host-primary-table-id="primaryTableId ?? null"
      :host-primary-table-display-name="primaryTableDisplayName"
      :host-sub-table-bindings-for-context="subTableBindingsForContext ?? linkedSubTableBindings"
      :host-parent-tables-by-id="parentTablesById"
      :host-linked-sub-table-bindings="linkedSubTableBindings"
      :record-note-fields="recordNoteFields"
      :record-note-table-id="tableId ?? null"
      :record-note-instance-id="recordNoteInstanceId"
      :record-note-function-unit-id="functionUnitId ?? null"
      :primary-key-fields="primaryKeyFields"
      :binding-id="bindingId"
      :field-permissions="fieldPermissions"
      @update:visible="dialogVisible = $event"
      :save-row="handleDialogSaveAndSyncNested"
    />

    <Teleport to="body">
      <div
        v-if="linkFormDialogVisible"
        class="link-form-modal-overlay"
        :style="{ zIndex: linkFormOverlayZ }"
      >
        <div
          ref="linkFormModalPanelRef"
          class="link-form-modal-panel"
          role="dialog"
          aria-modal="true"
        >
          <div class="link-form-modal-header">
            <span class="link-form-modal-title">{{ linkFormModalTitle }}</span>
            <!-- Native button: el-form :disabled (readonly completed task) injects into el-button via component tree; Teleport does not break that inheritance. -->
            <button
              type="button"
              class="link-form-modal-close"
              :aria-label="t('common.close')"
              @click="closeLinkFormDetailDialog"
            >
              <el-icon :size="18">
                <Close />
              </el-icon>
            </button>
          </div>
          <div class="link-form-dialog-body">
            <el-alert
              v-if="selectedLinkBinding && linkedFormFields.length === 0"
              type="warning"
              :closable="false"
              show-icon
              :title="t('subTable.noData')"
              style="margin-bottom: 12px;"
            />
            <el-form
              v-if="selectedLinkBinding && linkedFormFields.length > 0"
              :model="linkedFormData"
              :label-width="linkedFormLabelWidth"
              label-position="left"
            >
              <el-row :gutter="20">
                <!--
                  这里**刻意不监听** `@update:sub-table-data`。

                  Link Form 弹窗编辑的是 `linkedFormData`（那一行的副本），它要等
                  `saveLinkedFormData` 在关闭时才写回 `rows.value` 对应的父行。
                  嵌套表格的增删由 `@update:field('__subTables__')` 走
                  `updateLinkedFormField` 存进 `linkedFormData.__subTables__`，
                  随后被 `saveLinkedFormData` 一并带回父行 —— 这条链路本身是完整的。

                  曾在此接过 `syncNestedSubTableBindings()`：它按 `rows.value` 跨**所有**父行
                  重算并集，而此刻 `rows.value` 还没拿到本次编辑，于是用旧数据把删除盖了回去；
                  多行场景下还会把另一父行的 correspondence 混进来
                  （实测 task a736e30f：删 Corr-000039 无效，反而多出 TRANS-000008 的 Corr-000041）。
                -->
                <PortalFormFields
                  :fields="linkedFormFields"
                  :model="linkedFormData"
                  :readonly="!canEditSelectedLinkBinding"
                  :editable="canEditSelectedLinkBinding"
                  :sub-table-bindings="linkedSubTableBindings"
                  :linked-sub-table-bindings="linkedSubTableBindings"
                  :parent-row="linkedFormData"
                  :show-link-form-dialog-footer="showLinkFormDialogFooter"
                  :field-permissions="fieldPermissions"
                  :assignment-config="(selectedLinkBinding as any)?.assignmentConfig"
                  :host-table-id="selectedLinkBinding.tableId ?? null"
                  :host-field-definitions="(selectedLinkBinding as any)?.fieldDefinitions"
                  :host-function-unit-id="functionUnitId"
                  :host-task-id="taskId"
                  :host-primary-form-data="primaryFormData"
                  :host-primary-table-id="primaryTableId ?? null"
                  @update:field="(k, v) => updateLinkedFormField(k, v)"
                />
              </el-row>
            </el-form>
            <SubTableField
              v-else-if="selectedLinkBinding"
              :title="selectedLinkBinding.tableName"
              :columns="selectedLinkBinding.columns"
              :model-value="linkedSubTableRows"
              :editable="canEditSelectedLinkBinding"
              :linked-sub-table-bindings="linkedSubTableBindings"
              :show-link-form-dialog-footer="showLinkFormDialogFooter"
              :primary-key-fields="selectedLinkBinding.primaryKeyFields"
              :field-permissions="fieldPermissions"
              @update:model-value="handleLinkedSubTableUpdate"
            />
            <el-empty
              v-else
              :description="t('subTable.noData')"
              :image-size="60"
            />
            <!-- RecordNote panels from the linked binding's form design: RECORD scope
                 binds the linked row, TABLE scope binds this table's per-process stream.
                 Placed after the v-if chain; the list is empty unless the linked form
                 design actually contains recordNote components. -->
            <div
              v-for="rn in linkFormRecordNoteFields"
              :key="rn.key"
              class="link-form-record-note"
            >
              <RecordNoteField
                :config="rn._recordNote"
                :table-id="(selectedLinkBinding as any)?.tableId ?? null"
                :record-id="linkFormRowStableId"
                :process-instance-id="recordNoteInstanceId"
                :function-unit-id="functionUnitId ?? null"
                :task-id="taskId ?? null"
                :readonly="taskId != null && rn._recordNote?.readonly === true"
              />
            </div>
          </div>
          <div
            v-if="showLinkFormDetailActionFooter"
            class="link-form-modal-footer"
          >
            <button
              type="button"
              class="link-form-modal-footer-btn link-form-modal-footer-btn--secondary"
              @click="closeLinkFormDetailDialog"
            >
              {{ t('common.cancel') }}
            </button>
            <button
              type="button"
              class="link-form-modal-footer-btn link-form-modal-footer-btn--primary"
              @click="saveLinkedFormData"
            >
              {{ t('common.save') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!--
      The standalone user-picker dialog was removed together with the inline Assign/Reassign button:
      assignment now happens only through the row Edit dialog's assignee field.
    -->
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed, withDefaults, onMounted, onBeforeUnmount, inject } from 'vue'
import { useI18n } from 'vue-i18n'
import { Plus, Document, Search, Close, Download, Upload } from '@element-plus/icons-vue'
import { getActiveMiFieldNames } from '@/composables/tasks/useMiConfig'
import SubTableAddDialog from './SubTableAddDialog.vue'
import {
  resolveDisplayValue,
  isUserSnapshotLikeObject,
  userObjectTagDisplayString,
  userSnapshotViewFieldsFromRow,
  formatUserSnapshotCellValue,
  isUploadColumn
} from './subTableAddDialogHelpers'
import type { FormField, RowFormulaRule, SubTableValidationConfig } from './formRendererHelpers'
import { pullNestedRowsForBindingFromParentRows } from '@/composables/tasks/subTableNestedRows'
import { buildNestedSubTableDescriptors } from '@/composables/subTableField/nestedSubTableDescriptors'
import { FORM_RENDERER_FIELDS_CTX } from './formRendererFieldsContext'
import { collectRecordNoteFields, resolveRowStableId } from './formRendererHelpers/recordNoteFields'
import RecordNoteField from './RecordNoteField.vue'
import { calculateSummary } from './businessLogicEngine'
import FieldRenderer from './FieldRenderer.vue'
import OwnerChip from './owner/OwnerChip.vue'
import { ownerChips } from '@/composables/owner/useOwnerFieldModel'
import PortalFormFields from './PortalFormFields.vue'
import dayjs from 'dayjs'
import type { BindingFieldDefinition } from '@/utils/subTableRowRuntime'
import type { AssignmentConfig } from '@/utils/miAssignmentConfig'
import { isAssignmentConfigured } from '@/utils/miAssignmentConfig'
import type { Column, NestedSubTableDescriptor, SubTableBinding } from '@/composables/subTableField/subTableFieldTypes'
import { sanitizeHtml } from '@/composables/subTableField/subTableHtmlSanitize'
import {
  columnMinWidth,
  lookupSelectedRow,
  effectiveLookupViewFields,
  getSnapshotField,
  useSubTableLookupCells
} from '@/composables/subTableField/useSubTableLookupCells'
import { useSubTableStatusColumns } from '@/composables/subTableField/useSubTableStatusColumns'
import { useSubTableRowKeys } from '@/composables/subTableField/useSubTableRowKeys'
import { useSubTableLinkFormScope } from '@/composables/subTableField/useSubTableLinkFormScope'
import { useSubTableLinkFormDialog } from '@/composables/subTableField/useSubTableLinkFormDialog'
import { useSubTableLinkFormOpen } from '@/composables/subTableField/useSubTableLinkFormOpen'
import { useSubTableFileDownload } from '@/composables/subTableField/useSubTableFileDownload'
import { formatAssigneeDisplayLabel, useSubTableAssignment } from '@/composables/subTableField/useSubTableAssignment'
import { useSubTableAssigneeHydration } from '@/composables/subTableField/useSubTableAssigneeHydration'
import { useSubTablePollingSync } from '@/composables/subTableField/useSubTablePollingSync'
import { useSubTableRowDialog } from '@/composables/subTableField/useSubTableRowDialog'
import { useSubTableDialogOverlay } from '@/composables/subTableAddDialog/useSubTableDialogOverlay'

const { t } = useI18n()

const props = withDefaults(defineProps<{
  title: string
  columns: Column[]
  /** Form-design canvas columns for Add/Edit row dialog (excludes list-view-only audit fields). */
  dialogColumns?: Column[]
  /** This binding's own form-design fields — nested subTable widgets here render inside the Add/Edit dialog. */
  formFields?: FormField[]
  /** Sub-form Form Design options — Add/Edit dialog Form-level onCreated / onMounted. */
  formOptions?: Record<string, unknown> | null
  /** BPMN-derived MI assignment contract; absent means no Assignment Mode behavior. */
  assignmentConfig?: AssignmentConfig
  modelValue?: any[]
  editable?: boolean
  /**
   * 子表逐操作权限（设计器右侧属性面板配置，存于组件 rule.props）。
   * 缺省/undefined => 视为 true（回退到 editable，历史表单三项全开）；显式 false => 即使 editable 也隐藏该操作。
   * editable 作为总开关仍然优先：editable 为 false 时三项一律隐藏。
   */
  allowAdd?: boolean
  allowEdit?: boolean
  allowDelete?: boolean
  loading?: boolean
  rowFormulas?: RowFormulaRule[]
  summaryColumns?: string[]
  summaryAggregations?: Record<string, 'SUM' | 'AVG' | 'COUNT' | 'MIN' | 'MAX'>
  validationConfig?: SubTableValidationConfig
  uploadUrl?: string
  // Multi-instance assignment props
  taskId?: string
  assigneeField?: string
  canAssign?: boolean
  showAssignButton?: boolean
  // Real-time sync props
  enablePolling?: boolean
  pollingInterval?: number
  enableWebSocket?: boolean
  // View detail props (application detail read-only mode)
  showViewDetail?: boolean
  showTaskStatus?: boolean
  // Fill form button (todo detail for MI subtask)
  showFillButton?: boolean
  fillButtonLabel?: string
  linkedSubTableBindings?: SubTableBinding[]
  suppressLinkFormInitialData?: boolean
  /** Task To Do only: show Cancel/Save on Link Form detail (completed / My Request omit). */
  showLinkFormDialogFooter?: boolean
  /**
   * My Request + 「汇总列表 + Link/Details」：表格内 lookup / 用户快照只显示摘要标签，不在单元格内展开 el-descriptions，
   * 避免与「详情走 Link 弹层」的设计冲突（否则看起来像待办的 inline 表单区）。
   */
  compactLookupCells?: boolean
  /**
   * 表设计器在 dw_field_definitions 中标记的主键列名（经 admin-center 随 tableBindings 下发）。
   * 仅单列时参与 assignment / 行定位；多列主键仍回退到既有 id/rowId 等待办路径。
   */
  primaryKeyFields?: string[]
  /** Field FK/PK metadata from tableBindings (PRD S5). */
  fieldDefinitions?: BindingFieldDefinition[]
  tableId?: number | null
  /** This binding's numeric id — resolves this binding's `${bindingId}:${fieldName}` entries in fieldPermissions. */
  bindingId?: number | null
  /**
   * This binding's own bindingType (SUB / ACTION / RELATED). ACTION bindings (FORM_POPUP 弹窗
   * 写入的记录表，操作留痕语义) are forced read-only regardless of allowAdd/allowEdit/allowDelete —
   * see canAdd/canEdit/canDelete below.
   */
  bindingType?: string | null
  /** Task-node field permissions (`TaskFormData.fieldPermissions`); composite-keyed entries gate Add/Edit dialog fields. */
  fieldPermissions?: Record<string, string> | null
  functionUnitId?: string
  primaryFormData?: Record<string, unknown>
  subTableBindingsForContext?: Array<{
    tableId?: number | null
    bindingType?: string
    tableName?: string
    tableDisplayName?: string
  }>
  parentRow?: Record<string, unknown> | null
  parentTableId?: number | null
  primaryTableDisplayName?: string
  primaryTableId?: number | null
  parentTablesById?: Record<number, { fieldDefinitions: BindingFieldDefinition[] }>
  /** PRD S6: structural FK vs MI participant row link. */
  bindingLinkMode?: 'structuralFk' | 'miParticipantRow' | string
  bindingForeignKeyField?: string | null
  /** Flowable MI element id — seeds attachment/link-child row_id on Add (To Do sub form2). */
  miParticipantRowId?: string | number | null
  miParentParticipantRow?: Record<string, unknown> | null
  miParentTableId?: number | null
}>(), {
  showLinkFormDialogFooter: false,
  compactLookupCells: false,
  // Per-op switches default OPEN. Without an explicit default, Vue casts an *absent*
  // Boolean prop to false (not undefined), so every call site that omits allow-add
  // (e.g. the nested table inside SubTableAddDialog) would silently lose its Add button.
  allowAdd: true,
  allowEdit: true,
  allowDelete: true
})

const emit = defineEmits<{
  (e: 'update:modelValue', val: any[]): void
  (e: 'update:primaryFormData', val: Record<string, unknown>): void
  /** Nested sub-table: auto PK allocated on the (still unsaved) parent row while saving a child. */
  (e: 'update:parentRow', val: Record<string, unknown>): void
  (e: 'assignmentChanged'): void
  (e: 'dataRefreshed', rows: any[]): void
  (e: 'viewDetail', row: any, index: number): void
  (e: 'fillForm', row: any, index: number): void
  (e: 'update:linkedSubTableData', bindingId: number, rows: any[]): void
}>()

// 子表逐操作权限：editable 总开关优先，逐项标志缺省视为放开（历史数据三项全开）
// ACTION 绑定（操作留痕记录表）恒只读，不依赖 allow*/editable props——防止历史脏数据或遗漏的
// 调用点意外把它渲染成可编辑子表。
const isActionBinding = computed(() => props.bindingType === 'ACTION')
const canAdd = computed(() => !isActionBinding.value && props.editable === true && props.allowAdd !== false)
const canEdit = computed(() => !isActionBinding.value && props.editable === true && props.allowEdit !== false)
const canDelete = computed(() => !isActionBinding.value && props.editable === true && props.allowDelete !== false)

function ownerChipsForRow(col: Column, row: Record<string, unknown>) {
  const display = row[`${col.field}__display`]
  return ownerChips(
    row[col.field] != null ? String(row[col.field]) : '',
    typeof display === 'string' ? display : undefined,
  )
}

/**
 * MI role code resolved exclusively from the binding's AssignmentConfig.
 * 用于 Assignee 列在共享认领池未认领时显示角色信息，而非误导性的 "Unassigned"。
 */
/**
 * The BPMN contract alone decides whether this sub-table is assignment-driven — the same
 * rule the Add/Edit dialog follows. Requiring an `miAssignment` marker in the form design
 * meant sub-tables whose form predates the component resolved no config, so a row
 * assigned to a BU + role showed an empty Assignee cell instead of its shared role pool.
 */
const effectiveAssignmentConfig = computed(() =>
  isAssignmentConfigured(props.assignmentConfig) ? props.assignmentConfig : undefined)

function rowRoleCode(row: Record<string, any>): string {
  const roleField = effectiveAssignmentConfig.value?.roleField
  if (!roleField) return ''
  const rc = row?.[roleField]
  if (rc != null && String(rc).trim() !== '') return String(rc).trim()
  return ''
}

// 判断列中是否存在 FILE 类型的字段（有 FILE 列时隐藏 Import 按钮）
const hasFileColumn = computed(() => {
  return props.columns.some(col => col.type === 'upload' || isUploadColumn(col))
})

// 隐藏的文件 input，用于触发 CSV 文件选择
const importInputRef = ref<HTMLInputElement | null>(null)

function triggerImport() {
  importInputRef.value?.click()
}

// 解析 CSV 文本为行数组
function parseCSV(text: string): string[][] {
  const rows: string[][] = []
  const lines = text.split(/\r?\n/).filter(line => line.trim())
  for (const line of lines) {
    const cols: string[] = []
    let current = ''
    let inQuotes = false
    for (let i = 0; i < line.length; i++) {
      const ch = line[i]
      if (inQuotes) {
        if (ch === '"') {
          if (i + 1 < line.length && line[i + 1] === '"') {
            current += '"'
            i++
          } else {
            inQuotes = false
          }
        } else {
          current += ch
        }
      } else {
        if (ch === '"') {
          inQuotes = true
        } else if (ch === ',') {
          cols.push(current.trim())
          current = ''
        } else {
          current += ch
        }
      }
    }
    cols.push(current.trim())
    rows.push(cols)
  }
  return rows
}

function handleImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  const reader = new FileReader()
  reader.onload = (e) => {
    const text = e.target?.result as string
    if (!text) return

    const csvRows = parseCSV(text)
    if (csvRows.length < 1) return

    // 第一行是 header，映射到 props.columns 的 field
    const headers = csvRows[0]
    const colFieldSet = new Set(props.columns.map(c => c.field))
    const headerToField = new Map<number, string>()
    for (let i = 0; i < headers.length; i++) {
      const h = headers[i]
      if (colFieldSet.has(h)) {
        headerToField.set(i, h)
      }
    }

    // 从第二行开始解析数据
    const newRows: any[] = []
    for (let r = 1; r < csvRows.length; r++) {
      const row: Record<string, unknown> = {}
      for (const [colIdx, field] of headerToField.entries()) {
        const val = csvRows[r][colIdx] || ''
        const col = props.columns.find(c => c.field === field)
        // 根据列类型做基本的类型转换
        if (col?.type === 'number') {
          const num = Number(val)
          row[field] = isNaN(num) ? val : num
        } else if (col?.type === 'switch') {
          row[field] = val.toLowerCase() === 'true' || val === '1'
        } else {
          row[field] = val
        }
      }
      if (Object.keys(row).length > 0) {
        newRows.push(row)
      }
    }

    if (newRows.length > 0) {
      rows.value = [...rows.value, ...newRows]
      emit('update:modelValue', [...rows.value])
    }
  }
  reader.readAsText(file)
  // Reset input so the same file can be re-imported
  input.value = ''
}

// Normalize a date/datetime cell to ISO 8601 for export.
// Handles the app's stored formats (YYYY-MM-DD / YYYY-MM-DD HH:mm:ss), Java LocalDateTime
// arrays ([y,mo,d,h,mi,s]), epoch numbers, Date objects and locale strings (e.g. 7/7/2026).
// Unparseable values are passed through untouched rather than dropped.
function toIsoDateCell(raw: unknown, withTime: boolean): string {
  if (raw == null || raw === '') return ''
  const outFmt = withTime ? 'YYYY-MM-DDTHH:mm:ss' : 'YYYY-MM-DD'
  if (Array.isArray(raw)) {
    const [y, mo = 1, d = 1, h = 0, mi = 0, s = 0] = raw as number[]
    const dt = dayjs(new Date(y, mo - 1, d, h, mi, s))
    return dt.isValid() ? dt.format(outFmt) : String(raw)
  }
  const dt = dayjs(raw as string | number | Date)
  return dt.isValid() ? dt.format(outFmt) : String(raw)
}

// A lookup cell holds either the primary-key scalar directly or a full row snapshot.
// Export the PK scalar so re-import can rehydrate the cell via fetchLookupRowByPrimaryKey.
function lookupExportScalar(col: Column, raw: unknown): string {
  if (raw == null || raw === '') return ''
  if (typeof raw !== 'object') return String(raw)
  const snapshot = raw as Record<string, unknown>
  const pkField =
    (typeof col.props?.primaryKeyField === 'string' && col.props.primaryKeyField.trim())
    || (Array.isArray(col.props?.searchFields) && typeof col.props.searchFields[0] === 'string' && col.props.searchFields[0])
    || 'id'
  const v = snapshot[pkField] ?? snapshot.id
  return v == null ? '' : String(v)
}

function handleExport() {
  // linkForm columns hold no cell value (a runtime FK-resolved link) \u2014 still excluded.
  // lookup columns are exported as their primary-key scalar so they round-trip on import.
  const cols = props.columns.filter(c => c.type !== 'linkForm')
  const headers = cols.map(c => c.field)
  // BOM for Excel UTF-8 compatibility
  let csv = '\uFEFF' + headers.map(h => `"${h.replace(/"/g, '""')}"`).join(',') + '\n'
  // Append data rows
  for (const row of rows.value) {
    const values = cols.map(c => {
      let v: unknown
      if (c.type === 'lookup') v = lookupExportScalar(c, row[c.field])
      else if (c.type === 'date') v = toIsoDateCell(row[c.field], false)
      else if (c.type === 'datetime') v = toIsoDateCell(row[c.field], true)
      else v = row[c.field]
      if (v == null || v === '') return ''
      return `"${String(v).replace(/"/g, '""')}"`
    })
    csv += values.join(',') + '\n'
  }
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${props.title || 'subtable'}_export.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

const rows = ref<any[]>([])

// Lookup cells (scalar PK hydration watch registers first — same effect order as before the split)
const { effectiveLookupRowForCell, shouldShowLookupBackfill, lookupTagDisplayText } =
  useSubTableLookupCells(props, rows)

const { formatTaskStatus, effectiveShowTaskStatus, effectiveShowViewDetail } =
  useSubTableStatusColumns(props, rows, t)

/**
 * MI 状态列名来自当前 FU 的 Sub-Task Config（miTaskStatusField），不写死 `task_status`。
 * 用 computed 而非常量：切换 task / application 详情时活动配置会变。
 *
 * <p>未配置时为 `null` —— 模板里用它取值会落到不存在的键上，故统一给一个不可能命中的占位键，
 * 让 `scope.row[miStatusField]` 恒为 undefined（渲染成 Pending），而不是误读某个真实列。
 */
const miStatusFieldRaw = computed(() => getActiveMiFieldNames().statusField)
const miStatusField = computed(() => miStatusFieldRaw.value ?? '__mi_status_unconfigured__')
const isMiStatusColumnField = (field: unknown): boolean =>
  !!miStatusFieldRaw.value && String(field ?? '') === miStatusFieldRaw.value

const rowKeys = useSubTableRowKeys(props)

const linkFormScope = useSubTableLinkFormScope(props, rowKeys)

const linkFormDialog = useSubTableLinkFormDialog(props, rows, emit, t, {
  resolveSubTableRowPk: rowKeys.resolveSubTableRowPk,
  filterRowsByMiLinkFormParent: linkFormScope.filterRowsByMiLinkFormParent
})
const {
  linkFormDialogVisible,
  linkFormModalPanelRef,
  selectedLinkBinding,
  linkFormModalTitle,
  linkedFormFields,
  linkedFormLabelWidth,
  canEditSelectedLinkBinding,
  showLinkFormDetailActionFooter,
  linkedSubTableRows,
  linkedFormData,
  updateLinkedFormField,
  closeLinkFormDetailDialog,
  saveLinkedFormData,
  handleLinkedSubTableUpdate
} = linkFormDialog

const { dialogZIndex: linkFormOverlayZ } = useSubTableDialogOverlay(linkFormDialogVisible)

const { handleLinkFormClick } = useSubTableLinkFormOpen(props, linkFormDialog, linkFormScope)

const { uploadNames, previewStoredFile, uploadCellLabel } = useSubTableFileDownload(t)

const assignment = useSubTableAssignment(props, rows, emit, t, rowKeys)
const {
  userNameCache,
  showAssigneeColumn,
  getUserDisplayName,
  resolveRowAssigneeCell,
  performSubTableRowAssignment,
} = assignment

/**
 * Test-facing bindings: SubTableField.assign / FormRenderer.subTable tests assert these via
 * {@code wrapper.vm.*}; they are not referenced by this SFC's template, so mark them as read
 * for noUnusedLocals. `performSubTableRowAssignment` is the row Edit dialog's assignment call.
 */
void userNameCache
void performSubTableRowAssignment

// Summary row support
const hasSummary = computed(() => (props.summaryColumns?.length ?? 0) > 0)

function getSummaryMethod({ columns: tableCols }: { columns: any[] }) {
  const sums: string[] = []
  tableCols.forEach((col: any, index: number) => {
    if (index === 0) {
      sums[index] = t('subTable.summary')
      return
    }
    const prop = col.property
    if (!prop || !props.summaryColumns?.includes(prop) || !props.summaryAggregations?.[prop]) {
      sums[index] = ''
      return
    }
    const agg = props.summaryAggregations[prop]
    const val = calculateSummary(rows.value, prop, agg)
    sums[index] = `${val}`
  })
  return sums
}

watch(() => props.modelValue, (v) => { rows.value = v ? [...v] : [] }, { immediate: true })

const {
  dialogVisible,
  dialogMode,
  editingRowIndex,
  dialogInitialData,
  subTableDialogColumns,
  listViewColumnsForAudit,
  handleAdd,
  openEditDialog,
  handleDialogSave,
  deleteRow
} = useSubTableRowDialog(props, rows, emit, t, assignment)
/** Test-facing binding (wrapper.vm.editingRowIndex) — see comment above. */
void editingRowIndex

/**
 * Nested sub-tables placed in this binding's own form design render inside the Add/Edit
 * dialog. Bindings resolve from the linked pool; rows persist under the edited row's
 * `__subTables__` (same convention as form-below-table / Link Form).
 */
const nestedSubTableDescriptors = computed<NestedSubTableDescriptor[]>(() =>
  buildNestedSubTableDescriptors(props.formFields as FormField[] | undefined, props.linkedSubTableBindings),
)

/**
 * Nested rows live under the edited row's `__subTables__`, but the nested binding also has its
 * own top-level `__subTables__` slice, and {@code flattenNestedSubTableRowsIntoPayload} treats
 * that flat slice as authoritative on save. Without this write-back a nested edit reaches the
 * parent row and is then overwritten by the untouched flat copy on the next load.
 *
 * The union across every parent row is what the binding holds, so recompute from {@code rows} —
 * emitting only the edited row's slice would drop the other parents' children.
 */
function syncNestedSubTableBindings() {
  for (const d of nestedSubTableDescriptors.value) {
    const union = pullNestedRowsForBindingFromParentRows(
      {
        bindingId: d.bindingId,
        tableName: d.tableName,
        designerTableName: d.designerTableName,
        tableId: d.tableId ?? null,
      },
      rows.value,
    )
    emit('update:linkedSubTableData', d.bindingId, union)
  }
}

async function handleDialogSaveAndSyncNested(row: Record<string, unknown>) {
  await handleDialogSave(row)
  syncNestedSubTableBindings()
}

async function deleteRowAndSyncNested(i: number, row?: Record<string, any>) {
  // 传行对象而不是只传下标：下标来自 el-table 的渲染序号，一旦渲染顺序与底层数组不一致，
  // splice(i,1) 删掉的就是**另一行**——表现为「删掉一个 kk，另一行的值变成了 u」。
  await deleteRow(i, row)
  syncNestedSubTableBindings()
}

// ─── RecordNote panels placed in this binding's form design ──────────────────
// Rendered inside the row Add/Edit dialog: RECORD scope binds the edited row,
// TABLE scope binds this sub-table's per-process stream. Instance id comes from
// the FormRenderer context (optional — absent in detached/preview usages).
const recordNoteCtx = inject(FORM_RENDERER_FIELDS_CTX, null)

const recordNoteFields = computed<FormField[]>(() => collectRecordNoteFields(props.formFields as FormField[] | undefined))

// Link Form modal: recordNote components of the linked binding's own form design.
const linkFormRecordNoteFields = computed<FormField[]>(() =>
  collectRecordNoteFields(linkedFormFields.value as FormField[] | undefined))

const linkFormRowStableId = computed<string | null>(() =>
  resolveRowStableId(
    linkedFormData.value as Record<string, unknown> | null,
    (selectedLinkBinding.value as { primaryKeyFields?: string[] } | null)?.primaryKeyFields,
  ))

const recordNoteInstanceId = computed<string | null>(() => {
  const v = (recordNoteCtx as { processInstanceId?: unknown } | null)?.processInstanceId
  return typeof v === 'string' && v ? v : null
})

const { clearAssigneeDisplayHydrateTimer } = useSubTableAssigneeHydration(props, rows, emit, assignment)

const { startPolling, stopPolling, startWebSocketSubscription, stopWebSocketSubscription } =
  useSubTablePollingSync(props, rows, emit, rowKeys)

// Lifecycle hooks for polling
onMounted(() => {
  if (props.enablePolling) {
    startPolling()
  }
  if (props.enableWebSocket) {
    startWebSocketSubscription()
  }
})

onBeforeUnmount(() => {
  clearAssigneeDisplayHydrateTimer()
  stopPolling()
  stopWebSocketSubscription()
})
</script>

<style scoped lang="scss">
.sub-table-field {
  border: 1px solid #e6e6e6;
  border-radius: 4px;
  padding: 12px;
  background: #fafafa;

  .sub-table-scroll-wrapper {
    width: 100%;
    overflow-x: auto;
  }

  :deep(.el-table .cell) {
    white-space: nowrap;
  }

  .sub-table-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 10px;

    .title {
      font-weight: 500;
      font-size: 14px;
      color: #303133;
    }
  }

  .file-download-link {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    color: #165DFF;
    text-decoration: underline;
    font-size: 12px;
    cursor: pointer;
    transition: color 0.2s;

    &:hover { color: #0e44cc; }
  }

  .no-file {
    color: #909399;
    font-size: 12px;
  }

  .color-swatch {
    display: inline-block;
    width: 20px;
    height: 20px;
    border-radius: 3px;
    border: 1px solid #dcdfe6;
    vertical-align: middle;
  }

  .editor-preview {
    display: inline-block;
    max-width: 200px;
    max-height: 60px;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 12px;
    line-height: 1.4;
  }

  .signature-preview {
    max-width: 120px;
    max-height: 40px;
    object-fit: contain;
    vertical-align: middle;
  }

  .lookup-preview-wrapper {
    margin-bottom: 0;
  }

  .lookup-form-item {
    display: flex;
    align-items: flex-start;
  }

  .lookup-label-text {
    white-space: nowrap;
    width: auto;
    min-width: fit-content;
    max-width: 200px;
    height: auto;
    line-height: 1.5;
    padding-top: 6px;
    padding-right: 12px;
    font-size: 14px;
    color: #606266;
    box-sizing: border-box;
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .lookup-label-icon {
    color: #409eff;
    font-size: 14px;
  }

  .lookup-field {
    flex: 1;
    min-width: 0;
    position: relative;

    &.readonly {
      cursor: not-allowed;

      .lookup-selected-wrapper {
        background: var(--el-disabled-bg-color, #f5f7fa);
        border-color: var(--el-disabled-border-color, #e4e7ed);
        cursor: not-allowed;
        pointer-events: none;
      }

      .lookup-input :deep(.el-input__wrapper) {
        background-color: var(--el-disabled-bg-color, #f5f7fa);
        box-shadow: 0 0 0 1px var(--el-disabled-border-color, #e4e7ed) inset;
        cursor: not-allowed;
        pointer-events: none;
      }
    }

    .lookup-selected-wrapper {
      display: flex;
      align-items: center;
      min-height: 32px;
      padding: 4px 8px;
      border: 1px solid #dcdfe6;
      border-radius: 4px;
      background: #fff;
    }

    .lookup-selected-tag {
      display: inline-flex;
      align-items: center;
      max-width: 100%;
      height: 24px;
      padding: 0 8px;
      border-radius: 4px;
      background: #f0f2f5;
      font-size: 13px;
      color: #909399;
      line-height: 24px;

      .lookup-selected-text {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }

  .lookup-readonly-empty {
    color: #909399;
    line-height: 32px;
  }

  .lookup-input {
    width: 100%;
  }

  .lookup-view-display {
    margin-top: 8px;

    :deep(.lookup-view-label) {
      width: 40%;
      font-weight: 500;
      color: #606266;
      background: #fafafa;
    }

    :deep(.lookup-view-value) {
      color: #303133;
    }
  }

  .assignee-cell {
    display: flex;
    align-items: center;
    gap: 8px;

    .assignee-name {
      font-size: 13px;
      color: #303133;
    }

    .text-muted {
      font-size: 13px;
      color: #909399;
    }
  }
}

.link-form-modal-overlay {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background: rgba(0, 0, 0, 0.45);
}

.link-form-modal-panel {
  width: min(700px, calc(100vw - 48px));
  max-height: 84vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 4px;
  background: #fff;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.18);
}

.link-form-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
  color: #303133;
}

.link-form-modal-title {
  flex: 1;
  min-width: 0;
  font-weight: 600;
  font-size: 16px;
  line-height: 1.4;
}

.link-form-modal-close {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin: 0;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: #909399;
  cursor: pointer;

  &:hover {
    color: var(--el-color-primary);
    background: var(--el-fill-color-light);
  }

  &:focus-visible {
    outline: 2px solid var(--el-color-primary);
    outline-offset: 2px;
  }
}

.link-form-dialog-body {
  min-height: 160px;
  padding: 16px;
  overflow: auto;
  flex: 1;
  min-height: 0;
}

.link-form-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  flex-shrink: 0;
  padding: 12px 16px;
  border-top: 1px solid #ebeef5;
  background: #fafafa;
}

.link-form-modal-footer-btn {
  min-width: 88px;
  padding: 8px 20px;
  font-size: 14px;
  line-height: 1.5;
  border-radius: 4px;
  cursor: pointer;
  border: 1px solid transparent;
}

.link-form-modal-footer-btn--secondary {
  border-color: #dcdfe6;
  color: #606266;
  background: #fff;

  &:hover {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary-light-5);
  }
}

.link-form-modal-footer-btn--primary {
  color: #fff;
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);

  &:hover {
    background: var(--el-color-primary-light-3);
    border-color: var(--el-color-primary-light-3);
  }
}

.linked-form-card {
  width: 100%;
  margin-bottom: 12px;
}
</style>
