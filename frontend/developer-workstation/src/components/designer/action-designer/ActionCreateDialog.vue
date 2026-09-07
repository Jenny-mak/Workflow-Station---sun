<template>
  <el-dialog
    :model-value="modelValue"
    :title="$t('action.createActionTitle')"
    width="500px"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-form
      :model="createForm"
      label-width="auto"
      label-position="left"
    >
      <el-form-item
        :label="$t('action.actionName')"
        required
      >
        <el-input v-model="createForm.actionName" />
      </el-form-item>
      <el-form-item :label="$t('action.actionType')">
        <el-select v-model="createForm.actionType">
          <el-option-group :label="$t('action.approvalOperations')">
            <el-option
              :label="$t('action.approve')"
              value="APPROVE"
            />
            <el-option
              :label="$t('action.reject')"
              value="REJECT"
            />
            <el-option
              :label="$t('action.transfer')"
              value="TRANSFER"
            />
            <el-option
              :label="$t('action.delegate')"
              value="DELEGATE"
            />
            <el-option
              :label="$t('action.urge')"
              value="URGE"
            />
            <el-option
              :label="$t('action.rollback')"
              value="ROLLBACK"
            />
            <el-option
              :label="$t('action.withdraw')"
              value="WITHDRAW"
            />
            <el-option
              :label="$t('action.draft')"
              value="DRAFT"
            />
          </el-option-group>
          <el-option-group :label="$t('action.processOperations')">
            <el-option
              :label="$t('action.saveDraft')"
              value="SAVE"
            />
            <el-option
              :label="$t('action.processSubmit')"
              value="PROCESS_SUBMIT"
            />
            <el-option
              :label="$t('action.processReject')"
              value="PROCESS_REJECT"
            />
            <el-option
              :label="$t('action.composite')"
              value="COMPOSITE"
            />
          </el-option-group>
          <el-option-group :label="$t('action.customOperations')">
            <el-option
              :label="$t('action.apiCall')"
              value="API_CALL"
            />
            <el-option
              :label="$t('action.formPopup')"
              value="FORM_POPUP"
            />
            <el-option
              :label="$t('action.customScript')"
              value="CUSTOM_SCRIPT"
            />
          </el-option-group>
        </el-select>
      </el-form-item>
      <el-form-item :label="$t('action.buttonColor')">
        <ActionColorField v-model="createForm.buttonColor" />
      </el-form-item>
      <el-form-item :label="$t('action.description')">
        <el-input
          v-model="createForm.description"
          type="textarea"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">
        {{ $t('action.cancel') }}
      </el-button>
      <el-button
        type="primary"
        @click="$emit('confirm')"
      >
        {{ $t('action.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import ActionColorField from './ActionColorField.vue'

defineProps<{
  modelValue: boolean
  createForm: any
}>()

defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
}>()
</script>
