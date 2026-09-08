<template>
  <el-dialog
    v-model="visible"
    :title="t('task.reassign')"
    width="480px"
    class="task-reassign-dialog"
    destroy-on-close
    @open="loadCandidates"
  >
    <template #header>
      <div class="task-reassign-dialog__title">
        <span class="el-dialog__title">{{ t('task.reassign') }}</span>
        <PortalHelpLink
          path="/up-tasks-to-claim#leader"
          :ariaLabel="t('task.todoGuideLinkAria')"
          test-id="task-reassign-guide-link"
        />
      </div>
    </template>
    <el-form
      label-width="auto"
      label-position="left"
    >
      <el-form-item :label="t('task.reassignTo')">
        <el-select
          v-model="targetUserId"
          filterable
          :placeholder="t('task.selectUser')"
          :loading="loading"
          :teleported="false"
          style="width: 100%;"
          data-test="task-reassign-select"
        >
          <el-option
            v-for="user in candidates"
            :key="user.id"
            :label="user.name"
            :value="user.id"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <p
      v-if="!loading && candidates.length === 0"
      class="task-reassign-dialog__empty"
    >
      {{ t('task.reassignNoCandidates') }}
    </p>
    <template #footer>
      <el-button @click="visible = false">
        {{ t('common.cancel') }}
      </el-button>
      <el-button
        type="primary"
        data-test="task-reassign-confirm"
        :disabled="!targetUserId"
        :loading="submitting"
        @click="confirm"
      >
        {{ t('task.reassign') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import PortalHelpLink from '@/components/PortalHelpLink.vue'
import { userApi, type UserOption } from '@/api/user'

const props = defineProps<{
  modelValue: boolean
  candidateUserIds: string[]
  currentHolderId?: string
  submitting: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [targetUserId: string]
}>()

const { t } = useI18n()
const loading = ref(false)
const targetUserId = ref('')
const candidates = ref<UserOption[]>([])

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

async function loadCandidates(): Promise<void> {
  targetUserId.value = ''
  loading.value = true
  const holder = (props.currentHolderId || '').trim()
  const ids = [...new Set(props.candidateUserIds.filter((id) => id && id.trim() && id.trim() !== holder))]
  try {
    const resolved = await Promise.all(ids.map((id) => userApi.getUserSummary(id)))
    candidates.value = ids.map((id, index) => {
      const user = resolved[index]
      return user ?? { id, name: id, username: id }
    })
  } finally {
    loading.value = false
  }
}

function confirm(): void {
  if (!targetUserId.value) {
    return
  }
  emit('confirm', targetUserId.value)
}
</script>

<style scoped>
.task-reassign-dialog__title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-reassign-dialog__empty {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
