<template>
  <div class="relation-diagram-editor">
    <div class="diagram-toolbar">
      <el-alert
        type="info"
        :closable="false"
        class="diagram-hint"
      >
        {{ t('erDiagram.diagramHint') }}
      </el-alert>
      <div class="diagram-actions">
        <el-button
          size="small"
          @click="expandAll"
        >
          {{ t('erDiagram.expandAll') }}
        </el-button>
        <el-button
          size="small"
          @click="collapseAll"
        >
          {{ t('erDiagram.collapseAll') }}
        </el-button>
        <el-button
          size="small"
          @click="relayout"
        >
          {{ t('erDiagram.autoLayout') }}
        </el-button>
      </div>
    </div>

    <div
      v-if="!tables.length"
      class="diagram-empty"
    >
      {{ t('erDiagram.empty') }}
    </div>

    <div
      v-else
      class="diagram-canvas"
    >
      <VueFlow
        :nodes="nodes"
        :edges="edges"
        :node-types="{}"
        :default-viewport="{ zoom: 0.9 }"
        :min-zoom="0.2"
        :max-zoom="2"
        :connection-mode="ConnectionMode.Loose"
        :default-edge-options="defaultEdgeOptions"
        :is-valid-connection="isValidConnection"
        fit-view-on-init
        @connect="handleConnect"
        @edge-click="handleEdgeClick"
        @node-drag-stop="handleNodeDragStop"
      >
        <Background
          :gap="20"
          pattern-color="#dcdfe6"
        />
        <Controls :show-interactive="false" />

        <template #node-erTable="nodeProps">
          <div
            class="er-node"
            :class="{ 'is-collapsed': !nodeProps.data.expanded }"
          >
            <!--
              The header is both the expand/collapse control and the card's
              drag grip. It must NOT carry `nodrag`: VueFlow's drag filter
              walks up from the pressed element to the node root, so a
              `nodrag` header would exclude the whole card from dragging (a
              collapsed card is almost entirely header). Press-vs-click is
              disambiguated by movement instead — see `onHeaderPointerDown`.
            -->
            <div
              class="er-node-header"
              @pointerdown="onHeaderPointerDown"
              @click.stop="onHeaderClick($event, nodeProps.id)"
            >
              <div class="er-node-heading">
                <span class="er-node-title">{{ nodeProps.data.displayName }}</span>
                <span class="er-node-subtitle">{{ nodeProps.data.tableName }}</span>
              </div>
              <el-icon
                v-if="nodeProps.data.hiddenCount || nodeProps.data.expanded"
                class="er-node-caret"
                :class="{ 'is-open': nodeProps.data.expanded }"
              >
                <ArrowRight />
              </el-icon>
            </div>
            <div class="er-node-body">
              <div
                v-for="field in nodeProps.data.visibleFields"
                :key="field.fieldName"
                class="er-field-row"
                :class="{ 'is-pk': field.isPrimaryKey, 'is-fk': field.isForeignKey }"
              >
                <!--
                  Each side carries a source AND a target handle sharing one id
                  and one spot: an edge end resolves to the handle matching its
                  role, so both ends anchor on the card edge facing the other
                  card instead of falling back to the node's default side.
                -->
                <Handle
                  :id="`${field.fieldName}::l`"
                  type="target"
                  :position="Position.Left"
                  class="er-handle"
                  :class="{ 'er-handle-pk': field.isPrimaryKey }"
                />
                <Handle
                  :id="`${field.fieldName}::l`"
                  type="source"
                  :position="Position.Left"
                  class="er-handle er-handle-overlay"
                  :class="{ 'er-handle-pk': field.isPrimaryKey }"
                />
                <span class="er-field-badges">
                  <el-tag
                    v-if="field.isPrimaryKey"
                    size="small"
                    type="warning"
                    effect="dark"
                    disable-transitions
                  >{{ t('erDiagram.legendPk') }}</el-tag>
                  <el-tag
                    v-if="field.isForeignKey"
                    size="small"
                    type="success"
                    disable-transitions
                  >{{ t('erDiagram.legendFk') }}</el-tag>
                </span>
                <span class="er-field-name">{{ field.displayName || field.fieldName }}</span>
                <span class="er-field-type">{{ field.dataType }}</span>
                <Handle
                  :id="`${field.fieldName}::r`"
                  type="target"
                  :position="Position.Right"
                  class="er-handle"
                  :class="{ 'er-handle-pk': field.isPrimaryKey }"
                />
                <Handle
                  :id="`${field.fieldName}::r`"
                  type="source"
                  :position="Position.Right"
                  class="er-handle er-handle-overlay"
                  :class="{ 'er-handle-pk': field.isPrimaryKey }"
                />
              </div>
              <!-- A real control, not a grip: `nodrag` keeps a press here from moving the card. -->
              <button
                v-if="nodeProps.data.hiddenCount"
                type="button"
                class="er-more-row nodrag"
                @click.stop="toggleExpanded(nodeProps.id)"
              >
                {{ t('erDiagram.moreFields', { count: nodeProps.data.hiddenCount }) }}
              </button>
            </div>
          </div>
        </template>
      </VueFlow>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import {
  VueFlow,
  Handle,
  Position,
  MarkerType,
  ConnectionMode,
  useVueFlow,
  type Connection,
  type Edge,
  type Node,
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import type { RelationTableResponse, FieldDefinitionResponse } from '@/api/relationTable'
import {
  layoutRelationDiagram,
  chooseHandleSides,
  visibleFieldsForCard,
  type LayoutEdgeInput,
} from '@platform-shared/relationDiagramLayout'

import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

/** Relation row shape shared with the host page. */
export interface RelationRow {
  sourceTableId: number | null
  sourceFieldName: string
  relationType: string
  targetTableId: number | null
  targetFieldName: string
}

interface DiagramField {
  fieldName: string
  displayName?: string
  dataType?: string
  isPrimaryKey: boolean
  isForeignKey: boolean
}

const props = defineProps<{
  tables: RelationTableResponse[]
  modelValue: RelationRow[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: RelationRow[]): void
}>()

const { t } = useI18n()
const { fitView } = useVueFlow()

const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])

// Positions the user dragged; auto-layout fills in the rest.
const positionMap = ref<Record<string, { x: number; y: number }>>({})
// Cards start collapsed to keys only; ids here are the ones the user opened.
const expandedIds = ref<Set<string>>(new Set())

const NODE_WIDTH = 260
const COLUMN_GAP = 200
const ROW_GAP = 60
const HEADER_HEIGHT = 46
const FIELD_ROW_HEIGHT = 26
const MORE_ROW_HEIGHT = 24

function tableFields(table: RelationTableResponse): DiagramField[] {
  return (table.fieldDefinitions || []).map((f: FieldDefinitionResponse) => ({
    fieldName: f.fieldName,
    displayName: f.displayName,
    dataType: f.dataType,
    isPrimaryKey: !!f.isPrimaryKey,
    isForeignKey: !!f.isForeignKey,
  }))
}

function visibleFieldsFor(fields: DiagramField[], expanded: boolean): DiagramField[] {
  return visibleFieldsForCard(fields, expanded)
}

function nodeHeight(visibleCount: number, hiddenCount: number): number {
  return (
    HEADER_HEIGHT + visibleCount * FIELD_ROW_HEIGHT + (hiddenCount ? MORE_ROW_HEIGHT : 0)
  )
}

/**
 * Distance from a card's top to the middle of the row a relation attaches to.
 * The layout aligns on these so lines come out horizontal rather than stepped.
 */
function anchorOffset(tableId: number | null, fieldName: string): number | undefined {
  if (tableId == null) return undefined
  const table = props.tables.find(tb => tb.id === tableId)
  if (!table) return undefined
  const visible = visibleFieldsFor(tableFields(table), expandedIds.value.has(String(tableId)))
  const index = visible.findIndex(f => f.fieldName === fieldName)
  if (index < 0) return undefined
  return HEADER_HEIGHT + index * FIELD_ROW_HEIGHT + FIELD_ROW_HEIGHT / 2
}

/** Relations reduced to the table-level graph the layout ranks columns from. */
function layoutEdgeInputs(): LayoutEdgeInput[] {
  return props.modelValue
    .filter(r => r.sourceTableId != null && r.targetTableId != null)
    .map(r => ({
      source: String(r.sourceTableId),
      target: String(r.targetTableId),
      sourceAnchorOffset: anchorOffset(r.sourceTableId, r.sourceFieldName),
      targetAnchorOffset: anchorOffset(r.targetTableId, r.targetFieldName),
    }))
}

function buildNodes() {
  const built = props.tables.map(table => {
    const id = String(table.id)
    const expanded = expandedIds.value.has(id)
    const fields = tableFields(table)
    const visibleFields = visibleFieldsFor(fields, expanded)
    const hiddenCount = fields.length - visibleFields.length
    return {
      id,
      type: 'erTable',
      position: { x: 0, y: 0 },
      data: {
        tableName: table.tableName,
        displayName: table.displayName || table.tableName,
        expanded,
        visibleFields,
        hiddenCount,
      },
      style: { width: `${NODE_WIDTH}px` },
      height: nodeHeight(visibleFields.length, hiddenCount),
    }
  })

  const { positions } = layoutRelationDiagram(
    built.map(n => ({ id: n.id, height: n.height })),
    layoutEdgeInputs(),
    { nodeWidth: NODE_WIDTH, columnGap: COLUMN_GAP, rowGap: ROW_GAP },
  )

  nodes.value = built.map(n => {
    // A card the user moved keeps its spot; everything else follows the layout.
    const position = positionMap.value[n.id] || positions[n.id] || { x: 0, y: 0 }
    return { ...n, position } as Node
  })
}

function relationTypeShortLabel(relationType: string): string {
  switch (relationType) {
    case 'ONE_TO_ONE':
      return '1:1'
    case 'MANY_TO_MANY':
      return 'N:N'
    default:
      return '1:N'
  }
}

function fieldExists(tableId: number | null, fieldName: string): boolean {
  if (tableId == null) return false
  const table = props.tables.find(tb => tb.id === tableId)
  return !!table?.fieldDefinitions?.some(f => f.fieldName === fieldName)
}

const defaultEdgeOptions = {
  type: 'smoothstep',
  markerEnd: MarkerType.ArrowClosed,
}

/** Each field exposes two anchors: `<fieldName>::l` and `<fieldName>::r`. */
function handleId(fieldName: string, side: 'l' | 'r'): string {
  return `${fieldName}::${side}`
}

function fieldFromHandle(handle: string | null | undefined): string {
  if (!handle) return ''
  const idx = handle.lastIndexOf('::')
  return idx >= 0 ? handle.slice(0, idx) : handle
}

function nodeX(tableId: number | null): number {
  if (tableId == null) return 0
  return nodes.value.find(n => n.id === String(tableId))?.position.x ?? 0
}

/**
 * Whether the row a relation anchors on is currently rendered.
 *
 * Relations are derived from FK/PK field metadata, and a collapsed card keeps
 * exactly those keys, so this holds for every relation. It is asserted rather
 * than assumed: an unanchored line would be drawn from an arbitrary row, and
 * silently hiding it would look like the relation had been lost.
 */
function anchorRowIsVisible(tableId: number | null, fieldName: string): boolean {
  if (tableId == null) return false
  const table = props.tables.find(tb => tb.id === tableId)
  if (!table) return false
  const visible = visibleFieldsFor(tableFields(table), expandedIds.value.has(String(tableId)))
  return visible.some(f => f.fieldName === fieldName)
}

function buildEdges() {
  edges.value = props.modelValue
    .filter(
      r =>
        r.sourceTableId != null &&
        r.targetTableId != null &&
        r.sourceFieldName &&
        r.targetFieldName &&
        fieldExists(r.sourceTableId, r.sourceFieldName) &&
        fieldExists(r.targetTableId, r.targetFieldName),
    )
    .map(r => {
      const sides = chooseHandleSides(nodeX(r.sourceTableId), nodeX(r.targetTableId))
      if (
        !anchorRowIsVisible(r.sourceTableId, r.sourceFieldName) ||
        !anchorRowIsVisible(r.targetTableId, r.targetFieldName)
      ) {
        // Broken invariant: surface it instead of dropping the line in silence.
        console.warn(
          '[RelationDiagram] relation has no visible anchor row and was not drawn',
          r,
        )
        return null
      }
      return {
        id: `rel-${r.sourceTableId}-${r.sourceFieldName}`,
        source: String(r.sourceTableId),
        sourceHandle: handleId(r.sourceFieldName, sides.source),
        target: String(r.targetTableId),
        targetHandle: handleId(r.targetFieldName, sides.target),
        // Route out of / into the card edge that faces the other card, so a
        // line never doubles back across the card it belongs to.
        sourcePosition: sides.source === 'r' ? Position.Right : Position.Left,
        targetPosition: sides.target === 'l' ? Position.Left : Position.Right,
        type: 'smoothstep',
        label: relationTypeShortLabel(r.relationType),
        markerEnd: MarkerType.ArrowClosed,
        data: { relation: r },
        style: { strokeWidth: 1.5, stroke: '#409eff' },
        labelBgStyle: { fill: '#ecf5ff' },
      } as Edge
    })
    .filter((e): e is Edge => e !== null)
}

function isPkField(tableId: number, fieldName: string): boolean {
  const table = props.tables.find(tb => tb.id === tableId)
  return !!table?.fieldDefinitions?.some(f => f.fieldName === fieldName && f.isPrimaryKey)
}

function isValidConnection(connection: Connection): boolean {
  if (connection.source === connection.target) return false
  // A foreign key must reference a primary key on one of the two endpoints.
  const aTable = Number(connection.source)
  const bTable = Number(connection.target)
  const aField = fieldFromHandle(connection.sourceHandle)
  const bField = fieldFromHandle(connection.targetHandle)
  return isPkField(aTable, aField) || isPkField(bTable, bField)
}

function handleConnect(connection: Connection) {
  const aTableId = Number(connection.source)
  const bTableId = Number(connection.target)
  const aField = fieldFromHandle(connection.sourceHandle)
  const bField = fieldFromHandle(connection.targetHandle)

  if (!aField || !bField) return
  if (aTableId === bTableId) {
    ElMessage.warning(t('erDiagram.selfRefError'))
    return
  }

  // Orient FK -> PK by primary-key membership, regardless of drag direction (loose mode).
  let sourceTableId: number
  let sourceFieldName: string
  let targetTableId: number
  let targetFieldName: string
  if (isPkField(bTableId, bField)) {
    sourceTableId = aTableId
    sourceFieldName = aField
    targetTableId = bTableId
    targetFieldName = bField
  } else if (isPkField(aTableId, aField)) {
    sourceTableId = bTableId
    sourceFieldName = bField
    targetTableId = aTableId
    targetFieldName = aField
  } else {
    ElMessage.warning(t('erDiagram.targetNotPkError'))
    return
  }

  // One FK field references exactly one target: replace any existing relation on this source field.
  const existing = props.modelValue.find(
    r => r.sourceTableId === sourceTableId && r.sourceFieldName === sourceFieldName,
  )
  const next = props.modelValue.filter(
    r => !(r.sourceTableId === sourceTableId && r.sourceFieldName === sourceFieldName),
  )
  next.push({
    sourceTableId,
    sourceFieldName,
    targetTableId,
    targetFieldName,
    relationType: existing?.relationType || 'ONE_TO_MANY',
  })
  emit('update:modelValue', next)
}

async function handleEdgeClick({ edge }: { edge: Edge }) {
  const relation = (edge.data as { relation?: RelationRow } | undefined)?.relation
  if (!relation) return
  try {
    await ElMessageBox.confirm(
      t('erDiagram.deleteConfirm', { field: relation.sourceFieldName }),
      t('erDiagram.title'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  const next = props.modelValue.filter(
    r =>
      !(
        r.sourceTableId === relation.sourceTableId &&
        r.sourceFieldName === relation.sourceFieldName &&
        r.targetTableId === relation.targetTableId &&
        r.targetFieldName === relation.targetFieldName
      ),
  )
  emit('update:modelValue', next)
}

function handleNodeDragStop({ node }: { node: Node }) {
  positionMap.value[node.id] = { x: node.position.x, y: node.position.y }
  // Re-pick closest sides now that relative positions changed.
  buildEdges()
}

/**
 * Where the pointer went down on a card header, so a release can be judged a
 * click (expand/collapse) or the tail of a drag (move the card, change nothing).
 */
let headerPressAt: { x: number; y: number } | null = null

/** Movement under this many px still counts as a click, not a drag. */
const CLICK_SLOP_PX = 4

function onHeaderPointerDown(event: PointerEvent) {
  headerPressAt = { x: event.clientX, y: event.clientY }
}

/**
 * The header doubles as the drag grip, so a mouse-up after dragging a card also
 * fires `click`. Toggle only when the pointer effectively stayed put; otherwise
 * every reposition would expand or collapse the card the user just moved.
 */
function onHeaderClick(event: MouseEvent, nodeId: string) {
  const pressed = headerPressAt
  headerPressAt = null
  if (pressed) {
    const moved = Math.hypot(event.clientX - pressed.x, event.clientY - pressed.y)
    if (moved > CLICK_SLOP_PX) return
  }
  toggleExpanded(nodeId)
}

function toggleExpanded(nodeId: string) {
  const next = new Set(expandedIds.value)
  if (next.has(nodeId)) next.delete(nodeId)
  else next.add(nodeId)
  expandedIds.value = next
  // Card height changed, so re-run the layout for any card the user hasn't moved.
  buildNodes()
  buildEdges()
}

function expandAll() {
  expandedIds.value = new Set(props.tables.map(tb => String(tb.id)))
  buildNodes()
  buildEdges()
}

function collapseAll() {
  expandedIds.value = new Set()
  buildNodes()
  buildEdges()
}

/** Drop manual placements and re-run the card-free-gutter layout. */
function relayout() {
  positionMap.value = {}
  buildNodes()
  buildEdges()
  nextTick(() => fitView({ padding: 0.2 }))
}

watch(
  () => props.tables,
  () => {
    buildNodes()
    buildEdges()
    nextTick(() => fitView({ padding: 0.2 }))
  },
  { immediate: true, deep: true },
)

watch(
  () => props.modelValue,
  () => {
    buildNodes()
    buildEdges()
  },
  { deep: true },
)
</script>

<style lang="scss" scoped>
.relation-diagram-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.diagram-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.diagram-hint {
  flex: 1;
  min-width: 0;
  margin-bottom: 0;
}

.diagram-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.diagram-empty {
  height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  background: #f5f7fa;
  border-radius: 4px;
}

.diagram-canvas {
  position: relative;
  // `.vue-flow` is `height: 100%`, which only resolves against a DEFINITE
  // parent height. A flex-grown box is not definite for that purpose: the
  // canvas would look right while VueFlow's pane collapsed to 0px, leaving
  // `fitView` nothing to fit and the cards painted off-canvas. Keep an
  // explicit height (matching Developer Workstation).
  height: 560px;
  width: 100%;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fafafa;
  overflow: hidden;

  // Relation lines must stay readable over the cards they run between.
  // VueFlow writes node z-index inline, so both sides need !important to win.
  :deep(.vue-flow__edges),
  :deep(.vue-flow__edge) {
    z-index: 10 !important;
  }

  :deep(.vue-flow__node) {
    z-index: 1 !important;
  }
}

.er-node {
  border: 1px solid #c0c4cc;
  border-radius: 6px;
  background: #ffffff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  overflow: hidden;
  font-size: 12px;
}

.er-node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  background: #409eff;
  color: #ffffff;
  // The header both toggles the card and drags it, so advertise the move affordance.
  cursor: grab;
  user-select: none;

  &:active {
    cursor: grabbing;
  }
}

.er-node-heading {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.er-node-title {
  font-weight: 600;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.er-node-subtitle {
  font-size: 11px;
  opacity: 0.85;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.er-node-caret {
  flex-shrink: 0;
  transition: transform 0.15s;

  &.is-open {
    transform: rotate(90deg);
  }
}

.er-node-body {
  display: flex;
  flex-direction: column;
}

.er-field-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 12px;
  border-top: 1px solid #f0f2f5;
  white-space: nowrap;

  &.is-pk {
    background: #fdf6ec;
  }
}

.er-more-row {
  height: 24px;
  padding: 0 12px;
  border: none;
  border-top: 1px solid #f0f2f5;
  background: #f8f9fb;
  color: #909399;
  font-size: 11px;
  text-align: center;
  cursor: pointer;

  &:hover {
    color: #409eff;
    background: #ecf5ff;
  }
}

.er-field-badges {
  display: inline-flex;
  gap: 2px;

  :deep(.el-tag) {
    height: 16px;
    padding: 0 4px;
    line-height: 16px;
  }
}

.er-field-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #303133;
}

.er-field-type {
  color: #909399;
  font-size: 11px;
}

.er-handle {
  width: 7px;
  height: 7px;
  background: #c0c4cc;
  border: 1px solid #ffffff;
  opacity: 0.55;
  transition: opacity 0.15s, background 0.15s, transform 0.15s;
}

.er-handle-pk {
  background: #e6a23c;
}

// The source twin sits exactly on its target twin; only one dot should show.
.er-handle-overlay {
  background: transparent;
  border-color: transparent;
}

.er-field-row:hover .er-handle {
  opacity: 1;
  transform: scale(1.25);
}

.er-handle:hover {
  opacity: 1;
  background: #409eff;
  transform: scale(1.4);
}
</style>
