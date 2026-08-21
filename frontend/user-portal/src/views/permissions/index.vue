<template>
  <div class="permissions-page">
    <div class="page-header">
      <h1>{{ t('permission.title') }}</h1>
      <div class="page-header-actions">
        <el-button
          type="primary"
          @click="showApplyDialog"
        >
          {{ t('permission.applyPermission') }}
        </el-button>
        <el-button
          type="danger"
          plain
          @click="openRemovePermissionDialog"
        >
          {{ t('permission.removePermission') }}
        </el-button>
      </div>
    </div>

    <div class="portal-card my-bu-roles-card">
      <h2 class="section-title">
        {{ t('permission.myBuRoles') }}
      </h2>
      <el-empty
        v-if="myBuRoles.length === 0 && !loadingMyBuRoles"
        :description="t('permission.noMyBuRoles')"
      />
      <el-table
        v-else
        v-loading="loadingMyBuRoles"
        :data="myBuRoles"
        stripe
      >
        <el-table-column
          :label="t('permission.businessUnit')"
          min-width="160"
        >
          <template #default="{ row }">
            {{ row.businessUnitName || row.businessUnitId || '-' }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('permission.role')"
          min-width="140"
        >
          <template #default="{ row }">
            {{ row.roleName || row.roleId || '-' }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('permission.assignedAt')"
          width="180"
        >
          <template #default="{ row }">
            {{ formatDateTime(row.assignedAt || row.createdAt) }}
          </template>
        </el-table-column>
      </el-table>
      <p class="table-foot-hint">
        {{ t('permission.requestRemoveBuRoleHint') }}
      </p>
    </div>

    <div class="portal-card exit-bu-card">
      <h2 class="section-title">
        {{ t('exitRole.title') }}
      </h2>
      <p class="section-sub">
        {{ t('exitRole.subtitle') }}
      </p>
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="info-alert"
      >
        {{ t('exitRole.portalNoVirtualGroup') }}
      </el-alert>
      <el-empty
        v-if="!loadingExitBu && exitBuRows.length === 0"
        :description="t('exitRole.noMemberships')"
      />
      <el-table
        v-else
        v-loading="loadingExitBu"
        :data="exitBuRows"
        stripe
      >
        <el-table-column
          prop="businessUnitName"
          :label="t('exitRole.businessUnit')"
          min-width="200"
        />
        <el-table-column
          prop="joinedAt"
          :label="t('exitRole.joinTime')"
          width="180"
        >
          <template #default="{ row }">
            {{ formatDateTime(row.joinedAt) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('common.actions')"
          width="200"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              type="danger"
              link
              size="small"
              @click="openExitBuDialog(row)"
            >
              {{ t('exitRole.requestExitBu') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 申请与审批（合并原 My requests + Approvals） -->
    <div class="portal-card request-lists-card">
      <h2 class="section-title">
        {{ t('permission.sectionRequestsAndApprovals') }}
      </h2>
      <el-alert
        v-if="showPendingApprovalsBanner"
        class="pending-approvals-banner"
        type="warning"
        :closable="false"
        show-icon
        :title="t('permission.pendingApprovalsBanner', { count: approvalPendingCount })"
      />
      <el-select
        v-model="primaryWorkTab"
        class="work-domain-select"
      >
        <el-option
          :label="t('permission.sectionMyRequests')"
          value="myRequests"
        />
        <el-option
          v-if="isApprover"
          :label="t('permission.sectionApprovals')"
          value="approvals"
        />
      </el-select>

      <div v-if="!isApprover || primaryWorkTab === 'myRequests'">
        <el-tabs
          v-model="myRequestTab"
          class="list-tabs"
        >
          <el-tab-pane name="inProgress">
            <template #label>
              <span>{{ t('permission.tabInProgress') }}</span>
              <el-badge
                v-if="pendingCount > 0"
                :value="pendingCount"
                class="tab-badge"
              />
            </template>
            <PermissionRequestSharedList
              ref="myPendingListRef"
              scope="MY_PENDING"
              storage-key="portal-list-layout:permission-my-pending"
              :empty-text="t('permission.noPendingRequests')"
              action-mode="cancel"
              @total="onMyPendingTotal"
              @cancel="cancelRequest"
            />
          </el-tab-pane>

          <el-tab-pane
            :label="t('permission.tabCompleted')"
            name="completed"
          >
            <PermissionRequestSharedList
              ref="myCompletedListRef"
              scope="MY_COMPLETED"
              storage-key="portal-list-layout:permission-my-completed"
              :empty-text="t('permission.noRequests')"
              action-mode="none"
            />
          </el-tab-pane>
        </el-tabs>
      </div>

      <div v-if="isApprover && primaryWorkTab === 'approvals'">
        <el-tabs
          v-model="approvalTab"
          class="list-tabs"
        >
          <el-tab-pane name="pendingApproval">
            <template #label>
              <span>{{ t('permission.tabPendingApproval') }}</span>
              <el-badge
                v-if="approvalPendingCount > 0"
                :value="approvalPendingCount"
                class="tab-badge"
              />
            </template>
            <PermissionRequestSharedList
              ref="approvalPendingListRef"
              scope="APPROVALS_PENDING"
              storage-key="portal-list-layout:permission-approvals-pending"
              :empty-text="t('approval.noPendingApprovals')"
              action-mode="approve"
              :enabled="isApprover"
              @total="onApprovalPendingTotal"
              @approve="showApproveDialog"
              @reject="showRejectDialog"
            />
          </el-tab-pane>

          <el-tab-pane
            :label="t('permission.tabApprovalHistory')"
            name="approvalHistory"
          >
            <PermissionRequestSharedList
              ref="approvalHistoryListRef"
              scope="APPROVALS_HISTORY"
              storage-key="portal-list-layout:permission-approvals-history"
              :empty-text="t('approval.noApprovalHistory')"
              action-mode="none"
              :enabled="isApprover"
            />
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>

    <!-- 批准 / 拒绝（审批人） -->
    <el-dialog
      v-model="approveDialogVisible"
      :title="t('approval.approveTitle')"
      width="500px"
    >
      <div class="approval-dialog-info">
        <p><strong>{{ t('approval.applicant') }}:</strong> {{ getApplicantDisplay(currentApproverRequest) }}</p>
        <p><strong>{{ t('permission.requestType') }}:</strong> {{ getRequestTypeLabel(currentApproverRequest?.requestType) }}</p>
        <p><strong>{{ t('permission.requestTarget') }}:</strong> {{ getTargetName(currentApproverRequest) }}</p>
        <p><strong>{{ t('permission.reason') }}:</strong> {{ currentApproverRequest?.reason }}</p>
      </div>
      <el-form-item :label="t('approval.comment')">
        <el-input
          v-model="approveComment"
          type="textarea"
          :rows="3"
          :placeholder="t('approval.commentPlaceholder')"
        />
      </el-form-item>
      <template #footer>
        <el-button @click="approveDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="success"
          :loading="submittingApproval"
          @click="handleApprove"
        >
          {{ t('approval.approve') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="rejectDialogVisible"
      :title="t('approval.rejectTitle')"
      width="500px"
    >
      <div class="approval-dialog-info">
        <p><strong>{{ t('approval.applicant') }}:</strong> {{ getApplicantDisplay(currentApproverRequest) }}</p>
        <p><strong>{{ t('permission.requestType') }}:</strong> {{ getRequestTypeLabel(currentApproverRequest?.requestType) }}</p>
        <p><strong>{{ t('permission.requestTarget') }}:</strong> {{ getTargetName(currentApproverRequest) }}</p>
        <p><strong>{{ t('permission.reason') }}:</strong> {{ currentApproverRequest?.reason }}</p>
      </div>
      <el-form-item
        :label="t('approval.rejectReason')"
        required
      >
        <el-input
          v-model="rejectComment"
          type="textarea"
          :rows="3"
          :placeholder="t('approval.rejectReasonPlaceholder')"
        />
      </el-form-item>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="danger"
          :loading="submittingApproval"
          @click="handleReject"
        >
          {{ t('approval.reject') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 申请权限对话框 -->
    <el-dialog
      v-model="applyDialogVisible"
      :title="t('permission.applyPermission')"
      width="600px"
    >
      <el-form
        :model="applyForm"
        label-width="auto"
        label-position="left"
        class="apply-form"
      >
        <!-- 申请类型选择 -->
        <el-form-item :label="t('permission.applyType')">
          <el-tag
            type="primary"
            size="large"
          >
            {{ t('permission.joinBusinessUnit') }}
          </el-tag>
        </el-form-item>

        <el-form-item :label="t('permission.beneficiary')">
          <el-select
            v-model="applyForm.beneficiaryUserId"
            filterable
            remote
            clearable
            reserve-keyword
            :placeholder="t('permission.beneficiaryPlaceholder')"
            :remote-method="searchBeneficiaryUsers"
            :loading="loadingBeneficiarySearch"
            style="width: 100%"
            :teleported="false"
          >
            <el-option
              v-for="u in beneficiaryOptions"
              :key="u.userId"
              :label="beneficiaryOptionLabel(u)"
              :value="u.userId"
            />
          </el-select>
          <div class="form-hint">
            {{ t('permission.beneficiaryHint') }}
          </div>
        </el-form-item>

        <!-- 加入业务单元 -->
        <el-form-item
          :label="t('permission.businessUnit')"
          required
        >
          <el-tree-select
            v-model="applyForm.businessUnitId"
            :data="applicableBusinessUnitTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            :placeholder="t('permission.selectBusinessUnit')"
            style="width: 100%;"
            filterable
            check-strictly
            :loading="loadingBusinessUnits"
            :disabled="!loadingBusinessUnits && applicableBusinessUnits.length === 0"
            :teleported="false"
            @change="onBusinessUnitChange"
          />
          <div
            v-if="!loadingBusinessUnits && applicableBusinessUnits.length === 0"
            class="form-hint"
          >
            {{ t('permission.noApplicableBusinessUnits') }}
          </div>
        </el-form-item>

        <el-form-item
          :label="t('permission.role')"
          required
        >
          <el-select
            v-model="applyForm.roleId"
            :placeholder="t('permission.selectRole')"
            style="width: 100%;"
            filterable
            :loading="loadingRoles"
            :disabled="!applyForm.businessUnitId || (!loadingRoles && eligibleRoles.length === 0)"
            :teleported="false"
          >
            <el-option
              v-for="role in eligibleRoles"
              :key="role.id"
              :label="role.name"
              :value="role.id"
            />
          </el-select>
          <div
            v-if="applyForm.businessUnitId && !loadingRoles && eligibleRoles.length === 0"
            class="form-hint"
          >
            {{ t('permission.noEligibleRoles') }}
          </div>
        </el-form-item>

        <el-form-item
          :label="t('permission.reason')"
          required
        >
          <el-input
            v-model="applyForm.reason"
            type="textarea"
            :rows="3"
            :placeholder="t('permission.reasonPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          @click="submitApply"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 按功能单元多选移除 BU 角色 -->
    <el-dialog
      v-model="removePermissionDialogVisible"
      :title="t('permission.removePermissionDialogTitle')"
      width="720px"
      destroy-on-close
      class="remove-permission-dialog"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="remove-permission-alert"
      >
        {{ t('permission.removePermissionIntro') }}
      </el-alert>
      <el-form
        label-position="top"
        class="apply-form removal-form"
      >
        <el-form-item :label="t('permission.beneficiary')">
          <el-select
            v-model="removalBeneficiaryUserId"
            filterable
            remote
            clearable
            reserve-keyword
            :placeholder="t('permission.beneficiaryPlaceholder')"
            :remote-method="searchRemovalBeneficiaries"
            :loading="loadingRemovalBeneficiarySearch"
            style="width: 100%"
            :teleported="false"
          >
            <el-option
              v-for="u in removalBeneficiaryOptions"
              :key="u.userId"
              :label="beneficiaryOptionLabel(u)"
              :value="u.userId"
            />
          </el-select>
          <div class="form-hint">
            {{ t('permission.beneficiaryHint') }}
          </div>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            plain
            :loading="loadingRemovalOptions"
            @click="loadRemovalOptions"
          >
            {{ removalPayload ? t('permission.removalReload') : t('permission.removalLoadOptions') }}
          </el-button>
          <span
            v-if="selectedRemovalKeys.length"
            class="selected-count"
          >
            {{ t('permission.removalSelectedCount', { n: selectedRemovalKeys.length }) }}
          </span>
        </el-form-item>
      </el-form>

      <div
        v-loading="loadingRemovalOptions"
        class="removal-options-body"
      >
        <template v-if="removalPayload && !loadingRemovalOptions">
          <el-empty
            v-if="totalRemovableCount === 0"
            :description="t('permission.removalEmpty')"
          />
          <template v-else>
            <h4
              v-if="removalPayload.functionUnitGroups.length"
              class="removal-section-title"
            >
              {{ t('permission.removalFunctionUnitSection') }}
            </h4>
            <el-collapse
              v-if="removalPayload.functionUnitGroups.length"
              v-model="activeFuCollapseNames"
              class="fu-collapse"
            >
              <el-collapse-item
                v-for="g in removalPayload.functionUnitGroups"
                :key="g.functionUnitId"
                :name="g.functionUnitId"
              >
                <template #title>
                  <div class="fu-collapse-title">
                    <el-checkbox
                      :model-value="groupCheckState(g).checked"
                      :indeterminate="groupCheckState(g).indeterminate"
                      @change="(v: boolean | string | number) => toggleGroupAll(g, Boolean(v))"
                      @click.stop
                    />
                    <span class="fu-title-text">{{ g.functionUnitName || g.functionUnitId }}</span>
                    <el-tag
                      v-if="g.functionUnitCode"
                      size="small"
                      type="info"
                      class="fu-code-tag"
                    >
                      {{ t('permission.removalFunctionUnitCode') }}: {{ g.functionUnitCode }}
                    </el-tag>
                  </div>
                </template>
                <p class="fu-select-all-hint">
                  {{ t('permission.removalSelectAllInFu') }}
                </p>
                <div class="assignment-rows">
                  <el-checkbox
                    v-for="a in g.assignments"
                    :key="rowRemovalKey(a.businessUnitId, a.roleId)"
                    :model-value="selectedRemovalKeys.includes(rowRemovalKey(a.businessUnitId, a.roleId))"
                    class="assignment-check"
                    @change="(v: boolean | string | number) => toggleRemovalKey(rowRemovalKey(a.businessUnitId, a.roleId), Boolean(v))"
                  >
                    {{ removalRowLabel(a) }}
                  </el-checkbox>
                </div>
              </el-collapse-item>
            </el-collapse>

            <template v-if="removalPayload.otherAssignments.length">
              <h4 class="removal-section-title other-title">
                {{ t('permission.removalOtherSection') }}
              </h4>
              <p class="other-hint">
                {{ t('permission.removalOtherHint') }}
              </p>
              <div class="other-actions">
                <el-button
                  text
                  type="primary"
                  size="small"
                  @click="toggleOtherAll(true)"
                >
                  {{ t('common.all') }}
                </el-button>
                <el-button
                  text
                  type="info"
                  size="small"
                  @click="toggleOtherAll(false)"
                >
                  {{ t('common.clear') }}
                </el-button>
              </div>
              <div class="assignment-rows">
                <el-checkbox
                  v-for="a in removalPayload.otherAssignments"
                  :key="rowRemovalKey(a.businessUnitId, a.roleId)"
                  :model-value="selectedRemovalKeys.includes(rowRemovalKey(a.businessUnitId, a.roleId))"
                  class="assignment-check"
                  @change="(v: boolean | string | number) => toggleRemovalKey(rowRemovalKey(a.businessUnitId, a.roleId), Boolean(v))"
                >
                  {{ removalRowLabel(a) }}
                </el-checkbox>
              </div>
            </template>
          </template>
        </template>
      </div>

      <el-form
        label-position="top"
        class="apply-form"
      >
        <el-form-item
          :label="t('permission.reason')"
          required
        >
          <el-input
            v-model="removePermissionReason"
            type="textarea"
            :rows="3"
            :placeholder="t('permission.reasonPlaceholder')"
          />
        </el-form-item>
        <p class="batch-note">
          {{ t('permission.removalBatchNote') }}
        </p>
      </el-form>
      <template #footer>
        <el-button @click="removePermissionDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="danger"
          :loading="submittingRemovalBatch"
          :disabled="selectedRemovalKeys.length === 0"
          @click="submitRemovalBatch"
        >
          {{ t('permission.removalSubmitSelected') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 申请退出业务单元 -->
    <el-dialog
      v-model="exitBuDialogVisible"
      :title="t('exitRole.requestExitBuTitle')"
      width="520px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item :label="t('permission.beneficiary')">
          <el-select
            v-model="exitBuForm.beneficiaryUserId"
            filterable
            remote
            clearable
            reserve-keyword
            :placeholder="t('permission.beneficiaryPlaceholder')"
            :remote-method="searchExitBuBeneficiaries"
            :loading="loadingExitBuBeneficiarySearch"
            style="width: 100%"
          >
            <el-option
              v-for="u in exitBuBeneficiaryOptions"
              :key="u.userId"
              :label="beneficiaryOptionLabel(u)"
              :value="u.userId"
            />
          </el-select>
          <div class="form-hint">
            {{ t('permission.beneficiaryHint') }}
          </div>
        </el-form-item>
        <el-form-item
          :label="t('permission.reason')"
          required
        >
          <el-input
            v-model="exitBuForm.reason"
            type="textarea"
            :rows="3"
            :placeholder="t('permission.reasonPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exitBuDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="exitBuSubmitting"
          @click="submitExitBu"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePendingApprovalStore } from '@/stores/pendingApproval'
import PermissionRequestSharedList from '@/components/permissions/PermissionRequestSharedList.vue'
import { usePermissionFormatters } from '@/composables/permissions/usePermissionFormatters'
import { useMyBuRoles } from '@/composables/permissions/useMyBuRoles'
import { useMyRequests } from '@/composables/permissions/useMyRequests'
import { useApprovals } from '@/composables/permissions/useApprovals'
import { useApplyPermission } from '@/composables/permissions/useApplyPermission'
import { useExitBu } from '@/composables/permissions/useExitBu'
import { useRemovePermission } from '@/composables/permissions/useRemovePermission'
import {
  resolvePrimaryWorkTab,
  shouldShowPendingApprovalsBanner,
  type PrimaryWorkTab
} from '@/utils/permissionWorkTabs'

const { t } = useI18n()
const pendingApprovalStore = usePendingApprovalStore()
const primaryWorkTab = ref<PrimaryWorkTab>('myRequests')

type SharedListExpose = { reload: () => void | Promise<void> }
const myPendingListRef = ref<SharedListExpose | null>(null)
const myCompletedListRef = ref<SharedListExpose | null>(null)
const approvalPendingListRef = ref<SharedListExpose | null>(null)
const approvalHistoryListRef = ref<SharedListExpose | null>(null)

const pendingCount = ref(0)
const approvalPendingCount = ref(0)

function reloadMyLists() {
  void myPendingListRef.value?.reload()
  void myCompletedListRef.value?.reload()
}

function reloadApprovalLists() {
  void approvalPendingListRef.value?.reload()
  void approvalHistoryListRef.value?.reload()
}

/** Thin wrappers for apply / exit / remove success paths. */
const loadPendingRequests = () => {
  void myPendingListRef.value?.reload()
}
const loadHistoryRequests = () => {
  void myCompletedListRef.value?.reload()
}

// 纯展示/格式化辅助（状态、类型标签、目标名称、时间格式化等）
const {
  getApplicantDisplay,
  rowRemovalKey,
  removalRowLabel,
  beneficiaryOptionLabel,
  getRequestTypeLabel,
  getTargetName,
  formatDateTime
} = usePermissionFormatters(t)

// 我的业务单元角色
const { loadingMyBuRoles, myBuRoles, loadMyBuRoles } = useMyBuRoles()

// 我的申请（tab + 取消申请）
const { myRequestTab, cancelRequest } = useMyRequests(t, { reloadMyLists })

// 审批侧（审批人身份、批准/拒绝）
const {
  approvalTab,
  isApprover,
  approveDialogVisible,
  rejectDialogVisible,
  currentApproverRequest,
  approveComment,
  rejectComment,
  submittingApproval,
  checkApproverStatus,
  showApproveDialog,
  showRejectDialog,
  handleApprove,
  handleReject
} = useApprovals(t, {
  reloadMyLists,
  reloadApprovalLists,
  fetchPendingCount: () => pendingApprovalStore.fetchPendingCount()
})

const showPendingApprovalsBanner = computed(() =>
  shouldShowPendingApprovalsBanner({
    isApprover: isApprover.value,
    approvalPendingCount: approvalPendingCount.value
  })
)

function onMyPendingTotal(n: number) {
  pendingCount.value = n
}

function onApprovalPendingTotal(n: number) {
  approvalPendingCount.value = n
}

// 申请权限对话框
const {
  applyDialogVisible,
  submitting,
  loadingBusinessUnits,
  loadingRoles,
  loadingBeneficiarySearch,
  beneficiaryOptions,
  applicableBusinessUnits,
  applicableBusinessUnitTree,
  eligibleRoles,
  applyForm,
  searchBeneficiaryUsers,
  showApplyDialog,
  onBusinessUnitChange,
  submitApply
} = useApplyPermission(t, { loadPendingRequests, loadHistoryRequests })

// 退出业务单元（先于移除权限创建，供其刷新成员关系）
const {
  loadingExitBu,
  exitBuRows,
  exitBuDialogVisible,
  exitBuSubmitting,
  loadingExitBuBeneficiarySearch,
  exitBuBeneficiaryOptions,
  exitBuForm,
  loadExitBuMemberships,
  searchExitBuBeneficiaries,
  openExitBuDialog,
  submitExitBu
} = useExitBu(t, { loadPendingRequests, loadHistoryRequests, loadMyBuRoles })

// 移除权限对话框（按功能单元批量移除 BU 角色）
const {
  removePermissionDialogVisible,
  removalBeneficiaryUserId,
  removalBeneficiaryOptions,
  loadingRemovalBeneficiarySearch,
  removalPayload,
  loadingRemovalOptions,
  selectedRemovalKeys,
  activeFuCollapseNames,
  removePermissionReason,
  submittingRemovalBatch,
  totalRemovableCount,
  groupCheckState,
  toggleRemovalKey,
  toggleGroupAll,
  toggleOtherAll,
  searchRemovalBeneficiaries,
  openRemovePermissionDialog,
  loadRemovalOptions,
  submitRemovalBatch
} = useRemovePermission(t, {
  rowRemovalKey,
  loadPendingRequests,
  loadHistoryRequests,
  loadMyBuRoles,
  loadExitBuMemberships
})

// 初始化
onMounted(async () => {
  await checkApproverStatus()
  loadMyBuRoles()
  loadExitBuMemberships()
  await pendingApprovalStore.fetchPendingCount()
  if (isApprover.value && pendingApprovalStore.count > 0) {
    approvalPendingCount.value = pendingApprovalStore.count
  }
  primaryWorkTab.value = resolvePrimaryWorkTab({
    isApprover: isApprover.value,
    approvalPendingCount: approvalPendingCount.value
  })
})
</script>

<style lang="scss" scoped>
.permissions-page {
  .page-header {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    margin-bottom: 20px;

    h1 {
      font-size: 24px;
      font-weight: 500;
      color: var(--text-primary);
      margin: 0;
    }

    .page-header-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
    }
  }
  
  .tab-badge {
    margin-left: 6px;
  }

  .my-bu-roles-card {
    margin-bottom: 20px;
  }

  .section-title {
    font-size: 16px;
    font-weight: 500;
    color: var(--text-primary);
    margin: 0 0 12px;
  }

  .section-sub {
    margin: 0 0 12px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
    line-height: 1.5;
  }

  .table-foot-hint {
    margin: 10px 0 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.45;
  }

  .exit-bu-card {
    margin-bottom: 20px;

    .info-alert {
      margin-bottom: 12px;
    }
  }

  .request-lists-card {
    margin-bottom: 20px;
  }

  .pending-approvals-banner {
    margin-bottom: 12px;
  }

  .work-domain-select {
    width: 180px;
    margin-bottom: 12px;
  }

  .list-tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 12px;
    }
  }

  :deep(.apply-form) {
    .el-form-item__label {
      white-space: nowrap;
    }

    .form-hint {
      margin-top: 6px;
      color: var(--el-text-color-secondary);
      font-size: 12px;
      line-height: 1.4;
    }
  }
}
</style>

<!-- Dialog 挂载到 body，无 scoped 以便样式生效 -->
<style lang="scss">
.remove-permission-dialog {
  .remove-permission-alert {
    margin-bottom: 14px;
  }

  .removal-form {
    margin-bottom: 8px;
  }

  .selected-count {
    margin-left: 12px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .removal-options-body {
    min-height: 80px;
    margin-bottom: 12px;
  }

  .removal-section-title {
    margin: 0 0 8px;
    font-size: 14px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .other-title {
    margin-top: 16px;
  }

  .other-hint {
    margin: 0 0 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.4;
  }

  .other-actions {
    margin-bottom: 8px;
  }

  .fu-collapse.el-collapse {
    border: none;
  }

  .fu-collapse .el-collapse-item__header {
    height: auto;
    min-height: 48px;
    line-height: 1.4;
    padding-right: 8px;
  }

  .fu-collapse-title {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 4px 0;
  }

  .fu-title-text {
    font-weight: 500;
    color: var(--el-text-color-primary);
  }

  .fu-code-tag {
    font-weight: normal;
  }

  .fu-select-all-hint {
    margin: 0 0 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .assignment-rows {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 4px 0 8px;
  }

  .assignment-check.el-checkbox {
    margin-right: 0;
    align-items: flex-start;
    white-space: normal;
    height: auto;
  }

  .batch-note {
    margin: 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.approval-dialog-info {
  margin-bottom: 16px;
  padding: 12px 14px;
  background: var(--el-fill-color-light);
  border-radius: 8px;

  p {
    margin: 6px 0;
    font-size: 13px;
    line-height: 1.5;
  }

  strong {
    color: var(--el-text-color-secondary);
    margin-right: 8px;
  }
}
</style>
