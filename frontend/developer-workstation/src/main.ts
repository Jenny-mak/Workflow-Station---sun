import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import '@fontsource-variable/inter'
import FcDesigner from '@form-create/designer'
import formCreateMenus from '@form-create/designer/src/config/menu'
import { ensureEmptyRuleComponentEvents } from '@/utils/formCreateDefaultEvents'
// Import form-create styles
import '@form-create/designer/src/style/index.css'
import '@form-create/designer/src/style/icon.css'
import '@form-create/element-ui/src/style/index.css'
// Import form-create English locale
import enLocale from '@form-create/designer/locale/en.js'
import App from './App.vue'
import router from './router'
import i18n from './i18n'
import { setUploadAuthRefresher } from '@platform-shared/upload/uploadAuthRefresh'
import { refreshToken } from '@/api/auth'
import './styles/index.scss'
import './styles/designer-validate-panel.scss'
import './styles/form-readonly.scss'
import SubTablePlaceholderWidget from './components/designer/SubTablePlaceholderWidget.vue'
import SubTableBindingSelect from './components/designer/SubTableBindingSelect.vue'
import InlineSubFormPlaceholderWidget from './components/designer/InlineSubFormPlaceholderWidget.vue'
import LinkFormPlaceholderWidget from './components/designer/LinkFormPlaceholderWidget.vue'
import LinkFormBindingSelect from './components/designer/LinkFormBindingSelect.vue'
import { FcEditor, FcTransfer, FcCascader, FcSlider } from './components/designer/fc-custom-fields'
import LookupComponent from './components/designer/LookupComponent.vue'
import LookupBindingSelect from './components/designer/LookupBindingSelect.vue'
import RecordNotePlaceholderWidget from './components/designer/RecordNotePlaceholderWidget.vue'
import RecordNotePreview from './components/designer/RecordNotePreview.vue'
import OwnerPlaceholderWidget from './components/designer/OwnerPlaceholderWidget.vue'
import OwnerConfigEditor from './components/designer/OwnerConfigEditor.vue'
import FormControlTypeSelect from './components/designer/FormControlTypeSelect.vue'
import RecordNoteScopeSelect from './components/designer/RecordNoteScopeSelect.vue'
import SensitiveMaskPropsEditor from './components/designer/SensitiveMaskPropsEditor.vue'
import UploadMaxFilesEditor from './components/designer/UploadMaxFilesEditor.vue'
import UploadMaxFileSizeEditor from './components/designer/UploadMaxFileSizeEditor.vue'
import FileNetAdvancedEditor from './components/designer/FileNetAdvancedEditor.vue'
import FormUploadDrop from './components/designer/FormUploadDrop.vue'
import unique from '@form-create/utils/lib/unique'
import { DEFAULT_FILE_NET_CONFIG } from '@platform-shared/upload/fileNetConfig'
import SensitiveMaskedInput from './components/designer/SensitiveMaskedInput.vue'
import MiAssignmentPlaceholderWidget from './components/designer/MiAssignmentPlaceholderWidget.vue'
import HermesValidate from './components/designer/HermesValidate.vue'
import { registerFormCreateReadonlyParser } from './utils/registerFormCreateReadonlyParser'
import formCreateFactory from '@form-create/element-ui'

// Force set HTML lang attribute to English
document.documentElement.lang = 'en'

const app = createApp(App)

// Register Element Plus icons
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.use(i18n)
setUploadAuthRefresher(async () => {
  try {
    await refreshToken()
    return true
  } catch {
    return false
  }
})
// Set form-create designer to English locale
FcDesigner.useLocale(enLocale)
app.use(FcDesigner)
app.use(FcDesigner.formCreate)
const formCreateWithParser =
  (formCreateFactory as { default?: { parser?: (name: string, config: unknown) => void } }).default ??
  formCreateFactory
registerFormCreateReadonlyParser(formCreateWithParser as { parser: (name: string, config: unknown) => void })

// Validation+ must emit as soon as a rule is added (Error is optional). Upstream
// Validate.handleCommand does not emit, so empty Error blocked auto-save and Save.
// Register on designerForm only — same surface as upstream Validate (not the canvas formCreate).
;(FcDesigner as typeof FcDesigner & {
  designerForm: { component: (name: string, component: unknown) => void }
}).designerForm.component('Validate', HermesValidate)

// Register SubTableBindingSelect into both designerForm (props panel) and formCreate (canvas)
// FcDesigner.component() calls addComponent() which registers to both instances
FcDesigner.component('SubTableBindingSelect', SubTableBindingSelect)

// Register SubTablePlaceholderWidget as the canvas renderer for 'subTable' type
FcDesigner.component('SubTable', SubTablePlaceholderWidget)

// Register InlineSubFormPlaceholderWidget as the canvas renderer for 'inlineSubForm' type
FcDesigner.component('InlineSubForm', InlineSubFormPlaceholderWidget)

// Register LinkFormPlaceholderWidget as the canvas renderer for 'linkForm' type
FcDesigner.component('LinkForm', LinkFormPlaceholderWidget)
FcDesigner.component('LinkFormBindingSelect', LinkFormBindingSelect)

// Register custom field components so form-create can render them in canvas & preview
FcDesigner.component('Editor', FcEditor)
FcDesigner.component('Transfer', FcTransfer)
FcDesigner.component('Cascader', FcCascader)
FcDesigner.component('Slider', FcSlider)
FcDesigner.component('Lookup', LookupComponent)
FcDesigner.component('LookupBindingSelect', LookupBindingSelect)

// Register RecordNotePlaceholderWidget as the canvas renderer for 'recordNote' type
FcDesigner.component('RecordNote', RecordNotePlaceholderWidget)

// Preview-only renderer: form-create surfaces that render a whole rule in one instance
// (sub-table Add/Edit dialog, Link Form modal) retype `recordNote` to this so Preview shows
// the portal-shaped Notes panel instead of the canvas placeholder. Not a drag rule — it has
// no palette entry and is never persisted. See recordNotePreviewRules.ts.
FcDesigner.component('RecordNotePreview', RecordNotePreview)

// Owner field: canvas placeholder + props-panel editor for ownerConfig
FcDesigner.component('Owner', OwnerPlaceholderWidget)
FcDesigner.component('OwnerConfigEditor', OwnerConfigEditor)
FcDesigner.component('FormControlTypeSelect', FormControlTypeSelect)
FcDesigner.component('RecordNoteScopeSelect', RecordNoteScopeSelect)
FcDesigner.component('MiAssignment', MiAssignmentPlaceholderWidget)

// Dedicated "Extend" palette group for our own business components (Sub-Table,
// Inline Form, Link Form, Lookup, Owner, Record Note) — distinct from form-create's
// built-in Basic group so custom components aren't mixed in with stock field types.
// addMenu only supports unshift-to-front / push-to-end, so to land it between the
// built-in Basic ('main') and Subform ('subform') groups we splice the shared config
// array directly instead. Registered before any addDragRule call so rules carrying
// `menu: 'extend'` are filed into it instead of falling back to Basic.
formCreateMenus.splice(1, 0, {
  name: 'extend',
  title: String(i18n.global.t('form.extendMenuTitle')),
  list: [],
})

// Dedicated "MI" (multi-instance) palette group, sibling to the built-in
// Basic/Aide/Layout/Subform groups. Registered before any addDragRule call so
// rules carrying `menu: 'mi'` are filed into it instead of falling back to Basic.
// Future multi-instance components should use `menu: 'mi'` to land here.
FcDesigner.addMenu({
  name: 'mi',
  title: String(i18n.global.t('form.miMenuTitle')),
  list: [],
})

// Sensitive mask: props panel editor + Input display wrapper (Preview / canvas).
FcDesigner.component('SensitiveMaskPropsEditor', SensitiveMaskPropsEditor)
FcDesigner.component('UploadMaxFilesEditor', UploadMaxFilesEditor)
FcDesigner.component('UploadMaxFileSizeEditor', UploadMaxFileSizeEditor)
FcDesigner.component('FileNetAdvancedEditor', FileNetAdvancedEditor)
FcDesigner.component('formUploadDrop', FormUploadDrop)
FcDesigner.component('advancedUpload', FormUploadDrop)
FcDesigner.component('input', SensitiveMaskedInput)

// Register the subTable drag rule so it appears in the designer left menu
FcDesigner.addDragRule({
  name: 'subTable',
  label: 'Sub-Table',
  icon: 'icon-table',
  menu: 'extend',
  mask: true,
  input: false,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  // When loading a saved rule, copy top-level _bindingId into props so the config panel can read it.
  loadRule(rule: any) {
    ensureEmptyRuleComponentEvents(rule)
    rule.props = rule.props || {}
    if (rule._bindingId !== undefined) {
      rule.props._bindingId = rule._bindingId
    }
  },
  // When saving/exporting, move props._bindingId back to top-level _bindingId.
  parseRule(rule: any) {
    if (rule.props && rule.props._bindingId !== undefined) {
      rule._bindingId = rule.props._bindingId
      delete rule.props._bindingId
    } else {
      // Ensure _bindingId exists even if props was empty
      if (rule._bindingId === undefined) rule._bindingId = null
    }
  },
  // Keep top-level _bindingId in sync when the props panel changes props._bindingId
  watch: {
    _bindingId({ value, rule }: { value: any; rule: any }) {
      rule._bindingId = value ?? null
    }
  },
  rule() {
    const r = {
      type: 'subTable',
      _bindingId: null,
      title: 'Sub-Table',
      props: {
        _bindingId: null,
      }
    }
    ensureEmptyRuleComponentEvents(r)
    return r
  },
  props() {
    return [
      {
        type: 'SubTableBindingSelect',
        field: '_bindingId',
        title: 'Sub Table Binding',
        props: {}
      },
    ]
  }
})

// Inline Form: lays the bound SUB table's designed form out IN PLACE, with no grid above
// it and no dialog. Contrast with `subTable` (renders a grid) and `linkForm` (opens a modal).
//
// The loadRule/parseRule/watch triad is load-bearing and copied verbatim from `subTable`:
// this rule is `input: false`, so form-create forwards neither rule.props nor rule.on, and
// the top-level `_bindingId` <-> props round-trip is the ONLY channel that reaches the
// props panel. See docs/design/inline-sub-form-component.md.
FcDesigner.addDragRule({
  name: 'inlineSubForm',
  label: String(i18n.global.t('form.inlineSubFormLabel')),
  icon: 'icon-form',
  menu: 'extend',
  mask: true,
  input: false,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  // When loading a saved rule, copy top-level _bindingId into props so the config panel can read it.
  loadRule(rule: any) {
    ensureEmptyRuleComponentEvents(rule)
    rule.props = rule.props || {}
    if (rule._bindingId !== undefined) {
      rule.props._bindingId = rule._bindingId
    }
  },
  // When saving/exporting, move props._bindingId back to top-level _bindingId.
  parseRule(rule: any) {
    if (rule.props && rule.props._bindingId !== undefined) {
      rule._bindingId = rule.props._bindingId
      delete rule.props._bindingId
    } else {
      // Ensure _bindingId exists even if props was empty
      if (rule._bindingId === undefined) rule._bindingId = null
    }
  },
  // Keep top-level _bindingId in sync when the props panel changes props._bindingId
  watch: {
    _bindingId({ value, rule }: { value: any; rule: any }) {
      rule._bindingId = value ?? null
    }
  },
  rule() {
    const r = {
      type: 'inlineSubForm',
      _bindingId: null,
      title: String(i18n.global.t('form.inlineSubFormLabel')),
      props: {
        _bindingId: null,
      }
    }
    ensureEmptyRuleComponentEvents(r)
    return r
  },
  props() {
    return [
      {
        type: 'SubTableBindingSelect',
        field: '_bindingId',
        title: String(i18n.global.t('form.inlineSubFormBinding')),
        props: {}
      },
    ]
  }
})

// Multi-instance assignment orchestration container. BPMN remains the only source of
// assignment SETTINGS; this rule holds the assignee / BU / role fields as its children
// so the whole unit moves as one when dragged.
//
// It is a NORMAL drop container (like elCard): `drag: true` lets the author drag the
// imported fields in and back out, and `mask: false` keeps the children clickable so
// their own property panels still work. On first load a form that predates the container
// has its assignment fields adopted automatically (see nestAssignmentFieldsIntoContainer),
// after which membership and order belong to the author — `_miAdopted` stops the
// adoption pass from ever re-capturing a field that was deliberately dragged out.
FcDesigner.addDragRule({
  name: 'miAssignment',
  label: String(i18n.global.t('form.miAssignmentTitle')),
  icon: 'icon-radio',
  menu: 'mi',
  // No mask: children must stay selectable inside the container.
  mask: false,
  input: false,
  // Accept dropped components — this is what makes the reserved area a real drop zone.
  drag: true,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  loadRule(rule: any) {
    rule.props = rule.props || {}
    if (!Array.isArray(rule.children)) rule.children = []
  },
  parseRule(rule: any) {
    // Persist children (the nested fields) and the one-time adoption marker, so a
    // reopened form does not re-adopt fields the author moved out.
    if (!Array.isArray(rule.children)) rule.children = []
  },
  rule() {
    return {
      type: 'miAssignment',
      title: String(i18n.global.t('form.miAssignmentTitle')),
      props: {},
      // Dropped straight from the palette: an empty, ready-to-fill drop area. Marked
      // adopted so it never vacuums fields the author placed elsewhere on the form.
      _miAdopted: true,
      children: [],
    }
  },
  props() {
    return []
  },
})

// Register the linkForm drag rule so it appears in the designer left menu
FcDesigner.addDragRule({
  name: 'linkForm',
  label: 'Link Form',
  icon: 'icon-link',
  menu: 'extend',
  mask: true,
  input: false,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  loadRule(rule: any) {
    ensureEmptyRuleComponentEvents(rule)
    rule.props = rule.props || {}
    if (rule._componentId !== undefined) {
      rule.props._componentId = rule._componentId
    }
  },
  parseRule(rule: any) {
    if (rule.props && rule.props._componentId !== undefined) {
      rule._componentId = rule.props._componentId
      delete rule.props._componentId
    } else {
      if (rule._componentId === undefined) rule._componentId = null
    }
  },
  watch: {
    _componentId({ value, rule }: { value: any; rule: any }) {
      rule._componentId = value ?? null
    }
  },
  rule() {
    const r = {
      type: 'linkForm',
      _componentId: null,
      title: 'Link Form',
      props: { _componentId: null }
    }
    ensureEmptyRuleComponentEvents(r)
    return r
  },
  props() {
    return [
      {
        type: 'LinkFormBindingSelect',
        field: '_componentId',
        title: 'Link Form Component',
        props: {}
      }
    ]
  }
})

// ─── Register custom drag rules for new field types ──────────────────────────
// These allow fc-designer to recognise and render editor/signature/transfer/cascader/slider
// in the canvas and left-side menu.

FcDesigner.addDragRule({
  name: 'editor',
  label: 'Editor',
  icon: 'icon-editor',
  menu: 'main',
  mask: false,
  input: true,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  rule() {
    return {
      type: 'editor',
      field: 'editor',
      title: 'Editor',
      props: { rows: 5, placeholder: 'Please input content' }
    }
  },
  props() {
    return [
      { type: 'inputNumber', field: 'rows', title: 'Rows', props: { min: 2, max: 20 } },
      { type: 'input', field: 'placeholder', title: 'Placeholder' },
      { type: 'inputNumber', field: 'maxlength', title: 'Max Length' }
    ]
  }
})

FcDesigner.addDragRule({
  name: 'transfer',
  label: 'Transfer',
  icon: 'icon-transfer',
  menu: 'main',
  mask: false,
  input: true,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  rule() {
    return {
      type: 'transfer',
      field: 'transfer',
      title: 'Transfer',
      props: { options: [], leftTitle: 'Source', rightTitle: 'Target' }
    }
  },
  props() {
    return [
      { type: 'input', field: 'leftTitle', title: 'Left Title' },
      { type: 'input', field: 'rightTitle', title: 'Right Title' }
    ]
  }
})

FcDesigner.addDragRule({
  name: 'cascader',
  label: 'Cascader',
  icon: 'icon-cascader',
  menu: 'main',
  mask: false,
  input: true,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  rule() {
    return {
      type: 'cascader',
      field: 'cascader',
      title: 'Cascader',
      props: { options: [], placeholder: 'Please select' }
    }
  },
  props() {
    return [
      { type: 'input', field: 'placeholder', title: 'Placeholder' }
    ]
  }
})

FcDesigner.addDragRule({
  name: 'slider',
  label: 'Slider',
  icon: 'icon-slider',
  menu: 'main',
  mask: false,
  input: true,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  rule() {
    return {
      type: 'slider',
      field: 'slider',
      title: 'Slider',
      props: { min: 0, max: 100, step: 1 }
    }
  },
  props() {
    return [
      { type: 'inputNumber', field: 'min', title: 'Min' },
      { type: 'inputNumber', field: 'max', title: 'Max' },
      { type: 'inputNumber', field: 'step', title: 'Step' }
    ]
  }
})

FcDesigner.addDragRule({
  name: 'advancedUpload',
  label: String(i18n.global.t('form.advancedUpload')),
  icon: 'icon-upload',
  menu: 'extend',
  mask: true,
  input: true,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  rule() {
    return {
      type: 'advancedUpload',
      field: unique(),
      title: String(i18n.global.t('form.advancedUpload')),
      props: {
        action: '/api/v1/upload',
        maxFiles: 10,
        maxFileSizeMb: 10,
        limit: 10,
        multiple: true,
        cannotDownload: false,
        fileNet: {
          ...DEFAULT_FILE_NET_CONFIG,
          repositoryDetail: { ...DEFAULT_FILE_NET_CONFIG.repositoryDetail },
        },
      },
    }
  },
  props() {
    return [
      {
        type: 'UploadMaxFilesEditor',
        field: 'maxFiles',
        title: String(i18n.global.t('form.uploadMaxFiles')),
        value: 10,
      },
      {
        type: 'UploadMaxFileSizeEditor',
        field: 'maxFileSizeMb',
        title: String(i18n.global.t('form.uploadMaxFileSize')),
        value: 10,
      },
      {
        type: 'switch',
        field: 'cannotDownload',
        title: String(i18n.global.t('form.uploadCannotDownload')),
        value: false,
      },
      { type: 'switch', field: 'readonly', title: 'Readonly' },
      {
        type: 'FileNetAdvancedEditor',
        field: 'fileNet',
        title: String(i18n.global.t('form.fileNet.advance')),
        value: {
          ...DEFAULT_FILE_NET_CONFIG,
          repositoryDetail: { ...DEFAULT_FILE_NET_CONFIG.repositoryDetail },
        },
      },
    ]
  },
})

FcDesigner.addDragRule({
  name: 'lookup',
  label: 'Lookup',
  icon: 'icon-select',
  menu: 'extend',
  mask: false,
  input: true,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  loadRule(rule: any) {
    rule.wrap = rule.wrap || {}
    rule.wrap.class = rule.wrap.class || 'fc-lookup-wrap'
  },
  rule() {
    return {
      type: 'lookup',
      field: 'lookup',
      title: 'Lookup',
      wrap: {
        class: 'fc-lookup-wrap'
      },
      props: { placeholder: 'Click to search', lookupConfig: '{}' }
    }
  },
  props() {
    return [
      { type: 'input', field: 'placeholder', title: 'Placeholder' },
      { type: 'switch', field: 'readonly', title: 'Readonly' },
      { type: 'LookupBindingSelect', field: 'lookupConfig', title: 'Lookup Config', props: {} },
    ]
  }
})

// Owner field (docs/design/owner-field-component.md): control type on an existing
// VARCHAR column. Source is CREATOR or CURRENT_ASSIGNEE. Multiple per table/form.
FcDesigner.addDragRule({
  name: 'owner',
  label: String(i18n.global.t('form.ownerTitle')),
  icon: 'icon-owner',
  menu: 'extend',
  mask: false,
  input: true,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  rule() {
    return {
      type: 'owner',
      field: 'owner',
      title: String(i18n.global.t('form.ownerTitle')),
      props: { ownerConfig: '{"source":"CREATOR"}' }
    }
  },
  props() {
    return [
      {
        type: 'FormControlTypeSelect',
        field: '_controlType',
        title: String(i18n.global.t('form.ownerControlType')),
        props: {}
      },
      {
        type: 'OwnerConfigEditor',
        field: 'ownerConfig',
        title: String(i18n.global.t('form.ownerSourceLabel')),
        props: {}
      }
    ]
  }
})

// RecordNote: rich-text comments + attachments panel scoped to the hosting form's table.
// Display-only (no data field) — the portal runtime resolves the target from form context.
FcDesigner.addDragRule({
  name: 'recordNote',
  label: 'Record Note',
  icon: 'icon-textarea',
  menu: 'extend',
  mask: true,
  input: false,
  drag: false,
  dragBtn: true,
  inside: false,
  only: false,
  handleBtn: true,
  languageKey: [],
  loadRule(rule: any) {
    rule.props = rule.props || {}
    if (rule.props.scope !== 'TABLE' && rule.props.scope !== 'RECORD') {
      rule.props.scope = 'TABLE'
    }
    // Notes are permanently immutable once written (nobody, not even the author or
    // SYS_ADMIN, may edit/delete them) — these switches no longer control anything
    // at runtime. Drop them from existing configJson so the panel doesn't show dead controls.
    // `readonly` is unrelated: it governs whether notes may be ADDED, not edited.
    delete rule.props.allowEditOwn
    delete rule.props.allowDelete
  },
  rule() {
    return {
      type: 'recordNote',
      title: 'Record Note',
      props: {
        // Whole table is the only scope valid on every host; Single Record is
        // opt-in and only selectable on sub-table forms (see RecordNoteScopeSelect).
        scope: 'TABLE',
        panelTitle: 'Notes',
        allowAttachment: true,
        maxFileSizeMb: 10,
        pageSize: 5,
        // On a To Do form the task's handler may comment by default; switch this on to make
        // the panel read-only there. Request-form notes are audit opinions and stay governed
        // by the function unit's audit roles regardless of this flag.
        readonly: false
      }
    }
  },
  props() {
    return [
      {
        type: 'RecordNoteScopeSelect',
        field: 'scope',
        title: 'Scope'
      },
      { type: 'input', field: 'panelTitle', title: 'Panel Title' },
      { type: 'switch', field: 'allowAttachment', title: 'Allow Attachments' },
      { type: 'inputNumber', field: 'maxFileSizeMb', title: 'Max File Size (MB)', props: { min: 1, max: 10 } },
      { type: 'inputNumber', field: 'pageSize', title: 'Visible Notes', props: { min: 1, max: 20 } },
      { type: 'switch', field: 'readonly', title: 'Readonly' }
    ]
  }
})

// Override global pseudo-element styles injected by form-create library
// form-create uses fc-icon font and .icon-xxx:before pseudo-elements
const overrideStyle = document.createElement('style')
overrideStyle.id = 'fc-font-override'
overrideStyle.textContent = `
  /* Force page elements to use system font, excluding form-create designer */
  html, body, #app, 
  .page-container, .page-container *:not([class*="_fd-"]):not([class*="fc-"]):not(.fc-icon),
  .card, .card *:not([class*="_fd-"]):not([class*="fc-"]):not(.fc-icon),
  .el-input, .el-input *,
  .el-input__inner, 
  .el-input__wrapper,
  .el-select, .el-select *,
  .el-select__placeholder,
  .el-form-item:not([class*="_fd-"]) *,
  .el-button, .el-tag, .el-menu-item,
  .filter-form, .filter-form *,
  .tag-filter-item, .tag-filter-item *,
  input, textarea, select, button {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif !important;
  }
  
  /* Force placeholder to use system font */
  ::placeholder,
  ::-webkit-input-placeholder,
  ::-moz-placeholder,
  :-ms-input-placeholder,
  .el-input__inner::placeholder,
  input::placeholder,
  textarea::placeholder {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif !important;
  }
  
  /* Icon library page - disable all pseudo-elements */
  .icon-grid::before,
  .icon-grid::after,
  .icon-grid .icon-item::before,
  .icon-grid .icon-item::after,
  .icon-grid .icon-preview::before,
  .icon-grid .icon-preview::after,
  .icon-grid .icon-name::before,
  .icon-grid .icon-name::after,
  .icon-selector__grid::before,
  .icon-selector__grid::after,
  .icon-selector__grid .icon-item::before,
  .icon-selector__grid .icon-item::after {
    content: none !important;
    display: none !important;
  }
  
  /* Ensure BPMN icon font works correctly */
  [class*="bpmn-icon"] {
    font-family: 'bpmn' !important;
  }
  
  /* Ensure form-create designer icon font works correctly - use higher priority selectors */
  .fc-icon,
  i.fc-icon,
  [class*="fc-icon"],
  [class^="_fd-"] i,
  [class*=" _fd-"] i,
  ._fd-drag-tool i,
  ._fd-m i,
  ._fd-menu i,
  ._fc-designer i,
  .fc-designer i,
  ._fd-drag-btn i,
  ._fd-side-l i,
  ._fd-side-r i,
  ._fd-tool i,
  ._fd-m-tool i,
  ._fd-m-drag i {
    font-family: 'fc-icon' !important;
  }
  
  /* Pseudo-elements of form-create icons also need to use fc-icon font */
  .fc-icon::before,
  i.fc-icon::before,
  [class*="fc-icon"]::before,
  [class^="_fd-"] i::before,
  [class*=" _fd-"] i::before {
    font-family: 'fc-icon' !important;
  }

  /* Lookup field: ensure label and input are horizontally aligned in designer canvas */
  .fc-lookup-wrap {
    .el-form-item {
      display: flex !important;
      align-items: flex-start !important;
      flex-direction: row !important;
      flex-wrap: nowrap !important;
    }
    .el-form-item__label {
      flex-shrink: 0 !important;
      width: auto !important;
      max-width: 200px !important;
      padding-top: 6px !important;
      padding-right: 12px !important;
      text-align: left !important;
      display: inline-flex !important;
      align-items: center !important;
    }
    .el-form-item__content {
      flex: 1 !important;
      min-width: 0 !important;
      margin-left: 0 !important;
    }
  }

  /* Lookup field label icon */
  .fc-lookup-wrap .el-form-item__label > .fc-form-title::before {
    content: '';
    display: inline-block;
    width: 14px;
    height: 14px;
    margin-right: 4px;
    vertical-align: middle;
    background-color: #409eff;
    -webkit-mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1024 1024'%3E%3Cpath d='M909.6 854.5L649.9 594.8C690.2 542.7 714 478.4 714 408c0-167.4-135.6-303-303-303S108 240.6 108 408s135.6 303 303 303c70.4 0 134.7-23.8 186.8-64.1l259.7 259.6c6.2 6.2 16.4 6.2 22.6 0l29.5-29.5c6.3-6.2 6.3-16.4 0-22.5zM411 680c-150.1 0-272-121.9-272-272s121.9-272 272-272 272 121.9 272 272-121.9 272-272 272z'/%3E%3C/svg%3E") no-repeat center / contain;
    mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1024 1024'%3E%3Cpath d='M909.6 854.5L649.9 594.8C690.2 542.7 714 478.4 714 408c0-167.4-135.6-303-303-303S108 240.6 108 408s135.6 303 303 303c70.4 0 134.7-23.8 186.8-64.1l259.7 259.6c6.2 6.2 16.4 6.2 22.6 0l29.5-29.5c6.3-6.2 6.3-16.4 0-22.5zM411 680c-150.1 0-272-121.9-272-272s121.9-272 272-272 272 121.9 272 272-121.9 272-272 272z'/%3E%3C/svg%3E") no-repeat center / contain;
  }

  /* Owner palette tile: person-head silhouette (fc-icon has no owner glyph). */
  .fc-icon.icon-owner,
  i.icon-owner {
    font-family: inherit !important;
  }
  .fc-icon.icon-owner::before,
  i.icon-owner::before {
    content: '' !important;
    font-family: inherit !important;
    display: inline-block !important;
    width: 1em;
    height: 1em;
    vertical-align: -0.125em;
    background-color: currentColor;
    -webkit-mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Ccircle cx='12' cy='8' r='4'/%3E%3Cpath d='M4 20.5C4 16.4 7.6 14 12 14s8 2.4 8 6.5V21H4z'/%3E%3C/svg%3E") no-repeat center / contain;
    mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Ccircle cx='12' cy='8' r='4'/%3E%3Cpath d='M4 20.5C4 16.4 7.6 14 12 14s8 2.4 8 6.5V21H4z'/%3E%3C/svg%3E") no-repeat center / contain;
  }
`
document.head.appendChild(overrideStyle)

// Add global error handler to catch unhandled Promise rejections
// These errors usually come from browser extensions and should not affect app operation
// Use capture mode to catch errors as early as possible
window.addEventListener('unhandledrejection', (event) => {
  // Check if the error comes from a browser extension (content.js)
  const error = event.reason
  
  // Use broader matching conditions to catch all possible browser extension errors
  if (error && typeof error === 'object') {
    // Check error characteristics to determine if it comes from a browser extension
    const isExtensionError = 
      // Network error (httpStatus: 0)
      error.httpStatus === 0 ||
      // Network error text
      error.httpStatusText === 'TypeError: Failed to fetch' ||
      // Typical browser extension error format: name: 'n', code: 0 or code: 403
      (error.name === 'n' && (error.code === 0 || error.code === 403)) ||
      // Error stack contains content.js
      (error.stack && typeof error.stack === 'string' && error.stack.includes('content.js')) ||
      // HTTP status is 200 but code field is 403 (browser extension false positive)
      (error.httpStatus === 200 && error.code === 403 && error.name === 'n') ||
      // Broader condition: name is 'n' and httpError is false (browser extension characteristic)
      (error.name === 'n' && error.httpError === false && error.httpStatus === 200) ||
      // Broadest condition: as long as name is 'n' and httpStatus is 200, treat as extension error
      (error.name === 'n' && error.httpStatus === 200)
    
    if (isExtensionError) {
      // Silently ignore, no log output
      event.preventDefault() // Prevent error from showing in console
      event.stopPropagation() // Stop event propagation
      event.stopImmediatePropagation() // Immediately stop propagation
      return false // Return false to indicate handled
    }
  }
  // Other unhandled errors — no-op in production
}, true) // Use capture mode

app.mount('#app')
