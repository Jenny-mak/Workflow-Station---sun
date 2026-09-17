# 采购申请需求

## Background & Goals
员工在线提交采购申请，经部门经理审批。

（人工备注：本段由业务方撰写，请勿改写。）

## Roles
## Business Process
## Data Requirements
- 金额字段保留 4 位小数。

## Forms & Views
## Notifications & Integrations
## Business Rules & Decisions
- 审批超过 3 天未处理时自动发送提醒邮件。

## Non-functional Requirements
## Open Questions
- 当前流程设计仅有 StartEvent_1 和 EndEvent_1，未体现员工提交采购申请、部门经理审批及超过 3 天未处理自动提醒邮件的流程支持，需确认后续流程节点设计。