<template>
  <div class="document-diff">
    <el-empty
      v-if="!changed"
      :description="t('functionUnit.documents.noChanges')"
      :image-size="60"
    />
    <pre
      v-else
      class="document-diff__body"
    ><div
      v-for="(line, i) in lines"
      :key="i"
      :class="['document-diff__line', `is-${line.kind}`]"
    ><span class="document-diff__sign">{{ SIGNS[line.kind] }}</span>{{ line.text }}</div></pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { diffLines } from 'diff'

const props = defineProps<{
  oldText: string
  newText: string
}>()

const { t } = useI18n()

type LineKind = 'added' | 'removed' | 'same'
const SIGNS: Record<LineKind, string> = { added: '+', removed: '-', same: ' ' }

const lines = computed(() =>
  diffLines(props.oldText, props.newText).flatMap(part => {
    const kind: LineKind = part.added ? 'added' : part.removed ? 'removed' : 'same'
    return part.value.replace(/\n$/, '').split('\n').map(text => ({ kind, text }))
  })
)

const changed = computed(() => lines.value.some(line => line.kind !== 'same'))
</script>

<style lang="scss" scoped>
.document-diff__body {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: auto;
}

.document-diff__line {
  padding: 0 8px;

  &.is-added {
    background: #f0f9eb;
    color: #2f6f1a;
  }

  &.is-removed {
    background: #fef0f0;
    color: #a93636;
    text-decoration: line-through;
  }
}

.document-diff__sign {
  display: inline-block;
  width: 16px;
  user-select: none;
  color: #909399;
}
</style>
