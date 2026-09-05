<template>
  <div class="form-designer">
    <!-- Form list view -->
    <FormListSidebar
      v-if="!selectedForm"
      :function-unit-id="props.functionUnitId"
      :forms="store.forms"
      :loading="loading"
      :has-tables="store.tables.length > 0"
      :form-type-label="formTypeLabel"
      :get-primary-binding="getPrimaryBinding"
      :get-sub-bindings-count="getSubBindingsCount"
      :get-table-name="getTableName"
      :get-form-bound-nodes="getFormBoundNodes"
      :tables="store.tables"
      :main-table-views="mainTableViews"
      :initial-scene="lastFormListScene"
      @create="showCreateDialog = true"
      @refresh="loadForms"
      @import-from-table="handleImportFromTable"
      @select-form="handleSelectForm"
      @delete-form="handleDeleteForm"
      @more-action="onFormListMoreAction"
      @set-form-bound-views="handleSetFormBoundViews"
    />

    <!-- Form designer view -->
    <div
      v-else
      class="form-editor-view"
    >
      <div class="editor-header">
        <el-button @click="handleBackToList">
          <el-icon><ArrowLeft /></el-icon> {{ t('form.backToList') }}
        </el-button>
        <el-input
          v-if="inlineRenaming"
          ref="inlineRenameInputRef"
          v-model="inlineRenameName"
          class="form-name-input"
          size="default"
          @keyup.enter="handleInlineRenameConfirm"
          @keyup.escape="inlineRenaming = false"
          @blur="handleInlineRenameConfirm"
        />
        <span
          v-else
          class="form-name"
          :title="t('form.clickToRename')"
          @click="startInlineRename(selectedForm)"
        >{{ selectedForm.formName }}</span>
        <el-tag
          type="info"
          size="small"
          class="form-scene-tag"
        >
          {{ formTypeLabel(selectedForm.formType) }} · {{ selectedFormSceneLabel }}
        </el-tag>
        <el-tag
          v-if="selectedForm.boundTableId"
          type="success"
          size="small"
          class="bound-table-tag"
        >
          {{ t('form.boundTableLabel') }}: {{ getTableName(selectedForm.boundTableId) }}
        </el-tag>
        <div class="header-actions">
          <div class="auto-save-status">
            <span
              v-if="autoSaving"
              class="auto-saving"
            >
              <el-icon class="is-loading"><Loading /></el-icon>
              {{ t('form.autoSaving') }}
            </span>
            <span
              v-else-if="lastAutoSaveTime"
              class="auto-saved"
            >
              <el-icon><CircleCheck /></el-icon>
              {{ t('form.autoSaved') }} {{ formatAutoSaveTime(lastAutoSaveTime) }}
            </span>
          </div>
          <el-button @click="handleManageBindings(selectedForm)">
            {{ t('form.manageBindings') }}
          </el-button>
          <el-button
            :disabled="!selectedForm.boundTableId && (!selectedForm.tableBindings || selectedForm.tableBindings.length === 0)"
            @click="handleImportFieldsToDesigner"
          >
            <el-icon><Connection /></el-icon> {{ t('form.importTableFields') }}
          </el-button>
          <el-button @click="handleBindNode(selectedForm)">
            {{ t('form.bindProcessNode') }}
          </el-button>
          <el-button @mousedown.capture="prepareCustomPreviewValidation" @click="handlePreview">
            {{ t('common.preview') }}
          </el-button>
          <el-button
            type="primary"
            :loading="savingForm"
            :disabled="savingForm"
            @click="handleSaveForm(true)"
          >
            {{ t('common.save') }}
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="activeMiAssignmentWarning"
        class="mi-assignment-warning"
        :title="activeMiAssignmentWarning"
        type="warning"
        :closable="false"
        show-icon
      />
      
      <!-- Grouped tab navigation: Main Table | Sub Tables ▾ | Relation Tables ▾ -->
      <div class="designer-grouped-nav">
        <button
          v-if="showMainTableTab"
          class="designer-nav-btn"
          :class="{ 'is-active': activeDesignerTab === 'main' }"
          @click="switchToMain"
        >
          <el-tag type="primary" size="small" class="nav-tag">{{ t('form.mainTable') }}</el-tag>
          <span class="nav-label">{{ selectedForm.formName }}</span>
        </button>

        <el-dropdown
          v-if="designerSubBindingsGrouped.length > 0"
          trigger="click"
          placement="bottom-start"
          @command="(id: string) => switchToBinding(id)"
        >
          <button
            class="designer-nav-btn"
            :class="{ 'is-active': activeTabGroup === 'sub' }"
          >
            <el-tag type="success" size="small" class="nav-tag">{{ t('tableBinding.subTableType') }}</el-tag>
            <span class="nav-label">{{ subBindingsNavLabel }}</span>
            <el-icon class="nav-arrow"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="b in designerSubBindingsGrouped"
                :key="b.bindingId"
                :command="String(b.bindingId)"
                :class="{ 'is-active-item': activeDesignerTab === String(b.bindingId) }"
              >
                <span class="dropdown-item-inner">
                  <el-icon
                    class="check-icon"
                    :class="{ 'is-visible': activeDesignerTab === String(b.bindingId) }"
                  >
                    <Check />
                  </el-icon>
                  <span class="dropdown-item-label">{{ b.tableName }}</span>
                </span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <el-dropdown
          v-if="designerActionBindingsGrouped.length > 0"
          trigger="click"
          placement="bottom-start"
          @command="(id: string) => switchToBinding(id)"
        >
          <button
            class="designer-nav-btn"
            :class="{ 'is-active': activeTabGroup === 'action' }"
          >
            <el-tag type="danger" size="small" class="nav-tag">{{ t('tableBinding.actionTableType') }}</el-tag>
            <span class="nav-label">{{ actionBindingsNavLabel }}</span>
            <el-icon class="nav-arrow"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="b in designerActionBindingsGrouped"
                :key="b.bindingId"
                :command="String(b.bindingId)"
                :class="{ 'is-active-item': activeDesignerTab === String(b.bindingId) }"
              >
                <span class="dropdown-item-inner">
                  <el-icon
                    class="check-icon"
                    :class="{ 'is-visible': activeDesignerTab === String(b.bindingId) }"
                  >
                    <Check />
                  </el-icon>
                  <span class="dropdown-item-label">{{ b.tableName }}</span>
                </span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <el-dropdown
          v-if="designerRelationBindingsGrouped.length > 0"
          trigger="click"
          placement="bottom-start"
          @command="(id: string) => switchToBinding(id)"
        >
          <button
            class="designer-nav-btn"
            :class="{ 'is-active': activeTabGroup === 'relation' }"
          >
            <el-tag type="warning" size="small" class="nav-tag">{{ t('tableBinding.relationTableType') }}</el-tag>
            <span class="nav-label">{{ relationBindingsNavLabel }}</span>
            <el-icon class="nav-arrow"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="b in designerRelationBindingsGrouped"
                :key="b.bindingId"
                :command="String(b.bindingId)"
                :class="{ 'is-active-item': activeDesignerTab === String(b.bindingId) }"
              >
                <span class="dropdown-item-inner">
                  <el-icon
                    class="check-icon"
                    :class="{ 'is-visible': activeDesignerTab === String(b.bindingId) }"
                  >
                    <Check />
                  </el-icon>
                  <span class="dropdown-item-label">{{ b.tableName }}</span>
                </span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <el-tabs
        v-model="activeDesignerTab"
        class="designer-tabs designer-tabs--headless"
        @tab-change="handleTabChange"
      >
        <el-tab-pane
          v-if="showMainTableTab"
          name="main"
        >
          <template #label>
            <span>
              <el-tag
                type="primary"
                size="small"
                style="margin-right: 6px;"
              >{{ t('form.mainTable') }}</el-tag>
              {{ selectedForm.formName }}
            </span>
          </template>
          <div
            class="fc-designer-wrapper"
            :style="designerZoomStyle"
          >
            <div class="form-designer-canvas-toolbar-host">
              <FormDesignerCanvasToolbar
                v-model:show-hidden="designerShowHidden"
                v-model:zoom-percent="designerZoomPercent"
                in-designer-bar
              />
            </div>
            <div class="fc-designer-zoom-stage">
              <fc-designer
                ref="designerRef"
                :locale="fcDesignerEnLocale"
                :config="designerConfig"
                height="calc(100vh - 260px)"
                @active="onDesignerStructureChange"
                @change-field="onDesignerStructureChange"
              />
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane
          v-for="(binding, index) in designerSubBindings"
          :key="binding.bindingId"
          :name="String(binding.bindingId)"
        >
          <template #label>
            <span>
              <el-tag
                :type="bindingTypeTag(binding.bindingType)"
                size="small"
                style="margin-right: 6px;"
              >
                {{ binding.bindingType === 'SUB' ? t('tableBinding.subTableType')
                  : binding.bindingType === 'ACTION' ? t('tableBinding.actionTableType')
                  : t('tableBinding.relationTableType') }}
              </el-tag>
              {{ binding.tableName }}
            </span>
          </template>
          <!-- Relation Table: show data view instead of form designer -->
          <RelationTableView
            v-if="binding.bindingType === 'RELATED'"
            :ref="(el: any) => { if (el) relationTableViewRefs[binding.bindingId] = el }"
            :binding="binding"
            :function-unit-id="props.functionUnitId"
            :form-id="selectedForm!.id"
            :available-fields="relationViewState[binding.bindingId]?.allFields || []"
            :model-value="relationViewState[binding.bindingId]?.viewFields || []"
            @update:model-value="(val: any) => updateRelationViewFields(binding.bindingId, val)"
            @update:available-fields="(val: any) => updateRelationViewAllFields(binding.bindingId, val)"
          />
          <!-- Sub Table / Action Table: show form designer with List View tab (FORM_ONLY has no list view).
               ACTION bindings (e.g. FORM_POPUP "Meeting Remark") get the same canvas as SUB — they are a
               table of their own that the popup form writes to, structurally identical to a sub-table. -->
          <div
            v-else-if="binding.bindingType === 'SUB' || binding.bindingType === 'ACTION'"
            class="sub-table-design-wrapper"
          >
            <div class="sub-inner-tabs">
              <div class="sub-inner-tabs__header">
                <button
                  class="sub-inner-tab-btn"
                  :class="{ 'is-active': subTableActiveTab === 'form' }"
                  @click="subTableActiveTab = 'form'"
                >{{ t('subTableView.formDesign') }}</button>
                <button
                  v-if="binding.subMode !== 'FORM_ONLY' && binding.bindingType !== 'ACTION'"
                  class="sub-inner-tab-btn"
                  :class="{ 'is-active': subTableActiveTab === 'listView' }"
                  @click="handleSubTableInnerTabChange('listView', binding); subTableActiveTab = 'listView'"
                >{{ t('subTableView.listView') }}</button>
              </div>

              <div v-show="subTableActiveTab === 'form'">
                <div
                  class="fc-designer-wrapper"
                  :style="designerZoomStyle"
                >
                  <div class="form-designer-canvas-toolbar-host">
                    <FormDesignerCanvasToolbar
                      v-model:show-hidden="designerShowHidden"
                      v-model:zoom-percent="designerZoomPercent"
                      in-designer-bar
                    />
                  </div>
                  <div class="fc-designer-zoom-stage">
                    <fc-designer
                      :ref="(el: any) => setSubDesignerRef(el, index)"
                      :locale="fcDesignerEnLocale"
                      :config="designerConfig"
                      height="calc(100vh - 320px)"
                      @active="onSubDesignerStructureChange"
                      @change-field="onSubDesignerStructureChange"
                    />
                  </div>
                </div>
              </div>

              <div v-show="subTableActiveTab === 'listView' && binding.subMode !== 'FORM_ONLY' && binding.bindingType !== 'ACTION'">
                <SubTableListView
                  :ref="(el: any) => setSubTableListViewRef(el, binding.bindingId)"
                  :binding="binding"
                  :function-unit-id="props.functionUnitId"
                  :form-id="selectedForm!.id"
                  :available-fields="subTableViewState[binding.bindingId]?.allFields || []"
                  :model-value="subTableViewState[binding.bindingId]?.viewFields || []"
                  :link-form-components="linkFormComponents"
                  :sub-table-bindings="designerSubBindings.filter(b => b.bindingType === 'SUB')"
                  :resolve-sub-table-form-design="getSubTableFormDesign"
                  :resolve-lookup-preview-config="resolveLookupPreviewConfig"
                  :form-rule="getSubTableFormRule(binding.bindingId)"
                  :form-option="getSubTableFormOption(binding.bindingId)"
                  @update:model-value="(val: any) => updateSubTableViewFields(binding.bindingId, val)"
                  @update:available-fields="(val: any) => updateSubTableViewAllFields(binding.bindingId, val)"
                  @save="handleSubTableViewSave(binding.bindingId)"
                />
              </div>
            </div>
          </div>
          <!-- Sub Table (non-SUB fallback, should not happen) -->
          <div
            v-else
            class="fc-designer-wrapper"
            :style="designerZoomStyle"
          >
            <div class="form-designer-canvas-toolbar-host">
              <FormDesignerCanvasToolbar
                v-model:show-hidden="designerShowHidden"
                v-model:zoom-percent="designerZoomPercent"
                in-designer-bar
              />
            </div>
            <div class="fc-designer-zoom-stage">
              <fc-designer
                :ref="(el: any) => setSubDesignerRef(el, index)"
                :locale="fcDesignerEnLocale"
                :config="designerConfig"
                height="calc(100vh - 260px)"
                @active="onDesignerStructureChange"
                @change-field="onDesignerStructureChange"
              />
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- Field Permission Configuration (TASK forms only) -->
      <div
        v-if="selectedForm.formType === 'TASK'"
        class="field-permission-section"
        style="margin-top: 16px;"
      >
        <el-divider content-position="left">
          {{ t('form.fieldPermission') }}
        </el-divider>
        <div class="table-scroll-wrap">
        <el-table
          :data="currentFormFields"
          size="small"
          max-height="300"
          border
        >
          <el-table-column
            prop="field"
            :label="t('form.fieldName')"
            width="200"
          />
          <el-table-column
            prop="title"
            label="Label"
            width="200"
          />
          <el-table-column
            :label="t('form.fieldPermission')"
            width="180"
          >
            <template #default="{ row }">
              <el-select
                :model-value="getFieldPermission(row.field)"
                size="small"
                style="width: 100%"
                :disabled="isFieldPermissionLocked(row.field)"
                @update:model-value="setFieldPermission(row.field, $event)"
              >
                <el-option
                  :label="t('form.fieldPermissionEditable')"
                  value="EDITABLE"
                  :disabled="isFieldPermissionLocked(row.field)"
                />
                <el-option
                  :label="t('form.fieldPermissionReadonly')"
                  value="READONLY"
                />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
        </div>

        <!-- Sub-table field permissions, grouped by SUB binding -->
        <div
          v-for="group in currentSubFormFieldGroups"
          :key="group.bindingId"
          class="field-permission-subform-group"
          style="margin-top: 12px;"
        >
          <div class="field-permission-subform-label" style="margin-bottom: 4px; font-size: 13px; color: var(--el-text-color-secondary);">
            {{ group.label }}
          </div>
          <div class="table-scroll-wrap">
          <el-table
            :data="group.fields"
            size="small"
            max-height="300"
            border
          >
            <el-table-column
              prop="field"
              :label="t('form.fieldName')"
              width="200"
            />
            <el-table-column
              prop="title"
              label="Label"
              width="200"
            />
            <el-table-column
              :label="t('form.fieldPermission')"
              width="180"
            >
              <template #default="{ row }">
                <el-select
                  :model-value="getFieldPermission(row.field, group.bindingId)"
                  size="small"
                  style="width: 100%"
                  @update:model-value="setFieldPermission(row.field, $event, group.bindingId)"
                >
                  <el-option
                    :label="t('form.fieldPermissionEditable')"
                    value="EDITABLE"
                  />
                  <el-option
                    :label="t('form.fieldPermissionReadonly')"
                    value="READONLY"
                  />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
          </div>
        </div>
      </div>
    </div>

    <!-- Create form dialog -->
    <FormCreateDialog
      v-model="showCreateDialog"
      v-model:stage-ids="createFormStageIds"
      :create-form="createForm"
      :forms="store.forms"
      :tables="store.tables"
      :create-dialog-process-nodes="createDialogProcessNodes"
      :table-type-label="tableTypeLabel"
      :handle-create-form-type-change="handleCreateFormTypeChange"
      @confirm="handleCreateForm"
    />

    <!-- Rename form dialog -->
    <FormRenameDialog
      v-model="showRenameDialog"
      v-model:form-name="renameFormName"
      :loading="renaming"
      :title="t('form.renameFormTitle')"
      @confirm="handleConfirmRename"
    />

    <!-- Preview dialog -->
    <el-dialog
      v-model="showPreviewDialog"
      class="form-preview-dialog"
      :title="t('form.previewTitle')"
      width="900px"
      append-to-body
      :modal="false"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div
        v-loading="previewBuilding"
        :element-loading-text="t('form.previewLoading')"
        class="preview-container"
      >
        <FormPreviewItems
          v-if="!previewBuilding && previewItems.length > 0 && previewFormReady"
          v-model:preview-data="previewData"
          v-model:preview-table-rows="previewTableRows"
          :items="previewItems"
          :preview-option="previewDialogOption"
          :function-unit-id="functionUnitId"
          :primary-table-display-name="previewPrimaryTableDisplayName"
          :primary-table-id="previewPrimaryTableId"
          :parent-tables-by-id="previewParentTablesById"
          :preview-table-bindings="previewTableBindingsForContext"
          :request-id-config="previewRequestIdConfig"
        />
        <el-empty
          v-else-if="!previewBuilding"
          :description="t('form.noFormContent')"
        />
      </div>
    </el-dialog>

    <SubTableFormDialog
      :visible="previewRowDialog.visible && previewRowDialog.useFormRule"
      :title="previewRowDialog.title"
      :mode="previewRowDialog.mode"
      :initial-data="previewRowDialog.initialData"
      :rule="previewRowDialog.formRule"
      :option="previewRowDialog.formOption"
      :columns="previewRowDialog.columns"
      :assignment-config="previewRowDialog.assignmentConfig"
      @update:visible="onPreviewRowDialogVisibleChange"
      @save="handlePreviewRowDialogSave"
    />
    <SubTableAddDialog
      :visible="previewRowDialog.visible && !previewRowDialog.useFormRule"
      :columns="previewRowDialog.columns"
      :title="previewRowDialog.title"
      :mode="previewRowDialog.mode"
      :initial-data="previewRowDialog.initialData"
      @update:visible="onPreviewRowDialogVisibleChange"
      @save="handlePreviewRowDialogSave"
    />

    <!-- Bind node dialog -->
    <FormNodeBindDialog
      v-model="showBindDialog"
      :process-nodes="processNodes"
      :bind-dialog-key="bindDialogKey"
      :is-node-selected="isNodeSelected"
      :is-node-read-only="isNodeReadOnly"
      :toggle-node-selection="toggleNodeSelection"
      :set-node-read-only="setNodeReadOnly"
      :node-type-label="nodeTypeLabel"
      @confirm="handleConfirmBind"
    />

    <!-- Import fields from table dialog -->
    <el-dialog
      v-model="showImportFieldsDialog"
      :title="t('form.importFieldsTitle')"
      width="960px"
    >
      <div class="import-fields-dialog">
        <el-alert
          type="info"
          :closable="false"
          style="margin-bottom: 16px;"
        >
          {{ t('form.importFieldsHint') }}
          <span
            v-if="formBindings.length > 0"
            style="display: block; margin-top: 4px;"
          >
            {{ t('form.importFieldsHintWithBindings', { count: formBindings.length }) }}
          </span>
        </el-alert>
        
        <el-form
          label-width="auto"
          label-position="left"
          style="margin-bottom: 16px;"
        >
          <el-form-item :label="t('form.selectTable')">
            <el-select
              v-model="importTableId"
              :placeholder="t('form.selectTable')"
              style="width: 100%;"
              @change="handleTableChange"
            >
              <el-option-group
                v-if="formBindings.length > 0"
                :label="t('form.boundTables')"
              >
                <el-option 
                  v-for="binding in formBindings" 
                  :key="binding.tableId" 
                  :label="`${getTableName(binding.tableId, binding.tableName)} (${bindingTypeLabel(binding.bindingType)})`" 
                  :value="binding.tableId"
                >
                  <div class="table-option-with-binding">
                    <span>{{ getTableName(binding.tableId, binding.tableName) }}</span>
                    <el-tag
                      size="small"
                      :type="bindingTypeTag(binding.bindingType)"
                    >
                      {{ bindingTypeLabel(binding.bindingType) }}
                    </el-tag>
                  </div>
                </el-option>
              </el-option-group>
              <el-option-group :label="t('form.allTables')">
                <el-option
                  v-for="table in store.tables"
                  :key="table.id"
                  :label="`${table.tableDisplayName || table.tableName} (${tableTypeLabel(table.tableType)})`"
                  :value="table.id"
                />
              </el-option-group>
            </el-select>
          </el-form-item>
        </el-form>
        
        <div
          v-if="importTableId"
          class="field-selection"
        >
          <div class="field-header">
            <el-checkbox 
              :model-value="isAllFieldsSelected" 
              :indeterminate="isFieldsIndeterminate"
              @change="(val: any) => handleSelectAllFields(!!val)"
            >
              {{ t('form.selectAll') }}
            </el-checkbox>
            <span class="field-count">{{ t('form.selectedCount', { count: selectedImportFields.length, total: availableFields.length }) }}</span>
            <el-tag
              v-if="getImportTableBinding()"
              size="small"
              :type="bindingTypeTag(getImportTableBinding()!.bindingType)"
              style="margin-left: 8px;"
            >
              {{ bindingTypeLabel(getImportTableBinding()!.bindingType) }}
            </el-tag>
          </div>
          
          <div class="import-fields-table-wrap">
          <el-table
            :data="availableFields"
            size="small"
            max-height="300"
            :row-class-name="importFieldsRowClassName"
          >
            <el-table-column width="50">
              <template #default="{ row }">
                <el-tooltip
                  v-if="isFieldAlreadyImported(row)"
                  :content="t('form.alreadyOnForm')"
                  placement="top"
                >
                  <el-icon class="already-imported-icon">
                    <CircleCheck />
                  </el-icon>
                </el-tooltip>
                <el-checkbox
                  v-else
                  :model-value="isFieldSelected(row.fieldName)"
                  @change="toggleFieldSelection(row)"
                />
              </template>
            </el-table-column>
            <el-table-column
              prop="displayName"
              :label="t('form.displayName')"
              width="150"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <span>{{ row.displayName || row.fieldName }}</span>
              </template>
            </el-table-column>
            <el-table-column
              prop="fieldName"
              :label="t('form.fieldName')"
              min-width="150"
            >
              <template #default="{ row }">
                <span>{{ isRequestIdSyntheticField(row) ? (row.displayName || row.fieldName) : row.fieldName }}</span>
                <el-tag
                  v-if="isRequestIdSyntheticField(row)"
                  size="small"
                  type="info"
                  effect="plain"
                  class="virtual-field-tag"
                >
                  {{ t('form.virtualField') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="dataType"
              :label="t('form.dataType')"
              width="100"
            >
              <template #default="{ row }">
                {{ isRequestIdSyntheticField(row) ? '—' : row.dataType }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('form.formComponent')"
              width="150"
            >
              <template #default="{ row }">
                <el-tag size="small">
                  {{ isRequestIdSyntheticField(row) ? t('form.readonlyText') : getFormComponentType(row.dataType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              v-if="formBindings.length > 0"
              :label="t('form.sourceTable')"
              width="130"
            >
              <template #default>
                <span class="source-table">{{ getTableName(importTableId!) }}</span>
              </template>
            </el-table-column>
            <el-table-column
              prop="nullable"
              :label="t('form.required')"
              width="90"
            >
              <template #default="{ row }">
                <el-tag
                  :type="row.nullable ? 'info' : 'danger'"
                  size="small"
                >
                  {{ row.nullable ? t('form.no') : t('form.yes') }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          </div>
        </div>
        
        <el-empty
          v-else
          :description="t('form.selectTableFirst')"
        />
      </div>
      <template #footer>
        <el-button @click="showImportFieldsDialog = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :disabled="selectedImportFields.length === 0"
          @click="handleConfirmImportFields"
        >
          {{ t('form.importButton', { count: selectedImportFields.length }) }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Paste form configJson (cross-FU) with live binding repair -->
    <el-dialog
      v-model="showPasteConfigDialog"
      :title="t('form.pasteFormConfigTitle')"
      width="640px"
      destroy-on-close
      class="form-designer-dialog"
    >
      <p class="paste-config-hint">
        {{ t('form.pasteFormConfigHint') }}
      </p>
      <el-input
        v-model="pasteConfigText"
        type="textarea"
        :rows="14"
        :placeholder="t('form.pasteFormConfigPlaceholder')"
      />
      <template #footer>
        <el-button @click="showPasteConfigDialog = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="pasteRepairing"
          @click="handleConfirmPasteConfig"
        >
          {{ t('form.pasteFormConfigApply') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="showBindingManagerDialog"
      :title="t('form.manageBindingsTitle')"
      width="700px"
      destroy-on-close
    >
      <TableBindingManager
        v-if="bindingManagerForm"
        ref="bindingManagerRef"
        :function-unit-id="props.functionUnitId"
        :form-id="bindingManagerForm.id"
        :form-type="bindingManagerForm.formType"
        :tables="store.tables"
        @update="handleBindingUpdate"
        @add="handleBindingAdded"
      />
      <template #footer>
        <el-button @click="showBindingManagerDialog = false">
          {{ t('form.closeButton') }}
        </el-button>
      </template>
    </el-dialog>

    <BlockingProgressOverlay
      :visible="blockingProgressVisible"
      :message="blockingProgressMessage"
      :detail="blockingProgressDetail"
    />
  </div>
</template>
<script setup lang="ts">
import { ref, computed, provide, watch, toRef, reactive, nextTick, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowLeft, ArrowDown, Check, Connection, Loading, CircleCheck } from '@element-plus/icons-vue'
import { useFunctionUnitStore } from '@/stores/functionUnit'
import type { FormDefinition, TableBinding } from '@/api/functionUnit'
import { functionUnitApi } from '@/api/functionUnit'
import type { MainTableViewDefinition } from '@/api/mainTableView'
import { mainTableViewApi } from '@/api/mainTableView'
import fcDesignerEnLocale from '@form-create/designer/locale/en.js'
import {
  buildDefaultFormCreateOptions,
  buildDesignerUpdateDefaultRule,
  ensureEmptyRuleComponentEvents,
  walkRulesEnsureComponentEvents,
} from '@/utils/formCreateDefaultEvents'
import { buildSelectDefaultValuePropRule } from '@/utils/formCreateSelectDefaultValue'
import {
  flushDesignerValidatePanelToActiveRule,
  installFcDesignerPreviewCapture,
  installPreviewValidationDomProbe,
  wrapFcDesignerOpenPreview,
} from '@/utils/formDesignerPreviewValidation'
import { lookupStore } from './lookupStore'
import { formControlTypeStore } from './formControlTypeStore'
import {
  PREVIEW_MY_REQUESTS_ACTIVE_KEY,
  PREVIEW_SUBTABLE_DIALOG_KEY,
  type PreviewSubTableRowDialogOpen,
} from './previewSubTableDialog'
import { cloneFormRules, getRuleChildren, injectUploadButtonLabels, isInlineSubFormDropAllowed, walkFormCreateRules } from '@/utils/formDesigner'
import { resolveRelationViewEntry } from '@/utils/formConfigBindingResolve'
import { mapFormCreateRulesReadonlyDeep } from '@/utils/formCreateRuleUtils'
import { isRequestIdSyntheticField } from '@/utils/formFieldMeta'
import { filterOutTableAuditFields } from '@/utils/tableAuditFields'
import TableBindingManager from './TableBindingManager.vue'
import FormRenameDialog from './form-designer/FormRenameDialog.vue'
import FormCreateDialog from './form-designer/FormCreateDialog.vue'
import FormNodeBindDialog from './form-designer/FormNodeBindDialog.vue'
import FormListSidebar from './form-designer/FormListSidebar.vue'
import RelationTableView from './RelationTableView.vue'
import SubTableListView from './SubTableListView.vue'
import FormPreviewItems from './FormPreviewItems.vue'
import FormDesignerCanvasToolbar from './FormDesignerCanvasToolbar.vue'
import SubTableFormDialog from './SubTableFormDialog.vue'
import SubTableAddDialog from './SubTableAddDialog.vue'
import { useFormActions } from '@/composables/modules/useFormActions'
import { useFormLabels } from '@/composables/modules/useFormLabels'
import { useFormAutoSave } from '@/composables/modules/useFormAutoSave'
import { useFormDesignerCanvasChrome } from '@/composables/modules/useFormDesignerCanvasChrome'
import { useTableFieldRules } from '@/composables/formDesigner/useTableFieldRules'
import { useSubTableViews } from '@/composables/formDesigner/useSubTableViews'
import { useDesignerLinkFormColumns } from '@/composables/formDesigner/useDesignerLinkFormColumns'
import { useFieldImport } from '@/composables/formDesigner/useFieldImport'
import { useFormConfigPaste } from '@/composables/formDesigner/useFormConfigPaste'
import { useFormPreviewColumns } from '@/composables/formDesigner/useFormPreviewColumns'
import { useFormPreviewBuild } from '@/composables/formDesigner/useFormPreviewBuild'
import { useFormSave } from '@/composables/formDesigner/useFormSave'
import { useFormNodeBinding } from '@/composables/formDesigner/useFormNodeBinding'
import { useFormLifecycle } from '@/composables/formDesigner/useFormLifecycle'
import { useBlockingProgress } from '@/composables/useBlockingProgress'
import BlockingProgressOverlay from '@/components/common/BlockingProgressOverlay.vue'
import {
  MI_ASSIGNMENT_CONFIG_KEY,
  parseMiAssignmentsFromBpmn,
  ruleContainsMiAssignment,
  validateMiAssignmentComponents,
} from '@/utils/miAssignmentConfig'

const { t } = useI18n()
const props = defineProps<{ functionUnitId: number }>()
const store = useFunctionUnitStore()

// ── Core shared state ───────────────────────────────────────────────────────
const selectedForm = ref<FormDefinition | null>(null)
const designerRef = ref<any>(null)
const subDesignerRefs = ref<any[]>([])
const activeDesignerTab = ref<string>('main')
const linkFormComponents = ref<Array<{
  id: number
  componentName: string
  linkedFormId: number
  linkedFormName?: string
  displayField?: string
  linkText: string
  columnLabel?: string
  sortOrder: number
}>>([])
const autoSaving = ref(false)
const miValidationRevision = ref(0)
const lastAutoSaveTime = ref<Date | null>(null)
const showCreateDialog = ref(false)
const showRenameDialog = ref(false)
const renameFormName = ref('')
const renameTargetForm = ref<FormDefinition | null>(null)
const inlineRenaming = ref(false)
const inlineRenameName = ref('')
const inlineRenameInputRef = ref<{ focus: () => void } | null>(null)
const showBindingManagerDialog = ref(false)
const bindingManagerForm = ref<FormDefinition | null>(null)
// Holds auto-fill rules for newly added sub-bindings so handleBindingUpdate can seed the cache
const pendingSubFormCacheSeed = ref<Record<number, { rule: any[]; options: any }>>({})

// ── Type/label helpers ──────────────────────────────────────────────────────
const { formTypeLabel, nodeTypeLabel, tableTypeLabel, bindingTypeLabel, bindingTypeTag, getFormComponentType } = useFormLabels(t)

/**
 * Get table name by table ID
 */
function getTableName(tableId: number, fallback?: string): string {
  const table = store.tables.find(t => t.id === tableId)
  return table?.tableDisplayName || table?.tableName || fallback || t('form.unknownTable')
}

/**
 * Which list tab this form lives under (mirrors FormListSidebar.sceneOf), so the detail
 * header can show it — nothing else on this screen indicates To Do vs My Requests vs Views Form.
 */
const selectedFormSceneLabel = computed(() => {
  const form = selectedForm.value
  if (!form) return ''
  if (form.formType === 'DETAIL') return t('form.viewsForm')
  return (form as { scene?: string }).scene === 'REQUEST' ? t('form.sceneRequest') : t('form.sceneTask')
})

/**
 * Which FormListSidebar tab to reopen on "Back to List" — the scene of the form the developer
 * is currently in, so leaving a My Requests design doesn't drop them back on the To Do tab.
 * Mirrors FormListSidebar.sceneOf; kept as the last non-null value once the form closes.
 */
const lastFormListScene = ref<'TASK' | 'REQUEST' | 'DETAIL'>('TASK')
watch(selectedForm, (form) => {
  if (!form) return
  lastFormListScene.value =
    form.formType === 'DETAIL' ? 'DETAIL' : (form as { scene?: string }).scene === 'REQUEST' ? 'REQUEST' : 'TASK'
})

/**
 * Whether the "Main Table" tab has anything meaningful to show. PROCESS/TASK forms always
 * have (and require) a PRIMARY binding, so the Main Table canvas is their real content.
 * ACTION forms (FORM_POPUP) are reached via their Action Button, not a PRIMARY table row —
 * their real content lives on the ACTION binding's own canvas — so Main Table only appears
 * for them when a PRIMARY binding actually exists (e.g. legacy data not yet cleaned up).
 */
const hasPrimaryBinding = computed(() =>
  !!selectedForm.value?.tableBindings?.some(b => b.bindingType === 'PRIMARY'),
)
const showMainTableTab = computed(() =>
  selectedForm.value?.formType !== 'ACTION' || hasPrimaryBinding.value,
)

// Non-PRIMARY bindings for tabs（RELATED 用于 Lookup，也需要显示在设计器里配置视图字段）
const designerSubBindings = computed(() => {
  if (!selectedForm.value) return []
  const nonPrimary = (selectedForm.value.tableBindings || []).filter((b: TableBinding) => b.bindingType !== 'PRIMARY')
  return nonPrimary.map((b: TableBinding) => {
    const tableInStore = store.tables.find(t => t.id === b.tableId)
    return {
      bindingId: b.id as number,
      bindingType: b.bindingType,
      bindingMode: b.bindingMode,
      // tableName 保留「display name 优先」语义以兼容历史 tab/option 显示路径（见 issue 1372）。
      tableName: getTableName(b.tableId, b.tableName),
      // 显式暴露 tableDisplayName，让下游模板可以「tableDisplayName || tableName」自行选择，
      // 避免 tableName 同时承担技术名与显示名两种语义造成下游误用（见 issue 1373）。
      tableDisplayName: tableInStore?.tableDisplayName || undefined,
      assignmentTableName: tableInStore?.tableName || b.tableName || '',
      tableId: b.tableId,
      tableType: tableInStore?.tableType || (b.bindingType === 'RELATED' ? 'RELATION' : ''),
      tableDescription: tableInStore?.description || '',
      subMode: (b.subMode === 'FORM_ONLY') ? 'FORM_ONLY' : 'FULL',
    }
  })
})

// Grouped sub/action/relation bindings for the grouped nav
const designerSubBindingsGrouped = computed(() =>
  designerSubBindings.value.filter(b => b.bindingType === 'SUB')
)
const designerActionBindingsGrouped = computed(() =>
  designerSubBindings.value.filter(b => b.bindingType === 'ACTION')
)
const designerRelationBindingsGrouped = computed(() =>
  designerSubBindings.value.filter(b => b.bindingType === 'RELATED')
)

const parsedMiAssignments = computed(() => parseMiAssignmentsFromBpmn(store.process?.bpmnXml))
const activeMiAssignmentConfig = computed(() => {
  const bindingId = Number(activeDesignerTab.value)
  const binding = designerSubBindings.value.find((item) => item.bindingId === bindingId)
  return binding ? parsedMiAssignments.value.configs[binding.assignmentTableName] : undefined
})
provide(MI_ASSIGNMENT_CONFIG_KEY, activeMiAssignmentConfig)

/** BPMN assignment contract for a bound table — shared by canvas load and preview. */
function resolveAssignmentConfigForTable(tableId: number) {
  const tableName = store.tables.find((table) => table.id === tableId)?.tableName
  return tableName ? parsedMiAssignments.value.configs[tableName] : undefined
}

/**
 * Whether a form carries the Assignment Mode component is the developer's own call —
 * only two things are still flagged here: BPMN nodes disagreeing on the assignment
 * contract for this sub-table (CONFLICTING), and a placed component the active BPMN
 * doesn't actually need for this table (ORPHAN, own-form-only — a stray placement on
 * form A is not explained by form B's contract).
 */
const activeMiAssignmentWarning = computed(() => {
  void miValidationRevision.value
  const bindingId = Number(activeDesignerTab.value)
  const binding = designerSubBindings.value.find((item) =>
    item.bindingId === bindingId && item.bindingType === 'SUB')
  if (!binding) return ''
  const configJson = {
    subForms: Object.fromEntries(designerSubBindings.value.map((item, index) => {
      const saved = selectedForm.value?.configJson?.subForms?.[item.bindingId]
      let liveRule: unknown
      try {
        liveRule = subDesignerRefs.value[index]?.getRule?.()
      } catch {
        // FALLBACK(ux): this is a live warning only; save performs an authoritative
        // collection and validation. Preserve the cached/saved warning state while remounting.
        liveRule = undefined
      }
      return [String(item.bindingId), {
        rule: Array.isArray(liveRule) ? liveRule : (subFormCache.value[item.bindingId]?.rule || saved?.rule || []),
      }]
    })),
  }
  const guard = validateMiAssignmentComponents(
    parsedMiAssignments.value,
    designerSubBindings.value
      .filter((item) => item.bindingType === 'SUB')
      .map((item) => ({ bindingId: item.bindingId, tableName: item.assignmentTableName })),
    configJson,
  )
  const issue = guard.blocking.find((item) => item.subTableName === binding.assignmentTableName)
  if (issue) {
    return t('form.miAssignmentConflict', { subTable: issue.subTableName, nodes: issue.nodeIds.join(', ') })
  }
  const activeRules = (configJson.subForms as Record<string, { rule: unknown }>)[String(bindingId)]?.rule
  if (ruleContainsMiAssignment(activeRules) && !activeMiAssignmentConfig.value) {
    return t('form.miAssignmentOrphanWarning', { subTable: binding.assignmentTableName })
  }
  return ''
})

const activeTabGroup = computed<'main' | 'sub' | 'action' | 'relation' | null>(() => {
  if (activeDesignerTab.value === 'main') return 'main'
  const id = Number(activeDesignerTab.value)
  if (designerSubBindingsGrouped.value.some(b => b.bindingId === id)) return 'sub'
  if (designerActionBindingsGrouped.value.some(b => b.bindingId === id)) return 'action'
  if (designerRelationBindingsGrouped.value.some(b => b.bindingId === id)) return 'relation'
  return null
})

const activeBindingLabel = computed(() => {
  const id = Number(activeDesignerTab.value)
  const b = designerSubBindings.value.find(b => b.bindingId === id)
  return b?.tableDisplayName || b?.tableName || ''
})

// Show only the first table on the group title — the dropdown lists the rest.
function formatBindingGroupNavLabel(
  bindings: Array<{ tableName: string; tableDisplayName?: string }>,
): string {
  const names = bindings.map(b => b.tableDisplayName || b.tableName).filter(Boolean)
  return names[0] ?? ''
}

const subBindingsNavLabel = computed(() => {
  if (activeTabGroup.value === 'sub') return activeBindingLabel.value
  return formatBindingGroupNavLabel(designerSubBindingsGrouped.value)
})

const actionBindingsNavLabel = computed(() => {
  if (activeTabGroup.value === 'action') return activeBindingLabel.value
  return formatBindingGroupNavLabel(designerActionBindingsGrouped.value)
})

const relationBindingsNavLabel = computed(() => {
  if (activeTabGroup.value === 'relation') return activeBindingLabel.value
  return formatBindingGroupNavLabel(designerRelationBindingsGrouped.value)
})

function switchToMain() {
  activeDesignerTab.value = 'main'
  handleTabChange('main')
  nextTick(() => {
    if (designerRef.value?.activeModule) designerRef.value.activeModule = 'base'
  })
}

function switchToBinding(id: string) {
  subTableActiveTab.value = 'form'
  activeDesignerTab.value = id
  nextTick(() => {
    handleTabChange(id)
    const index = designerSubBindings.value.findIndex(b => String(b.bindingId) === id)
    const subRef = index >= 0 ? subDesignerRefs.value[index] : null
    if (subRef?.activeModule) subRef.activeModule = 'base'
  })
}

// Default form options — label left-aligned + empty Form event handlers (onChange, onSubmit, …)
const defaultFormOption = computed(() => buildDefaultFormCreateOptions({
  form: { labelPosition: 'left' },
  language: {
    en: {
      clickToUpload: t('form.clickToUpload'),
    },
  },
}))

function getActiveDesignerRef(): { getRule?: () => unknown[]; setRule?: (r: unknown[]) => void } | null | undefined {
  if (activeDesignerTab.value === 'main') {
    return designerRef.value
  }
  const bindingId = Number(activeDesignerTab.value)
  if (!Number.isFinite(bindingId)) return null
  const index = designerSubBindings.value.findIndex((b) => b.bindingId === bindingId)
  if (index < 0) return null
  return subDesignerRefs.value[index]
}

// ── Canvas chrome (show-hidden toggle + zoom) ───────────────────────────────
const {
  designerShowHidden,
  designerZoomPercent,
  designerZoomStyle,
  scheduleSyncHiddenMarkers,
  setupMarkerObserver,
  teardownMarkerObserver,
} = useFormDesignerCanvasChrome({
  activeDesignerTab,
  designerRef,
  subDesignerRefs,
  designerSubBindings,
  hiddenBadgeLabel: () => t('form.canvasHiddenBadge'),
})

function patchDesignerRulesDefaultEvents() {
  const designer = getActiveDesignerRef()
  if (!designer?.getRule || !designer.setRule) return
  let rules: unknown[] = []
  try {
    rules = designer.getRule() || []
  } catch {
    return
  }
  // Only setRule when defaults were actually added — full reload clears activeRule and can
  // drop in-memory _on/_hook edits before the user saves the form.
  if (!walkRulesEnsureComponentEvents(rules)) return
  try {
    designer.setRule(rules)
  } catch {
    // ignore designer sync errors
  }
}

function parseLookupConfigJson(raw: unknown): Record<string, unknown> {
  try {
    const cfg = typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw || {})
    return cfg && typeof cfg === 'object' ? (cfg as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function resolveActiveEditingLookupField(): string | null {
  const designer = getActiveDesignerRef() as {
    activeRule?: { field?: string; type?: string }
    baseForm?: { api?: { formData?: () => { field?: string } } }
  } | null | undefined
  if (designer?.activeRule?.type === 'lookup' && designer.activeRule.field) {
    return String(designer.activeRule.field)
  }
  const panelField = designer?.baseForm?.api?.formData?.()?.field
  if (typeof panelField === 'string' && panelField) {
    const rules = (getActiveDesignerRef()?.getRule?.() || []) as Array<{ field?: string; type?: string }>
    const match = rules.find(r => r?.field === panelField && r?.type === 'lookup')
    if (match) return panelField
  }
  return null
}

function refreshSiblingLookups() {
  const designer = getActiveDesignerRef()
  let rules: unknown[] = []
  try {
    rules = designer?.getRule?.() || []
  } catch {
    lookupStore.siblingLookupFields = []
    lookupStore.editingLookupField = resolveActiveEditingLookupField()
    return
  }
  const siblings: typeof lookupStore.siblingLookupFields = []
  walkFormCreateRules(rules, (rule) => {
    if (rule?.type !== 'lookup' || !rule.field) return
    const lookupCfg = parseLookupConfigJson(rule.props?.lookupConfig)
    const bindingId = lookupCfg.bindingId != null ? Number(lookupCfg.bindingId) : null
    const tableId = lookupCfg.tableId != null ? Number(lookupCfg.tableId) : null
    const tableName = String(lookupCfg.tableName || '')
    siblings.push({
      field: String(rule.field),
      title: String(rule.title || rule.field),
      tableId: Number.isFinite(tableId) ? tableId : null,
      tableName,
      bindingId: Number.isFinite(bindingId) ? bindingId : null,
      lookupConfig: lookupCfg,
    })
  })
  lookupStore.siblingLookupFields = siblings
  lookupStore.editingLookupField = resolveActiveEditingLookupField()
}

function onDesignerStructureChange() {
  miValidationRevision.value++
  scheduleSyncHiddenMarkers()
  refreshSiblingLookups()
  nextTick(() => {
    patchDesignerRulesDefaultEvents()
  })
  // Assigned after useFormConfigPaste — remaps stale _bindingId from left JSON paste.
  scheduleAutoRepairStaleBindingsFn()
}

function onSubDesignerStructureChange() {
  miValidationRevision.value++
  scheduleSyncHiddenMarkers()
}

/** Filled by useFormConfigPaste; no-op until then. */
let scheduleAutoRepairStaleBindingsFn: () => void = () => {}


function installDesignerPreviewCaptureHooks() {
  installPreviewValidationDomProbe()
  const root = document.querySelector('.form-editor-view')
  const getSavedRules = () => {
    const cfg = selectedForm.value?.configJson as Record<string, unknown> | undefined
    if (!cfg) return []
    if (activeDesignerTab.value === 'main') {
      return Array.isArray(cfg.rule) ? cfg.rule : []
    }
    const subForms = cfg.subForms
    if (!subForms || typeof subForms !== 'object') return []
    const bag = (subForms as Record<string, { rule?: unknown[] }>)[String(activeDesignerTab.value)]
    return Array.isArray(bag?.rule) ? bag.rule : []
  }
  const validateText = t('common.validate')
  installFcDesignerPreviewCapture(
    root,
    () => getActiveDesignerRef() as ReturnType<typeof getActiveDesignerRef>,
    validateText,
    getSavedRules,
  )
  wrapFcDesignerOpenPreview(designerRef.value, validateText, getSavedRules)
  for (const subRef of subDesignerRefs.value) {
    wrapFcDesignerOpenPreview(subRef, validateText, getSavedRules)
  }
}

// ── Table-field → rule mapping & Table Design hydration ─────────────────────
const tableFieldRules = useTableFieldRules({
  store: store as any,
  selectedForm,
  designerRef,
  subDesignerRefs,
  designerSubBindings,
  activeDesignerTab,
  getActiveDesignerRef,
  defaultFormOption,
  getAssignmentConfig: resolveAssignmentConfigForTable,
  t,
})
const {
  getPrimaryBindingFieldDefinitions,
  getTableFieldDefinitionsByTableId,
  hydrateDesignerRulesFromLatestTableDefaults,
  refreshActiveDesignerRulesFromTableDefaults,
  getTableFieldDefinitions,
  mergeTaskPermissionsForFields,
  refreshFormRulesFromTableMetadata,
  mapFieldsToFormRules,
  getRequestIdConfigByTableId,
  buildEffectiveMainFormConfig,
  buildEffectiveSubFormConfig,
} = tableFieldRules

// ── Sub-table / relation view state ─────────────────────────────────────────
const subTableViews = useSubTableViews({
  functionUnitId: props.functionUnitId,
  store: store as any,
  selectedForm,
  designerSubBindings,
  subDesignerRefs,
  linkFormComponents,
  defaultFormOption,
  t,
})
const {
  relationViewState,
  relationTableViewRefs,
  subTableListViewRefs,
  subTableViewState,
  subFormCache,
  setSubTableListViewRef,
  setSubDesignerRef,
  updateRelationViewFields,
  updateRelationViewAllFields,
  updateSubTableViewFields,
  updateSubTableViewAllFields,
  handleSubTableViewSave,
  loadSubTableViewConfig,
  getSubTableListViewBaseColumns,
  appendSubTableListFieldColumns,
  syncSubTableListViewFromFormRules,
  resolveDesignerBindingDisplayName,
  getSubTableFormDesign,
  getSubTableFormRule,
  getSubTableFormOption,
} = subTableViews

// ── Auto-save ───────────────────────────────────────────────────────────────
const { formatAutoSaveTime, scheduleAutoSave, setupAutoSavePolling, cleanupAutoSavePolling } = useFormAutoSave({
  selectedForm,
  designerRef,
  handleSaveForm: (isManual?: boolean) => formSave.handleSaveForm(isManual),
  relationViewState,
  t,
  autoSaving,
  lastAutoSaveTime,
  flushPendingCanvasEdits: () => {
    flushDesignerValidatePanelToActiveRule(getActiveDesignerRef())
  },
  getPollDesigner: () => getActiveDesignerRef() ?? designerRef.value,
})

// ── Link Form columns available to sub-table list views ─────────────────────
const { designerLinkFormColumnsMap } = useDesignerLinkFormColumns({
  selectedForm,
  designerSubBindings,
  subTableViewState,
  resolveDesignerBindingDisplayName,
})

// ── Preview column derivation ───────────────────────────────────────────────
const {
  makeLookupPreviewItem,
  resolveLookupPreviewConfig,
  toSubTablePreviewColumns,
} = useFormPreviewColumns({
  store: store as any,
  selectedForm,
  designerSubBindings,
  relationViewState,
  subTableViewState,
  getSubTableFormDesign,
  resolveDesignerBindingDisplayName,
  t,
})

// ── Preview dialog + build pipeline ─────────────────────────────────────────
const {
  showPreviewDialog,
  previewBuilding,
  previewFormReady,
  previewDialogOption,
  previewData,
  previewTableRows,
  previewItems,
  previewPrimaryTableDisplayName,
  previewPrimaryTableId,
  previewParentTablesById,
  previewTableBindingsForContext,
  prepareCustomPreviewValidation,
  handlePreview,
} = useFormPreviewBuild({
  functionUnitId: props.functionUnitId,
  store: store as any,
  selectedForm,
  designerRef,
  subDesignerRefs,
  subFormCache,
  designerSubBindings,
  getActiveDesignerRef,
  getTableFieldDefinitions,
  getPrimaryBindingFieldDefinitions,
  toSubTablePreviewColumns,
  makeLookupPreviewItem,
  getTableName,
  getAssignmentConfig: resolveAssignmentConfigForTable,
  t,
})

// Request ID config of the preview's PRIMARY main table — preview recomputes the
// readonly Request ID live from these fields (no backend in preview).
const previewRequestIdConfig = computed(() => {
  const id = previewPrimaryTableId.value
  if (id == null) return null
  return store.tables.find(tbl => tbl.id === id)?.requestIdConfig ?? null
})

// ── Import-fields dialog ────────────────────────────────────────────────────
const {
  showImportFieldsDialog,
  importTableId,
  selectedImportFields,
  formBindings,
  availableFields,
  isAllFieldsSelected,
  isFieldsIndeterminate,
  isFieldAlreadyImported,
  getImportTableBinding,
  isFieldSelected,
  toggleFieldSelection,
  handleSelectAllFields,
  handleTableChange,
  handleImportFromTable,
  handleImportFieldsToDesigner,
  handleConfirmImportFields,
} = useFieldImport({
  functionUnitId: props.functionUnitId,
  store: store as any,
  selectedForm,
  designerRef,
  subDesignerRefs,
  designerSubBindings,
  activeDesignerTab,
  relationViewState,
  subTableViewState,
  getSubTableListViewBaseColumns,
  appendSubTableListFieldColumns,
  mapFieldsToFormRules,
  getRequestIdConfigByTableId,
  mergeTaskPermissionsForFields,
  refreshFormRulesFromTableMetadata,
  t,
})

// Named function instead of an inline arrow, because Vue SFC template expressions don't support
// inline TS type annotations on destructured arrow-function params (`({ row }: { row: T }) => …`
// is a hard vue-tsc parse error, not just a lint warning).
function importFieldsRowClassName({ row }: { row: Parameters<typeof isFieldAlreadyImported>[0] }): string {
  return isFieldAlreadyImported(row) ? 'is-already-imported' : ''
}

const {
  showPasteConfigDialog,
  pasteConfigText,
  pasteRepairing,
  openPasteConfigDialog,
  handleConfirmPasteConfig,
  repairCurrentDesignerBindings,
  scheduleAutoRepairStaleBindings,
  willProvisionOnSave,
  provisionAndRepairForSave,
} = useFormConfigPaste({
  functionUnitId: props.functionUnitId,
  selectedForm,
  getMainDesignerRule: () => {
    const designer = designerRef.value as { getRule?: () => unknown[] } | null | undefined
    const rules = designer?.getRule?.()
    return Array.isArray(rules) ? rules : []
  },
  getKnownBindingIds: () =>
    (selectedForm.value?.tableBindings || [])
      .map((b) => b.id)
      .filter((id): id is number => id != null),
  handleSelectForm: (row) => formLifecycle.handleSelectForm(row),
  t,
})
scheduleAutoRepairStaleBindingsFn = scheduleAutoRepairStaleBindings

const blockingProgress = useBlockingProgress()
const {
  visible: blockingProgressVisible,
  message: blockingProgressMessage,
  detail: blockingProgressDetail,
} = blockingProgress

// ── Form persistence + field permissions ────────────────────────────────────
const formSave = useFormSave({
  functionUnitId: props.functionUnitId,
  store: store as any,
  selectedForm,
  designerRef,
  subDesignerRefs,
  designerSubBindings,
  subFormCache,
  relationViewState,
  subTableViewState,
  subTableListViewRefs,
  getActiveDesignerRef,
  getPrimaryBindingFieldDefinitions,
  syncSubTableListViewFromFormRules,
  loadForms: () => formLifecycle.loadForms(),
  autoSaving,
  lastAutoSaveTime,
  provisionAndRepairForSave,
  willProvisionOnSave,
  blockingProgress,
  getBpmnXml: () => store.process?.bpmnXml,
  t,
  hasMainTableTab: showMainTableTab,
})
const {
  loadDataTableColumns,
  currentFormFields,
  currentSubFormFieldGroups,
  getFieldPermission,
  setFieldPermission,
  isFieldPermissionLocked,
  handleSaveForm,
  savingForm,
} = formSave

// ── Form ↔ BPMN node binding ────────────────────────────────────────────────
const formNodeBinding = useFormNodeBinding({
  functionUnitId: props.functionUnitId,
  store: store as any,
  t,
})
const {
  bindDialogKey,
  processNodes,
  showBindDialog,
  parseFormBindingsFromBpmn,
  getFormBoundNodes,
  isNodeSelected,
  isNodeReadOnly,
  toggleNodeSelection,
  setNodeReadOnly,
  handleBindNode,
  handleConfirmBind,
} = formNodeBinding

// ── Form list/selection lifecycle ───────────────────────────────────────────
const formLifecycle = useFormLifecycle({
  functionUnitId: props.functionUnitId,
  store: store as any,
  selectedForm,
  designerRef,
  subDesignerRefs,
  subFormCache,
  subTableListViewRefs,
  subTableViewState,
  relationViewState,
  designerSubBindings,
  activeDesignerTab,
  showCreateDialog,
  defaultFormOption,
  buildEffectiveMainFormConfig,
  buildEffectiveSubFormConfig,
  getTableFieldDefinitions,
  getPrimaryBindingFieldDefinitions,
  getTableFieldDefinitionsByTableId,
  mergeTaskPermissionsForFields,
  hydrateDesignerRulesFromLatestTableDefaults,
  refreshFormRulesFromTableMetadata,
  loadSubTableViewConfig,
  parseFormBindingsFromBpmn,
  patchDesignerRulesDefaultEvents,
  installDesignerPreviewCaptureHooks,
  onDesignerStructureChange,
  setupAutoSavePolling,
  cleanupAutoSavePolling,
  setupMarkerObserver,
  teardownMarkerObserver,
  scheduleSyncHiddenMarkers,
  t,
})
const {
  loading,
  subTableActiveTab,
  createForm,
  createFormStageIds,
  createDialogProcessNodes,
  loadForms,
  handleSelectForm,
  handleTabChange,
  handleSubTableInnerTabChange,
  handleBackToList,
  handleCreateForm,
  handleCreateFormTypeChange,
} = formLifecycle

// ── Form CRUD actions (rename / copy / delete) ──────────────────────────────
const { renaming, handleDeleteForm, handleConfirmRename, handleCopyForm, handleCopyProcessToTaskForm } = useFormActions({
  functionUnitId: props.functionUnitId,
  store: store as any,
  renameTargetForm,
  renameFormName,
  showRenameDialog,
  selectedForm,
  loadForms,
  t,
})

function openRenameDialog(form: FormDefinition) {
  renameTargetForm.value = form
  renameFormName.value = form.formName
  showRenameDialog.value = true
}

function startInlineRename(form: FormDefinition) {
  renameTargetForm.value = form
  inlineRenameName.value = form.formName
  inlineRenaming.value = true
  nextTick(() => inlineRenameInputRef.value?.focus())
}

async function handleInlineRenameConfirm() {
  if (!inlineRenaming.value) return
  inlineRenaming.value = false
  const name = inlineRenameName.value.trim()
  if (!name || name === renameTargetForm.value?.formName) return
  renameFormName.value = name
  await handleConfirmRename()
}

/**
 * Get PRIMARY binding for a form
 */
function getPrimaryBinding(form: FormDefinition): TableBinding | undefined {
  return form.tableBindings?.find(b => b.bindingType === 'PRIMARY')
}

/**
 * Get sub/related binding count for a form
 */
function getSubBindingsCount(form: FormDefinition): number {
  return form.tableBindings?.filter(b => b.bindingType !== 'PRIMARY').length || 0
}

/**
 * Open manage table bindings dialog
 */
function handleManageBindings(form: FormDefinition) {
  bindingManagerForm.value = form
  showBindingManagerDialog.value = true
}

/** 列表「更多」：复制 / 表绑定 / 绑定节点 */
function onFormListMoreAction(command: string, row: FormDefinition) {
  switch (command) {
    case 'rename':
      openRenameDialog(row)
      break
    case 'copy':
      void handleCopyForm(row)
      break
    case 'copy-to-task':
      void handleCopyProcessToTaskForm(row)
      break
    case 'bindings':
      handleManageBindings(row)
      break
    case 'bindNode':
      handleBindNode(row)
      break
    default:
      break
  }
}

/**
 * Table binding update callback
 */
async function handleBindingUpdate() {
  // Reload bindings directly instead of full loadForms
  if (selectedForm.value) {
    try {
      const res = await functionUnitApi.getFormBindings(props.functionUnitId, selectedForm.value.id)
      selectedForm.value = { ...selectedForm.value, tableBindings: res.data || [] }
      // Reset sub designer state so new tabs render cleanly
      subDesignerRefs.value = []
      // Restore any pending auto-fill seeds before Vue renders the new tabs
      subFormCache.value = { ...pendingSubFormCacheSeed.value }
      pendingSubFormCacheSeed.value = {}
      // relationViewState is keyed by bindingId — rebuild it for all RELATED bindings so
      // newly added ones get initialised with empty state (rather than undefined → blank view)
      const updated: Record<number, { allFields: any[]; viewFields: any[] }> = {}
      const config = selectedForm.value.configJson || {}
      for (const b of (selectedForm.value.tableBindings || [])) {
        if (b.bindingType === 'RELATED') {
          const id = b.id as number
          const saved = resolveRelationViewEntry(
            config.relationViews || {},
            id,
            selectedForm.value.tableBindings || [],
          )
          updated[id] = saved
            ? { allFields: saved.allFields || [], viewFields: saved.viewFields || [] }
            : { allFields: [], viewFields: [] }
        }
      }
      relationViewState.value = updated
    } catch (e) {
      console.error('[FormDesigner] Failed to update bindings:', e)
    }
  }
}

/**
 * Poll for the (re)mounted sub-designer ref of a binding and push the auto-fill rules onto it.
 * el-tabs mounts all panes eagerly, so the ref appears within a few render ticks after
 * handleBindingUpdate replaces the bindings list. We retry a handful of times to absorb the
 * async fetch + re-render + designer init latency.
 */
async function applySubDesignerRulesWhenReady(bindingId: number, rules: any[], attempt = 0) {
  await nextTick()
  const index = designerSubBindings.value.findIndex(b => b.bindingId === bindingId)
  const subRef = index >= 0 ? subDesignerRefs.value[index] : null
  if (subRef && typeof subRef.setRule === 'function') {
    try {
      const current: any[] = subRef.getRule() || []
      // Only fill an empty designer (don't clobber if the user already edited it)
      if (current.length === 0) {
        const merged = cloneFormRules(rules)
        injectUploadButtonLabels(merged, t('form.clickToUpload'))
        subRef.setRule(merged)
        if (subRef.activeModule) subRef.activeModule = 'base'
        // Keep the cache in sync with what we just applied
        subFormCache.value[bindingId] = { rule: cloneFormRules(merged), options: subFormCache.value[bindingId]?.options || {} }
      }
    } catch { /* ignore */ }
    return
  }
  if (attempt < 25) {
    setTimeout(() => { void applySubDesignerRulesWhenReady(bindingId, rules, attempt + 1) }, 80)
  }
}

/**
 * Auto-fill all fields of a newly added binding into the appropriate designer tab / list view.
 * Called after TableBindingManager emits 'add'.
 *
 * Timing challenge: @update fires first → handleBindingUpdate async-fetches bindings, then
 * sets subFormCache = {} and subDesignerRefs = []. @add fires after @update starts but they
 * run concurrently (both async). We use pendingSubFormCacheSeed so that handleBindingUpdate
 * can restore the seed immediately after clearing the cache.
 */
async function handleBindingAdded(payload: { tableId: number; bindingType: string; bindingId: number }) {
  const { tableId, bindingType, bindingId } = payload
  const fields = filterOutTableAuditFields(getTableFieldDefinitions(tableId))
  if (!fields || fields.length === 0) return

  if (bindingType === 'PRIMARY') {
    // Wait for handleBindingUpdate to have re-mounted the main designer
    await nextTick()
    await nextTick()
    const targetRef = designerRef.value
    if (!targetRef) return
    const rules = mapFieldsToFormRules(fields)
    mergeTaskPermissionsForFields(fields)
    const currentRules: any[] = targetRef.getRule() || []
    const existingFields = new Set(currentRules.map((r: any) => r.field))
    const newRules = rules.filter(r => !existingFields.has(r.field))
    if (newRules.length > 0) {
      const merged = [...currentRules, ...newRules]
      injectUploadButtonLabels(merged, t('form.clickToUpload'))
      targetRef.setRule(merged)
    }
    refreshFormRulesFromTableMetadata()

  } else if (bindingType === 'SUB' || bindingType === 'ACTION') {
    // ACTION bindings (e.g. FORM_POPUP "Meeting Remark") get the exact same auto-populate
    // treatment as SUB — they render through the identical canvas/list-view branch above.
    const rules = mapFieldsToFormRules(fields)
    mergeTaskPermissionsForFields(fields)
    injectUploadButtonLabels(rules, t('form.clickToUpload'))

    // Seed the cache so handleTabChange / save persistence picks it up even before the
    // designer ref mounts. handleBindingUpdate restores this after clearing the cache.
    pendingSubFormCacheSeed.value[bindingId] = { rule: cloneFormRules(rules), options: {} }

    // Also apply directly to the live sub-designer once handleBindingUpdate has re-rendered
    // the tabs and the new designer ref is mounted (el-tabs mounts all panes eagerly).
    void applySubDesignerRulesWhenReady(bindingId, rules)

    // Populate the sub-table list view columns (this state is not cleared by handleBindingUpdate)
    const subFields = fields.map(f => ({
      fieldName: f.fieldName,
      dataType: f.dataType || 'VARCHAR',
      length: f.length,
      precision: f.precision,
      scale: f.scale,
      nullable: f.nullable ?? true,
      isPrimaryKey: f.isPrimaryKey ?? false,
      defaultValue: f.defaultValue,
      displayName: f.displayName || f.fieldName,
    }))
    subTableViewState.value = {
      ...subTableViewState.value,
      [bindingId]: {
        allFields: subFields,
        viewFields: getSubTableListViewBaseColumns(bindingId),
      },
    } as typeof subTableViewState.value

  } else if (bindingType === 'RELATED') {
    // Populate relation table view columns (state survives handleBindingUpdate)
    const viewFields = fields.map((f, idx) => ({
      id: idx,
      fieldName: f.fieldName,
      dataType: f.dataType || 'VARCHAR',
      length: f.length,
      precision: f.precision,
      scale: f.scale,
      nullable: f.nullable ?? true,
      isPrimaryKey: f.isPrimaryKey ?? false,
      defaultValue: f.defaultValue,
      displayName: f.displayName,
      sortOrder: idx,
    }))
    relationViewState.value = {
      ...relationViewState.value,
      [bindingId]: { allFields: viewFields, viewFields: [] },
    }
  }
}

// ── Form-create designer config ─────────────────────────────────────────────
const designerConfig = computed(() => ({
  showDevice: true,
  showSave: false, // Use custom save button
  fieldReadonly: false,
  /** Validation / props panel: commit on change (not blur) so deletes persist when re-selecting a field. */
  updateConfigOnBlur: false,
  formOptions: buildDefaultFormCreateOptions({
    form: { labelPosition: 'left' },
  }),
  beforeActiveRule: ({ rule }: { rule: Record<string, unknown> }) => {
    flushDesignerValidatePanelToActiveRule(getActiveDesignerRef())
    ensureEmptyRuleComponentEvents(rule)
    formControlTypeStore.activeRule = rule
  },
  // MVP boundary enforcement — see isInlineSubFormDropAllowed's doc comment for why this is
  // this component's only enforcement point.
  checkDrag: (drag: { menu?: { name?: string } }) => {
    if (isInlineSubFormDropAllowed(drag?.menu?.name, activeDesignerTab.value)) return true
    ElMessage.warning(t('form.inlineSubFormMainCanvasOnly'))
    return false
  },
  updateDefaultRule: buildDesignerUpdateDefaultRule(),
  // formCreateValue → rule.value (Select Default Value). Required by fc-designer mapping.
  appendConfigData: ['formCreateValue'],
  componentRule: {
    // Hide is handled by form-create's built-in top toggle (toolHidden → rule-level `_hidden`),
    // which marks the field with a badge WITHOUT collapsing its content. We only append Readonly.
    default: {
      append: true,
      rule(rule: { type?: string }) {
        // lookup is custom — Readonly lives in lookup drag rule props() (main.ts), not fc built-in Props.
        const builtInReadonly = new Set(['input', 'textarea', 'password', 'timePicker', 'datePicker', 'lookup'])
        if (builtInReadonly.has(String(rule.type ?? ''))) return []
        return [{ type: 'switch', field: 'readonly', title: 'Readonly' }]
      },
    },
    // Select: append Default Value (options → rule.value + props.value) + Readonly (default rule is skipped for typed overrides).
    select: {
      append: true,
      rule(activeRule: Record<string, unknown>) {
        return [
          buildSelectDefaultValuePropRule(activeRule, {
            title: t('form.componentDefaultValue'),
            placeholder: t('form.componentDefaultValuePlaceholder'),
          }),
          { type: 'switch', field: 'readonly', title: 'Readonly' },
        ]
      },
    },
    // 子表：右侧属性面板追加 新增/编辑/删除 三个逐操作开关（写入 rule.props.allowAdd/allowEdit/allowDelete）。
    // 默认全开 → 与历史「只要可编辑就全放开」一致；关掉某项 → SubTableField 隐藏对应 Add/Edit/Delete。
    // 只追加这三项；Readonly 已由 form-create 内建面板提供，不再重复。
    // ACTION 绑定（FORM_POPUP 弹窗写入的记录表，如 "Meeting Remark"）语义上是操作留痕、天生只读——
    // 不提供这三个开关（没有"关掉的选项"，因为压根不该有开的选项）。绑定已被删除等异常情况保守地
    // 按非 ACTION 处理（不隐藏开关），避免历史脏数据下属性面板突然消失控件。
    subTable: {
      append: true,
      rule(activeRule: { props?: Record<string, unknown> }) {
        const bindingId = activeRule?.props?._bindingId
        const bindingType = bindingId != null
          ? designerSubBindings.value.find(b => b.bindingId === Number(bindingId))?.bindingType
          : undefined
        const isActionBinding = bindingType === 'ACTION'

        const presentationSwitches = [
          // Presentation. A plain table is the default; each of these is opted into.
          {
            type: 'switch',
            field: 'compactCells',
            title: t('form.subTableSummary.compactCells'),
            value: false,
            info: t('form.subTableSummary.compactCellsHint'),
          },
        ]

        if (isActionBinding) {
          return presentationSwitches
        }

        return [
          { type: 'switch', field: 'allowAdd', title: t('form.subTablePermission.allowAdd'), value: true },
          { type: 'switch', field: 'allowEdit', title: t('form.subTablePermission.allowEdit'), value: true },
          { type: 'switch', field: 'allowDelete', title: t('form.subTablePermission.allowDelete'), value: true },
          ...presentationSwitches,
        ]
      },
    },
    // Upload: Can not download (rule.props.cannotDownload). Typed override skips default,
    // so Readonly is re-appended here to keep the built-in switch.
    upload: {
      append: true,
      rule() {
        return [
          {
            type: 'UploadMaxFilesEditor',
            field: 'maxFiles',
            title: t('form.uploadMaxFiles'),
            value: 10,
          },
          {
            type: 'switch',
            field: 'cannotDownload',
            title: t('form.uploadCannotDownload'),
            value: false,
          },
          { type: 'switch', field: 'readonly', title: 'Readonly' },
          {
            type: 'FileNetAdvancedEditor',
            field: 'fileNet',
            title: t('form.fileNet.panelTitle'),
            value: {
              enabled: false,
              connectionUid: '',
              headerInfo: [],
              repositoryDetail: { repositoryID: '', documentClass: '', objectStoreName: '' },
              docProperty: [],
              searchDetailList: [],
              retrieveRequest: {},
              orderBy: [],
            },
          },
        ]
      },
    },
    // Input（单行）：敏感信息打码。textarea / password 不展示、运行时也不打码。
    input: {
      append: true,
      rule(activeRule: Record<string, unknown>) {
        const props = (activeRule?.props && typeof activeRule.props === 'object')
          ? activeRule.props as Record<string, unknown>
          : {}
        if (props.type === 'textarea' || props.type === 'password') return []
        return [
          {
            type: 'FormControlTypeSelect',
            field: '_controlType',
            title: t('form.ownerControlType'),
          },
          {
            type: 'SensitiveMaskPropsEditor',
            field: 'sensitiveMask',
            title: t('form.sensitiveMask.panelTitle'),
            value: props.sensitiveMask ?? {
              enabled: false,
              preset: 'all',
              keepPrefix: 0,
              keepSuffix: 4,
              maskChar: '*',
              revealPlainOnFocus: false,
            },
          },
        ]
      },
    },
  },
  hiddenItemConfig: {
    // Hide the built-in Basic "Hidden" (rule-level `hidden`) — it collapses field content on the
    // canvas. The built-in top toggle (props.hide → `_hidden`) is the single Hide control.
    default: ['disabled', 'hidden'],
    lookup: ['disabled', 'hidden'],
    owner: ['disabled', 'hidden'],
    subTable: ['disabled', 'hidden'],
    inlineSubForm: ['disabled', 'hidden'],
    linkForm: ['disabled', 'hidden'],
    editor: ['disabled', 'hidden'],
    transfer: ['disabled', 'hidden'],
    cascader: ['disabled', 'hidden'],
    slider: ['disabled', 'hidden'],
    upload: ['disabled', 'hidden', 'multiple', 'limit', 'uploadLimit'],
  },
}))

// ── Preview row dialog (sub-table add/edit) ─────────────────────────────────
const previewRowDialog = reactive({
  visible: false,
  mode: 'add' as 'add' | 'edit',
  title: '',
  initialData: undefined as Record<string, any> | undefined,
  formRule: [] as any[],
  formOption: {} as Record<string, any>,
  columns: [] as any[],
  assignmentConfig: undefined as import('@/utils/miAssignmentConfig').AssignmentConfig | undefined,
  useFormRule: false,
  onSave: null as PreviewSubTableRowDialogOpen['onSave'] | null,
})

const previewMyRequestsActive = ref(false)
provide(PREVIEW_MY_REQUESTS_ACTIVE_KEY, previewMyRequestsActive)

provide(PREVIEW_SUBTABLE_DIALOG_KEY, {
  rowDialogOpen: toRef(previewRowDialog, 'visible'),
  openRowDialog(payload: PreviewSubTableRowDialogOpen) {
    previewRowDialog.mode = payload.mode
    previewRowDialog.title = payload.title
    previewRowDialog.initialData = payload.initialData
      ? { ...payload.initialData }
      : undefined
    previewRowDialog.formRule = mapFormCreateRulesReadonlyDeep(cloneFormRules(payload.formRule || [])) as any[]
    previewRowDialog.formOption = payload.formOption
      ? JSON.parse(JSON.stringify(payload.formOption))
      : {}
    previewRowDialog.columns = payload.columns.map((col) => ({ ...col }))
    previewRowDialog.assignmentConfig = payload.assignmentConfig
    previewRowDialog.useFormRule = previewRowDialog.formRule.length > 0
    previewRowDialog.onSave = payload.onSave
    previewRowDialog.visible = false
    window.setTimeout(() => {
      previewRowDialog.visible = true
    }, 0)
  },
})

watch(showPreviewDialog, (open) => {
  if (open) {
    return
  }
  previewBuilding.value = false
  previewFormReady.value = false
  previewRowDialog.visible = false
  previewRowDialog.onSave = null
  previewMyRequestsActive.value = false
})

function onPreviewRowDialogVisibleChange(visible: boolean) {
  previewRowDialog.visible = visible
  if (!visible) {
    previewRowDialog.onSave = null
  }
}

function handlePreviewRowDialogSave(row: Record<string, any>) {
  previewRowDialog.onSave?.(row)
  previewRowDialog.visible = false
  previewRowDialog.onSave = null
}

// ── Provides for fc-designer property-panel components ──────────────────────
// Provide subBindings to SubTablePlaceholderWidget via inject
provide('designerSubBindings', () => designerSubBindings.value.map(b => ({
  id: b.bindingId,
  tableName: b.tableName,
  tableDisplayName: b.tableDisplayName,
  tableId: b.tableId,
  tableDescription: b.tableDescription,
  bindingType: b.bindingType,
})))

// Provide relation bindings for LookupBindingSelect
provide('designerRelationBindings', () => designerSubBindings.value
  .filter(b => b.bindingType === 'RELATED')
  .map(b => ({
    bindingId: b.bindingId,
    tableName: b.tableName,
    tableDescription: b.tableDescription,
    tableId: b.tableId,
  }))
)

// Provide formId for lookup config components
provide('designerFormId', () => selectedForm.value?.id ?? null)

// Provide link form components for LinkFormBindingSelect
provide('linkFormComponents', () => linkFormComponents.value.map(c => ({
  id: c.id,
  componentName: c.componentName,
  linkedFormName: c.linkedFormName,
})))

provide('designerLinkFormColumns', () => designerLinkFormColumnsMap.value)

// Expose switchToBinding via module-level singleton so fc-designer property-panel components
// (registered in a separate Vue app context where provide/inject doesn't reach) can navigate.
lookupStore.switchToBinding = (id: number) => switchToBinding(String(id))
lookupStore.refreshSiblingLookups = refreshSiblingLookups

// ── Watches ─────────────────────────────────────────────────────────────────
// Sync relation bindings and formId to lookupStore for fc-designer property panel components
watch([() => selectedForm.value?.id, designerSubBindings, () => store.tables], () => {
  lookupStore.formId = selectedForm.value?.id ?? null
  lookupStore.relationBindings = designerSubBindings.value
    .filter(b => b.bindingType === 'RELATED')
    .map(b => ({
      bindingId: b.bindingId,
      tableName: b.tableName,
      tableDescription: b.tableDescription,
      tableId: b.tableId,
    }))
  lookupStore.tables = store.tables as any[]
}, { immediate: true })

watch(activeDesignerTab, () => {
  nextTick(() => refreshSiblingLookups())
})

// Table Design saved while another tab was open — refresh canvas defaults when tables update.
watch(
  () => store.tables,
  () => {
    if (!selectedForm.value) return
    nextTick(() => refreshActiveDesignerRulesFromTableDefaults())
  },
  { deep: true },
)

watch([() => selectedForm.value?.id], ([formId]) => {
  if (!formId) return
  nextTick(() => installDesignerPreviewCaptureHooks())
})

// Watch for selectedForm changes and load linkFormComponents
watch([() => selectedForm.value, () => props.functionUnitId], async ([form, fuId]) => {
  if (form && fuId) {
    await loadLinkFormComponents()
  } else {
    linkFormComponents.value = []
  }
}, { immediate: true })

async function loadLinkFormComponents() {
  try {
    const { linkFormComponentApi } = await import('@/api/linkFormComponent')
    const res = await linkFormComponentApi.getComponents(props.functionUnitId)
    linkFormComponents.value = res.data || []
  } catch (e) {
    console.error('[FormDesigner] failed to load linkFormComponents:', e)
    linkFormComponents.value = []
  }
}

/**
 * Main-table views of this function unit, so the Views Form tab can show which views use each
 * DETAIL form and let the developer point a view at one.
 */
const mainTableViews = ref<MainTableViewDefinition[]>([])

async function loadMainTableViews() {
  try {
    const res = await mainTableViewApi.list(props.functionUnitId)
    mainTableViews.value = res.data || []
  } catch (e: any) {
    // Surfaced rather than swallowed: without views the Views Form tab silently looks empty.
    mainTableViews.value = []
    ElMessage.error(e?.response?.data?.message || t('mainTableView.loadFailed'))
  }
}

/**
 * Bind-from-the-form-side (Views Form's "Bind Views" button): `checkedViewIds` is the full
 * next-state selection for `form` among `candidateViews`, not a delta. Each view's detailFormId
 * is a single slot (a view can show only one detail form), so:
 *  - newly checked → point that view's detailFormId at `form`
 *  - unchecked but was pointing at `form` → clear it (never touch a view already pointing at a
 *    DIFFERENT form — that binding belongs to that other form's checkbox, not this one).
 */
async function handleSetFormBoundViews(
  form: FormDefinition,
  candidateViews: MainTableViewDefinition[],
  checkedViewIds: number[],
) {
  const checked = new Set(checkedViewIds.map(Number))
  const toBind = candidateViews.filter(
    v => checked.has(v.id) && Number(v.detailFormId) !== Number(form.id),
  )
  const toUnbind = candidateViews.filter(
    v => !checked.has(v.id) && Number(v.detailFormId) === Number(form.id),
  )
  if (toBind.length === 0 && toUnbind.length === 0) return

  try {
    await Promise.all([
      ...toBind.map(v => mainTableViewApi.updateDetailForm(props.functionUnitId, v.id, form.id)),
      ...toUnbind.map(v => mainTableViewApi.updateDetailForm(props.functionUnitId, v.id, null)),
    ])
    ElMessage.success(t('form.viewDetailFormSaved'))
    await loadMainTableViews()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('form.saveFailed'))
  }
}

onMounted(() => {
  loadForms()
  loadDataTableColumns()
  loadMainTableViews()
  formLifecycle.loadCreateDialogProcessNodes()
})
</script>


<style lang="scss" scoped>
.form-designer {
  height: 100%;
}

.sub-inner-tabs__header {
  display: flex;
  border-bottom: 1px solid var(--el-border-color-light);
  margin-bottom: 0;
}

.sub-inner-tab-btn {
  padding: 8px 16px;
  font-size: 14px;
  color: var(--el-text-color-regular);
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  outline: none;
  transition: color 0.2s, border-color 0.2s;

  &:hover {
    color: var(--el-color-primary);
  }

  &.is-active {
    color: var(--el-color-primary);
    border-bottom-color: var(--el-color-primary);
    font-weight: 500;
  }
}

.sub-table-portal-views-bar {
  padding: 8px 12px;
  margin: 0 0 8px 0;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;

  :deep(.portal-views-editor) {
    width: 100%;
  }
}

.form-editor-view {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.bound-nodes-header {
  display: flex;
  gap: 4px;
}

.editor-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e6e6e6;
  margin-bottom: 16px;
  
  .form-name {
    font-size: 18px;
    font-weight: 600;
    color: #303133;
    cursor: pointer;
    border-bottom: 1px dashed transparent;
    &:hover {
      border-bottom-color: #409eff;
      color: #409eff;
    }
  }

  .form-name-input {
    width: 200px;
    font-size: 18px;
    font-weight: 600;
  }
  
  .header-actions {
    margin-left: auto;
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .paste-config-hint {
    margin: 0 0 12px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
    line-height: 1.5;
  }

  .auto-save-status {
    font-size: 14px;
    color: #909399;
    display: flex;
    align-items: center;
    min-width: 150px;

    .auto-saving {
      display: flex;
      align-items: center;
      gap: 6px;
      color: #409eff;
    }

    .auto-saved {
      display: flex;
      align-items: center;
      gap: 6px;
      color: #67c23a;
    }
  }
}

.fc-designer-wrapper {
  /* form-create 右侧属性栏：320px（与 _fc-r-config 280px 内容区 + 内边距一致，同 Control「Edit」按钮列宽） */
  --fc-designer-menu-width: 251px;
  --fc-designer-side-r-width: 320px;
  --fc-designer-top-actions-width: 200px;
  flex: 1;
  overflow: auto;
  position: relative;
  border: 1px solid #e6e6e6;
  border-radius: 4px;

  /* Show hidden + 缩放：顶栏右侧，紧贴 Preview 左侧（Y 与 fc-designer 顶栏对齐） */
  .form-designer-canvas-toolbar-host {
    position: absolute;
    top: 0;
    left: var(--fc-designer-menu-width);
    right: var(--fc-designer-side-r-width);
    height: 40px;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    padding-right: var(--fc-designer-top-actions-width);
    z-index: 30;
    pointer-events: none;

    :deep(.form-designer-canvas-toolbar) {
      pointer-events: auto;
    }
  }

  .fc-designer-zoom-stage {
    transform: scale(var(--fc-designer-zoom, 1));
    transform-origin: top left;
    width: calc(100% / var(--fc-designer-zoom, 1));
    min-height: calc(100% / var(--fc-designer-zoom, 1));
  }

  /* form-create: `._fd-drag-hidden` is a direct-child overlay (not a class on the drag-tool) */
  &:not(.fc-designer-show-hidden) {
    :deep(._fd-drag-tool:has(> ._fd-drag-hidden)) {
      display: none !important;
    }
  }

  /* Show hidden ON: reveal drag chrome and hide the eye-close overlay so the field is editable */
  &.fc-designer-show-hidden {
    :deep(._fd-drag-tool:has(> ._fd-drag-hidden)) {
      display: block !important;
    }

    :deep(._fd-drag-hidden) {
      display: none !important;
    }

    :deep(.fc-designer-hidden-field.fc-designer-hidden-field--concealed) {
      display: block !important;
    }
  }

  :deep(.fc-designer-hidden-field) {
    position: relative;

    &.fc-designer-hidden-field--concealed {
      display: none !important;
    }

    &:not(.fc-designer-hidden-field--concealed)::before {
      content: '';
      position: absolute;
      inset: 0;
      border: 2px dashed #e6a23c;
      background: rgba(230, 162, 60, 0.1);
      border-radius: 4px;
      pointer-events: none;
      z-index: 4;
    }

    &:not(.fc-designer-hidden-field--concealed)::after {
      content: var(--fc-designer-hidden-badge, 'Hidden');
      position: absolute;
      top: 4px;
      right: 6px;
      font-size: 11px;
      line-height: 1.2;
      padding: 2px 8px;
      background: #e6a23c;
      color: #fff;
      border-radius: 3px;
      z-index: 5;
      pointer-events: none;
      font-weight: 600;
      letter-spacing: 0.02em;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
    }
  }
  
  :deep(.fc-designer) {
    height: 100% !important;
  }

  /* fc-designer 右侧属性栏默认 320px — 覆盖为 --fc-designer-side-r-width */
  :deep(._fc-r) {
    width: var(--fc-designer-side-r-width) !important;
    flex: 0 0 var(--fc-designer-side-r-width) !important;
    max-width: var(--fc-designer-side-r-width) !important;
    min-width: var(--fc-designer-side-r-width) !important;
  }

  /* Validate tab — reinforce layout (see designer-validate-panel.scss) */
  :deep(._fc-r ._fd-validate) {
    .el-form-item {
      display: block !important;
    }

    .el-form-item__label {
      width: 100% !important;
      min-width: 0 !important;
    }

    .el-form-item__content {
      width: 100% !important;
      margin-left: 0 !important;
    }

    .el-input-number .el-input__wrapper {
      padding-right: 64px;
    }
  }
  
  // 确保 form-create 设计器内的样式正确应用
  :deep(.form-create) {
    width: 100%;
  }
  
  // 确保设计器内的表单项样式正确
  :deep(.el-form-item) {
    margin-bottom: 18px;
  }
  
  // 确保设计器内的输入框等组件样式正确
  :deep(.el-input),
  :deep(.el-select),
  :deep(.el-date-picker),
  :deep(.el-textarea) {
    width: 100%;
  }
}


// Grouped navigation bar above the tabs
.designer-grouped-nav {
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 0 4px;
  border-bottom: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
  flex-shrink: 0;

  .designer-nav-btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 0 14px;
    height: 40px;
    border: none;
    border-bottom: 2px solid transparent;
    background: transparent;
    cursor: pointer;
    font-size: 14px;
    color: var(--el-text-color-regular);
    white-space: nowrap;
    transition: color 0.2s, border-color 0.2s;
    margin-bottom: -1px;

    &:hover {
      color: var(--el-color-primary);
    }

    &.is-active {
      color: var(--el-color-primary);
      border-bottom-color: var(--el-color-primary);
    }

    .nav-tag {
      flex-shrink: 0;
    }

    .nav-label {
      max-width: 160px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .nav-arrow {
      font-size: 12px;
      color: var(--el-text-color-secondary);
      flex-shrink: 0;
    }
  }
}

.designer-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;

  :deep(.el-tabs__header) {
    margin-bottom: 0;
    flex-shrink: 0;
  }

  :deep(.el-tabs__content) {
    flex: 1;
    overflow: hidden;
  }

  :deep(.el-tab-pane) {
    height: 100%;
  }

  // Hide the native tab header when using grouped nav
  &.designer-tabs--headless :deep(.el-tabs__header) {
    display: none;
  }
}

.dropdown-item-inner {
  display: flex;
  align-items: center;
  min-width: 120px;

  .check-icon {
    flex-shrink: 0;
    width: 16px;
    margin-right: 6px;
    font-size: 14px;
    color: var(--el-color-primary);
    visibility: hidden;

    &.is-visible {
      visibility: visible;
    }
  }

  .dropdown-item-label {
    flex: 1;
    min-width: 0;
  }
}

:deep(.is-active-item) {
  background-color: var(--el-color-primary-light-9);
}

.designer-sub-tables {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.designer-sub-table-item {
  .sub-table-binding-label {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;

    .sub-table-name {
      font-size: 14px;
      font-weight: 500;
      color: #303133;
    }
  }
}

.sub-preview-header {
  display: flex;
  align-items: center;
  padding: 8px 0 4px;
}

.preview-container {
  min-height: 300px;
  padding: 20px;
  
  .form-preview-wrapper {
    // 确保 form-create 样式能够正确应用
    :deep(.form-create) {
      width: 100%;
    }
    
    // 确保表单项样式正确
    :deep(.el-form-item) {
      margin-bottom: 18px;
    }

    // label 不折行；保留 label-width 统一宽度使各行输入框左对齐，超长时撑开
    :deep(.el-form-item__label) {
      white-space: nowrap !important;
      min-width: max-content !important;
      max-width: none !important;
      height: auto !important;
      line-height: 1.5 !important;
      padding-top: 6px;
    }

    :deep(.el-form-item) {
      display: flex !important;
      align-items: flex-start !important;
    }
    
    // 确保输入框等组件样式正确
    :deep(.el-input),
    :deep(.el-select),
    :deep(.el-date-picker),
    :deep(.el-textarea) {
      width: 100%;
    }
    
    // 确保按钮样式正确
    :deep(.el-button) {
      margin-right: 10px;
    }

    // index.scss hides validation errors globally — show them in custom preview
    :deep(.el-form-item__error) {
      display: block !important;
      position: static;
    }
  }
}



.import-fields-dialog {
  .field-selection {
    .field-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 12px;
      padding: 8px 12px;
      background: #f5f7fa;
      border-radius: 4px;
      
      .field-count {
        font-size: 13px;
        color: #909399;
      }
    }
  }
  
  .table-option-with-binding {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
  }
  
  .source-table {
    font-size: 12px;
    color: #909399;
  }

  // Keep the field table at full dialog width (avoid global .table-scroll-wrap
  // max-content sizing which adds an inner scrollbar that clips the last row).
  .import-fields-table-wrap {
    width: 100%;

    :deep(.el-table) {
      width: 100%;
    }

    // Fields already on the target form/sub-table: muted text, no checkbox — re-selecting
    // them is a no-op (import silently skips duplicates), so the row reads as "done" at a glance.
    :deep(.el-table__row.is-already-imported) {
      color: #a8abb2;

      .el-tag {
        opacity: 0.7;
      }
    }
  }

  .already-imported-icon {
    color: #67c23a;
    font-size: 16px;
    vertical-align: middle;
  }
}

.form-item-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.form-scene-tag,
.bound-table-tag {
  margin-left: 8px;
}



.bind-table-dialog {
  .table-option {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
  }
  
  .table-fields-preview {
    max-height: 150px;
    overflow-y: auto;
    padding: 8px;
    background: #f5f7fa;
    border-radius: 4px;
  }
}

.sub-table-placeholder-widget {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  border: 1px dashed #c0c4cc;
  border-radius: 4px;
  background: #f5f7fa;
  min-height: 36px;
}

.relation-preview-wrapper {
  margin: -4px 0 16px 0;
}

.relation-preview-table {
  width: 100%;
  :deep(tr) {
    background-color: #f5f7fa !important;
  }
  :deep(td.el-table__cell) {
    background-color: #f5f7fa !important;
  }
}
</style>
