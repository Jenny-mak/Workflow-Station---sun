<template>
  <!-- 遮罩必须挂到 body：dialog 已 append-to-body，若遮罩留在子表/Tab 内会因祖先 transform 导致 fixed 错位或裁剪 -->
  <Teleport to="body">
    <div
      v-if="visible"
      class="sub-table-backdrop"
      role="presentation"
      aria-hidden="true"
      :data-sub-table-dialog="dialogInstanceId"
      :style="{ zIndex: dialogZIndex - 1 }"
      @click="handleClose"
    />
  </Teleport>
  <el-dialog
    :model-value="visible"
    :title="title || (mode === 'edit' ? t('subTable.editRecord') : t('subTable.addRecord'))"
    :width="dialogWidth"
    :close-on-click-modal="false"
    :modal="false"
    :z-index="dialogZIndex"
    :data-sub-table-dialog="dialogInstanceId"
    append-to-body
    @update:model-value="handleClose"
    @close="handleClose"
  >
    <el-form
      :key="dialogKey"
      ref="formRef"
      class="form-readonly-surface"
      :model="formData"
      :rules="formRules"
      :label-width="stableLabelWidth"
      label-position="left"
      :validate-on-rule-change="false"
    >
      <template
        v-for="group in dialogLayoutGroups"
        :key="group.key"
      >
        <component
          :is="group.title !== null ? 'el-card' : 'div'"
          v-bind="group.title !== null ? { shadow: 'never' } : {}"
          :class="group.title !== null ? 'sub-table-dialog-card' : undefined"
        >
          <template
            v-if="group.title"
            #header
          >
            <span class="sub-table-dialog-card-title">{{ group.title }}</span>
          </template>
          <template
            v-for="item in group.items"
            :key="item.key"
          >
            <!-- Assignment Mode block: routing this row to a person or to a role pool.
             The mode cards and the picker the chosen mode needs live in one box, so
             the block is never an empty frame (see mi-assignment-block CSS). -->
            <div
              v-if="item.type === 'miAssignment' && showAssignmentBlock"
              class="mi-assignment-block__head"
            >
              <div class="mi-assignment-block__title">
                {{ t('subTable.assignMode') }}
              </div>
              <div
                class="mi-assignment-block__modes"
                role="radiogroup"
                :aria-label="t('subTable.assignMode')"
              >
                <button
                  v-for="option in assignModeOptions"
                  :key="option.value"
                  type="button"
                  role="radio"
                  :aria-checked="assignMode === option.value"
                  :aria-disabled="isModeCardDisabled(option.value)"
                  class="mi-assignment-mode-card"
                  :class="{
                    'is-selected': assignMode === option.value,
                    'is-disabled': isModeCardDisabled(option.value),
                  }"
                  @click="selectAssignMode(option.value)"
                >
                  <span class="mi-assignment-mode-card__dot" />
                  <span class="mi-assignment-mode-card__text">
                    <span class="mi-assignment-mode-card__name">{{ t(option.label) }}</span>
                    <span class="mi-assignment-mode-card__hint">{{ t(option.hint) }}</span>
                  </span>
                </button>
              </div>
            </div>

            <template v-else-if="item.type === 'column'">
              <el-form-item
                :label="item.column.label"
                :prop="item.column.field"
                :error="columnErrorMessages[item.column.field]"
                :class="{
                  'mi-assignment-block__field': !!item.assignmentSlot,
                  'mi-assignment-block__field--last': item.assignmentSlot === 'last',
                }"
              >
                <!-- Keep the existing control branches scoped to the ordered column item. -->
                <template
                  v-for="col in [item.column]"
                  :key="col.field"
                >
                  <!-- MI 按角色分派：BU 级联树选择（父 BU 可展开子 BU，与 admin 一致；按 field 名抢先匹配） -->
                  <el-cascader
                    v-if="col.field === configuredBuField"
                    v-model="selectedBuId"
                    :options="buTree"
                    :props="(buCascaderProps as any)"
                    :placeholder="col.placeholder || t('subTable.selectBusinessUnit')"
                    :disabled="isColDisabled(col)"
                    filterable
                    clearable
                    :teleported="true"
                    :popper-class="overlayPopperClass"
                    style="width: 100%"
                    @change="(v: any) => onBuChange(v)"
                  />

                  <!-- MI 按角色分派：Role 选择（选项随所选 BU 收敛） -->
                  <el-select
                    v-else-if="configuredBuField && col.field === configuredRoleField"
                    v-model="formData[col.field]"
                    :placeholder="col.placeholder || t('subTable.selectRole')"
                    :loading="roleLoading"
                    :clearable="!isColDisabled(col)"
                    :disabled="isColDisabled(col) || !formData[configuredBuField]"
                    filterable
                    :teleported="true"
                    :popper-class="overlayPopperClass"
                    style="width: 100%"
                    @change="(v: string) => onRoleChange(v)"
                  >
                    <el-option
                      v-for="opt in roleOptions"
                      :key="opt.value"
                      :label="opt.label"
                      :value="opt.value"
                    />
                  </el-select>

                  <!-- text (sensitive mask is display-only; formData stays plaintext) -->
                  <el-input
                    v-else-if="(!col.type || col.type === 'text') && !isUploadColumn(col, formData[col.field])"
                    :model-value="textDisplay(col)"
                    :placeholder="col.placeholder || col.label"
                    :maxlength="col.props?.maxlength"
                    :disabled="isColDisabled(col)"
                    :readonly="showMasked(col) && !isColDisabled(col)"
                    :clearable="!isColDisabled(col) && !showMasked(col)"
                    @update:model-value="(v: string) => onTextUpdate(col, v)"
                    @change="() => onDialogFieldChange(col.field)"
                    @focus="onTextFocus(col)"
                    @blur="() => { onTextBlur(col); onDialogFieldBlur(col.field) }"
                  />

                  <!-- textarea -->
                  <el-input
                    v-else-if="col.type === 'textarea'"
                    v-model="formData[col.field]"
                    type="textarea"
                    :rows="col.props?.rows || 3"
                    :placeholder="col.placeholder || col.label"
                    :maxlength="col.props?.maxlength"
                    :disabled="isColDisabled(col)"
                    @change="() => onDialogFieldChange(col.field)"
                    @blur="() => onDialogFieldBlur(col.field)"
                  />

                  <!-- number -->
                  <el-input-number
                    v-else-if="col.type === 'number'"
                    v-model="formData[col.field]"
                    :precision="col.props?.precision"
                    :min="col.props?.min"
                    :max="col.props?.max"
                    :placeholder="col.placeholder || col.label"
                    :disabled="isColDisabled(col)"
                    style="width: 100%"
                    @change="(v: number | undefined) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- select -->
                  <el-select
                    v-else-if="col.type === 'select'"
                    v-model="formData[col.field]"
                    :placeholder="col.placeholder || col.label"
                    :multiple="col.props?.multiple"
                    :clearable="!isColDisabled(col)"
                    :disabled="isColDisabled(col)"
                    :teleported="true"
                    :popper-class="overlayPopperClass"
                    style="width: 100%"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  >
                    <el-option
                      v-for="opt in (col.props?.options ?? col.options ?? [])"
                      :key="opt.value"
                      :label="opt.label"
                      :value="opt.value"
                    />
                  </el-select>

                  <!-- radio -->
                  <el-radio-group
                    v-else-if="col.type === 'radio'"
                    v-model="formData[col.field]"
                    :disabled="isColDisabled(col)"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  >
                    <el-radio
                      v-for="opt in (col.props?.options ?? col.options ?? [])"
                      :key="opt.value"
                      :value="opt.value"
                    >
                      {{ opt.label }}
                    </el-radio>
                  </el-radio-group>

                  <!-- checkbox -->
                  <el-checkbox-group
                    v-else-if="col.type === 'checkbox'"
                    v-model="formData[col.field]"
                    :disabled="isColDisabled(col)"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  >
                    <el-checkbox
                      v-for="opt in (col.props?.options ?? col.options ?? [])"
                      :key="opt.value"
                      :value="opt.value"
                    >
                      {{ opt.label }}
                    </el-checkbox>
                  </el-checkbox-group>

                  <!-- password -->
                  <el-input
                    v-else-if="col.type === 'password'"
                    v-model="formData[col.field]"
                    type="password"
                    show-password
                    :placeholder="col.placeholder || col.label"
                    :disabled="isColDisabled(col)"
                    :clearable="!isColDisabled(col)"
                    @change="() => onDialogFieldChange(col.field)"
                    @blur="() => onDialogFieldBlur(col.field)"
                  />

                  <!-- timerange -->
                  <el-time-picker
                    v-else-if="col.type === 'timerange'"
                    v-model="formData[col.field]"
                    is-range
                    value-format="HH:mm:ss"
                    :start-placeholder="col.props?.startPlaceholder || t('subTable.startTime')"
                    :end-placeholder="col.props?.endPlaceholder || t('subTable.endTime')"
                    :disabled="isColDisabled(col)"
                    :teleported="true"
                    :popper-class="datePopperClass"
                    style="width: 100%"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- treeselect -->
                  <el-tree-select
                    v-else-if="col.type === 'treeselect'"
                    v-model="formData[col.field]"
                    :data="col.props?.treeData || []"
                    :multiple="col.props?.multiple"
                    :check-strictly="col.props?.checkStrictly !== false"
                    :placeholder="col.placeholder || col.label"
                    :clearable="!isColDisabled(col)"
                    :disabled="isColDisabled(col)"
                    :teleported="true"
                    :popper-class="overlayPopperClass"
                    style="width: 100%"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- tree -->
                  <el-tree
                    v-else-if="col.type === 'tree'"
                    :data="col.props?.treeData || []"
                    :props="col.props?.labelProps || { label: 'label', children: 'children' }"
                    :node-key="col.props?.nodeKey || 'id'"
                    :show-checkbox="col.props?.showCheckbox !== false && !isColDisabled(col)"
                    :class="{ 'tree-readonly': isColDisabled(col) }"
                    @check="(_node: any, state: any) => {
                      if (isColDisabled(col)) return
                      formData[col.field] = state.checkedKeys
                      onDialogFieldChange(col.field, state.checkedKeys)
                    }"
                  />

                  <!-- switch -->
                  <el-switch
                    v-else-if="col.type === 'switch'"
                    v-model="formData[col.field]"
                    :disabled="isColDisabled(col)"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- date -->
                  <el-date-picker
                    v-else-if="col.type === 'date'"
                    v-model="formData[col.field]"
                    type="date"
                    value-format="YYYY-MM-DD"
                    :placeholder="col.placeholder || col.label"
                    :disabled="isColDisabled(col)"
                    :teleported="true"
                    :popper-class="datePopperClass"
                    style="width: 100%"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- datetime -->
                  <el-date-picker
                    v-else-if="col.type === 'datetime'"
                    v-model="formData[col.field]"
                    type="datetime"
                    value-format="YYYY-MM-DD HH:mm:ss"
                    :placeholder="col.placeholder || col.label"
                    :disabled="isColDisabled(col)"
                    :teleported="true"
                    :popper-class="datePopperClass"
                    style="width: 100%"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- upload (readonly) -->
                  <div
                    v-else-if="isUploadColumn(col, formData[col.field]) && isColDisabled(col)"
                    class="ro-value"
                  >
                    <FormUploadDropZone
                      compact
                      disabled
                      :action="col.props?.action && col.props.action !== '/' ? col.props.action : (uploadUrl || '/api/v1/upload')"
                      :accept="col.props?.accept || ''"
                      :limit="maxFilesOf(col)"
                      :multiple="isMultiple(col)"
                      :file-list="uploadFileLists[col.field] || []"
                      :max-file-size-mb="maxFileSizeMbOf(col)"
                      :drag-text="t('upload.dragText')"
                      :click-text="t('upload.clickText')"
                      :fail-label="t('upload.failed')"
                      :remove-label="t('common.delete')"
                      :handle-open-details="(file) => openDialogDetails(col, file)"
                    />
                  </div>
                  <!-- upload -->
                  <div
                    v-else-if="isUploadColumn(col, formData[col.field])"
                    style="display: flex; flex-direction: column; gap: 4px;"
                  >
                    <FormUploadDropZone
                      compact
                      :action="col.props?.action && col.props.action !== '/' ? col.props.action : (uploadUrl || '/api/v1/upload')"
                      :accept="col.props?.accept || ''"
                      :limit="maxFilesOf(col)"
                      :multiple="isMultiple(col)"
                      :file-list="uploadFileLists[col.field] || []"
                      :http-request="httpRequest"
                      :max-file-size-mb="maxFileSizeMbOf(col)"
                      :drag-text="t('upload.dragText')"
                      :click-text="t('upload.clickText')"
                      :tip="t('upload.tip', { types: col.props?.accept || 'jpg/png/pdf/docx/xlsx', size: maxFileSizeMbOf(col) })"
                      :fail-label="t('upload.failed')"
                      :remove-label="t('common.delete')"
                      :handle-success="(res: unknown, file: { name?: string; url?: string }, list: Array<{ url?: string; name?: string; status?: string; response?: unknown }>) => handleUploadSuccess(res, file, col, list)"
                      :handle-change="(_file: unknown, list: Array<{ url?: string; name?: string; status?: string; response?: unknown }>) => handleUploadChange(col, list)"
                      :handle-remove="(_file: unknown, list: Array<{ url?: string; name?: string; status?: string; response?: unknown }>) => handleUploadRemove(col, list)"
                      :handle-exceed="() => handleUploadExceed(col)"
                      :handle-error="(error: unknown) => handleUploadError(col, error)"
                      :handle-size-exceed="() => handleSizeExceed(col)"
                      :handle-open-details="(file) => openDialogDetails(col, file)"
                    />
                  </div>

                  <!-- colorPicker -->
                  <el-color-picker
                    v-else-if="col.type === 'colorPicker'"
                    v-model="formData[col.field]"
                    :show-alpha="col.props?.showAlpha || false"
                    :disabled="isColDisabled(col)"
                    :teleported="true"
                    :popper-class="colorPopperClass"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- rate -->
                  <el-rate
                    v-else-if="col.type === 'rate'"
                    v-model="formData[col.field]"
                    :max="col.props?.max || 5"
                    :allow-half="col.props?.allowHalf || false"
                    :disabled="isColDisabled(col)"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- slider -->
                  <el-slider
                    v-else-if="col.type === 'slider'"
                    v-model="formData[col.field]"
                    :min="col.props?.min ?? 0"
                    :max="col.props?.max ?? 100"
                    :step="col.props?.step || 1"
                    :disabled="isColDisabled(col)"
                    style="width: 100%"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- editor (readonly) -->
                  <div
                    v-else-if="col.type === 'editor' && isColDisabled(col)"
                    class="editor-readonly-ro"
                    v-html="sanitizeHtml(formData[col.field] || '')"
                  />
                  <!-- editor -->
                  <div
                    v-else-if="col.type === 'editor'"
                    class="sub-table-editor-wrapper"
                  >
                    <Toolbar
                      :editor="editorInstances[col.field]"
                      :default-config="{}"
                      mode="simple"
                      style="border-bottom: 1px solid #ccc"
                    />
                    <Editor
                      :model-value="formData[col.field] || ''"
                      :default-config="{ placeholder: col.placeholder || col.label }"
                      mode="simple"
                      style="height: 200px; overflow-y: hidden"
                      @on-created="(editor: any) => onEditorCreated(editor, col.field)"
                      @on-change="(editor: any) => onEditorChange(editor, col.field)"
                    />
                  </div>

                  <!-- signature (readonly) -->
                  <img
                    v-else-if="col.type === 'signature' && isColDisabled(col) && formData[col.field]"
                    :src="formData[col.field]"
                    class="signature-preview-ro"
                    alt="Signature"
                  >
                  <span
                    v-else-if="col.type === 'signature' && isColDisabled(col)"
                    class="ro-value"
                  >-</span>
                  <!-- signature -->
                  <div
                    v-else-if="col.type === 'signature'"
                    style="width: 100%;"
                  >
                    <canvas
                      :ref="(el: any) => { if (el) signatureCanvasRefs[col.field] = el }"
                      class="signature-canvas"
                      @mousedown="startSign($event, col.field)"
                      @mousemove="drawSign($event, col.field)"
                      @mouseup="endSign(col.field)"
                      @mouseleave="endSign(col.field)"
                      @touchstart.prevent="startSignTouch($event, col.field)"
                      @touchmove.prevent="drawSignTouch($event, col.field)"
                      @touchend="endSign(col.field)"
                    />
                    <div style="margin-top: 4px;">
                      <el-button
                        size="small"
                        @click="clearSignature(col.field)"
                      >
                        {{ t('fieldRenderer.clear') }}
                      </el-button>
                    </div>
                  </div>

                  <!-- transfer -->
                  <el-transfer
                    v-else-if="col.type === 'transfer'"
                    v-model="formData[col.field]"
                    :data="(col.props?.options ?? col.options ?? []).map((o: any) => ({ key: o.value, label: o.label }))"
                    :titles="[col.props?.leftTitle || t('subTable.transferSource'), col.props?.rightTitle || t('subTable.transferTarget')]"
                    :filterable="!isColDisabled(col)"
                    :disabled="isColDisabled(col)"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- cascader -->
                  <el-cascader
                    v-else-if="col.type === 'cascader'"
                    v-model="formData[col.field]"
                    :options="col.props?.options ?? col.options ?? []"
                    :props="col.props?.cascaderProps"
                    :placeholder="col.placeholder || col.label"
                    :clearable="!isColDisabled(col)"
                    :disabled="isColDisabled(col)"
                    :teleported="true"
                    :popper-class="overlayPopperClass"
                    style="width: 100%"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- lookup -->
                  <div
                    v-else-if="col.type === 'lookup'"
                    class="lookup-field-wrapper"
                  >
                    <LookupField
                      v-model="formData[col.field]"
                      :table-id="Number(col.props?.tableId || 0)"
                      :search-fields="col.props?.searchFields || []"
                      :display-field="col.props?.displayField || ''"
                      :display-fields="col.props?.displayFields || []"
                      :selected-display-field="getLookupSelectedDisplayField(col)"
                      :filter-conditions="effectiveLookupFilterConditions(col)"
                      :lookup-config="col.props?.lookupConfig"
                      :view-fields="col.props?.viewFields || []"
                      :placeholder="col.placeholder || col.label"
                      :readonly="isColDisabled(col)"
                      :multiple="col.props?.multiple === true"
                      @select="(row: Record<string, any>) => onLookupSelectWithEvents(col.field, row)"
                      @clear="() => onLookupClearWithEvents(col.field)"
                      @view-fields-loaded="(fields: any[]) => onLookupViewFieldsLoaded(col.field, fields)"
                    />
                    <LookupViewDisplay
                      v-if="col.props?.showBackfillView !== false && effectiveLookupSelectedRow(col.field)"
                      :selected-data="effectiveLookupSelectedRow(col.field)"
                      :view-fields="effectiveLookupViewFieldsForDialog(col)"
                    />
                  </div>

                  <!-- owner: readonly; backend fills Creator / Current Assignee -->
                  <OwnerField
                    v-else-if="col.type === 'owner'"
                    :model-value="formData[col.field]"
                    :owner-config="col.props?.ownerConfig as string | undefined"
                    :display="typeof formData[`${col.field}__display`] === 'string' ? formData[`${col.field}__display`] : ''"
                    readonly
                  />

                  <!-- user -->
                  <el-select
                    v-else-if="col.type === 'user'"
                    v-model="formData[col.field]"
                    :placeholder="col.placeholder || t('subTable.selectUser')"
                    filterable
                    remote
                    :remote-method="(query: string) => handleUserSearch(query, col.field)"
                    :loading="userSearchLoading[col.field]"
                    :clearable="!isColDisabled(col)"
                    :disabled="isColDisabled(col)"
                    :teleported="true"
                    :popper-class="overlayPopperClass"
                    style="width: 100%"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  >
                    <el-option
                      v-for="user in (userSearchOptions[col.field] || [])"
                      :key="user.id"
                      :label="user.name"
                      :value="user.id"
                    />
                  </el-select>

                  <!-- department -->
                  <el-tree-select
                    v-else-if="col.type === 'department'"
                    v-model="formData[col.field]"
                    :data="departmentTreeData"
                    :props="({ label: 'name', value: 'id', children: 'children' } as any)"
                    :placeholder="col.placeholder || t('subTable.selectDepartment')"
                    :loading="departmentLoading"
                    check-strictly
                    :clearable="!isColDisabled(col)"
                    :disabled="isColDisabled(col)"
                    filterable
                    :teleported="true"
                    :popper-class="overlayPopperClass"
                    style="width: 100%"
                    @change="(v: unknown) => onDialogFieldChange(col.field, v)"
                  />

                  <!-- fallback -->
                  <el-input
                    v-else-if="col.type && !HANDLED_TYPES.has(col.type)"
                    v-model="formData[col.field]"
                    :placeholder="col.placeholder || col.label"
                    :disabled="isColDisabled(col)"
                    :clearable="!isColDisabled(col)"
                  />
                </template>
              </el-form-item>
            </template>
          </template>
        </component>
      </template>
    </el-form>

    <!-- Nested sub-tables placed in this binding's form design (sub-table-in-sub-table) -->
    <div
      v-for="nested in nestedSubTables || []"
      :key="`nested-sub-table-${nested.bindingId}`"
      class="dialog-nested-sub-table"
    >
      <NestedSubTableField
        :title="nested.tableName"
        :columns="nested.columns"
        :dialog-columns="nested.dialogColumns"
        :form-fields="nested.formFields"
        :form-options="nested.formOptions"
        :assignment-config="nested.assignmentConfig"
        :model-value="nestedRowsFor(nested)"
        :primary-key-fields="nested.primaryKeyFields"
        :upload-url="uploadUrl"
        editable
        :allow-add="nested.allowAdd"
        :allow-edit="nested.allowEdit"
        :allow-delete="nested.allowDelete"
        :table-id="nested.tableId ?? null"
        :field-definitions="nested.fieldDefinitions"
        :function-unit-id="hostFunctionUnitId"
        :task-id="hostTaskId"
        :binding-link-mode="nested.bindingLinkMode"
        :binding-foreign-key-field="nested.foreignKeyField"
        :parent-row="formData"
        :parent-table-id="hostTableId ?? null"
        :parent-tables-by-id="nestedParentTablesById"
        :primary-form-data="hostPrimaryFormData"
        :primary-table-id="hostPrimaryTableId ?? null"
        :primary-table-display-name="hostPrimaryTableDisplayName"
        :sub-table-bindings-for-context="hostSubTableBindingsForContext"
        :linked-sub-table-bindings="hostLinkedSubTableBindings"
        :binding-id="nested.bindingId"
        :field-permissions="fieldPermissions"
        @update:model-value="(nestedRows: unknown[]) => onNestedRowsUpdate(nested, nestedRows)"
        @update:parent-row="onNestedParentRowPatch"
      />
    </div>

    <!-- RecordNote panels from this binding's form design: RECORD scope binds the
         edited row (disabled until the row is saved), TABLE scope binds this
         sub-table's shared stream within the current process. -->
    <div
      v-for="rn in recordNoteFields || []"
      :key="rn.key"
      class="dialog-record-note"
    >
      <RecordNoteField
        :config="rn._recordNote"
        :table-id="recordNoteTableId ?? null"
        :record-id="editingRowStableId"
        :process-instance-id="recordNoteInstanceId ?? null"
        :function-unit-id="recordNoteFunctionUnitId ?? null"
        :task-id="hostTaskId ?? null"
        :readonly="hostTaskId != null && rn._recordNote?.readonly === true"
      />
    </div>

    <template #footer>
      <el-button @click="handleClose">
        {{ t('common.cancel') }}
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="saveDialogRow"
      >
        {{ t('common.save') }}
      </el-button>
    </template>
  </el-dialog>
  <FormUploadDetailsDrawer
    v-model="detailsOpen"
    :title="t('upload.fileDetails')"
    :file="detailsFile"
    :readonly="detailsReadonly"
    :labels="uploadDetailLabels"
    :preview-file="previewDetailsFile"
  />
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, inject, nextTick, onBeforeUnmount, ref, toRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import FormUploadDropZone from '@platform-shared/upload/FormUploadDropZone.vue'
import FormUploadDetailsDrawer from '@platform-shared/upload/FormUploadDetailsDrawer.vue'
import type { UploadDetailFile } from '@platform-shared/upload/FormUploadFileDetails.vue'
import '@wangeditor/editor/dist/css/style.css'
import { isUploadColumn, getLookupSelectedDisplayField } from './subTableAddDialogHelpers'
import type { DialogColumn } from './subTableAddDialogHelpers'
import { extractFileLinks } from '@platform-shared/list/fileNames'
import { FILE_PREVIEW_PLAYLIST_KEY, openFilePreviewFromList } from '@/composables/filePreview/useFilePreview'
import { uploadPropsBlockDownload } from '@/utils/filePreview'
import { warnIfUploadsBlocking } from '@platform-shared/upload/uploadSubmitGate'
import {
  buildDialogLayoutGroups,
  groupAssignmentFieldsUnderMarker,
} from './subTableAddDialogHelpers/dialogFormLayout'
import type { FormField, RowFormulaRule, ValidationRule } from './formRendererHelpers'
import { resolveRowStableId } from './formRendererHelpers/recordNoteFields'
import RecordNoteField from './RecordNoteField.vue'
import DOMPurify from 'dompurify'
import LookupField from './lookup/LookupField.vue'
import OwnerField from './owner/OwnerField.vue'
import LookupViewDisplay from './lookup/LookupViewDisplay.vue'
import { useSubTableDialogLookup } from '@/composables/subTableAddDialog/useSubTableDialogLookup'
import { useSubTableBuRoleCascade } from '@/composables/subTableAddDialog/useSubTableBuRoleCascade'
import { useSubTableDialogSignature } from '@/composables/subTableAddDialog/useSubTableDialogSignature'
import { useSubTableDialogEditor } from '@/composables/subTableAddDialog/useSubTableDialogEditor'
import { useSubTableDialogRelations } from '@/composables/subTableAddDialog/useSubTableDialogRelations'
import { useSubTableDialogUpload } from '@/composables/subTableAddDialog/useSubTableDialogUpload'
import { useSubTableDialogForm } from '@/composables/subTableAddDialog/useSubTableDialogForm'
import { useSubTableDialogComponentEvents } from '@/composables/subTableAddDialog/useSubTableDialogComponentEvents'
import { useSubTableDialogSensitiveMask } from '@/composables/subTableAddDialog/useSubTableDialogSensitiveMask'
import { mergeNestedSubTableRowsIntoSto } from './formRendererHelpers'
import { pullNestedRowsForBindingFromParentRows } from '@/composables/tasks/subTableNestedRows'
import type { NestedSubTableDescriptor, SubTableBinding } from '@/composables/subTableField/subTableFieldTypes'
import type { BindingFieldDefinition } from '@/utils/subTableRowRuntime'
import {
  fieldsHiddenByMode,
  fieldsOwnedByMode,
  isAssignModeSwitchable,
  isAssignmentConfigured,
  lockedAssignMode,
  resolveAssignModeFromRow,
  type AssignmentConfig,
  type AssignmentMode,
} from '@/utils/miAssignmentConfig'

// SubTableField hosts this dialog and the dialog hosts nested SubTableField — resolve the
// circular SFC pair lazily.
const NestedSubTableField = defineAsyncComponent(() => import('./SubTableField.vue'))

// ─── Component ────────────────────────────────────────────────────────────────

const { t } = useI18n()

/** All field types that have explicit rendering above the fallback.
 *  The fallback el-input / readonly span must NOT render for these types
 *  to avoid double controls for the same field. */
const HANDLED_TYPES = new Set([
  'text', 'textarea', 'number', 'select', 'radio', 'checkbox',
  'password', 'timerange', 'treeselect', 'tree', 'switch', 'date',
  'datetime', 'upload', 'advancedUpload', 'colorPicker', 'rate', 'slider', 'editor',
  'signature', 'transfer', 'cascader', 'lookup', 'user', 'department', 'owner',
])

/** Sanitize HTML content to prevent XSS */
function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'u', 's', 'ol', 'ul', 'li',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'a', 'img', 'table', 'tr', 'td', 'th', 'span', 'div'],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'class', 'style', 'target', 'rel'],
  })
}

const props = defineProps<{
  visible: boolean
  columns: DialogColumn[]
  /** List-view columns for audit auto-fill on save (created_at / updated_at etc.). */
  auditColumns?: DialogColumn[]
  /**
   * Designer form-field tree for this binding (includes elCard). When present with cards,
   * Add/Edit dialog wraps fields in the same card layout as DW Form Preview.
   */
  formFields?: FormField[]
  /** Sub-form Form Design options (onCreated / onMounted / …). */
  formOptions?: Record<string, unknown> | null
  /** BPMN-derived MI assignment contract for this binding. */
  assignmentConfig?: AssignmentConfig
  title?: string
  mode: 'add' | 'edit'
  initialData?: Record<string, any>
  rowFormulas?: RowFormulaRule[]
  /** This binding's own table columns — supplies the computed (formula) column definitions. */
  fieldDefinitions?: BindingFieldDefinition[]
  columnValidationRules?: Record<string, ValidationRule[]>
  uploadUrl?: string
  /** Nested sub-tables from this binding's form design — rows save under the row's `__subTables__`. */
  nestedSubTables?: NestedSubTableDescriptor[]
  /**
   * Host binding's FK/PK runtime context, forwarded to nested sub-tables so a grandchild row
   * goes through the same allocate/seed path as a top-level one: `host*` describes the row this
   * dialog edits (it is the nested rows' parent), the rest is the host's own ancestor chain.
   */
  hostTableId?: number | null
  hostFieldDefinitions?: BindingFieldDefinition[]
  hostFunctionUnitId?: string
  hostTaskId?: string
  hostPrimaryFormData?: Record<string, unknown>
  hostPrimaryTableId?: number | null
  hostPrimaryTableDisplayName?: string
  hostSubTableBindingsForContext?: Array<{
    tableId?: number | null
    bindingType?: string
    tableName?: string
    tableDisplayName?: string
  }>
  hostParentTablesById?: Record<number, { fieldDefinitions: BindingFieldDefinition[] }>
  hostLinkedSubTableBindings?: SubTableBinding[]
  /** When set, awaited before closing (supports async PK allocate on Save). */
  saveRow?: (row: Record<string, unknown>) => void | Promise<void>
  /** RecordNote components placed in this binding's form design. */
  recordNoteFields?: FormField[]
  recordNoteTableId?: number | string | null
  /** Current process instance id — TABLE scope stream anchor. */
  recordNoteInstanceId?: string | null
  recordNoteFunctionUnitId?: string | number | null
  /** This sub-table's PK fields — resolve the edited row's stable id for RECORD scope. */
  primaryKeyFields?: string[]
  /** This dialog's own hosting binding id — resolves this binding's `${bindingId}:${fieldName}` entries in fieldPermissions. */
  bindingId?: number | null
  /**
   * Task-node field permissions. Gates this dialog's own top-level columns (via isColDisabled in
   * useSubTableDialogForm) AND is forwarded to nested sub-tables-in-sub-tables so their fields get
   * the same enforcement.
   */
  fieldPermissions?: Record<string, string> | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', val: boolean): void
  (e: 'save', rowData: Record<string, any>): void
}>()

// Shared model owned by the SFC and threaded through the composables below.
const formData = ref<Record<string, any>>({})

const {
  onDialogFieldChange,
  onDialogFieldBlur,
  isDialogFieldVisible,
  eventRequiredState,
  eventRequiredTick,
  resetDialogEventVisibility,
  bootstrapDialogFormLifecycle,
  runFormOnReload,
  runFormBeforeSubmit,
  runFormOnSubmit,
  runFormOnReset,
  scriptFieldErrors,
  isDialogFieldDisabled,
} = useSubTableDialogComponentEvents(
  formData,
  () => props.columns,
  () => props.formOptions,
)

// Stable identity of the row being edited — RECORD-scope note anchor. Resolution
// mirrors subTableRowMerge: declared PK first, then rowId, then the platform
// id / id_idw alias pair. New (unsaved) rows have none: the panel shows its
// "available after save" hint.
const editingRowStableId = computed<string | null>(() =>
  props.mode === 'edit' ? resolveRowStableId(formData.value, props.primaryKeyFields) : null)

// ─── Nested sub-tables (sub-table-in-sub-table inside the row dialog) ────────
/**
 * A nested sub-table brings a whole table (plus its Operation column) into the dialog, which
 * a 600px form-sized shell cannot fit — its rightmost columns land outside the viewport.
 * Widen the shell whenever nested tables are present; plain field-only rows keep 600px.
 */
const dialogWidth = computed(() =>
  props.nestedSubTables?.length ? 'min(1100px, calc(100vw - 48px))' : '600px')

/**
 * Base layer when this dialog opens straight from a page (nothing stacked above it).
 * Kept as the historical value so the ordinary case renders exactly as before.
 */
const BASE_DIALOG_Z = 2010

/**
 * The layer this dialog (and its own backdrop, 1 below) must sit on.
 *
 * This dialog opens from several depths: directly on a form, and also from inside the Link
 * Form modal, which is a hand-rolled overlay at z-index 5000. A fixed 2010 put the dialog
 * BEHIND that overlay — the "Add" button on a nested sub-table appeared to do nothing, while
 * the dialog was actually rendered and unreachable underneath.
 *
 * So measure instead of guessing: take the highest z-index among the overlays currently on
 * screen and go above it. Recomputed on each open (not once at mount) because the same
 * component instance can be opened from different depths during its lifetime.
 */
const dialogZIndex = ref(BASE_DIALOG_Z)

/** Marks this instance's own nodes so a reopen never stacks on top of itself. */
const dialogInstanceId = `sub-table-dialog-${Math.random().toString(36).slice(2, 10)}`

function highestOverlayZIndex(): number {
  // Every stacking surface that can legitimately be above the page: Element Plus's own
  // overlays/poppers plus this app's hand-rolled modal panels.
  const selectors = '.el-overlay, .el-dialog__wrapper, .el-popper, .link-form-modal-overlay'
  let highest = 0
  for (const el of document.querySelectorAll<HTMLElement>(selectors)) {
    // Skip this instance's own nodes: counting them would ratchet the layer upward on every
    // reopen. Matched by instance id rather than by content, because a dialog's markup varies
    // with the design it renders (elCard groups, assignment block, …) and would not always
    // identify itself. Other SubTableAddDialogs are NOT skipped — a nested one must clear
    // the one it opened from.
    if (el.closest(`[data-sub-table-dialog="${dialogInstanceId}"]`)) continue
    if (el.querySelector(`[data-sub-table-dialog="${dialogInstanceId}"]`)) continue
    if (el.offsetParent === null && getComputedStyle(el).position !== 'fixed') continue
    const z = Number.parseInt(getComputedStyle(el).zIndex || '', 10)
    if (Number.isFinite(z)) highest = Math.max(highest, z)
  }
  return highest
}

/**
 * Layer for THIS dialog's teleported select / date / colour poppers.
 *
 * The poppers are teleported to body, so they cannot inherit the dialog's layer through the
 * DOM and are given it through a custom property instead. That property must be per-dialog,
 * not global: a nested sub-table's dialog opens while its parent dialog is still open, so a
 * single shared `:root` value would be overwritten by the nested dialog and leave the parent's
 * pickers resolving against the wrong (higher) layer once the nested one closed.
 *
 * Element Plus lets each popper carry its own class, so scope the variable to this instance's
 * poppers via a stylesheet rule keyed on the instance id.
 */
const popperClassSuffix = dialogInstanceId
const overlayPopperClass = `sub-table-dialog-popper ${popperClassSuffix}`
const datePopperClass = `sub-table-date-popper ${popperClassSuffix}`
const colorPopperClass = `sub-table-color-popper ${popperClassSuffix}`

let popperLayerStyleEl: HTMLStyleElement | null = null

function publishPopperLayer(z: number): void {
  if (!popperLayerStyleEl) {
    popperLayerStyleEl = document.createElement('style')
    popperLayerStyleEl.dataset.subTableDialogPopperLayer = dialogInstanceId
    document.head.appendChild(popperLayerStyleEl)
  }
  // +40 keeps a picker clear of its own dialog without reaching the next modal above it.
  popperLayerStyleEl.textContent = `.${popperClassSuffix} { z-index: ${z + 40} !important; }`
}

onBeforeUnmount(() => {
  popperLayerStyleEl?.remove()
  popperLayerStyleEl = null
})

watch(
  () => props.visible,
  (open) => {
    if (!open) return
    // +2 leaves the slot directly beneath for this dialog's own backdrop.
    dialogZIndex.value = Math.max(BASE_DIALOG_Z, highestOverlayZIndex() + 2)
    publishPopperLayer(dialogZIndex.value)
  },
  { immediate: true, flush: 'post' },
)

/** Rows for one nested table, read from the edited row's `__subTables__` (alias keys). */
function nestedRowsFor(nested: NestedSubTableDescriptor): Record<string, unknown>[] {
  return pullNestedRowsForBindingFromParentRows(
    {
      bindingId: nested.bindingId,
      tableName: nested.tableName,
      designerTableName: nested.designerTableName,
      tableId: nested.tableId ?? null,
    },
    [formData.value],
  )
}

/** Write edited nested rows back into formData so Save carries them on the row. */
function onNestedRowsUpdate(nested: NestedSubTableDescriptor, nestedRows: unknown[]) {
  formData.value = {
    ...formData.value,
    __subTables__: mergeNestedSubTableRowsIntoSto([formData.value], nested, nestedRows),
  }
}

/**
 * The edited row is the nested rows' parent. Saving a grandchild forces this row's auto PK to
 * be allocated early (the child's FK needs it); adopt that value here so Save persists the row
 * under the very key the child now references instead of allocating a second one.
 */
function onNestedParentRowPatch(patch: Record<string, unknown>) {
  const next = { ...formData.value }
  let changed = false
  for (const [key, value] of Object.entries(patch)) {
    if (key === '__subTables__') continue
    const current = next[key]
    if (current != null && String(current).trim() !== '') continue
    if (value == null || String(value).trim() === '') continue
    next[key] = value
    changed = true
  }
  if (changed) formData.value = next
}

/** Host row's own table joins the ancestor pool so a grandchild FK to it can be auto-filled. */
const nestedParentTablesById = computed(() => {
  const base = { ...(props.hostParentTablesById ?? {}) }
  if (props.hostTableId != null && props.hostFieldDefinitions?.length) {
    base[Number(props.hostTableId)] = { fieldDefinitions: props.hostFieldDefinitions }
  }
  return base
})

// ─── Lookup backfill ────────────────────────────────────────────────────────
const {
  effectiveLookupViewFieldsForDialog,
  effectiveLookupFilterConditions,
  onLookupViewFieldsLoaded,
  onLookupSelect,
  onLookupClear,
  effectiveLookupSelectedRow,
  resetLookupState,
} = useSubTableDialogLookup(formData, toRef(props, 'columns'))

async function onLookupSelectWithEvents(field: string, row: Record<string, any>) {
  await onLookupSelect(field, row)
  onDialogFieldChange(field, formData.value[field])
}

function onLookupClearWithEvents(field: string) {
  onLookupClear(field)
  onDialogFieldChange(field, formData.value[field])
}

// ─── Signature canvas ─────────────────────────────────────────────────────────
const {
  signatureCanvasRefs,
  startSign,
  drawSign,
  endSign,
  clearSignature,
  startSignTouch,
  drawSignTouch,
} = useSubTableDialogSignature(formData)

// ─── Editor (wangeditor) ──────────────────────────────────────────────────────
const {
  editorInstances,
  onEditorCreated,
  onEditorChange,
  destroyEditors,
} = useSubTableDialogEditor(formData)

// ─── User search + department tree ────────────────────────────────────────────
const {
  userSearchOptions,
  userSearchLoading,
  handleUserSearch,
  departmentTreeData,
  departmentLoading,
  fetchDepartmentTree,
} = useSubTableDialogRelations()

// ─── Upload helpers ───────────────────────────────────────────────────────────
const {
  uploadFileLists,
  httpRequest,
  maxFilesOf,
  maxFileSizeMbOf,
  isMultiple,
  resetUploadNames,
  backfillUploadNames,
  handleUploadSuccess,
  handleUploadRemove,
  handleUploadChange,
  handleUploadError,
  handleSizeExceed,
  handleUploadExceed,
} = useSubTableDialogUpload(formData, () => props.columns, t)

const uploadDetailLabels = computed(() => ({
  description: t('upload.fileDescription'),
  callbackUrl: t('upload.callbackUrl'),
  status: t('upload.autoSendToFileNet'),
  completed: t('upload.statusCompleted'),
  saveFailed: t('upload.descriptionSaveFailed'),
}))

const detailsOpen = ref(false)
const detailsFile = ref<UploadDetailFile | null>(null)
const detailsCol = ref<DialogColumn | null>(null)

function openDialogDetails(col: DialogColumn, file: UploadDetailFile) {
  detailsCol.value = col
  detailsFile.value = file
  detailsOpen.value = true
}

function previewDetailsFile(file: UploadDetailFile) {
  if (!detailsCol.value) return
  previewDialogFile(detailsCol.value, file.url)
}

const previewPlaylist = inject(FILE_PREVIEW_PLAYLIST_KEY, null)

function previewDialogFile(col: DialogColumn, url?: string) {
  const links = extractFileLinks(formData.value[col.field])
  const hit = url ? links.find((l) => l.url === url) : links[0]
  if (!hit) return
  openFilePreviewFromList(
    {
      url: hit.url,
      name: hit.name,
      cannotDownload: uploadPropsBlockDownload(col.props),
    },
    previewPlaylist?.collect() ?? [],
  )
}

// ─── BU→Role 级联（MI 子任务「按角色分派」）+ 与 assignee 行级互斥 ──────────────
const {
  buTree,
  buCascaderProps,
  selectedBuId,
  roleOptions,
  roleLoading,
  loadBusinessUnits,
  onBuChange,
  onRoleChange,
  primeFromExistingRow,
} = useSubTableBuRoleCascade(formData, toRef(props, 'assignmentConfig'))

/**
 * The BPMN contract supplies the block's CONTENT (which modes, which fields); the
 * designer's `miAssignment` marker decides whether it renders at all and where.
 * A sub-form that never placed the component shows no block — the design is the
 * only truth, same rule the dialog's column set follows.
 */
const effectiveAssignmentConfig = computed(() =>
  isAssignmentConfigured(props.assignmentConfig) ? props.assignmentConfig : undefined)
const configuredBuField = computed(() => effectiveAssignmentConfig.value?.buField || '')
const configuredRoleField = computed(() => effectiveAssignmentConfig.value?.roleField || '')

const hasConfiguredBuRoleColumns = () =>
  !!configuredBuField.value
  && !!configuredRoleField.value
  && props.columns.some(c => c.field === configuredBuField.value)
  && props.columns.some(c => c.field === configuredRoleField.value)

// 弹窗打开时，仅在 AssignmentConfig 指定 BU/Role 字段后加载目录；编辑态预热已选 BU 的 role。
watch(
  () => props.visible,
  (visible) => {
    if (!visible || !hasConfiguredBuRoleColumns()) return
    if (props.mode === 'edit') {
      primeFromExistingRow()
    } else {
      loadBusinessUnits()
    }
  },
  { immediate: true }
)

// ─── MI 分派方式卡片（个人 / 角色，两张卡片恒显示，按 BPMN 单/双模式锁定切换）──────
const assignModeSwitchable = computed(() =>
  isAssignModeSwitchable(effectiveAssignmentConfig.value))
const lockedMode = computed(() =>
  lockedAssignMode(effectiveAssignmentConfig.value))
/** BPMN configured only one mode — the other card renders but is not selectable. */
function isModeCardDisabled(value: AssignmentMode): boolean {
  // Readonly block → neither card is selectable (see assignmentBlockReadonly).
  if (assignmentBlockReadonly.value) return true
  return !assignModeSwitchable.value && lockedMode.value !== value
}
/**
 * Render the Assignment Mode block for any configured MI sub-table — including
 * single-mode setups, where both cards still show (one locked) framing the
 * assignee (or BU + role) picker the block owns.
 */
const showAssignmentBlock = computed(() =>
  isAssignmentConfigured(effectiveAssignmentConfig.value))
const assignMode = ref<AssignmentMode>('person')

const assignModeOptions = [
  { value: 'person' as const, label: 'subTable.assignByPerson', hint: 'subTable.assignByPersonHint' },
  { value: 'role' as const, label: 'subTable.assignByRole', hint: 'subTable.assignByRoleHint' },
]

function selectAssignMode(mode: AssignmentMode) {
  if (isModeCardDisabled(mode)) return
  if (assignMode.value === mode) return
  assignMode.value = mode
  onAssignModeChange(mode)
}

// 打开/切数据时确定初始卡片：单模式恒定到 BPMN 配置的那一种；双模式时已填 role/bu → role，否则 person。
watch(
  () => [props.visible, props.mode, props.initialData] as const,
  ([visible]) => {
    if (!visible || !effectiveAssignmentConfig.value) return
    if (!assignModeSwitchable.value) {
      assignMode.value = lockedMode.value ?? 'person'
      return
    }
    const d = (props.initialData || {}) as Record<string, any>
    assignMode.value = resolveAssignModeFromRow(d, effectiveAssignmentConfig.value)
  },
  { immediate: true }
)

// radio 切换：清掉另一种方式的值，避免残留导致两种并存。
function onAssignModeChange(mode: string | number | boolean | undefined) {
  const config = effectiveAssignmentConfig.value
  if (!config) return
  // Defense in depth: selectAssignMode already blocks the locked card's click,
  // but this setter must not trust an unexpected call either.
  if ((mode === 'person' || mode === 'role') && isModeCardDisabled(mode)) return
  if (mode === 'person') {
    if (config.buField) formData.value[config.buField] = ''
    if (config.roleField) formData.value[config.roleField] = ''
  } else if (mode === 'role' && config.assigneeField) {
    formData.value[config.assigneeField] = ''
  }
}

// Also drop fields hidden by Form Design component events (api.hidden / api.display).
const visibleColumns = computed(() => {
  const config = effectiveAssignmentConfig.value
  const byAssign = !config
    ? props.columns
    : props.columns.filter(column => !fieldsHiddenByMode(assignMode.value, config).has(column.field))
  return byAssign.filter(c => isDialogFieldVisible(c.field))
})

/**
 * Switching assignment mode swaps which fields exist, and `label-width="auto"` then
 * re-measures against a different set — "Business Unit" is wider than "Assignee", so
 * every other row's input edge jumped ~29px sideways on each toggle.
 *
 * Fix: after each render, take the widest label the form has EVER shown and pin the
 * column to it. Measuring real rendered labels (rather than estimating from character
 * widths) keeps the repo's "labels never wrap" rule intact across fonts and locales,
 * and the high-water mark means the width only ever grows — so toggling modes cannot
 * move anything. See portal-dialog-form-labels.
 */
const stableLabelWidth = ref<string>('auto')

/**
 * Measure every label the dialog can show — including the ones the current mode hides —
 * against a detached span using the real label font. Measuring the rendered labels does
 * not work: with `label-width: auto` Element Plus writes an inline width onto each
 * label, so their scrollWidth reports the constrained value, never the natural one.
 */
function syncStableLabelWidth() {
  const el = formRef.value?.$el as HTMLElement | undefined
  if (!el) return
  const sample = el.querySelector<HTMLElement>('.el-form-item__label')
  const texts = props.columns.map(c => c.label || c.field).filter(Boolean)
  if (texts.length === 0) return

  const ruler = document.createElement('span')
  const font = sample ? getComputedStyle(sample) : null
  ruler.style.cssText =
    `position:absolute;visibility:hidden;white-space:nowrap;left:-9999px;top:-9999px;`
    + `font:${font ? font.font || `${font.fontSize} ${font.fontFamily}` : '14px sans-serif'};`
  document.body.appendChild(ruler)
  let widest = 0
  for (const text of texts) {
    ruler.textContent = text
    widest = Math.max(widest, ruler.getBoundingClientRect().width)
  }
  ruler.remove()
  if (widest <= 0) return

  // Element Plus adds the label's right padding (and the required asterisk gutter)
  // on top of the text itself.
  const pad = sample
    ? Number.parseFloat(getComputedStyle(sample).paddingRight || '0') || 12
    : 12
  stableLabelWidth.value = `${Math.ceil(widest + pad + 8)}px`
}

// A fresh dialog must not inherit the previous row's width.
watch(() => props.visible, (open) => { if (!open) stableLabelWidth.value = 'auto' })

/**
 * Fields the Assignment Mode block owns and renders inside its own box, in the
 * block's reading order (BU before Role, since BU narrows the role list).
 */
const assignmentOwnedFields = computed(() => {
  const config = effectiveAssignmentConfig.value
  if (!config) return [] as string[]
  return fieldsOwnedByMode(assignMode.value, config)
})

/** DW Form Preview parity: group columns under designer elCard titles when present. */
const dialogLayoutGroups = computed(() => {
  const groups = buildDialogLayoutGroups(props.formFields, visibleColumns.value).map(group => ({
    ...group,
    items: groupAssignmentFieldsUnderMarker(group.items, assignmentOwnedFields.value),
  }))
  return groups
})

/**
 * The designer set Readonly on the Assignment Mode block: its pickers already render
 * disabled (dialogFormLayout stamps them), so the mode cards must stop switching too —
 * otherwise the user could still move the row between an assignee and a role pool.
 * Read back off the stamped columns so there is one source of truth for the flag.
 */
const assignmentBlockReadonly = computed(() => {
  const owned = dialogLayoutGroups.value
    .flatMap(group => group.items)
    .filter(item => item.type === 'column' && !!item.assignmentSlot)
  return owned.length > 0
    && owned.every(item => item.type === 'column' && item.column.readonly === true)
})

// ─── Form core (state / rules / formulas / validation / open / save) ───────────
const {
  formRef,
  saving,
  dialogKey,
  formRules,
  isColDisabled,
  columnErrors,
  computedFieldErrors,
  handleClose,
  handleSave,
} = useSubTableDialogForm(props, emit, t, {
  formData,
  resetUploadNames,
  backfillUploadNames,
  resetLookupState,
  destroyEditors,
  fetchDepartmentTree,
  resetDialogEventVisibility,
  bootstrapDialogFormLifecycle: () => bootstrapDialogFormLifecycle(props.formOptions),
  runFormOnReload: () => runFormOnReload(props.formOptions),
  runFormBeforeSubmit: () => runFormBeforeSubmit(props.formOptions),
  runFormOnSubmit: () => runFormOnSubmit(props.formOptions),
  runFormOnReset: () => runFormOnReset(props.formOptions),
  eventRequiredFlags: computed(() => {
    void eventRequiredTick.value
    return eventRequiredState.flags
  }),
  scriptFieldErrors,
  isDialogFieldDisabled,
})

const detailsReadonly = computed(() => !detailsCol.value || isColDisabled(detailsCol.value))

function saveDialogRow() {
  if (!warnIfUploadsBlocking({
    inflight: t('upload.waitUntilComplete'),
    failed: t('upload.fixFailedBeforeSubmit'),
  })) return
  return handleSave()
}

const {
  showMasked,
  textDisplay,
  onTextUpdate,
  onTextFocus,
  onTextBlur,
} = useSubTableDialogSensitiveMask(formData, isColDisabled)

/**
 * One inline error per field, from the two sources that can produce one. Precomputed as a map so
 * the form does not re-derive a message for every column on every render.
 */
const columnErrorMessages = computed<Record<string, string>>(() => {
  const messages: Record<string, string> = {}
  for (const [field, errors] of Object.entries(columnErrors.value)) {
    if (errors.length) messages[field] = errors.join('; ')
  }
  for (const [field, code] of Object.entries(computedFieldErrors.value)) {
    messages[field] = t('computedField.evaluationFailed', { code })
  }
  for (const [field, message] of Object.entries(scriptFieldErrors.value)) {
    messages[field] = message
  }
  return messages
})

// Re-measure whenever the visible field set changes (open, mode switch, row change).
// Declared after formRef / dialogLayoutGroups exist, and flushed post-render so the
// labels being measured are the ones actually on screen.
watch(
  () => [props.visible, dialogLayoutGroups.value] as const,
  () => { void nextTick(syncStableLabelWidth) },
  { flush: 'post', immediate: true },
)
</script>

<style>
@use '@/styles/form-readonly.scss';

/* 遮罩经 Teleport 挂 body。层级不再写死：dialog 打开时按屏幕上最高的浮层算出 z-index
   （见 dialogZIndex），遮罩取它 -1，picker 取它 +40。这里的数值只是兜底，实际由内联
   style / --sub-table-dialog-z 覆盖 —— 因为本弹窗既可能直接开在页面上，也可能开在
   z-index 5000 的 Link Form 浮层里。 */
.sub-table-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  z-index: 2009;
}

/* Scoped popper styles using popper-class (Req 31).
   The z-index is NOT set here: poppers are teleported to body and must clear whichever layer
   their own dialog landed on, which is only known at open time. Each dialog instance injects
   a rule for its own poppers (see publishPopperLayer) — a shared value here would be wrong as
   soon as a nested sub-table dialog opened over its parent. This base value only applies
   before the first open. */
.sub-table-date-popper,
.sub-table-color-popper,
.sub-table-dialog-popper {
  z-index: 2050;
}

/* Adjacent designer cards — same 10px gap as FormRenderer / DW Preview */
.sub-table-dialog-card {
  width: 100%;
  margin-bottom: 10px;
}

.sub-table-dialog-card-title {
  font-weight: 500;
  color: #303133;
}

/* ── Assignment Mode block ────────────────────────────────────────────────────
   Routing a row has two destinations: a named person, or a role pool in a BU.
   The two modes are rendered as selectable cards rather than bare radios, and
   the picker the chosen mode needs sits directly beneath them — so the block
   always shows the consequence of the choice instead of an empty frame.

   The CARD presentation lives in styles/miAssignmentModeCard.scss, loaded once from
   styles/index.scss — NOT imported here (see that file's header for why importing it
   into both a scoped and an unscoped <style> silently breaks this dialog). Only the
   FRAME below is local: __head and the owned fields are siblings here (the shared
   column branches above can't be wrapped), so the frame is split across them — head
   draws top + sides, fields continue the sides, and --last closes the bottom. */

.mi-assignment-block__head {
  margin-top: 4px;
  padding: 12px 14px 4px;
  border: 1px solid #dcdfe6;
  border-bottom: none;
  border-radius: 6px 6px 0 0;
  background: #f7f9fc;
}

.mi-assignment-block__title {
  margin-bottom: 10px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: #8a9099;
}

.mi-assignment-block__modes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

/* Owned fields continue the box: side borders only, no top border. Only the
   --last field closes the bottom — otherwise role mode's two stacked fields
   (BU, then Role) each draw a full border and BU shows a stray line above Role. */
.el-form-item.mi-assignment-block__field {
  margin-bottom: 0;
  padding: 8px 14px 0;
  border: 1px solid #dcdfe6;
  border-top: none;
  border-bottom: none;
  background: #f7f9fc;
}

/* Last owned field closes the box. */
.el-form-item.mi-assignment-block__field--last {
  margin-bottom: 18px;
  padding-bottom: 14px;
  border-bottom: 1px solid #dcdfe6;
  border-radius: 0 0 6px 6px;
}

/* "person" owns one picker, "role" owns two, so toggling modes resized the dialog.
   Reserve the taller branch's height on the block's LAST row only when it is also the
   first — i.e. the single-picker (person) branch — so that branch occupies the same
   height as the two-picker one and nothing below the block moves. */
.mi-assignment-block__head + .el-form-item.mi-assignment-block__field--last {
  min-height: 96px;
  box-sizing: border-box;
}

/* Narrow dialogs (mobile): stack the modes rather than crushing the hint text.
   Host-owned: it targets this dialog's own modes container. The reduced-motion rule for
   the cards themselves lives in the shared stylesheet imported above. */
@media (max-width: 560px) {
  .mi-assignment-block__modes {
    grid-template-columns: minmax(0, 1fr);
  }
}

.signature-canvas {
  width: 100%;
  height: 120px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  cursor: crosshair;
  background: #fff;
}

.sub-table-editor-wrapper {
  border: 1px solid #ccc;
  border-radius: 4px;
  overflow: hidden;
  width: 100%;
}

.lookup-field-wrapper {
  width: 100%;
}

/* ── Read-only display in dialog ────────────────────────────────────── */
.ro-value {
  display: flex;
  align-items: center;
  width: 100%;
  box-sizing: border-box;
  min-height: 32px;
  padding: 0 11px;
  color: var(--el-disabled-text-color, #a8abb2);
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
  background: var(--el-disabled-bg-color, #f5f7fa);
  border-radius: 4px;
  border: 1px solid var(--el-disabled-border-color, #e4e7ed);
  cursor: not-allowed;
  pointer-events: none;
}

.tree-readonly {
  opacity: 0.7;
  pointer-events: none;
  cursor: not-allowed;
}

.color-swatch-ro {
  display: inline-block;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  border: 1px solid #dcdfe6;
  vertical-align: middle;
}

.signature-preview-ro {
  max-width: 200px;
  max-height: 80px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
}

.editor-readonly-ro {
  padding: 8px 12px;
  min-height: 60px;
  max-height: 200px;
  overflow-y: auto;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  line-height: 1.6;
  color: #606266;
}

.upload-download-link {
  color: #409eff;
  text-decoration: none;
  cursor: pointer;
}
.upload-download-link:hover {
  text-decoration: underline;
}
.upload-filename-tag {
  cursor: pointer;
}
.dialog-nested-sub-table {
  margin-top: 8px;
  margin-bottom: 8px;
}
</style>
