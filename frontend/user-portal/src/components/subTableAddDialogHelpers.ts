// Barrel for sub-table row dialog helpers. Behaviour-preserving split of the original
// monolithic module — every export below keeps its original name and import path
// (`@/components/subTableAddDialogHelpers`). See ./subTableAddDialogHelpers/* for impls.

// Shared types
export type {
  ColumnType,
  TreeNode,
  DialogColumn,
  UserSnapshotViewField,
  RelationFieldDef,
  SubListViewColumn,
  ParsedLookupConfig,
} from './subTableAddDialogHelpers/types'

// Control component mapping
export { CONTROL_TYPE_MAP, resolveControlComponent } from './subTableAddDialogHelpers/controlTypeMap'

// Row initialization / validation rules / seed merge
export { buildInitialRow, mergeFormRowWithSeed, buildRules, applyAuditFieldDefaults, applyEditAuditDefaults, isAuditField } from './subTableAddDialogHelpers/rowInit'

// User-like value display helpers
export {
  extractUserIdFromCellValue,
  unwrapUserLikeValueToDisplayString,
  userObjectTagDisplayString,
  isUserSnapshotLikeObject,
  userSnapshotViewFieldsFromRow,
  formatUserSnapshotCellValue,
} from './subTableAddDialogHelpers/userDisplay'

// File / upload column detection + list-view column merge
export {
  isStoredFileUrl,
  isLikelyFileStorageFieldName,
  isUploadColumn,
  inferColumnTypeFromFieldAndValue,
  resolveListColumnFieldType,
  mergeListViewFieldColumn,
  normalizeSubTableColumns,
} from './subTableAddDialogHelpers/fileColumns'
export { unionListViewWithSubFormUploadColumns } from '@platform-shared/upload/unionUploadColumns'

// Relation-table field defs, indexes, schema resolution
export {
  enrichColumnsWithTableFieldDisplayNames,
  resolveBindingFieldDefinitions,
  buildParentTablesByIdFromBindings,
  mapRelationFieldDataTypeToColumnType,
  defaultAttachmentListColumns,
  deriveColumnsFromRelationFieldDefinitions,
  mergeMissingTableFieldColumns,
  buildRelationTableFieldIndexFromDataTables,
  resolveSubListViewColumnsForBinding,
  resolveSubTableSchemaByTableId,
} from './subTableAddDialogHelpers/relationFields'

// Lookup config / display helpers
export {
  parseLookupConfig,
  getLookupPrimaryKeyFieldFromProps,
  getLookupSelectedDisplayFieldFromProps,
  getLookupSelectedDisplayField,
  buildLookupColumnProps,
  resolveLookupCellTagText,
  unwrapSingleLookupModelValue,
  enrichLookupColumnPropsFromSubFormRule,
} from './subTableAddDialogHelpers/lookup'

// Table cell display value resolution
export { resolveDisplayValue } from './subTableAddDialogHelpers/displayValue'

// Form-design canvas columns (Add/Edit dialog — excludes list-view-only audit columns)
export {
  flattenSubFormRuleLayoutContainers,
  isDialogMappableSubFormRule,
  mapSubFormRuleToDialogColumns,
  resolveSubFormDialogColumnsForBinding,
  resolveSubFormRuleForBinding,
  type SubFormColumnLookupContext,
} from './subTableAddDialogHelpers/subFormCanvasColumns'

export {
  buildDialogLayoutGroups,
  type DialogLayoutGroup,
} from './subTableAddDialogHelpers/dialogFormLayout'
