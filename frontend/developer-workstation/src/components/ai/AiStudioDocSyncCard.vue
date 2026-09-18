<template>
  <div
    class="doc-sync-card"
    :class="`is-${docSync.status.toLowerCase()}`"
  >
    <div class="doc-sync-card__title">
      <el-icon><Document /></el-icon>
      {{ t(`ai.studio.docSync.status.${docSync.status}`) }}
      <span class="doc-sync-card__trigger">{{ triggerLabel }}</span>
    </div>

    <template v-if="docSync.status === 'FAILED'">
      <div class="doc-sync-card__error">
        {{ formatDocSyncError(t, te, docSync.errorCode, docSync.errorMessage) }}
      </div>
      <el-button
        v-if="canModify"
        size="small"
        plain
        :disabled="syncing"
        @click="emit('retry')"
      >
        {{ t('ai.studio.docSync.retry') }}
      </el-button>
    </template>

    <template v-else>
      <div
        v-if="docSync.changeSummary"
        class="doc-sync-card__summary"
      >
        {{ docSync.changeSummary }}
      </div>
      <div
        v-for="row in rows"
        :key="row.type"
        class="doc-sync-card__row"
      >
        <span class="doc-sync-card__doc">{{ t(`functionUnit.documents.type.${row.type}`) }}</span>
        <template v-if="row.doc.blockedBy">
          <span class="doc-sync-card__blocked">
            {{ t('ai.studio.docSync.blocked', { name: row.doc.blockedBy }) }}
          </span>
          <el-button
            v-if="canModify"
            link
            type="primary"
            :disabled="syncing"
            @click="emit('retry')"
          >
            {{ t('ai.studio.docSync.recheck') }}
          </el-button>
        </template>
        <template v-else-if="row.doc.toVersion > row.doc.fromVersion">
          <span>{{ row.doc.fromLabel ?? '—' }} → {{ row.doc.toLabel }}</span>
          <el-button
            link
            type="primary"
            @click="openDiff(row.type, row.doc)"
          >
            {{ t('ai.studio.docSync.viewChanges') }}
          </el-button>
          <el-button
            v-if="canModify && row.doc.fromVersion > 0"
            link
            type="warning"
            :loading="restoring === row.type"
            @click="restorePrevious(row.type, row.doc)"
          >
            {{ t('ai.studio.docSync.restorePrevious') }}
          </el-button>
        </template>
        <span
          v-else
          class="doc-sync-card__muted"
        >{{ t('ai.studio.docSync.noChange') }}</span>
      </div>
      <el-button
        link
        type="primary"
        @click="emit('open-documents')"
      >
        {{ t('ai.studio.docSync.openDocuments') }}
      </el-button>
    </template>

    <DocumentVersionDiffDialog
      v-if="diff"
      v-model="diffVisible"
      :function-unit-id="functionUnitId"
      :type="diff.type"
      :from-version="diff.from"
      :to-version="diff.to"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document } from '@element-plus/icons-vue'
import DocumentVersionDiffDialog from '@/components/function-unit/DocumentVersionDiffDialog.vue'
import type { AiStudioDocSync, AiStudioDocSyncDocument } from '@/api/aiStudioThread'
import {
  functionUnitDocumentApi,
  isDocumentConflict,
  FUNCTION_UNIT_DOCUMENT_TYPES,
  type FunctionUnitDocumentType
} from '@/api/functionUnitDocument'
import { aiStudioPhaseLabel, type AiStudioPhase } from '@/utils/aiStudioDraft'
import { resolveUserFacingHttpMessage } from '@/utils/httpErrorMessage'
import { documentVersionLabel, formatDocSyncError } from '@/utils/functionUnitDocumentSource'

const props = defineProps<{
  docSync: AiStudioDocSync
  functionUnitId: number
  canModify: boolean
  /** 有同步作业在跑时不允许再发起 */
  syncing: boolean
}>()

const emit = defineEmits<{
  retry: []
  'open-documents': []
  /** 恢复上一版成功，文档抽屉需要刷新 */
  restored: []
}>()

const { t, te } = useI18n()

const rows = computed(() => FUNCTION_UNIT_DOCUMENT_TYPES
  .map(type => ({ type, doc: props.docSync.documents?.[type] }))
  .filter((row): row is { type: FunctionUnitDocumentType; doc: AiStudioDocSyncDocument } => !!row.doc))

const triggerLabel = computed(() => props.docSync.phases.length
  ? props.docSync.phases.map(p => aiStudioPhaseLabel(t, p as AiStudioPhase)).join(', ')
  : t('ai.studio.docSync.fullCheck'))

const diff = ref<{ type: FunctionUnitDocumentType; from: number; to: number } | null>(null)
const diffVisible = ref(false)
const restoring = ref<FunctionUnitDocumentType | null>(null)

function openDiff(type: FunctionUnitDocumentType, doc: AiStudioDocSyncDocument) {
  diff.value = { type, from: doc.fromVersion, to: doc.toVersion }
  diffVisible.value = true
}

async function restorePrevious(type: FunctionUnitDocumentType, doc: AiStudioDocSyncDocument) {
  try {
    await ElMessageBox.confirm(
      t('ai.studio.docSync.restoreConfirm', {
        doc: t(`functionUnit.documents.type.${type}`), from: doc.fromLabel ?? `v${doc.fromVersion}`
      }),
      t('ai.studio.docSync.restorePrevious'),
      { type: 'warning', confirmButtonText: t('functionUnit.documents.restore') }
    )
  } catch {
    return // 用户取消
  }
  restoring.value = type
  try {
    // 以这次 AI 写入的版本为基准：之后若有人又改过，后端回 409，不会把别人的修改一起冲掉
    const res = await functionUnitDocumentApi.restore(props.functionUnitId, type, doc.fromVersion, doc.toVersion)
    ElMessage.success(t('functionUnit.documents.restored',
      { from: doc.fromLabel ?? `v${doc.fromVersion}`, to: documentVersionLabel(res.data) }))
    emit('restored')
  } catch (e) {
    ElMessage.error(isDocumentConflict(e)
      ? t('ai.studio.docSync.restoreConflict')
      : resolveUserFacingHttpMessage(e, t))
  } finally {
    restoring.value = null
  }
}
</script>

<style lang="scss" scoped>
.doc-sync-card {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid #dcdfe6;
  border-left: 3px solid #409eff;
  border-radius: 6px;
  background: #fff;
  font-size: 12px;

  &.is-failed {
    border-left-color: #f56c6c;
  }

  &.is-skipped {
    border-left-color: #e6a23c;
  }

  &.is-unchanged {
    border-left-color: #c0c4cc;
  }
}

.doc-sync-card__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
}

.doc-sync-card__trigger {
  margin-left: auto;
  color: #909399;
  font-weight: normal;
  font-size: 12px;
}

.doc-sync-card__summary,
.doc-sync-card__error {
  margin: 6px 0;
  color: #606266;
  word-break: break-word;
}

.doc-sync-card__error {
  color: #c45656;
}

.doc-sync-card__row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  column-gap: 12px;
  row-gap: 2px;
  padding: 2px 0;

  .el-button + .el-button {
    margin-left: 0;
  }
}

.doc-sync-card__doc {
  flex-basis: 100%;
  font-weight: 600;
  color: #303133;
}

.doc-sync-card__blocked {
  color: #b88230;
}

.doc-sync-card__muted {
  color: #909399;
}
</style>
