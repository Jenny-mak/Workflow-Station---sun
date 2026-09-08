<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import PortalFormFields, { type PortalSubTableBindingLite } from './PortalFormFields.vue'
import { flattenLeafFormFields, type FormField } from './formRendererHelpers'
import type { BindingFieldDefinition } from '@/utils/subTableRowRuntime'
import type { AssignmentConfig } from '@/utils/miAssignmentConfig'
import {
  useSubTableDialogComponentEvents,
  type DialogColumnWithEvents,
} from '@/composables/subTableAddDialog/useSubTableDialogComponentEvents'
import { isEffectivelyRequired } from '@/utils/formCreateEventRuntime'
import { PLATFORM_ROW_UUID_FIELD } from '@/utils/subTableRowIdentity'

/**
 * Sub-table form rendered inline: the bound sub-table's designed form, laid out
 * in place (the `inlineSubForm` widget). Nested subTable widgets use
 * {@link PortalFormFields} so structure matches Developer Workstation preview.
 *
 * Form Design Events reuse the Add/Edit dialog runtime (same subset: Form
 * onCreated/onMounted/onChange/onReload/beforeSubmit/onSubmit/onReset and
 * field change/blur + hook load/mounted/value).
 */

interface Props {
  title?: string
  fields: FormField[]
  currentRow?: Record<string, unknown> | null
  readonly?: boolean
  labelWidth?: string
  /**
   * Left-aligns by default to match the host FormRenderer's own default (`FormRenderer.vue`'s
   * `labelPosition` prop defaults to `'left'`). Element Plus's own default is `'right'` — left
   * unset here, this form's labels visually diverged from the rest of the page (each label's
   * right edge flush against its input, but no consistent left edge — read as "not aligned").
   */
  labelPosition?: 'left' | 'right' | 'top'
  subTableBindings?: PortalSubTableBindingLite[]
  linkedSubTableBindings?: PortalSubTableBindingLite[]
  suppressLinkOnlyStandaloneSubTables?: boolean
  /** FK/PK runtime context of the sub-table row this form edits — needed by nested sub-tables. */
  hostTableId?: number | null
  hostFieldDefinitions?: BindingFieldDefinition[]
  hostFunctionUnitId?: string
  hostTaskId?: string
  hostPrimaryFormData?: Record<string, unknown>
  hostPrimaryTableId?: number | null
  /** Inline Form widget: rows persist with the host form, so it owns no Save button. */
  hideSaveButton?: boolean
  /** Inline Form widget: renders flush in the host layout, without the el-card chrome. */
  bordered?: boolean
  /**
   * Inline Form widget: draw a labelled frame around the block. Its fields come from a
   * DIFFERENT table than the host form, and without a boundary they read as ordinary host
   * fields — the author cannot tell which rows belong to the embedded sub-table.
   */
  framed?: boolean
  /**
   * Cycle-guard ancestry threaded through from the caller — passed straight to the inner
   * PortalFormFields so a nested inlineSubForm field (if `fields` itself contains one) resolves
   * its own fields with the full ancestor chain, not just its immediate parent.
   */
  visitedInlineSubFormBindingIds?: ReadonlySet<number>
  /**
   * Task-node field permissions, passed straight to the inner PortalFormFields so nested
   * inlineSubForm fields (if `fields` itself contains one) get the same READONLY enforcement
   * as this form's own top-level fields already do via SubTableField's Add/Edit dialog.
   */
  fieldPermissions?: Record<string, string> | null
  /** Sub-form Form Design options — Form-level onCreated / onMounted / onChange. */
  formOptions?: Record<string, unknown> | null
  /** Canvas columns from the source binding — sourceRule fallback when FormField has none. */
  dialogColumns?: DialogColumnWithEvents[] | Array<Record<string, unknown>> | null
  /**
   * 该子表在设计器里配置的主键（`dw_field_definitions`）。用于行身份判定 —— 主键不叫
   * `id_idw` 的表（实测 ATM_Transaction 是 `row_id`、subtable 是 `id_idwnn`）不能靠猜列名。
   */
  primaryKeyFields?: string[] | null
  /**
   * BPMN-derived MI assignment contract for the bound sub-table — passed straight to the inner
   * PortalFormFields so an Assignment Mode block placed in this sub-form renders here exactly
   * as it does in the grid's Add/Edit dialog. Absent means no Assignment Mode behavior.
   */
  assignmentConfig?: AssignmentConfig
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  readonly: false,
  labelWidth: '160px',
  labelPosition: 'left',
  suppressLinkOnlyStandaloneSubTables: false,
  hideSaveButton: false,
  bordered: true,
  framed: false,
})

const emit = defineEmits<{
  (e: 'update:row', row: Record<string, unknown>): void
  (e: 'change', key: string, value: unknown): void
  (e: 'save'): void
  /** 透传内层 PortalFormFields 的子表行变化，让宿主同步 `binding.data`（见该 emit 的说明）。 */
  (e: 'update:sub-table-data', bindingId: number, rows: unknown[]): void
}>()

const { t } = useI18n()

const rowModel = ref<Record<string, unknown>>({})

/**
 * 行身份候选键：配置主键与平台标识优先，其后保留 `row_id` / `sub_task_id` / `id` / `id_idw`
 * 作为**本地**兜底。
 *
 * <p><b>为什么这里的兜底不能删（与 `subTableRowMerge` 不同）。</b>平台行标识目前只在
 * **提交时**盖（`useTaskActions` → `ensureSubTableMapIdentities`），加载和新增行时都不盖；
 * 而本组件的两个生产调用点（`FormRendererFields.vue` 的两处 `<SubTableInlineForm>`）
 * 根本没传 `primary-key-fields`。也就是说一行未保存的新行在这里**既无配置主键、也无平台标识**，
 * 删掉兜底会让任意两行的身份字符串同为 `''`，watch 源不变 → 切换行时上一行的可见性与
 * 正在编辑的值原样残留。
 *
 * <p>这里的身份只用于「要不要重新 bootstrap 表单」这一个本地判断，判错的后果是多重置或少重置
 * 一次表单，**不会跨行合并或覆盖数据**——与合并链路上按名字猜主键的风险不是一回事。
 * 真正的修法是让平台标识在加载/新增时就盖上，并把 `primaryKeyFields` 透传给这两个调用点，
 * 那之后这里的兜底才可以安全删除。
 */
const inlineRowIdentityKeys = computed<string[]>(() => {
  const pk = (props.primaryKeyFields ?? []).map(f => String(f ?? '').trim()).filter(Boolean)
  return [...new Set([...pk, PLATFORM_ROW_UUID_FIELD, 'sub_task_id'])]
})

function identityKeyValue(row: Record<string, unknown>, key: string): string | null {
  const v = row[key]
  if (v == null || String(v).trim() === '') return null
  return String(v)
}

/**
 * Stable row id for bootstrap. Prefer business keys (`row_id` / `sub_task_id`)
 * before allocated `id` / `id_idw` — otherwise PK allocation changes the identity
 * string, rebootstrap copies the parent snapshot, and in-progress Y/N is dropped.
 */
function inlineFormRowIdentity(row: Record<string, unknown> | null | undefined): string {
  if (!row) return ''
  for (const k of inlineRowIdentityKeys.value) {
    const v = identityKeyValue(row, k)
    if (v != null) return `${k}:${v}`
  }
  return ''
}

/**
 * Same selected row after parent write-back (PK appeared, or only one identity
 * key is present on each side). A real row switch has at least one shared key
 * with a different value.
 */
function inlineFormIsSameLogicalRow(
  prev: Record<string, unknown> | null,
  next: Record<string, unknown> | null,
): boolean {
  if (!prev || !next) return false
  let compared = false
  for (const k of inlineRowIdentityKeys.value) {
    const a = identityKeyValue(prev, k)
    const b = identityKeyValue(next, k)
    if (a == null || b == null) continue
    compared = true
    if (a !== b) return false
  }
  if (compared) return true
  const prevHadId = inlineRowIdentityKeys.value.some(k => identityKeyValue(prev, k) != null)
  const nextHadId = inlineRowIdentityKeys.value.some(k => identityKeyValue(next, k) != null)
  return !prevHadId && nextHadId
}

function formOptionsEventFingerprint(options: Record<string, unknown> | null | undefined): string {
  if (!options || typeof options !== 'object') return ''
  return ['onChange', 'onMounted', 'onCreated', 'onReload', 'beforeSubmit']
    .map((k) => String(options[k] ?? ''))
    .join('\0')
}

function eventColumnsFromInlineForm(): DialogColumnWithEvents[] {
  const dialogByField = new Map<string, DialogColumnWithEvents>()
  for (const col of props.dialogColumns ?? []) {
    if (!col || typeof col !== 'object') continue
    const field = String((col as DialogColumnWithEvents).field ?? '').trim()
    if (!field) continue
    dialogByField.set(field, col as DialogColumnWithEvents)
  }
  return flattenLeafFormFields(props.fields)
    .filter(f => typeof f.key === 'string' && f.key.length > 0 && !f.key.startsWith('__'))
    .map((f) => {
      const d = dialogByField.get(f.key)
      return {
        field: f.key,
        label: f.label,
        type: f.type,
        hidden: f.hidden === true || d?.hidden === true,
        sourceRule: f.sourceRule ?? d?.sourceRule,
      }
    })
}

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
} = useSubTableDialogComponentEvents(
  rowModel,
  eventColumnsFromInlineForm,
  () => props.formOptions,
)

/**
 * Bind rowModel + Event bootstrap only when the selected row changes.
 * `getCurrentRowForInlineForm` returns a new object every parent render — copying
 * that snapshot back (and emitting it) was overwriting in-progress Y/N edits and
 * triggering sub-table autosave of the page-load value, so Save/reload showed N again.
 * Dialog Event runtime also keeps bootstrap mutations local until the user confirms.
 *
 * Identity string can still change on the same row (`''` → `id:…` after PK
 * allocation). Rebootstrap would run onChange('__bootstrap__'), which field-gated
 * scripts skip, wiping api.hidden from the click that just allocated the PK.
 */
let lastBoundRow: Record<string, unknown> | null = null

/**
 * 触发源必须是身份**字符串**而不是 `currentRow` 引用：`resolveInlineSubFormRow` 每次都返回
 * `{ ...target }`（新对象），watch 引用会在父组件每次重渲染时触发，把用户正在编辑的值冲掉
 * ——「同一行被父级用陈旧快照重渲染」正是下面几个测试锁定的不变量。
 */
watch(
  () => inlineFormRowIdentity(props.currentRow),
  (_nextId, prevId) => {
    const r = props.currentRow
    if (r && lastBoundRow && inlineFormIsSameLogicalRow(lastBoundRow, r)) {
      lastBoundRow = { ...r }
      return
    }
    lastBoundRow = r != null && typeof r === 'object' ? { ...r } : null
    rowModel.value = lastBoundRow ? { ...lastBoundRow } : {}
    resetDialogEventVisibility()
    if (!r) {
      if (prevId) runFormOnReset()
      return
    }
    bootstrapDialogFormLifecycle()
    if (prevId) runFormOnReload()
  },
  { immediate: true },
)

watch(
  () => formOptionsEventFingerprint(props.formOptions),
  (next, prev) => {
    if (!next || next === prev) return
    if (!inlineFormRowIdentity(props.currentRow) && !props.currentRow) return
    resetDialogEventVisibility()
    if (props.currentRow) bootstrapDialogFormLifecycle()
  },
)

function handleFieldUpdate(key: string, value: unknown) {
  onDialogFieldChange(key, value)
  emit('update:row', { ...rowModel.value })
  emit('change', key, value)
}

function handleFieldBlur(key: string) {
  onDialogFieldBlur(key)
  emit('update:row', { ...rowModel.value })
}

function isInlineFieldRequired(field: FormField): boolean {
  void eventRequiredTick.value
  return isEffectivelyRequired(field.key, field.required === true, eventRequiredState.flags)
}

/** Flush row model into bindings before persist so Save allocates PK on the latest inline edits. */
function handleSaveClick() {
  if (!runFormBeforeSubmit()) return
  const merged = { ...rowModel.value }
  rowModel.value = merged
  emit('update:row', merged)
  runFormOnSubmit()
  emit('save')
}

const cardTitle = computed(() =>
  props.title?.trim() ? props.title : t('subTable.formBelowTableTitle'),
)
</script>

<template>
  <component
    :is="bordered ? 'el-card' : 'div'"
    v-bind="bordered ? { shadow: 'never' } : {}"
    class="sub-table-inline-form"
    :class="{ 'is-borderless': !bordered, 'is-framed': framed }"
  >
    <template
      v-if="bordered"
      #header
    >
      <span class="title">{{ cardTitle }}</span>
    </template>
    <!--
      Framed mode marks where the embedded sub-table's fields start and end: they belong to a
      different table than the host form, so without this boundary they read as host fields.
    -->
    <div
      v-if="framed"
      class="inline-form-frame-header"
    >
      <el-icon class="inline-form-frame-icon"><Document /></el-icon>
      <span class="inline-form-frame-title">{{ cardTitle }}</span>
    </div>
    <el-form
      :model="rowModel"
      :label-width="labelWidth"
      :label-position="labelPosition"
      :disabled="readonly"
    >
      <el-row :gutter="20">
        <PortalFormFields
          :fields="fields"
          :model="rowModel"
          :readonly="readonly"
          :editable="!readonly"
          :sub-table-bindings="subTableBindings"
          :linked-sub-table-bindings="linkedSubTableBindings"
          :parent-row="currentRow"
          :suppress-link-only-standalone-sub-tables="suppressLinkOnlyStandaloneSubTables"
          :host-table-id="hostTableId ?? null"
          :host-field-definitions="hostFieldDefinitions"
          :host-function-unit-id="hostFunctionUnitId"
          :host-task-id="hostTaskId"
          :host-primary-form-data="hostPrimaryFormData"
          :host-primary-table-id="hostPrimaryTableId ?? null"
          :visited-inline-sub-form-binding-ids="visitedInlineSubFormBindingIds"
          :field-permissions="fieldPermissions"
          :is-field-visible="isDialogFieldVisible"
          :is-field-required="isInlineFieldRequired"
          :assignment-config="assignmentConfig"
          @update:field="handleFieldUpdate"
          @update:sub-table-data="(bid: number, rows: unknown[]) => emit('update:sub-table-data', bid, rows)"
          @field-blur="handleFieldBlur"
        />
      </el-row>
      <el-empty
        v-if="fields.length === 0"
        :description="t('subTable.formBelowTableEmpty')"
      />
      <div
        v-if="!readonly && fields.length > 0 && !hideSaveButton"
        class="inline-form-actions"
      >
        <el-button
          type="primary"
          @click="handleSaveClick"
        >
          {{ t('common.save') }}
        </el-button>
      </div>
    </el-form>
  </component>
</template>

<style scoped>
.sub-table-inline-form {
  margin-bottom: 16px;
}

/* Inline Form widget: sits flush in the host layout, no card frame or padding of its own. */
.sub-table-inline-form.is-borderless {
  margin-bottom: 0;
}

/*
 * Framed variant: a labelled boundary around fields that belong to the embedded sub-table
 * rather than the host form. Uses a tinted surface + accent left edge so the group is obvious
 * at a glance without competing with el-card sections already on the page.
 */
.sub-table-inline-form.is-framed {
  margin: 8px 0 16px;
  padding: 0 0 4px;
  /* Neutral grey only: the brand accent here is red, which reads as an error state on a
     block that is merely a grouping boundary. */
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 4px;
  background: var(--el-fill-color-blank, #fff);
}

.sub-table-inline-form.is-framed .inline-form-frame-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 3px 3px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular, #606266);
}

.sub-table-inline-form.is-framed .inline-form-frame-icon {
  color: var(--el-text-color-secondary, #909399);
}

/* Keep field rows clear of the frame edge. */
.sub-table-inline-form.is-framed :deep(.el-form) {
  padding: 0 12px;
}

.sub-table-inline-form .title {
  font-weight: 600;
  font-size: 14px;
}

.inline-form-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
