<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="500px"
  >
    <el-form
      :model="formData"
      label-width="auto"
      label-position="left"
    >
      <el-form-item
        :label="$t('task.comment')"
        :required="commentRequired"
        data-testid="approve-comment-field"
      >
        <el-input
          v-model="formData.comment"
          type="textarea"
          :rows="4"
          :placeholder="commentRequired ? $t('task.commentRequired') : $t('task.commentPlaceholder')"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">
        {{ $t('common.cancel') }}
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        @click="$emit('confirm')"
      >
        {{ $t('common.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{
  modelValue: boolean
  title: string
  formData: { comment: string }
  submitting: boolean
  commentRequired?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm'): void
}>()

const visible = ref(props.modelValue)

watch(() => props.modelValue, (val) => {
  visible.value = val
})

watch(visible, (val) => {
  emit('update:modelValue', val)
})
</script>
