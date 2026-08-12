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
        class="portal-list-grid"
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
        class="portal-list-grid"
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
        @change="onPrimaryWorkDomainChange"
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
          @tab-change="onMyRequestTabChange"
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
            <el-empty
              v-if="pendingList.length === 0 && !loadingPending"
              :description="t('permission.noPendingRequests')"
            />
            <template v-else>
              <el-table
                v-loading="loadingPending"
                class="portal-list-grid"
                :data="displayPendingRows"
                stripe
                table-layout="fixed"
                :span-method="pendingSpanMethod"
                :row-class-name="groupRowClassName"
              >
                <el-table-column
                  v-for="(field, idx) in orderedPendingFields"
                  :key="field"
                  :prop="field"
                  :width="pendingColWidth(field, pendingWidthFallback(field))"
                  :fixed="field === 'actions' ? 'right' : undefined"
                  show-overflow-tooltip
                >
                  <template #header>
                    <PortalListColumnHeader
                      :label="pendingColumnLabel(field)"
                      :width="pendingColWidth(field, pendingWidthFallback(field))"
                      :has-filter="field !== 'actions' && pendingHasFilter(field)"
                      :sort-direction="field !== 'actions' ? pendingSortDirection(field) : null"
                      :is-grouped="field !== 'actions' && pendingIsGrouped(field)"
                      :can-move-left="field !== 'actions' && pendingCanMoveLeft(field)"
                      :can-move-right="field !== 'actions' && pendingCanMoveRight(field)"
                      :sortable="field !== 'actions'"
                      :filterable="field !== 'actions'"
                      :groupable="field !== 'actions'"
                      :movable="field !== 'actions'"
                      :date-like="field === 'createdAt' || field === 'approvedAt'"
                      @sort-asc="onPendingSort(field, 'ASC')"
                      @sort-desc="onPendingSort(field, 'DESC')"
                      @group-by="onPendingGroup(field)"
                      @filter="pendingOpenFilter(field, pendingColumnLabel(field))"
                      @clear-filter="onPendingClearFilter(field)"
                      @move-left="pendingMoveLeft(field)"
                      @move-right="pendingMoveRight(field)"
                      @resize="(w) => pendingOnResize(field, w)"
                      @resize-end="pendingOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    <template v-if="isPortalListGroupHeader(row)">
                      <div
                        v-if="idx === 0"
                        class="group-header-cell"
                      >
                        <strong>{{ row._groupLabel }}</strong>
                        <span class="group-count">({{ row._groupCount }})</span>
                      </div>
                    </template>
                    <template v-else-if="field === 'requestType'">
                      <el-tag
                        :type="getRequestTypeTag(row.requestType)"
                        size="small"
                      >
                        {{ getRequestTypeLabel(row.requestType) }}
                      </el-tag>
                    </template>
                    <template v-else-if="field === 'requestTarget'">
                      {{ getTargetName(row) }}
                    </template>
                    <template v-else-if="field === 'beneficiary'">
                      {{ row.applicantUsername || row.applicantId || '-' }}
                    </template>
                    <template v-else-if="field === 'submittedBy'">
                      <span v-if="row.submittedByUserId && row.submittedByUserId !== row.applicantId">
                        {{ row.submittedByUsername || row.submittedByUserId }}
                        <el-tag
                          size="small"
                          type="info"
                          class="proxy-tag"
                        >{{ t('permission.proxyBadge') }}</el-tag>
                      </span>
                      <span v-else>{{ t('permission.selfBeneficiary') }}</span>
                    </template>
                    <template v-else-if="field === 'reason'">
                      {{ row.reason }}
                    </template>
                    <template v-else-if="field === 'createdAt'">
                      {{ formatDateTime(row.createdAt) }}
                    </template>
                    <template v-else-if="field === 'status'">
                      <el-tag
                        :type="getStatusType(row.status)"
                        size="small"
                      >
                        {{ getStatusLabel(row.status) }}
                      </el-tag>
                    </template>
                    <template v-else-if="field === 'approvedAt'">
                      {{ formatDateTime(row.approvedAt || row.updatedAt) }}
                    </template>
                    <template v-else-if="field === 'applicant'">
                      {{ getApplicantDisplay(row) }}
                    </template>
                    <template v-else-if="field === 'actions'">
                      <el-button
                        v-if="canCancelAsBeneficiary(row)"
                        type="danger"
                        size="small"
                        text
                        @click="cancelRequest(row)"
                      >
                        {{ t('permission.cancelRequest') }}
                      </el-button>
                    </template>
                  </template>
                </el-table-column>
              </el-table>
              <PortalListPagination
                v-model:current-page="pendingPagination.page"
                v-model:page-size="pendingPagination.size"
                :disabled="loadingPending"
                :total="pendingTotal"
                :visible="true"
                @change="onPendingPageChange"
              />
              <PortalListFilterDialog
                v-model="pendingFilterDialogVisible"
                :title="pendingFilterDialogField
                  ? `${t('mainTableView.colFilterBy')}: ${pendingFilterDialogField.label}`
                  : t('mainTableView.colFilterBy')"
                :initial="pendingFilterDialogField
                  ? pendingColState.filters[pendingFilterDialogField.field]
                  : null"
                @apply="onPendingApplyFilter"
                @clear="onPendingClearFilter()"
              />
            </template>
          </el-tab-pane>

          <el-tab-pane
            :label="t('permission.tabCompleted')"
            name="completed"
          >
            <el-empty
              v-if="historyList.length === 0 && !loadingHistory"
              :description="t('permission.noRequests')"
            />
            <template v-else>
              <el-table
                v-loading="loadingHistory"
                class="portal-list-grid"
                :data="displayHistoryRows"
                stripe
                table-layout="fixed"
                :span-method="historySpanMethod"
                :row-class-name="groupRowClassName"
              >
                <el-table-column
                  prop="requestType"
                  :width="historyColWidth('requestType', 140)"
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.requestType')"
                      :width="historyColWidth('requestType', 140)"
                      :has-filter="historyHasFilter('requestType')"
                      :sort-direction="historySortDirection('requestType')"
                      :is-grouped="historyIsGrouped('requestType')"
                      :can-move-left="historyCanMoveLeft('requestType')"
                      :can-move-right="historyCanMoveRight('requestType')"
                      :date-like="false"
                      @sort-asc="onHistorySort('requestType', 'ASC')"
                      @sort-desc="onHistorySort('requestType', 'DESC')"
                      @group-by="onHistoryGroup('requestType')"
                      @filter="historyOpenFilter('requestType', t('permission.requestType'))"
                      @clear-filter="onHistoryClearFilter('requestType')"
                      @move-left="historyMoveLeft('requestType')"
                      @move-right="historyMoveRight('requestType')"
                      @resize="(w) => historyOnResize('requestType', w)"
                      @resize-end="historyOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    <el-tag
                      :type="getRequestTypeTag(row.requestType)"
                      size="small"
                    >
                      {{ getRequestTypeLabel(row.requestType) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column :width="historyColWidth('requestTarget', 180)">
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.requestTarget')"
                      :width="historyColWidth('requestTarget', 140)"
                      :has-filter="historyHasFilter('requestTarget')"
                      :sort-direction="historySortDirection('requestTarget')"
                      :is-grouped="historyIsGrouped('requestTarget')"
                      :can-move-left="historyCanMoveLeft('requestTarget')"
                      :can-move-right="historyCanMoveRight('requestTarget')"
                      :date-like="false"
                      @sort-asc="onHistorySort('requestTarget', 'ASC')"
                      @sort-desc="onHistorySort('requestTarget', 'DESC')"
                      @group-by="onHistoryGroup('requestTarget')"
                      @filter="historyOpenFilter('requestTarget', t('permission.requestTarget'))"
                      @clear-filter="onHistoryClearFilter('requestTarget')"
                      @move-left="historyMoveLeft('requestTarget')"
                      @move-right="historyMoveRight('requestTarget')"
                      @resize="(w) => historyOnResize('requestTarget', w)"
                      @resize-end="historyOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ getTargetName(row) }}
                  </template>
                </el-table-column>
                <el-table-column
                  :width="historyColWidth('beneficiary', 130)"
                  show-overflow-tooltip
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.beneficiaryColumn')"
                      :width="historyColWidth('beneficiary', 140)"
                      :has-filter="historyHasFilter('beneficiary')"
                      :sort-direction="historySortDirection('beneficiary')"
                      :is-grouped="historyIsGrouped('beneficiary')"
                      :can-move-left="historyCanMoveLeft('beneficiary')"
                      :can-move-right="historyCanMoveRight('beneficiary')"
                      :date-like="false"
                      @sort-asc="onHistorySort('beneficiary', 'ASC')"
                      @sort-desc="onHistorySort('beneficiary', 'DESC')"
                      @group-by="onHistoryGroup('beneficiary')"
                      @filter="historyOpenFilter('beneficiary', t('permission.beneficiaryColumn'))"
                      @clear-filter="onHistoryClearFilter('beneficiary')"
                      @move-left="historyMoveLeft('beneficiary')"
                      @move-right="historyMoveRight('beneficiary')"
                      @resize="(w) => historyOnResize('beneficiary', w)"
                      @resize-end="historyOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ row.applicantUsername || row.applicantId || '-' }}
                  </template>
                </el-table-column>
                <el-table-column
                  :width="historyColWidth('submittedBy', 120)"
                  show-overflow-tooltip
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.submittedByColumn')"
                      :width="historyColWidth('submittedBy', 140)"
                      :has-filter="historyHasFilter('submittedBy')"
                      :sort-direction="historySortDirection('submittedBy')"
                      :is-grouped="historyIsGrouped('submittedBy')"
                      :can-move-left="historyCanMoveLeft('submittedBy')"
                      :can-move-right="historyCanMoveRight('submittedBy')"
                      :date-like="false"
                      @sort-asc="onHistorySort('submittedBy', 'ASC')"
                      @sort-desc="onHistorySort('submittedBy', 'DESC')"
                      @group-by="onHistoryGroup('submittedBy')"
                      @filter="historyOpenFilter('submittedBy', t('permission.submittedByColumn'))"
                      @clear-filter="onHistoryClearFilter('submittedBy')"
                      @move-left="historyMoveLeft('submittedBy')"
                      @move-right="historyMoveRight('submittedBy')"
                      @resize="(w) => historyOnResize('submittedBy', w)"
                      @resize-end="historyOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    <span v-if="row.submittedByUserId && row.submittedByUserId !== row.applicantId">
                      {{ row.submittedByUsername || row.submittedByUserId }}
                    </span>
                    <span v-else>—</span>
                  </template>
                </el-table-column>
                <el-table-column
                  prop="reason"
                  :width="historyColWidth('reason', 150)"
                  show-overflow-tooltip
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.reason')"
                      :width="historyColWidth('reason', 140)"
                      :has-filter="historyHasFilter('reason')"
                      :sort-direction="historySortDirection('reason')"
                      :is-grouped="historyIsGrouped('reason')"
                      :can-move-left="historyCanMoveLeft('reason')"
                      :can-move-right="historyCanMoveRight('reason')"
                      :date-like="false"
                      @sort-asc="onHistorySort('reason', 'ASC')"
                      @sort-desc="onHistorySort('reason', 'DESC')"
                      @group-by="onHistoryGroup('reason')"
                      @filter="historyOpenFilter('reason', t('permission.reason'))"
                      @clear-filter="onHistoryClearFilter('reason')"
                      @move-left="historyMoveLeft('reason')"
                      @move-right="historyMoveRight('reason')"
                      @resize="(w) => historyOnResize('reason', w)"
                      @resize-end="historyOnResizeEnd"
                    />
                  </template>
                </el-table-column>
                <el-table-column
                  prop="status"
                  :width="historyColWidth('status', 110)"
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.status')"
                      :width="historyColWidth('status', 140)"
                      :has-filter="historyHasFilter('status')"
                      :sort-direction="historySortDirection('status')"
                      :is-grouped="historyIsGrouped('status')"
                      :can-move-left="historyCanMoveLeft('status')"
                      :can-move-right="historyCanMoveRight('status')"
                      :date-like="false"
                      @sort-asc="onHistorySort('status', 'ASC')"
                      @sort-desc="onHistorySort('status', 'DESC')"
                      @group-by="onHistoryGroup('status')"
                      @filter="historyOpenFilter('status', t('permission.status'))"
                      @clear-filter="onHistoryClearFilter('status')"
                      @move-left="historyMoveLeft('status')"
                      @move-right="historyMoveRight('status')"
                      @resize="(w) => historyOnResize('status', w)"
                      @resize-end="historyOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    <el-tag
                      :type="getStatusType(row.status)"
                      size="small"
                    >
                      {{ getStatusLabel(row.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  prop="approverComment"
                  :width="historyColWidth('approverComment', 150)"
                  show-overflow-tooltip
                >
                  <template #header>
                                                            <PortalListColumnHeader
                      :label="t('approval.comment')"
                      :width="apprPendingColWidth('approverComment', 140)"
                      :has-filter="apprPendingHasFilter('approverComment')"
                      :sort-direction="apprPendingSortDirection('approverComment')"
                      :is-grouped="apprPendingIsGrouped('approverComment')"
                      :can-move-left="apprPendingCanMoveLeft('approverComment')"
                      :can-move-right="apprPendingCanMoveRight('approverComment')"
                      :date-like="false"
                      @sort-asc="onApprPendingSort('approverComment', 'ASC')"
                      @sort-desc="onApprPendingSort('approverComment', 'DESC')"
                      @group-by="onApprPendingGroup('approverComment')"
                      @filter="apprPendingOpenFilter('approverComment', t('approval.comment'))"
                      @clear-filter="onApprPendingClearFilter('approverComment')"
                      @move-left="apprPendingMoveLeft('approverComment')"
                      @move-right="apprPendingMoveRight('approverComment')"
                      @resize="(w) => apprPendingOnResize('approverComment', w)"
                      @resize-end="apprPendingOnResizeEnd"
                    />
                  </template>
                </el-table-column>
                <el-table-column
                  prop="createdAt"
                  :width="historyColWidth('createdAt', 170)"
                >
                  <template #header>
                                                            <PortalListColumnHeader
                      :label="t('permission.applyTime')"
                      :width="apprPendingColWidth('createdAt', 140)"
                      :has-filter="apprPendingHasFilter('createdAt')"
                      :sort-direction="apprPendingSortDirection('createdAt')"
                      :is-grouped="apprPendingIsGrouped('createdAt')"
                      :can-move-left="apprPendingCanMoveLeft('createdAt')"
                      :can-move-right="apprPendingCanMoveRight('createdAt')"
                      :date-like="true"
                      @sort-asc="onApprPendingSort('createdAt', 'ASC')"
                      @sort-desc="onApprPendingSort('createdAt', 'DESC')"
                      @group-by="onApprPendingGroup('createdAt')"
                      @filter="apprPendingOpenFilter('createdAt', t('permission.applyTime'))"
                      @clear-filter="onApprPendingClearFilter('createdAt')"
                      @move-left="apprPendingMoveLeft('createdAt')"
                      @move-right="apprPendingMoveRight('createdAt')"
                      @resize="(w) => apprPendingOnResize('createdAt', w)"
                      @resize-end="apprPendingOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ formatDateTime(row.createdAt) }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="updatedAt"
                  :width="historyColWidth('updatedAt', 170)"
                >
                  <template #header>
                                                            <PortalListColumnHeader
                      :label="t('permission.approvedAt')"
                      :width="apprPendingColWidth('updatedAt', 140)"
                      :has-filter="apprPendingHasFilter('updatedAt')"
                      :sort-direction="apprPendingSortDirection('updatedAt')"
                      :is-grouped="apprPendingIsGrouped('updatedAt')"
                      :can-move-left="apprPendingCanMoveLeft('updatedAt')"
                      :can-move-right="apprPendingCanMoveRight('updatedAt')"
                      :date-like="true"
                      @sort-asc="onApprPendingSort('updatedAt', 'ASC')"
                      @sort-desc="onApprPendingSort('updatedAt', 'DESC')"
                      @group-by="onApprPendingGroup('updatedAt')"
                      @filter="apprPendingOpenFilter('updatedAt', t('permission.approvedAt'))"
                      @clear-filter="onApprPendingClearFilter('updatedAt')"
                      @move-left="apprPendingMoveLeft('updatedAt')"
                      @move-right="apprPendingMoveRight('updatedAt')"
                      @resize="(w) => apprPendingOnResize('updatedAt', w)"
                      @resize-end="apprPendingOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ formatDateTime(row.updatedAt) }}
                  </template>
                </el-table-column>
              </el-table>
              <PortalListPagination
                v-model:current-page="historyPagination.page"
                v-model:page-size="historyPagination.size"
                :disabled="loadingHistory"
                :total="historyTotal"
                :visible="true"
                @change="onHistoryPageChange"
              />
              <PortalListFilterDialog
                v-model="historyFilterDialogVisible"
                :title="historyFilterDialogField
                  ? `${t('mainTableView.colFilterBy')}: ${historyFilterDialogField.label}`
                  : t('mainTableView.colFilterBy')"
                :initial="historyFilterDialogField
                  ? historyColState.filters[historyFilterDialogField.field]
                  : null"
                @apply="onHistoryApplyFilter"
                @clear="onHistoryClearFilter()"
              />
            </template>
          </el-tab-pane>
        </el-tabs>
      </div>

      <div v-if="isApprover && primaryWorkTab === 'approvals'">
        <el-tabs
          v-model="approvalTab"
          class="list-tabs"
          @tab-change="onApprovalTabChange"
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
            <el-empty
              v-if="approverPendingList.length === 0 && !loadingApproverPending"
              :description="t('approval.noPendingApprovals')"
            />
            <template v-else>
              <el-table
                v-loading="loadingApproverPending"
                class="portal-list-grid"
                :data="displayApprPendingRows"
                stripe
                table-layout="fixed"
                :span-method="apprPendingSpanMethod"
                :row-class-name="groupRowClassName"
              >
                <el-table-column
                  prop="applicantId"
                  :width="apprPendingColWidth('applicantId', 150)"
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.beneficiaryColumn')"
                      :width="apprHistoryColWidth('applicantId', 140)"
                      :has-filter="apprHistoryHasFilter('applicantId')"
                      :sort-direction="apprHistorySortDirection('applicantId')"
                      :is-grouped="apprHistoryIsGrouped('applicantId')"
                      :can-move-left="apprHistoryCanMoveLeft('applicantId')"
                      :can-move-right="apprHistoryCanMoveRight('applicantId')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('applicantId', 'ASC')"
                      @sort-desc="onApprHistorySort('applicantId', 'DESC')"
                      @group-by="onApprHistoryGroup('applicantId')"
                      @filter="apprHistoryOpenFilter('applicantId', t('permission.beneficiaryColumn'))"
                      @clear-filter="onApprHistoryClearFilter('applicantId')"
                      @move-left="apprHistoryMoveLeft('applicantId')"
                      @move-right="apprHistoryMoveRight('applicantId')"
                      @resize="(w) => apprHistoryOnResize('applicantId', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ getApplicantDisplay(row) }}
                  </template>
                </el-table-column>
                <el-table-column
                  :width="apprPendingColWidth('submittedBy', 140)"
                  show-overflow-tooltip
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.submittedByColumn')"
                      :width="apprHistoryColWidth('submittedBy', 140)"
                      :has-filter="apprHistoryHasFilter('submittedBy')"
                      :sort-direction="apprHistorySortDirection('submittedBy')"
                      :is-grouped="apprHistoryIsGrouped('submittedBy')"
                      :can-move-left="apprHistoryCanMoveLeft('submittedBy')"
                      :can-move-right="apprHistoryCanMoveRight('submittedBy')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('submittedBy', 'ASC')"
                      @sort-desc="onApprHistorySort('submittedBy', 'DESC')"
                      @group-by="onApprHistoryGroup('submittedBy')"
                      @filter="apprHistoryOpenFilter('submittedBy', t('permission.submittedByColumn'))"
                      @clear-filter="onApprHistoryClearFilter('submittedBy')"
                      @move-left="apprHistoryMoveLeft('submittedBy')"
                      @move-right="apprHistoryMoveRight('submittedBy')"
                      @resize="(w) => apprHistoryOnResize('submittedBy', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ getSubmitterDisplay(row) }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="requestType"
                  :width="apprPendingColWidth('requestType', 140)"
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.requestType')"
                      :width="apprHistoryColWidth('requestType', 140)"
                      :has-filter="apprHistoryHasFilter('requestType')"
                      :sort-direction="apprHistorySortDirection('requestType')"
                      :is-grouped="apprHistoryIsGrouped('requestType')"
                      :can-move-left="apprHistoryCanMoveLeft('requestType')"
                      :can-move-right="apprHistoryCanMoveRight('requestType')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('requestType', 'ASC')"
                      @sort-desc="onApprHistorySort('requestType', 'DESC')"
                      @group-by="onApprHistoryGroup('requestType')"
                      @filter="apprHistoryOpenFilter('requestType', t('permission.requestType'))"
                      @clear-filter="onApprHistoryClearFilter('requestType')"
                      @move-left="apprHistoryMoveLeft('requestType')"
                      @move-right="apprHistoryMoveRight('requestType')"
                      @resize="(w) => apprHistoryOnResize('requestType', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    <el-tag
                      :type="getRequestTypeTag(row.requestType)"
                      size="small"
                    >
                      {{ getRequestTypeLabel(row.requestType) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column :width="apprPendingColWidth('requestTarget', 180)">
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.requestTarget')"
                      :width="apprHistoryColWidth('requestTarget', 140)"
                      :has-filter="apprHistoryHasFilter('requestTarget')"
                      :sort-direction="apprHistorySortDirection('requestTarget')"
                      :is-grouped="apprHistoryIsGrouped('requestTarget')"
                      :can-move-left="apprHistoryCanMoveLeft('requestTarget')"
                      :can-move-right="apprHistoryCanMoveRight('requestTarget')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('requestTarget', 'ASC')"
                      @sort-desc="onApprHistorySort('requestTarget', 'DESC')"
                      @group-by="onApprHistoryGroup('requestTarget')"
                      @filter="apprHistoryOpenFilter('requestTarget', t('permission.requestTarget'))"
                      @clear-filter="onApprHistoryClearFilter('requestTarget')"
                      @move-left="apprHistoryMoveLeft('requestTarget')"
                      @move-right="apprHistoryMoveRight('requestTarget')"
                      @resize="(w) => apprHistoryOnResize('requestTarget', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ getTargetName(row) }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="reason"
                  :width="apprPendingColWidth('reason', 200)"
                  show-overflow-tooltip
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.reason')"
                      :width="apprHistoryColWidth('reason', 140)"
                      :has-filter="apprHistoryHasFilter('reason')"
                      :sort-direction="apprHistorySortDirection('reason')"
                      :is-grouped="apprHistoryIsGrouped('reason')"
                      :can-move-left="apprHistoryCanMoveLeft('reason')"
                      :can-move-right="apprHistoryCanMoveRight('reason')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('reason', 'ASC')"
                      @sort-desc="onApprHistorySort('reason', 'DESC')"
                      @group-by="onApprHistoryGroup('reason')"
                      @filter="apprHistoryOpenFilter('reason', t('permission.reason'))"
                      @clear-filter="onApprHistoryClearFilter('reason')"
                      @move-left="apprHistoryMoveLeft('reason')"
                      @move-right="apprHistoryMoveRight('reason')"
                      @resize="(w) => apprHistoryOnResize('reason', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                </el-table-column>
                <el-table-column
                  prop="createdAt"
                  :width="apprPendingColWidth('createdAt', 170)"
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.applyTime')"
                      :width="apprHistoryColWidth('createdAt', 140)"
                      :has-filter="apprHistoryHasFilter('createdAt')"
                      :sort-direction="apprHistorySortDirection('createdAt')"
                      :is-grouped="apprHistoryIsGrouped('createdAt')"
                      :can-move-left="apprHistoryCanMoveLeft('createdAt')"
                      :can-move-right="apprHistoryCanMoveRight('createdAt')"
                      :date-like="true"
                      @sort-asc="onApprHistorySort('createdAt', 'ASC')"
                      @sort-desc="onApprHistorySort('createdAt', 'DESC')"
                      @group-by="onApprHistoryGroup('createdAt')"
                      @filter="apprHistoryOpenFilter('createdAt', t('permission.applyTime'))"
                      @clear-filter="onApprHistoryClearFilter('createdAt')"
                      @move-left="apprHistoryMoveLeft('createdAt')"
                      @move-right="apprHistoryMoveRight('createdAt')"
                      @resize="(w) => apprHistoryOnResize('createdAt', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ formatDateTime(row.createdAt) }}
                  </template>
                </el-table-column>
                <el-table-column
                  :width="apprPendingColWidth('actions', 180)"
                  fixed="right"
                >
                  <template #header>
                    <PortalListColumnHeader
                      :label="t('common.actions')"
                      :width="apprPendingColWidth('actions', 180)"
                      :sortable="false"
                      :filterable="false"
                      :groupable="false"
                      :movable="false"
                      @resize="(w) => apprPendingOnResize('actions', w)"
                      @resize-end="apprPendingOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    <el-button
                      type="success"
                      size="small"
                      @click="showApproveDialog(row)"
                    >
                      {{ t('approval.approve') }}
                    </el-button>
                    <el-button
                      type="danger"
                      size="small"
                      @click="showRejectDialog(row)"
                    >
                      {{ t('approval.reject') }}
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <PortalListPagination
                v-model:current-page="approverPendingPagination.page"
                v-model:page-size="approverPendingPagination.size"
                :disabled="loadingApproverPending"
                :total="approvalPendingTotal"
                :visible="true"
                @change="onApprPendingPageChange"
              />
              <PortalListFilterDialog
                v-model="apprPendingFilterDialogVisible"
                :title="apprPendingFilterDialogField
                  ? `${t('mainTableView.colFilterBy')}: ${apprPendingFilterDialogField.label}`
                  : t('mainTableView.colFilterBy')"
                :initial="apprPendingFilterDialogField
                  ? apprPendingColState.filters[apprPendingFilterDialogField.field]
                  : null"
                @apply="onApprPendingApplyFilter"
                @clear="onApprPendingClearFilter()"
              />
            </template>
          </el-tab-pane>

          <el-tab-pane
            :label="t('permission.tabApprovalHistory')"
            name="approvalHistory"
          >
            <el-empty
              v-if="approverHistoryList.length === 0 && !loadingApproverHistory"
              :description="t('approval.noApprovalHistory')"
            />
            <template v-else>
              <el-table
                v-loading="loadingApproverHistory"
                class="portal-list-grid"
                :data="displayApprHistoryRows"
                stripe
                table-layout="fixed"
                :span-method="apprHistorySpanMethod"
                :row-class-name="groupRowClassName"
              >
                <el-table-column
                  prop="applicantId"
                  :width="apprHistoryColWidth('applicantId', 150)"
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.beneficiaryColumn')"
                      :width="apprHistoryColWidth('applicantId', 140)"
                      :has-filter="apprHistoryHasFilter('applicantId')"
                      :sort-direction="apprHistorySortDirection('applicantId')"
                      :is-grouped="apprHistoryIsGrouped('applicantId')"
                      :can-move-left="apprHistoryCanMoveLeft('applicantId')"
                      :can-move-right="apprHistoryCanMoveRight('applicantId')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('applicantId', 'ASC')"
                      @sort-desc="onApprHistorySort('applicantId', 'DESC')"
                      @group-by="onApprHistoryGroup('applicantId')"
                      @filter="apprHistoryOpenFilter('applicantId', t('permission.beneficiaryColumn'))"
                      @clear-filter="onApprHistoryClearFilter('applicantId')"
                      @move-left="apprHistoryMoveLeft('applicantId')"
                      @move-right="apprHistoryMoveRight('applicantId')"
                      @resize="(w) => apprHistoryOnResize('applicantId', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ getApplicantDisplay(row) }}
                  </template>
                </el-table-column>
                <el-table-column
                  :width="apprHistoryColWidth('submittedBy', 140)"
                  show-overflow-tooltip
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.submittedByColumn')"
                      :width="apprHistoryColWidth('submittedBy', 140)"
                      :has-filter="apprHistoryHasFilter('submittedBy')"
                      :sort-direction="apprHistorySortDirection('submittedBy')"
                      :is-grouped="apprHistoryIsGrouped('submittedBy')"
                      :can-move-left="apprHistoryCanMoveLeft('submittedBy')"
                      :can-move-right="apprHistoryCanMoveRight('submittedBy')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('submittedBy', 'ASC')"
                      @sort-desc="onApprHistorySort('submittedBy', 'DESC')"
                      @group-by="onApprHistoryGroup('submittedBy')"
                      @filter="apprHistoryOpenFilter('submittedBy', t('permission.submittedByColumn'))"
                      @clear-filter="onApprHistoryClearFilter('submittedBy')"
                      @move-left="apprHistoryMoveLeft('submittedBy')"
                      @move-right="apprHistoryMoveRight('submittedBy')"
                      @resize="(w) => apprHistoryOnResize('submittedBy', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ getSubmitterDisplay(row) }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="requestType"
                  :width="apprHistoryColWidth('requestType', 140)"
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.requestType')"
                      :width="apprHistoryColWidth('requestType', 140)"
                      :has-filter="apprHistoryHasFilter('requestType')"
                      :sort-direction="apprHistorySortDirection('requestType')"
                      :is-grouped="apprHistoryIsGrouped('requestType')"
                      :can-move-left="apprHistoryCanMoveLeft('requestType')"
                      :can-move-right="apprHistoryCanMoveRight('requestType')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('requestType', 'ASC')"
                      @sort-desc="onApprHistorySort('requestType', 'DESC')"
                      @group-by="onApprHistoryGroup('requestType')"
                      @filter="apprHistoryOpenFilter('requestType', t('permission.requestType'))"
                      @clear-filter="onApprHistoryClearFilter('requestType')"
                      @move-left="apprHistoryMoveLeft('requestType')"
                      @move-right="apprHistoryMoveRight('requestType')"
                      @resize="(w) => apprHistoryOnResize('requestType', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    <el-tag
                      :type="getRequestTypeTag(row.requestType)"
                      size="small"
                    >
                      {{ getRequestTypeLabel(row.requestType) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column :width="apprHistoryColWidth('requestTarget', 180)">
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.requestTarget')"
                      :width="apprHistoryColWidth('requestTarget', 140)"
                      :has-filter="apprHistoryHasFilter('requestTarget')"
                      :sort-direction="apprHistorySortDirection('requestTarget')"
                      :is-grouped="apprHistoryIsGrouped('requestTarget')"
                      :can-move-left="apprHistoryCanMoveLeft('requestTarget')"
                      :can-move-right="apprHistoryCanMoveRight('requestTarget')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('requestTarget', 'ASC')"
                      @sort-desc="onApprHistorySort('requestTarget', 'DESC')"
                      @group-by="onApprHistoryGroup('requestTarget')"
                      @filter="apprHistoryOpenFilter('requestTarget', t('permission.requestTarget'))"
                      @clear-filter="onApprHistoryClearFilter('requestTarget')"
                      @move-left="apprHistoryMoveLeft('requestTarget')"
                      @move-right="apprHistoryMoveRight('requestTarget')"
                      @resize="(w) => apprHistoryOnResize('requestTarget', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ getTargetName(row) }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="status"
                  :width="apprHistoryColWidth('status', 110)"
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('permission.status')"
                      :width="apprHistoryColWidth('status', 140)"
                      :has-filter="apprHistoryHasFilter('status')"
                      :sort-direction="apprHistorySortDirection('status')"
                      :is-grouped="apprHistoryIsGrouped('status')"
                      :can-move-left="apprHistoryCanMoveLeft('status')"
                      :can-move-right="apprHistoryCanMoveRight('status')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('status', 'ASC')"
                      @sort-desc="onApprHistorySort('status', 'DESC')"
                      @group-by="onApprHistoryGroup('status')"
                      @filter="apprHistoryOpenFilter('status', t('permission.status'))"
                      @clear-filter="onApprHistoryClearFilter('status')"
                      @move-left="apprHistoryMoveLeft('status')"
                      @move-right="apprHistoryMoveRight('status')"
                      @resize="(w) => apprHistoryOnResize('status', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    <el-tag
                      :type="getStatusType(row.status)"
                      size="small"
                    >
                      {{ getStatusLabel(row.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  prop="approverComment"
                  :width="apprHistoryColWidth('approverComment', 150)"
                  show-overflow-tooltip
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('approval.comment')"
                      :width="apprHistoryColWidth('approverComment', 140)"
                      :has-filter="apprHistoryHasFilter('approverComment')"
                      :sort-direction="apprHistorySortDirection('approverComment')"
                      :is-grouped="apprHistoryIsGrouped('approverComment')"
                      :can-move-left="apprHistoryCanMoveLeft('approverComment')"
                      :can-move-right="apprHistoryCanMoveRight('approverComment')"
                      :date-like="false"
                      @sort-asc="onApprHistorySort('approverComment', 'ASC')"
                      @sort-desc="onApprHistorySort('approverComment', 'DESC')"
                      @group-by="onApprHistoryGroup('approverComment')"
                      @filter="apprHistoryOpenFilter('approverComment', t('approval.comment'))"
                      @clear-filter="onApprHistoryClearFilter('approverComment')"
                      @move-left="apprHistoryMoveLeft('approverComment')"
                      @move-right="apprHistoryMoveRight('approverComment')"
                      @resize="(w) => apprHistoryOnResize('approverComment', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                </el-table-column>
                <el-table-column
                  prop="approvedAt"
                  :width="apprHistoryColWidth('approvedAt', 170)"
                >
                  <template #header>
                                        <PortalListColumnHeader
                      :label="t('approval.processedAt')"
                      :width="apprHistoryColWidth('approvedAt', 140)"
                      :has-filter="apprHistoryHasFilter('approvedAt')"
                      :sort-direction="apprHistorySortDirection('approvedAt')"
                      :is-grouped="apprHistoryIsGrouped('approvedAt')"
                      :can-move-left="apprHistoryCanMoveLeft('approvedAt')"
                      :can-move-right="apprHistoryCanMoveRight('approvedAt')"
                      :date-like="true"
                      @sort-asc="onApprHistorySort('approvedAt', 'ASC')"
                      @sort-desc="onApprHistorySort('approvedAt', 'DESC')"
                      @group-by="onApprHistoryGroup('approvedAt')"
                      @filter="apprHistoryOpenFilter('approvedAt', t('approval.processedAt'))"
                      @clear-filter="onApprHistoryClearFilter('approvedAt')"
                      @move-left="apprHistoryMoveLeft('approvedAt')"
                      @move-right="apprHistoryMoveRight('approvedAt')"
                      @resize="(w) => apprHistoryOnResize('approvedAt', w)"
                      @resize-end="apprHistoryOnResizeEnd"
                    />
                  </template>
                  <template #default="{ row }">
                    {{ formatDateTime(row.approvedAt || row.updatedAt) }}
                  </template>
                </el-table-column>
              </el-table>
              <PortalListPagination
                v-model:current-page="approverHistoryPagination.page"
                v-model:page-size="approverHistoryPagination.size"
                :disabled="loadingApproverHistory"
                :total="approvalHistoryTotal"
                :visible="true"
                @change="onApprHistoryPageChange"
              />
              <PortalListFilterDialog
                v-model="apprHistoryFilterDialogVisible"
                :title="apprHistoryFilterDialogField
                  ? `${t('mainTableView.colFilterBy')}: ${apprHistoryFilterDialogField.label}`
                  : t('mainTableView.colFilterBy')"
                :initial="apprHistoryFilterDialogField
                  ? apprHistoryColState.filters[apprHistoryFilterDialogField.field]
                  : null"
                @apply="onApprHistoryApplyFilter"
                @clear="onApprHistoryClearFilter()"
              />
            </template>
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
import PortalListPagination from '@/components/portal-list/PortalListPagination.vue'
import PortalListColumnHeader from '@/components/portal-list/PortalListColumnHeader.vue'
import PortalListFilterDialog from '@/components/portal-list/PortalListFilterDialog.vue'
import { usePortalListColumnState } from '@/composables/usePortalListColumnState'
import {
  applyGroupHeaders,
  isPortalListGroupHeader,
  normalizeGroupCounts,
  portalListGroupSpanMethod,
  type PortalListColumnFilter,
  type PortalListSortDirection,
} from '@/utils/portalListGridRuntime'
import type { PermissionRequestRecord } from '@/api/permission'

const { t } = useI18n()
const pendingApprovalStore = usePendingApprovalStore()
const primaryWorkTab = ref<PrimaryWorkTab>('myRequests')

const PENDING_FIELDS = ['requestType', 'requestTarget', 'beneficiary', 'submittedBy', 'reason', 'createdAt']
const HISTORY_FIELDS = ['requestType', 'requestTarget', 'beneficiary', 'submittedBy', 'reason', 'status', 'approverComment', 'createdAt', 'updatedAt']
const APPR_PENDING_FIELDS = ['requestType', 'requestTarget', 'applicant', 'beneficiary', 'submittedBy', 'reason', 'createdAt']
const APPR_HISTORY_FIELDS = ['applicantId', 'submittedBy', 'requestType', 'requestTarget', 'status', 'createdAt', 'approvedAt']

const pendingCols = usePortalListColumnState('permissions-pending')
const {
  state: pendingColState,
  filterDialogVisible: pendingFilterDialogVisible,
  filterDialogField: pendingFilterDialogField,
  width: pendingColWidth,
  onResize: pendingOnResize,
  onResizeEnd: pendingOnResizeEnd,
  toggleSort: pendingToggleSort,
  toggleGroup: pendingToggleGroup,
  moveLeft: pendingMoveLeft,
  moveRight: pendingMoveRight,
  canMoveLeft: pendingCanMoveLeft,
  canMoveRight: pendingCanMoveRight,
  ensureOrder: pendingEnsureOrder,
  orderedColumnFields: pendingOrderedColumnFields,
  openFilter: pendingOpenFilter,
  applyFilter: pendingApplyFilter,
  clearFilter: pendingClearFilter,
  hasFilter: pendingHasFilter,
  sortDirection: pendingSortDirection,
  isGrouped: pendingIsGrouped,
  activeFilters: pendingActiveFilters,
} = pendingCols
pendingEnsureOrder(PENDING_FIELDS)

const historyCols = usePortalListColumnState('permissions-history')
const {
  state: historyColState,
  filterDialogVisible: historyFilterDialogVisible,
  filterDialogField: historyFilterDialogField,
  width: historyColWidth,
  onResize: historyOnResize,
  onResizeEnd: historyOnResizeEnd,
  toggleSort: historyToggleSort,
  toggleGroup: historyToggleGroup,
  moveLeft: historyMoveLeft,
  moveRight: historyMoveRight,
  canMoveLeft: historyCanMoveLeft,
  canMoveRight: historyCanMoveRight,
  ensureOrder: historyEnsureOrder,
  orderedColumnFields: historyOrderedColumnFields,
  openFilter: historyOpenFilter,
  applyFilter: historyApplyFilter,
  clearFilter: historyClearFilter,
  hasFilter: historyHasFilter,
  sortDirection: historySortDirection,
  isGrouped: historyIsGrouped,
  activeFilters: historyActiveFilters,
} = historyCols
historyEnsureOrder(HISTORY_FIELDS)

const apprPendingCols = usePortalListColumnState('permissions-approver-pending')
const {
  state: apprPendingColState,
  filterDialogVisible: apprPendingFilterDialogVisible,
  filterDialogField: apprPendingFilterDialogField,
  width: apprPendingColWidth,
  onResize: apprPendingOnResize,
  onResizeEnd: apprPendingOnResizeEnd,
  toggleSort: apprPendingToggleSort,
  toggleGroup: apprPendingToggleGroup,
  moveLeft: apprPendingMoveLeft,
  moveRight: apprPendingMoveRight,
  canMoveLeft: apprPendingCanMoveLeft,
  canMoveRight: apprPendingCanMoveRight,
  ensureOrder: apprPendingEnsureOrder,
  orderedColumnFields: apprPendingOrderedColumnFields,
  openFilter: apprPendingOpenFilter,
  applyFilter: apprPendingApplyFilter,
  clearFilter: apprPendingClearFilter,
  hasFilter: apprPendingHasFilter,
  sortDirection: apprPendingSortDirection,
  isGrouped: apprPendingIsGrouped,
  activeFilters: apprPendingActiveFilters,
} = apprPendingCols
apprPendingEnsureOrder(APPR_PENDING_FIELDS)

const apprHistoryCols = usePortalListColumnState('permissions-approver-history')
const {
  state: apprHistoryColState,
  filterDialogVisible: apprHistoryFilterDialogVisible,
  filterDialogField: apprHistoryFilterDialogField,
  width: apprHistoryColWidth,
  onResize: apprHistoryOnResize,
  onResizeEnd: apprHistoryOnResizeEnd,
  toggleSort: apprHistoryToggleSort,
  toggleGroup: apprHistoryToggleGroup,
  moveLeft: apprHistoryMoveLeft,
  moveRight: apprHistoryMoveRight,
  canMoveLeft: apprHistoryCanMoveLeft,
  canMoveRight: apprHistoryCanMoveRight,
  ensureOrder: apprHistoryEnsureOrder,
  orderedColumnFields: apprHistoryOrderedColumnFields,
  openFilter: apprHistoryOpenFilter,
  applyFilter: apprHistoryApplyFilter,
  clearFilter: apprHistoryClearFilter,
  hasFilter: apprHistoryHasFilter,
  sortDirection: apprHistorySortDirection,
  isGrouped: apprHistoryIsGrouped,
  activeFilters: apprHistoryActiveFilters,
} = apprHistoryCols
apprHistoryEnsureOrder(APPR_HISTORY_FIELDS)

const orderedPendingFields = computed(() => [...pendingOrderedColumnFields(PENDING_FIELDS), 'actions'])
const orderedHistoryFields = computed(() => historyOrderedColumnFields(HISTORY_FIELDS))
const orderedApprPendingFields = computed(() => [...apprPendingOrderedColumnFields(APPR_PENDING_FIELDS), 'actions'])
const orderedApprHistoryFields = computed(() => apprHistoryOrderedColumnFields(APPR_HISTORY_FIELDS))

function permCell(row: PermissionRequestRecord, field: string): unknown {
  switch (field) {
    case 'requestType': return getRequestTypeLabel(row.requestType)
    case 'requestTarget': return getTargetName(row)
    case 'beneficiary': return row.applicantUsername || row.applicantId || '-'
    case 'submittedBy':
      return row.submittedByUserId && row.submittedByUserId !== row.applicantId
        ? (row.submittedByUsername || row.submittedByUserId)
        : t('permission.selfBeneficiary')
    case 'applicant': return getApplicantDisplay(row)
    case 'status': return getStatusLabel(row.status)
    case 'createdAt': return formatDateTime(row.createdAt)
    case 'approvedAt': return formatDateTime(row.approvedAt || row.updatedAt)
    case 'processedAt': return formatDateTime(row.updatedAt || row.processedAt)
    default: return (row as Record<string, unknown>)[field]
  }
}

function buildDisplay(
  rows: PermissionRequestRecord[],
  state: { groupBy: string | null },
  groupCounts: Record<string, number> | null,
) {
  return applyGroupHeaders(
    rows as unknown as Record<string, unknown>[],
    state.groupBy,
    (r, f) => permCell(r as unknown as PermissionRequestRecord, f),
    groupCounts,
  )
}

// 纯展示/格式化辅助（状态、类型标签、目标名称、时间格式化等）
const {
  getApplicantDisplay,
  getSubmitterDisplay,
  rowRemovalKey,
  removalRowLabel,
  beneficiaryOptionLabel,
  canCancelAsBeneficiary,
  getStatusType,
  getStatusLabel,
  getRequestTypeTag,
  getRequestTypeLabel,
  getTargetName,
  formatDateTime
} = usePermissionFormatters(t)

// 我的业务单元角色
const { loadingMyBuRoles, myBuRoles, loadMyBuRoles } = useMyBuRoles()

// 我的申请（进行中 / 已完成、取消申请）
const {
  myRequestTab,
  loadingPending,
  loadingHistory,
  pendingList,
  historyList,
  pendingCount,
  pendingTotal,
  historyTotal,
  pendingGroupCounts,
  historyGroupCounts,
  pendingPagination,
  historyPagination,
  loadPendingRequests,
  loadHistoryRequests,
  cancelRequest
} = useMyRequests(t)

// 审批侧（审批人列表、批准/拒绝）
const {
  approvalTab,
  isApprover,
  approverPendingList,
  approverHistoryList,
  loadingApproverPending,
  loadingApproverHistory,
  approveDialogVisible,
  rejectDialogVisible,
  currentApproverRequest,
  approveComment,
  rejectComment,
  submittingApproval,
  approvalPendingCount,
  approvalPendingTotal,
  approvalHistoryTotal,
  approvalPendingGroupCounts,
  approvalHistoryGroupCounts,
  approverPendingPagination,
  approverHistoryPagination,
  checkApproverStatus,
  loadApproverPending,
  loadApproverHistory,
  showApproveDialog,
  showRejectDialog,
  handleApprove,
  handleReject
} = useApprovals(t, {
  loadPendingRequests: () => {
    void loadPendingRequests(chromeOpts(pendingColState, pendingActiveFilters))
  },
  loadHistoryRequests: () => {
    void loadHistoryRequests(chromeOpts(historyColState, historyActiveFilters))
  },
  fetchPendingCount: () => pendingApprovalStore.fetchPendingCount()
})

function chromeOpts(
  state: { sort: { field: string; direction: PortalListSortDirection } | null; groupBy: string | null },
  activeFilters: () => Record<string, PortalListColumnFilter>,
) {
  const filters = activeFilters()
  return {
    sortField: state.sort?.field,
    sortDirection: state.sort?.direction,
    filters: Object.keys(filters).length ? JSON.stringify(filters) : undefined,
    groupBy: state.groupBy || undefined,
  }
}

const displayPendingRows = computed(() => buildDisplay(
  pendingList.value, pendingColState, normalizeGroupCounts(pendingGroupCounts.value),
))
const displayHistoryRows = computed(() => buildDisplay(
  historyList.value, historyColState, normalizeGroupCounts(historyGroupCounts.value),
))
const displayApprPendingRows = computed(() => buildDisplay(
  approverPendingList.value, apprPendingColState, normalizeGroupCounts(approvalPendingGroupCounts.value),
))
const displayApprHistoryRows = computed(() => buildDisplay(
  approverHistoryList.value, apprHistoryColState, normalizeGroupCounts(approvalHistoryGroupCounts.value),
))

function groupRowClassName({ row }: { row: unknown }) {
  return isPortalListGroupHeader(row) ? 'group-header-row' : ''
}
function pendingSpanMethod({ row, columnIndex }: { row: unknown; columnIndex: number }) {
  return portalListGroupSpanMethod(row, columnIndex, PENDING_FIELDS.length, 0)
}
function historySpanMethod({ row, columnIndex }: { row: unknown; columnIndex: number }) {
  return portalListGroupSpanMethod(row, columnIndex, HISTORY_FIELDS.length, 0)
}
function apprPendingSpanMethod({ row, columnIndex }: { row: unknown; columnIndex: number }) {
  return portalListGroupSpanMethod(row, columnIndex, APPR_PENDING_FIELDS.length, 0)
}
function apprHistorySpanMethod({ row, columnIndex }: { row: unknown; columnIndex: number }) {
  return portalListGroupSpanMethod(row, columnIndex, APPR_HISTORY_FIELDS.length, 0)
}

function reloadPending() {
  void loadPendingRequests(chromeOpts(pendingColState, pendingActiveFilters))
}
function reloadHistory() {
  void loadHistoryRequests(chromeOpts(historyColState, historyActiveFilters))
}
function reloadApprPending() {
  void loadApproverPending(chromeOpts(apprPendingColState, apprPendingActiveFilters))
}
function reloadApprHistory() {
  void loadApproverHistory(chromeOpts(apprHistoryColState, apprHistoryActiveFilters))
}

function onApprovalTabChange(tab: string | number) {
  if (String(tab) === 'approvalHistory') {
    reloadApprHistory()
  }
}

function onPendingPageChange() {
  reloadPending()
}
function onHistoryPageChange() {
  reloadHistory()
}
function onApprPendingPageChange() {
  reloadApprPending()
}
function onApprHistoryPageChange() {
  reloadApprHistory()
}

function onPendingSort(field: string, direction: PortalListSortDirection) {
  pendingToggleSort(field, direction)
  pendingPagination.page = 1
  reloadPending()
}
function onPendingGroup(field: string) {
  pendingToggleGroup(field)
  pendingPagination.page = 1
  reloadPending()
}
function onPendingApplyFilter(filter: PortalListColumnFilter) {
  pendingApplyFilter(filter)
  pendingPagination.page = 1
  reloadPending()
}
function onPendingClearFilter(field?: string) {
  pendingClearFilter(field)
  pendingPagination.page = 1
  reloadPending()
}

function onHistorySort(field: string, direction: PortalListSortDirection) {
  historyToggleSort(field, direction)
  historyPagination.page = 1
  reloadHistory()
}
function onHistoryGroup(field: string) {
  historyToggleGroup(field)
  historyPagination.page = 1
  reloadHistory()
}
function onHistoryApplyFilter(filter: PortalListColumnFilter) {
  historyApplyFilter(filter)
  historyPagination.page = 1
  reloadHistory()
}
function onHistoryClearFilter(field?: string) {
  historyClearFilter(field)
  historyPagination.page = 1
  reloadHistory()
}

function onApprPendingSort(field: string, direction: PortalListSortDirection) {
  apprPendingToggleSort(field, direction)
  approverPendingPagination.page = 1
  reloadApprPending()
}
function onApprPendingGroup(field: string) {
  apprPendingToggleGroup(field)
  approverPendingPagination.page = 1
  reloadApprPending()
}
function onApprPendingApplyFilter(filter: PortalListColumnFilter) {
  apprPendingApplyFilter(filter)
  approverPendingPagination.page = 1
  reloadApprPending()
}
function onApprPendingClearFilter(field?: string) {
  apprPendingClearFilter(field)
  approverPendingPagination.page = 1
  reloadApprPending()
}

function onApprHistorySort(field: string, direction: PortalListSortDirection) {
  apprHistoryToggleSort(field, direction)
  approverHistoryPagination.page = 1
  reloadApprHistory()
}
function onApprHistoryGroup(field: string) {
  apprHistoryToggleGroup(field)
  approverHistoryPagination.page = 1
  reloadApprHistory()
}
function onApprHistoryApplyFilter(filter: PortalListColumnFilter) {
  apprHistoryApplyFilter(filter)
  approverHistoryPagination.page = 1
  reloadApprHistory()
}
function onApprHistoryClearFilter(field?: string) {
  apprHistoryClearFilter(field)
  approverHistoryPagination.page = 1
  reloadApprHistory()
}

function pendingColumnLabel(field: string): string {
  const map: Record<string, string> = {
    requestType: t('permission.requestType'),
    requestTarget: t('permission.requestTarget'),
    beneficiary: t('permission.beneficiaryColumn'),
    submittedBy: t('permission.submittedByColumn'),
    reason: t('permission.reason'),
    createdAt: t('permission.applyTime'),
    actions: t('common.actions'),
    status: t('permission.status'),
    processedAt: t('permission.processTime'),
    approvedAt: t('approval.processedAt'),
    updatedAt: t('permission.approvedAt'),
    approverComment: t('approval.comment'),
    applicant: t('permission.applicant'),
    applicantId: t('permission.beneficiaryColumn'),
  }
  return map[field] ?? field
}
function pendingWidthFallback(field: string): number {
  const map: Record<string, number> = {
    requestType: 160, requestTarget: 160, beneficiary: 140, submittedBy: 130,
    reason: 160, createdAt: 170, actions: 150, status: 120, processedAt: 170, applicant: 140,
  }
  return map[field] ?? 140
}

const showPendingApprovalsBanner = computed(() =>
  shouldShowPendingApprovalsBanner({
    isApprover: isApprover.value,
    approvalPendingCount: approvalPendingCount.value
  })
)

function onMyRequestTabChange(tab: string | number) {
  if (String(tab) === 'completed') {
    reloadHistory()
  } else {
    reloadPending()
  }
}

function onPrimaryWorkDomainChange(domain: string | number | boolean) {
  if (domain === 'approvals') {
    reloadApprPending()
    if (approvalTab.value === 'approvalHistory') {
      reloadApprHistory()
    }
  }
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
} = useApplyPermission(t, { loadPendingRequests: reloadPending, loadHistoryRequests: reloadHistory })

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
} = useExitBu(t, { loadPendingRequests: reloadPending, loadHistoryRequests: reloadHistory, loadMyBuRoles })

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
  loadPendingRequests: reloadPending,
  loadHistoryRequests: reloadHistory,
  loadMyBuRoles,
  loadExitBuMemberships
})

// 初始化
onMounted(async () => {
  await checkApproverStatus()
  loadMyBuRoles()
  loadExitBuMemberships()
  reloadPending()
  reloadHistory()
  if (isApprover.value) {
    await reloadApprPending()
  }
  await pendingApprovalStore.fetchPendingCount()
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

  .proxy-tag {
    margin-left: 6px;
    vertical-align: middle;
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
