# 后端规则（backend/**）

处理 `backend/` 下文件时自动加载。继承根 [CLAUDE.md](../CLAUDE.md) 的全局规则。

## 后端规则（自动同步）

> 下方区块由 `.claude/scripts/sync-cursor-rules.mjs` 自动维护。**不要手动编辑**——
> 新增/删除后端规则只改 `.cursor/rules/*.mdc`（`globs: backend/**`），下次会话自动归位到这里。

<!-- BEGIN cursor-rules:auto -->
@../.cursor/rules/api-design.mdc
@../.cursor/rules/backend-architecture.mdc
@../.cursor/rules/docker-k8s-config-sync.mdc
@../.cursor/rules/form-preview-fk-pk-runtime.mdc
@../.cursor/rules/function-unit-portability-consumers.mdc
@../.cursor/rules/jpa-entity.mdc
@../.cursor/rules/json-row-storage-no-physical-tables.mdc
@../.cursor/rules/testing.mdc
<!-- END cursor-rules:auto -->

> 关键提醒：分层方向 `Controller → Component → Service → Repository` 不可违反；
> 统一 `ApiResponse<T>` 包装；表名前缀 `dw_`/`ac_`/`up_`/`we_`；时间字段用 `Instant`。
> 改 `platform-common` 爆炸半径最大（影响全部下游）—— 见根 `cross-cutting` 规则。
