import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance, FormRules } from 'element-plus'
import { materializeFormCreateValidationRules } from '../../utils/formCreateValidateRules'
import type { FormField, FormBusinessLogicConfig } from '../../components/formRendererHelpers'
import {
  emptyLookupModelValue,
  lookupFilterConditionsForField,
  processLookupCascadeClear,
  processLookupCascadeSelect,
} from './useFormLookupCascade'
import {
  REQUEST_ID_FIELD,
  fieldFeedsRequestId,
  computeRequestId,
  type RequestIdConfig,
} from '../../utils/formFieldMeta'
import { subTableComponentEventFieldKey } from '../../utils/formCreateComponentEvents'
import { overlayEventRequiredOnFormRules } from '../../utils/formCreateEventRuntime'
import { isAuditField } from '../../components/subTableAddDialogHelpers/rowInit'

/** Copy Owner/Lookup `"<field>__display"` companions that leaf-field init would otherwise drop. */
export function copyFieldDisplayCompanion(
  parent: Record<string, unknown>,
  fieldKey: string,
  target: Record<string, unknown>,
): void {
  const displayKey = `${fieldKey}__display`
  const display = parent[displayKey]
  if (typeof display === 'string' && display.length > 0) {
    target[displayKey] = display
  }
}

function pushRequiredRule(
  fieldRules: Array<Record<string, unknown>>,
  field: FormField,
  t: (key: string, params?: Record<string, unknown>) => string,
): void {
  if (fieldRules.some((rule) => rule.required === true)) return
  const trigger = field.type === 'select' || field.type === 'checkbox' || field.type === 'switch'
    ? 'change'
    : 'blur'
  if (field.type === 'switch') {
    fieldRules.push({
      type: 'boolean',
      required: true,
      message: t('common.pleaseInput', { label: field.label }),
      trigger,
    })
    return
  }
  fieldRules.push({
    required: true,
    message: t('common.pleaseInput', { label: field.label }),
    trigger,
  })
}

interface FormDataDeps {
  formRef: Ref<FormInstance | undefined>
  allFields: ComputedRef<FormField[]>
  modelValue: () => Record<string, any>
  readonly: () => boolean
  config: () => FormBusinessLogicConfig | undefined
  getInternalUpdate: () => boolean
  setInternalUpdate: (v: boolean) => void
  emitChange: (key: string, value: any) => void
  emitModelValue: (value: Record<string, any>) => void
  emitSubTableData: (bindingId: number, rows: any[]) => void
  // wired-in cross-composable behavior (broken via closures to avoid TDZ cycles)
  runComponentEventsOnFieldChange: (key: string, value: unknown) => void
  formOptionsOnChange: () => unknown
  fieldComponentEventsHas: (key: string) => boolean
  runFormOptionsOnChange: (field: string, value: unknown) => void
  engineOnFieldChange: (key: string, value: any, formData: Record<string, any>) => any
  applyEngineResult: (result: any) => void
  engineOnSubTableChange: (bindingId: number, rows: any[], formData: Record<string, any>) => any
  engineCalculatedValues: Ref<Map<string, number>>
  /** Main-table Request ID config — drives live recompute of the readonly __request_id field. */
  requestIdConfig?: () => RequestIdConfig | null | undefined
  /** Re-previews the main table's computed columns; no-op when the table has none. */
  recomputeComputedFields?: (changedSubTable?: { bindingId: number; rows: unknown[] }) => void
  /** Linkage `field-state-change` required/disabled flags from BusinessLogicEngine. */
  engineFieldStates?: Ref<Map<string, { disabled?: boolean; required?: boolean }>>
  /** Script `api.required` overlay (true/false); missing key → designer + linkage. */
  eventRequiredFlags?: Ref<Map<string, boolean>>
}

export function useFormData(deps: FormDataDeps) {
  const { t } = useI18n()
  const formData = ref<Record<string, any>>({})

  // Lookup selected data state
  const lookupSelectedData = ref<Record<string, Record<string, any>>>({})
  const lookupLoadedViewFields = ref<Record<string, any[]>>({})
  /** Parity with Form Preview / FieldRenderer — honor lookupConfig.showBackfillView === false. */
  function lookupShowBackfillView(field: FormField): boolean {
    return (field as any)._lookupShowBackfillView !== false
  }
  /** Flush internal formData to parent v-model immediately (bypass 150ms watchThrottled). */
  function syncFormDataToParent() {
    if (deps.readonly()) return
    deps.emitModelValue({ ...formData.value })
  }

  const handleLookupSelect = async (fieldKey: string, row: Record<string, any>) => {
    lookupSelectedData.value[fieldKey] = row
    const field = deps.allFields.value.find((f) => f.key === fieldKey)
    const isMulti = (field as { _lookupMultiple?: boolean } | undefined)?._lookupMultiple === true
    // Multi LOOKUP already updated via @update:modelValue — do not overwrite the array.
    if (!isMulti) {
      handleFieldChange(fieldKey, row)
    } else {
      // Critical: multi skips handleFieldChange, so parent sync was only via watchThrottled(150ms).
      // Start Process ensureMainPrimaryKey / submit read parent formData and would drop test_status.
      syncFormDataToParent()
    }
    await processLookupCascadeSelect(
      fieldKey,
      row,
      deps.allFields.value,
      formData,
      lookupSelectedData,
      handleFieldChange,
    )
  }

  /**
   * Multi LOOKUP tag remove / toggle only emits update:modelValue (no @select).
   * Keep parent formData in sync so submit/draft/PK allocate cannot drop the array.
   */
  function handleLookupModelUpdate(fieldKey: string, value: unknown) {
    const field = deps.allFields.value.find((f) => f.key === fieldKey)
    if ((field as { _lookupMultiple?: boolean } | undefined)?._lookupMultiple !== true) return
    formData.value[fieldKey] = value
    if (Array.isArray(value) && value[0] != null && typeof value[0] === 'object') {
      lookupSelectedData.value[fieldKey] = value[0] as Record<string, any>
    } else if (!Array.isArray(value) || value.length === 0) {
      delete lookupSelectedData.value[fieldKey]
    }
    syncFormDataToParent()
  }

  const handleLookupClear = (fieldKey: string) => {
    processLookupCascadeClear(
      fieldKey,
      deps.allFields.value,
      formData,
      lookupSelectedData,
      handleFieldChange,
    )
    delete lookupSelectedData.value[fieldKey]
    const empty = emptyLookupModelValue(deps.allFields.value.find((f) => f.key === fieldKey))
    handleFieldChange(fieldKey, empty)
  }
  const lookupFilterConditionsFor = (field: FormField) =>
    lookupFilterConditionsForField(field, lookupSelectedData.value)

  // Manage file upload lists independently to avoid re-render issues when deriving from formData
  const uploadFileLists = ref<Record<string, Array<{ name: string; url: string; uid?: number }>>>({})

  // ---------------------------------------------------------------------------
  // Form data initialization
  // ---------------------------------------------------------------------------
  const initFormData = () => {
    const data: Record<string, any> = {}
    const parent = deps.modelValue() || {}
    deps.allFields.value.forEach(field => {
      const bound = parent[field.key]
      if (bound !== undefined && bound !== null && bound !== '') {
        data[field.key] = bound
      } else if (field.defaultValue !== undefined && field.defaultValue !== null && field.defaultValue !== '') {
        data[field.key] = field.defaultValue
        if (typeof field._ownerPrefillDisplay === 'string' && field._ownerPrefillDisplay.length > 0) {
          data[`${field.key}__display`] = field._ownerPrefillDisplay
        }
      } else if (field.type === 'checkbox') {
        data[field.key] = []
      } else if (field.type === 'switch') {
        data[field.key] = false
      } else {
        data[field.key] = null
      }
      copyFieldDisplayCompanion(parent, field.key, data)
    })
    // Seed the readonly Request ID: prefer the backend-filled value, else compute from
    // the contributing fields already present (e.g. new-request page has no backend fill yet).
    const ridCfg = deps.requestIdConfig?.()
    if (ridCfg) {
      const seeded = data[REQUEST_ID_FIELD]
      if (seeded === undefined || seeded === null || seeded === '') {
        const computed = computeRequestId(data, ridCfg)
        if (computed !== undefined) data[REQUEST_ID_FIELD] = computed
      }
    }
    // Seed lookupSelectedData from object-shaped LOOKUP values so cascade filters
    // work on To Do / Completed / Start when parent is already filled.
    // Multi LOOKUP stores an array of rows — use the first row as cascade parent context.
    const seededLookupRows: Record<string, Record<string, any>> = {}
    for (const field of deps.allFields.value) {
      if (field.type !== 'lookup') continue
      const v = data[field.key]
      if (v != null && typeof v === 'object' && !Array.isArray(v)) {
        seededLookupRows[field.key] = v as Record<string, any>
      } else if (Array.isArray(v) && v[0] != null && typeof v[0] === 'object') {
        seededLookupRows[field.key] = v[0] as Record<string, any>
      }
    }
    lookupSelectedData.value = seededLookupRows

    deps.setInternalUpdate(true)
    formData.value = data
    // Preview the formulas over the seeded values: on a fresh request there is no server-computed
    // value yet, and on an existing record this confirms the stored value against what is on screen.
    deps.recomputeComputedFields?.()
    setTimeout(() => { deps.setInternalUpdate(false) }, 0)
    // Element Plus AsyncValidator resolves as micro-tasks after nextTick;
    // use setTimeout (macro-task) to guarantee clearValidate runs last.
    setTimeout(() => {
      const el = deps.formRef.value
      if (el && typeof (el as { clearValidate?: () => void }).clearValidate === 'function') {
        el.clearValidate()
      }
    }, 0)
  }

  // ---------------------------------------------------------------------------
  // Form rules
  // ---------------------------------------------------------------------------
  const formRules = computed<FormRules>(() => {
    if (deps.readonly()) return {}
    const rules: FormRules = {}
    deps.allFields.value.forEach(field => {
      // System audit fields stay empty until the backend fills them at insert/update;
      // never validate them (a designer-set required would block every submit).
      if (isAuditField(field.key)) return
      const fieldRules: any[] = []
      if (field.rules?.length) {
        fieldRules.push(
          ...materializeFormCreateValidationRules(
            field.rules,
            () => formData.value,
            () => deps.allFields.value,
          ),
        )
      }
      const linkageRequired = deps.engineFieldStates?.value.get(field.key)?.required === true
      if (field.required || linkageRequired) {
        pushRequiredRule(fieldRules, field, t)
      }
      if (fieldRules.length > 0) {
        rules[field.key] = fieldRules
      }
    })
    return overlayEventRequiredOnFormRules(
      rules as Record<string, unknown>,
      deps.allFields.value.filter((field) => !isAuditField(field.key)).map((field) => field.key),
      deps.eventRequiredFlags?.value,
      (key) => {
        const field = deps.allFields.value.find((item) => item.key === key)
        if (!field) return []
        const acc: Array<Record<string, unknown>> = []
        pushRequiredRule(acc, field, t)
        return acc
      },
    ) as FormRules
  })

  // ---------------------------------------------------------------------------
  // Field change handler (Task 7.1 + 7.2)
  // ---------------------------------------------------------------------------
  function handleFieldChange(key: string, value: any) {
    formData.value[key] = value

    // Live Request ID: recompute the readonly __request_id when a contributing field changes.
    const ridCfg = deps.requestIdConfig?.()
    if (fieldFeedsRequestId(key, ridCfg)) {
      formData.value[REQUEST_ID_FIELD] = computeRequestId(formData.value, ridCfg) ?? ''
    }

    // Same idea as Request ID above: readonly derived columns follow the field that feeds them.
    deps.recomputeComputedFields?.()

    deps.emitChange(key, value)

    deps.runComponentEventsOnFieldChange(key, value)

    const onChangeHandler = deps.formOptionsOnChange()
    if (onChangeHandler) {
      deps.runFormOptionsOnChange(key, value)
    }
    if (onChangeHandler || deps.fieldComponentEventsHas(key)) {
      if (!deps.readonly()) {
        deps.emitModelValue({ ...formData.value })
      }
    }

    // Task 7.2: Trigger engine evaluation on field change
    if (deps.config()) {
      const result = deps.engineOnFieldChange(key, value, formData.value)
      deps.applyEngineResult(result)
      const stateChanges = result?.stateChanges as Map<string, { required?: boolean }> | undefined
      if (stateChanges && deps.formRef.value) {
        for (const [fieldKey, state] of stateChanges) {
          if (state.required !== true) {
            deps.formRef.value.clearValidate(fieldKey)
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Upload handlers
  // ---------------------------------------------------------------------------
  function handleUploadSuccess(_response: unknown, _file: unknown, _fieldKey: string) {
    // Value already applied via FieldRenderer update:modelValue (single URL or file array).
    if (!deps.readonly()) deps.emitModelValue({ ...formData.value })
  }

  function handleUploadRemove(_file: unknown, _fieldKey: string) {
    if (!deps.readonly()) deps.emitModelValue({ ...formData.value })
  }

  // ---------------------------------------------------------------------------
  // SubTableField config helpers (Req 10.1, 10.2, 10.3)
  // ---------------------------------------------------------------------------
  function getSubFormRowFormulas(bindingId?: number) {
    const config = deps.config()
    if (!bindingId || !config?.subForms) return undefined
    return config.subForms[String(bindingId)]?.rowFormulas
  }

  function getSummaryColumns(bindingId?: number) {
    const config = deps.config()
    if (!bindingId || !config?.summaryRules) return undefined
    return config.summaryRules
      .filter(r => r.sourceBindingId === bindingId)
      .map(r => r.sourceColumn)
  }

  function getSummaryAggregations(bindingId?: number) {
    const config = deps.config()
    if (!bindingId || !config?.summaryRules) return undefined
    const aggs: Record<string, 'SUM' | 'AVG' | 'COUNT' | 'MIN' | 'MAX'> = {}
    config.summaryRules
      .filter(r => r.sourceBindingId === bindingId)
      .forEach(r => { aggs[r.sourceColumn] = r.aggregation })
    return Object.keys(aggs).length > 0 ? aggs : undefined
  }

  function getSubTableValidation(bindingId?: number) {
    const config = deps.config()
    if (!bindingId || !config?.subTableValidation) return undefined
    return config.subTableValidation[String(bindingId)]
  }

  function handleSubTableUpdate(bindingId: number, rows: any[]) {
    deps.emitSubTableData(bindingId, rows)

    // Aggregate formulas read these rows, so the total has to move with the row that changed.
    deps.recomputeComputedFields?.({ bindingId, rows })

    // Form Design SubTable on.change / hook_value (keyed as __subTable_${bindingId}).
    const eventKey = subTableComponentEventFieldKey(bindingId)
    deps.runComponentEventsOnFieldChange(eventKey, rows)
    const onChangeHandler = deps.formOptionsOnChange()
    if (onChangeHandler) {
      deps.runFormOptionsOnChange(eventKey, rows)
    }
    if (onChangeHandler || deps.fieldComponentEventsHas(eventKey)) {
      if (!deps.readonly()) {
        deps.emitModelValue({ ...formData.value })
      }
    }

    // Trigger engine summary calculations
    if (deps.config()) {
      const summaryResult = deps.engineOnSubTableChange(bindingId, rows, formData.value)
      for (const [targetField, value] of summaryResult.summaryValues) {
        formData.value[targetField] = value
        deps.engineCalculatedValues.value.set(targetField, value)
      }
      deps.engineCalculatedValues.value = new Map(deps.engineCalculatedValues.value)
    }
  }

  function handlePrimaryFormDataPatch(patch: Record<string, unknown>) {
    if (!patch || typeof patch !== 'object') return
    Object.assign(formData.value, patch)
    // Live Request ID: a programmatic patch (e.g. main PK generated on sub-table add)
    // bypasses handleFieldChange, so recompute here if it touched a contributing field.
    const ridCfg = deps.requestIdConfig?.()
    if (ridCfg && ridCfg.fieldNames.some((name) => Object.prototype.hasOwnProperty.call(patch, name))) {
      formData.value[REQUEST_ID_FIELD] = computeRequestId(formData.value, ridCfg) ?? ''
    }
    deps.emitModelValue({ ...formData.value })
  }

  // ---------------------------------------------------------------------------
  // Existing exposed methods
  // ---------------------------------------------------------------------------
  const resetForm = () => {
    deps.formRef.value?.resetFields()
    initFormData()
  }

  const getFormData = () => {
    return { ...formData.value }
  }

  const setFieldValue = (key: string, value: any) => {
    formData.value[key] = value
  }

  return {
    formData,
    lookupSelectedData,
    lookupLoadedViewFields,
    lookupShowBackfillView,
    lookupFilterConditionsFor,
    handleLookupSelect,
    handleLookupModelUpdate,
    handleLookupClear,
    syncFormDataToParent,
    uploadFileLists,
    initFormData,
    formRules,
    handleFieldChange,
    handleUploadSuccess,
    handleUploadRemove,
    getSubFormRowFormulas,
    getSummaryColumns,
    getSummaryAggregations,
    getSubTableValidation,
    handleSubTableUpdate,
    handlePrimaryFormDataPatch,
    resetForm,
    getFormData,
    setFieldValue,
  }
}
