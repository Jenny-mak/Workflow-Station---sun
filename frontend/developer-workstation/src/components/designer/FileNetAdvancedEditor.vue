<template>
  <div
    class="fn-adv"
    data-testid="upload-filenet-advance"
  >
    <div class="fn-row">
      <el-switch
        :model-value="local.enabled"
        @update:model-value="onEnabled"
      />
      <DesignerHelpLink
        path="/form-upload#advance"
        :aria-label="t('form.fileNet.guideLinkAria')"
        test-id="upload-filenet-guide-link"
      />
    </div>
    <div class="fn-hint">{{ t('form.fileNet.advanceHint') }}</div>

    <template v-if="local.enabled">
      <section class="fn-section">
        <div class="fn-section-title">{{ t('form.fileNet.headerInfo') }}</div>
        <div
          v-for="(row, idx) in local.headerInfo"
          :key="'h' + idx"
          class="fn-pair"
        >
          <el-input
            :model-value="row.name"
            :placeholder="t('form.fileNet.headerName')"
            @update:model-value="(v: string) => patchHeader(idx, { name: v })"
          />
          <el-input
            :model-value="row.value"
            :placeholder="t('form.fileNet.headerValue')"
            @update:model-value="(v: string) => patchHeader(idx, { value: v })"
          />
          <el-button
            link
            type="danger"
            @click="removeHeader(idx)"
          >
            {{ t('form.fileNet.remove') }}
          </el-button>
        </div>
        <el-button
          size="small"
          @click="addHeader"
        >
          {{ t('form.fileNet.addHeader') }}
        </el-button>
      </section>

      <section class="fn-section">
        <div class="fn-section-title">{{ t('form.fileNet.repositoryDetail') }}</div>
        <el-input
          :model-value="local.repositoryDetail.repositoryID"
          :placeholder="t('form.fileNet.repositoryId')"
          @update:model-value="(v: string) => patchRepo({ repositoryID: v })"
        />
        <el-input
          :model-value="local.repositoryDetail.documentClass"
          :placeholder="t('form.fileNet.documentClass')"
          @update:model-value="(v: string) => patchRepo({ documentClass: v })"
        />
        <el-input
          :model-value="local.repositoryDetail.objectStoreName"
          :placeholder="t('form.fileNet.objectStoreName')"
          @update:model-value="(v: string) => patchRepo({ objectStoreName: v })"
        />
      </section>

      <section class="fn-section">
        <div class="fn-section-title">{{ t('form.fileNet.docProperty') }}</div>
        <div
          v-for="(row, idx) in local.docProperty"
          :key="'p' + idx"
          class="fn-doc"
        >
          <el-input
            :model-value="row.propertyName"
            :placeholder="t('form.fileNet.propertyName')"
            @update:model-value="(v: string) => patchDoc(idx, { propertyName: v })"
          />
          <el-select
            :model-value="row.dataType"
            @update:model-value="(v: 'String' | 'DateTime') => patchDoc(idx, { dataType: v })"
          >
            <el-option
              label="String"
              value="String"
            />
            <el-option
              label="DateTime"
              value="DateTime"
            />
          </el-select>
          <el-select
            :model-value="row.source"
            @update:model-value="(v: 'static' | 'formField') => patchDoc(idx, { source: v })"
          >
            <el-option
              :label="t('form.fileNet.sourceStatic')"
              value="static"
            />
            <el-option
              :label="t('form.fileNet.sourceFormField')"
              value="formField"
            />
          </el-select>
          <el-input
            v-if="row.source !== 'formField'"
            :model-value="row.value"
            :placeholder="t('form.fileNet.staticValue')"
            @update:model-value="(v: string) => patchDoc(idx, { value: v })"
          />
          <el-input
            v-else
            :model-value="row.fieldKey"
            :placeholder="t('form.fileNet.fieldKey')"
            @update:model-value="(v: string) => patchDoc(idx, { fieldKey: v })"
          />
          <el-button
            link
            type="danger"
            @click="removeDoc(idx)"
          >
            {{ t('form.fileNet.remove') }}
          </el-button>
        </div>
        <el-button
          size="small"
          @click="addDoc"
        >
          {{ t('form.fileNet.addProperty') }}
        </el-button>
      </section>

      <section class="fn-section">
        <div class="fn-section-title">{{ t('form.fileNet.searchDetail') }}</div>
        <div class="fn-hint">{{ t('form.fileNet.skeletonHint') }}</div>
      </section>
      <section class="fn-section">
        <div class="fn-section-title">{{ t('form.fileNet.retrieveRequest') }}</div>
        <div class="fn-hint">{{ t('form.fileNet.skeletonHint') }}</div>
      </section>
      <section class="fn-section">
        <div class="fn-section-title">{{ t('form.fileNet.orderBy') }}</div>
        <div class="fn-hint">{{ t('form.fileNet.skeletonHint') }}</div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import DesignerHelpLink from '@/components/designer/DesignerHelpLink.vue'
import { formControlTypeStore } from './formControlTypeStore'
import {
  normalizeFileNetConfig,
  type FileNetConfig,
  type FileNetDocProperty,
  type FileNetHeaderItem,
} from '@platform-shared/upload/fileNetConfig'

const props = defineProps<{ modelValue?: FileNetConfig | Record<string, unknown> | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: FileNetConfig] }>()
const { t } = useI18n()

const local = computed(() => normalizeFileNetConfig(props.modelValue))

function commit(next: FileNetConfig): void {
  emit('update:modelValue', next)
  const rule = formControlTypeStore.activeRule
  if (!rule || rule.type !== 'advancedUpload') return
  const nextProps = (rule.props && typeof rule.props === 'object')
    ? rule.props as Record<string, unknown>
    : {}
  rule.props = nextProps
  nextProps.fileNet = next
}

function onEnabled(enabled: boolean): void {
  commit({ ...local.value, enabled })
}

function patchHeader(idx: number, partial: Partial<FileNetHeaderItem>): void {
  const headerInfo = local.value.headerInfo.map((row, i) => (i === idx ? { ...row, ...partial } : row))
  commit({ ...local.value, headerInfo })
}

function addHeader(): void {
  commit({ ...local.value, headerInfo: [...local.value.headerInfo, { name: '', value: '' }] })
}

function removeHeader(idx: number): void {
  commit({ ...local.value, headerInfo: local.value.headerInfo.filter((_, i) => i !== idx) })
}

function patchRepo(partial: Partial<FileNetConfig['repositoryDetail']>): void {
  commit({
    ...local.value,
    repositoryDetail: { ...local.value.repositoryDetail, ...partial },
  })
}

function patchDoc(idx: number, partial: Partial<FileNetDocProperty>): void {
  const docProperty = local.value.docProperty.map((row, i) => (i === idx ? { ...row, ...partial } : row))
  commit({ ...local.value, docProperty })
}

function addDoc(): void {
  commit({
    ...local.value,
    docProperty: [
      ...local.value.docProperty,
      { propertyName: '', dataType: 'String', source: 'static', value: '' },
    ],
  })
}

function removeDoc(idx: number): void {
  commit({ ...local.value, docProperty: local.value.docProperty.filter((_, i) => i !== idx) })
}
</script>

<style scoped>
.fn-adv {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.fn-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fn-section-title {
  font-size: 12px;
  color: #606266;
}
.fn-section-title {
  font-weight: 600;
}
.fn-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
}
.fn-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.fn-pair,
.fn-doc {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
