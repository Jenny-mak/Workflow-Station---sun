<script setup lang="ts">
import MainTableViewFilterEditor from './MainTableViewFilterEditor.vue'
import DesignerHelpLink from '@/components/designer/DesignerHelpLink.vue'
import type { MainTableViewDefinition } from '@/api/mainTableView'
import { useMainTableViewDesigner } from '@/composables/mainTableView/useMainTableViewDesigner'

const props = defineProps<{ functionUnitId: number; view: MainTableViewDefinition }>()
const emit = defineEmits<{
  saved: [view: MainTableViewDefinition]
  'navigate-to-table-view': [refTableId: number]
}>()

const {
  t, Search, Close, Menu, DArrowRight, DArrowLeft, Plus, Filter, CaretTop, CaretBottom, Connection, Key,
  columnsPanelOpen, propsPanelOpen, fieldSearchKeyword, saving, viewName, viewFields, sortConfig, filterConfig,
  enableExport, restrictToInvolvedUsers, detailFormId, detailFormOptions, selectedBusinessUnitIds, selectedRoleIds,
  businessUnitOptions, roleOptions, accessOptionsLoading,
  mainTableName, isMainTableView, filterDialogVisible, addColumnPopoverVisible, thenSortField,
  dragOverIndex, dragSourceField, visibleColumns, displayFilterConditions,
  sortFieldOptions, filteredCatalog, filteredLookupCatalog, filteredLookupCatalogGroups,
  filteredFkCatalog, filteredFkCatalogGroups, previewRowCount,
  fieldLabel, getFieldIcon, getMockValue, sortIndicator,
  formatFilterTag, addField, removeField, toggleSortDirection, sortDirectionTooltip, onFilterEditorSave,
  removeDisplayFilterTag, addSortField, removeSort, handleSave, onFieldDragStart, onFieldDragEnd, onGridDrop,
  onColDragStart, onColDragOver, onColDragLeave, onColDrop, onColDragEnd,
  isFkField, isPkField, onFkColumnClick, isLookupDisplayField, isFkDisplayField,
  selectedCatalogFields, toggleCatalogSelect, addSelectedFields, clearAllFields,
  allCatalogSelected, someCatalogSelected, toggleSelectAllCatalog,
  selectedLookupCatalogFields, toggleLookupCatalogSelect, addSelectedLookupFields,
  allLookupCatalogSelected, someLookupCatalogSelected, toggleSelectAllLookupCatalog,
  selectedFkCatalogFields, toggleFkCatalogSelect, addSelectedFkFields,
  allFkCatalogSelected, someFkCatalogSelected, toggleSelectAllFkCatalog,
} = useMainTableViewDesigner(props, emit)
</script>



<template>
<div class="main-table-view-designer">
    <div class="designer-toolbar">
      <div class="toolbar-right">
        <el-button
          type="primary"
          size="small"
          :loading="saving"
          @click="handleSave"
        >
          {{ t('common.save') }}
        </el-button>
      </div>
    </div>



    <div class="designer-body">
      <!-- Left: Table columns -->
      <div
        v-if="columnsPanelOpen"
        class="columns-panel"
      >
        <div class="columns-panel-header">
          <div class="columns-panel-title">
            <el-icon><Menu /></el-icon>
            <span>{{ t('mainTableView.tableColumns') }}</span>
          </div>
          <el-icon
            class="close-btn"
            @click="columnsPanelOpen = false"
          >
            <Close />
          </el-icon>
        </div>
        <div
          v-if="mainTableName"
          class="columns-panel-table-name"
        >
          {{ mainTableName }}
        </div>
        <div class="columns-panel-search">
          <el-input
            v-model="fieldSearchKeyword"
            :placeholder="t('common.search')"
            clearable
            size="small"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
</template>

          </el-input>

        </div>

        <div
          v-if="filteredCatalog.length"
          class="columns-select-all"
        >
          <el-checkbox
            :model-value="allCatalogSelected"
            :indeterminate="someCatalogSelected && !allCatalogSelected"
            @change="toggleSelectAllCatalog($event === true)"
          >
            {{ t('mainTableView.selectAllColumns') }}
          </el-checkbox>
        </div>

        <div class="columns-field-list">

          <div

            v-for="field in filteredCatalog"

            :key="field.fieldName"

            class="field-item"

            :class="{ dragging: dragSourceField === field.fieldName, selected: selectedCatalogFields.has(field.fieldName) }"

            draggable="true"

            @dragstart="onFieldDragStart($event, field)"

            @dragend="onFieldDragEnd"

            @click="toggleCatalogSelect(field.fieldName)"

          >

            <el-checkbox
              :model-value="selectedCatalogFields.has(field.fieldName)"
              @click.stop
              @change="toggleCatalogSelect(field.fieldName)"
            />

            <el-icon class="field-icon">

              <component :is="getFieldIcon(field.dataType)" />

            </el-icon>

            <span class="field-name">{{ field.displayName || field.fieldName }}</span>

          </div>

          <el-empty

            v-if="!filteredCatalog.length"

            :description="t('mainTableView.noAvailableFields')"

            :image-size="40"

          />

        </div>

        <div
          v-if="filteredLookupCatalogGroups.length"
          class="columns-panel-lookup"
        >
          <div class="columns-panel-subtitle">
            {{ t('mainTableView.lookupColumns') }}
          </div>
          <div
            v-if="filteredLookupCatalog.length"
            class="columns-select-all"
          >
            <el-checkbox
              :model-value="allLookupCatalogSelected"
              :indeterminate="someLookupCatalogSelected && !allLookupCatalogSelected"
              @change="toggleSelectAllLookupCatalog($event === true)"
            >
              {{ t('mainTableView.selectAllLookupColumns') }}
            </el-checkbox>
          </div>
          <div
            v-for="group in filteredLookupCatalogGroups"
            :key="group.sourceField"
            class="lookup-group"
          >
            <div class="lookup-group-title">
              {{ group.sourceLabel }}
              <span class="lookup-group-table">({{ group.tableName }})</span>
            </div>
            <div
              v-for="field in group.fields"
              :key="field.fieldName"
              class="field-item"
              :class="{ selected: selectedLookupCatalogFields.has(field.fieldName) }"
              @click="toggleLookupCatalogSelect(field.fieldName)"
            >
              <el-checkbox
                :model-value="selectedLookupCatalogFields.has(field.fieldName)"
                @click.stop
                @change="toggleLookupCatalogSelect(field.fieldName)"
              />
              <el-icon class="field-icon">
                <Connection />
              </el-icon>
              <span class="field-name">{{ field.lookupDisplayField || field.fieldName }}</span>
            </div>
          </div>
        </div>

        <div
          v-if="filteredFkCatalogGroups.length"
          class="columns-panel-lookup"
        >
          <div class="columns-panel-subtitle">
            {{ t('mainTableView.relatedColumns') }}
          </div>
          <div
            v-if="filteredFkCatalog.length"
            class="columns-select-all"
          >
            <el-checkbox
              :model-value="allFkCatalogSelected"
              :indeterminate="someFkCatalogSelected && !allFkCatalogSelected"
              @change="toggleSelectAllFkCatalog($event === true)"
            >
              {{ t('mainTableView.selectAllRelatedColumns') }}
            </el-checkbox>
          </div>
          <div
            v-for="group in filteredFkCatalogGroups"
            :key="'fk-' + group.sourceField"
            class="lookup-group"
          >
            <div class="lookup-group-title">
              {{ group.sourceLabel }}
              <span class="lookup-group-table">({{ group.tableName }})</span>
            </div>
            <div
              v-for="field in group.fields"
              :key="field.fieldName"
              class="field-item"
              :class="{ selected: selectedFkCatalogFields.has(field.fieldName) }"
              @click="toggleFkCatalogSelect(field.fieldName)"
            >
              <el-checkbox
                :model-value="selectedFkCatalogFields.has(field.fieldName)"
                @click.stop
                @change="toggleFkCatalogSelect(field.fieldName)"
              />
              <el-icon class="field-icon">
                <Connection />
              </el-icon>
              <span class="field-name">{{ field.lookupDisplayField || field.fieldName }}</span>
            </div>
          </div>
        </div>

        <div class="columns-panel-actions">
          <el-button
            type="primary"
            size="small"
            :disabled="!selectedCatalogFields.size && !selectedLookupCatalogFields.size && !selectedFkCatalogFields.size"
            @click="() => { addSelectedFields(); addSelectedLookupFields(); addSelectedFkFields() }"
          >
            {{ t('mainTableView.addSelectedColumns') }}
            <template v-if="selectedCatalogFields.size + selectedLookupCatalogFields.size + selectedFkCatalogFields.size">
              ({{ selectedCatalogFields.size + selectedLookupCatalogFields.size + selectedFkCatalogFields.size }})
            </template>
          </el-button>
          <el-button
            size="small"
            :disabled="!visibleColumns.length"
            @click="clearAllFields"
          >
            {{ t('mainTableView.clearAllColumns') }}
          </el-button>
        </div>

      </div>

      <div

        v-else

        class="columns-toggle"

        @click="columnsPanelOpen = true"

      >

        <el-icon><DArrowRight /></el-icon>

      </div>



      <!-- Center: Live preview grid -->

      <div

        class="preview-panel"

        @dragover.prevent

        @drop="onGridDrop"

      >

        <div

          v-if="visibleColumns.length"

          class="preview-grid"

        >

          <div class="column-headers">

            <div

              v-for="(field, index) in visibleColumns"

              :key="field.fieldName"

              class="column-header"

              :class="{ 'drag-over': dragOverIndex === index }"

              :style="{ flex: field.columnWidth ? `0 0 ${field.columnWidth}px` : undefined }"

              draggable="true"

              @dragstart="onColDragStart($event, index)"

              @dragover.prevent="onColDragOver($event, index)"

              @dragleave="onColDragLeave"

              @drop.stop="onColDrop($event, index)"

              @dragend="onColDragEnd"

            >

              <el-icon
                v-if="isPkField(field.fieldName)"
                class="col-key-icon"
                :title="t('mainTableView.primaryKeyField')"
              ><Key /></el-icon>

              <span
                v-if="isFkField(field.fieldName)"
                class="col-name col-fk-link"
                :title="t('mainTableView.openRelatedView')"
                @click.stop="onFkColumnClick(field.fieldName)"
              >
                {{ field.displayLabel || field.fieldName }}
                <el-icon class="col-fk-icon"><Connection /></el-icon>
              </span>
              <span
                v-else
                class="col-name"
              >{{ field.displayLabel || field.fieldName }}</span>

              <span class="col-sort-icons">

                <el-icon

                  v-if="sortIndicator(field.fieldName) === 'ASC'"

                  class="sort-icon active"

                ><CaretTop /></el-icon>

                <el-icon

                  v-else-if="sortIndicator(field.fieldName) === 'DESC'"

                  class="sort-icon active"

                ><CaretBottom /></el-icon>

              </span>

              <el-icon

                class="col-remove"

                @click.stop="removeField(viewFields.findIndex(f => f.fieldName === field.fieldName))"

              >

                <Close />

              </el-icon>

            </div>

            <el-popover

              v-model:visible="addColumnPopoverVisible"

              placement="bottom"

              :width="220"

              trigger="click"

            >

              <template #reference>

                <div class="add-column-header">

                  <el-icon><Plus /></el-icon>

                  <span>{{ t('mainTableView.addViewColumn') }}</span>

                </div>

              </template>

              <div class="add-column-popover">

                <div

                  v-for="field in filteredCatalog"

                  :key="'add-' + field.fieldName"

                  class="field-item compact"

                  @click="addField(field)"

                >

                  {{ field.displayName || field.fieldName }}

                </div>

                <template v-if="filteredLookupCatalog.length">
                  <div class="add-column-popover-subtitle">
                    {{ t('mainTableView.lookupColumns') }}
                  </div>
                  <div
                    v-for="field in filteredLookupCatalog"
                    :key="'add-lookup-' + field.fieldName"
                    class="field-item compact"
                    @click="addField(field)"
                  >
                    {{ field.displayName || field.fieldName }}
                  </div>
                </template>

                <template v-if="filteredFkCatalog.length">
                  <div class="add-column-popover-subtitle">
                    {{ t('mainTableView.relatedColumns') }}
                  </div>
                  <div
                    v-for="field in filteredFkCatalog"
                    :key="'add-fk-' + field.fieldName"
                    class="field-item compact"
                    @click="addField(field)"
                  >
                    {{ field.displayName || field.fieldName }}
                  </div>
                </template>

                <el-empty

                  v-if="!filteredCatalog.length && !filteredLookupCatalog.length && !filteredFkCatalog.length"

                  :description="t('mainTableView.noAvailableFields')"

                  :image-size="32"

                />

              </div>

            </el-popover>

          </div>

          <div

            v-for="rowIdx in previewRowCount"

            :key="'row-' + rowIdx"

            class="data-row"

          >

            <div

              v-for="field in visibleColumns"

              :key="field.fieldName + '-' + rowIdx"

              class="data-cell"

              :style="{ flex: field.columnWidth ? `0 0 ${field.columnWidth}px` : undefined }"

            >

              <a
                v-if="isFkField(field.fieldName)"
                class="cell-fk-link"
                :title="t('mainTableView.openRelatedView')"
                @click="onFkColumnClick(field.fieldName)"
              >{{ getMockValue(field, rowIdx - 1) }}</a>
              <template v-else>{{ getMockValue(field, rowIdx - 1) }}</template>

            </div>

            <div class="add-column-spacer" />

          </div>

        </div>

        <el-empty

          v-else

          class="preview-empty"

          :description="t('mainTableView.noColumns')"

          :image-size="64"

        />

      </div>



      <!-- Right: collapsed strip to re-open the properties panel -->
      <div
        v-if="!propsPanelOpen"
        class="props-toggle"
        :title="t('mainTableView.expandProps')"
        @click="propsPanelOpen = true"
      >
        <el-icon><DArrowLeft /></el-icon>
      </div>

      <!-- Right: View properties -->

      <div
        v-if="propsPanelOpen"
        class="properties-panel"
      >

        <div class="properties-header">

          <div class="properties-title-row">
            <div class="properties-title">
              {{ viewName || props.view.viewName }}
            </div>
            <el-icon
              class="props-collapse-btn"
              :title="t('mainTableView.collapseProps')"
              @click="propsPanelOpen = false"
            >
              <DArrowRight />
            </el-icon>
          </div>

          <div class="properties-subtitle">

            {{ t('mainTableView.entityTypeView') }}

          </div>

        </div>



        <div class="properties-section">

          <label class="section-label">{{ t('mainTableView.viewName') }}</label>

          <el-input

            v-model="viewName"

            size="small"

          />

        </div>



        <div class="properties-section">

          <label class="section-label">{{ t('mainTableView.portalToolbar') }}</label>

          <div class="toolbar-toggles">

            <el-checkbox v-model="enableExport">

              {{ t('mainTableView.enableExport') }}

            </el-checkbox>

          </div>

        </div>



        <div class="properties-section">

          <label class="section-label">{{ t('mainTableView.detailForm') }}</label>

          <!-- A MAIN-table row is a request, so it opens the request detail page directly
               instead of a designed form. No form to pick here. -->
          <div
            v-if="isMainTableView"
            class="detail-form-hint"
          >
            {{ t('mainTableView.detailFormMainTableHint') }}
          </div>

          <el-select
            v-else
            v-model="detailFormId"
            clearable
            filterable
            :placeholder="t('mainTableView.detailFormNone')"
            style="width: 100%;"
          >
            <el-option
              v-for="f in detailFormOptions"
              :key="f.id"
              :label="f.formName"
              :value="f.id"
            />
          </el-select>

        </div>



        <div class="properties-section access-control-section">

          <div class="section-label-row">
            <label class="section-label">{{ t('mainTableView.accessControl') }}</label>
            <DesignerHelpLink
              path="/view-design#access"
              :aria-label="t('mainTableView.guideLinkAria')"
              test-id="view-access-guide-link"
            />
          </div>

          <p class="access-hint">{{ t('mainTableView.accessControlHint') }}</p>

          <div class="access-field">

            <label class="access-field-label">{{ t('mainTableView.businessUnits') }}</label>

            <el-select
              v-model="selectedBusinessUnitIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              size="small"
              class="access-select"
              :loading="accessOptionsLoading"
              :placeholder="t('mainTableView.businessUnitsPlaceholder')"
            >

              <el-option
                v-for="bu in businessUnitOptions"
                :key="bu.id"
                :label="bu.name"
                :value="bu.id"
              />

            </el-select>

          </div>

          <div class="access-field">

            <label class="access-field-label">{{ t('mainTableView.roles') }}</label>

            <el-select
              v-model="selectedRoleIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              size="small"
              class="access-select"
              :loading="accessOptionsLoading"
              :disabled="selectedBusinessUnitIds.length === 0"
              :placeholder="selectedBusinessUnitIds.length === 0
                ? t('mainTableView.rolesSelectBuFirst')
                : t('mainTableView.rolesPlaceholder')"
            >

              <el-option
                v-for="role in roleOptions"
                :key="role.id"
                :label="role.name"
                :value="role.id"
              />

            </el-select>

          </div>

          <div class="access-switch-row">

            <span class="access-switch-label">{{ t('mainTableView.restrictToInvolvedUsers') }}</span>

            <el-switch v-model="restrictToInvolvedUsers" size="small" />

          </div>

        </div>



        <div class="properties-section sort-by-section">

          <label class="section-label">{{ t('mainTableView.sortBy') }}</label>

          <div
            v-if="sortConfig.length"
            class="sort-by-list"
          >
            <div
              v-for="(sort, idx) in sortConfig"
              :key="'sort-' + idx"
              class="sort-pill"
            >
              <el-tooltip
                :content="sortDirectionTooltip(sort)"
                placement="bottom"
                :show-after="300"
              >
                <button
                  type="button"
                  class="sort-direction-btn"
                  :aria-label="sortDirectionTooltip(sort)"
                  @click="toggleSortDirection(sort)"
                >
                  <el-icon :size="14">
                    <CaretTop v-if="sort.direction === 'ASC'" />
                    <CaretBottom v-else />
                  </el-icon>
                </button>
              </el-tooltip>
              <span class="sort-pill-label">{{ fieldLabel(sort.fieldName) }}</span>
              <button
                type="button"
                class="sort-pill-remove"
                :aria-label="t('common.delete')"
                @click="removeSort(idx)"
              >
                <el-icon :size="12"><Close /></el-icon>
              </button>
            </div>
          </div>

          <el-select
            v-if="sortFieldOptions.some(o => !sortConfig.some(s => s.fieldName === o.fieldName))"
            v-model="thenSortField"
            size="small"
            class="then-sort-select"
            :placeholder="sortConfig.length ? t('mainTableView.thenSortBy') : t('mainTableView.sortBy')"
            clearable
            @change="(val: string) => { if (val) addSortField(val) }"
          >
            <el-option
              v-for="opt in sortFieldOptions.filter(o => !sortConfig.some(s => s.fieldName === o.fieldName))"
              :key="opt.fieldName"
              :label="opt.label"
              :value="opt.fieldName"
            />
          </el-select>

        </div>



        <div class="properties-section">

          <label class="section-label">{{ t('mainTableView.filters') }}</label>

          <div

            v-if="displayFilterConditions.length"

            class="tag-list"

          >

            <el-tag

              v-for="(cond, idx) in displayFilterConditions"

              :key="'filter-' + idx"

              closable

              size="default"

              class="config-tag"

              @close="removeDisplayFilterTag(idx)"

            >

              {{ formatFilterTag(cond) }}

            </el-tag>

          </div>

          <el-button

            link

            type="primary"

            class="edit-filters-btn"

            @click="filterDialogVisible = true"

          >

            <el-icon><Filter /></el-icon>

            {{ t('mainTableView.editFilters') }}

          </el-button>

        </div>



        <div class="properties-section">

          <label class="section-label">{{ t('mainTableView.columnSettings') }}</label>

          <div

            v-for="field in viewFields"

            :key="'col-set-' + field.fieldName"

            class="column-setting-row"

          >

            <el-checkbox
              :model-value="field.visible !== false"
              @update:model-value="field.visible = $event === true"
            />

            <el-input

              v-model="field.displayLabel"

              size="small"

              class="col-label-input"

            />

            <el-tag

              v-if="field.systemField"

              size="small"

              type="info"

            >

              {{ t('mainTableView.systemField') }}

            </el-tag>

            <el-tag

              v-else-if="isLookupDisplayField(field)"

              size="small"

              type="warning"

              :title="t('mainTableView.lookupDerivedFieldHint', {
                source: field.lookupSourceField,
                attr: field.lookupDisplayField,
              })"

            >

              {{ t('mainTableView.lookupDerivedField') }}

            </el-tag>

            <el-tag

              v-else-if="isFkDisplayField(field)"

              size="small"

              type="success"

              :title="t('mainTableView.relatedDerivedFieldHint', {
                source: field.lookupSourceField,
                attr: field.lookupDisplayField,
              })"

            >

              {{ t('mainTableView.relatedDerivedField') }}

            </el-tag>

          </div>

        </div>

      </div>

    </div>



    <MainTableViewFilterEditor
      v-model="filterDialogVisible"
      :filter-config="filterConfig"
      :field-options="sortFieldOptions"
      @save="onFilterEditorSave"
    />
  </div>
</template>



<style scoped lang="scss">
@import "./MainTableViewDesigner.scss";
</style>

