# AI / Agent Layer & External Automation Integrations — X-Ray

Repo: `/Users/qiweige/Desktop/PROJECTXXXSUN/Workflow-Station---sun` (branch `common_0701_timeline`, working-tree state incl. uncommitted AI-layer changes).
All paths below are repo-absolute. Status labels: **Confirmed** (code + tests + docs agree, tested per docs), **Partial** (implemented but incomplete/unverified in prod), **Mocked**, **Dead**, **Unknown**.

---

## 1. AI Generation in Developer Workstation — Status: Partial (2026-07-29 re-implemented against the group AI gateway; **not yet run end-to-end** — the gateway is only reachable from the corporate cluster)

### 1.1 What it is

A 3-phase conversational "generate a whole Function Unit with AI" feature inside the Developer Workstation. Phases: `REQUIREMENTS → DESIGN → GENERATION` (`enums/AiPhase.java`), modes `NEW`/`MODIFY` (auto-detected). The final GENERATION phase emits a full **AiGeneratedData** payload — table definitions, form definitions, action definitions, DMN decision definitions, table relations (FKs), BPMN process definition, and an icon — which, on user "Apply", is validated and written into the Function Unit's design entities. So yes: **tables + forms + actions + decisions + FK relations + BPMN, i.e. a complete function unit**, not just one artifact.

Evidence for what gets written: `AiWriteServiceImpl.applyGeneratedData()` clears then writes tables, FKs (`writeForeignKeys` L234), table relations (L285), forms (L320), actions (L610), decisions (L643), process/BPMN (L667) — `backend/developer-workstation/src/main/java/com/developer/service/impl/AiWriteServiceImpl.java:41-680`.

### 1.2 End-to-end request flow

```mermaid
sequenceDiagram
    participant U as Developer (AiPanel.vue)
    participant K as Kong (/api/v1/ai-generation, 300s timeouts)
    participant C as AiGenerationController
    participant Comp as AiGenerationComponentImpl
    participant Svc as AiGenerationServiceImpl
    participant GW as Group AI gateway<br/>(OpenAI-compatible /chat/completions)

    U->>K: POST /ai-generation/chat/stream (fetch + ReadableStream, JWT + X-User-Id)
    K->>C: route (read/write timeout 300s)
    C->>C: resolveAmToken (X-AM-Token header, else AMToken cookie)
    C->>Comp: chatStream(request, userId, amToken)
    Comp->>Comp: assert workspace access + extend edit lock
    Comp->>Svc: createSession / restoreSession, saveMessage(USER)
    Comp->>Svc: serializeFunctionUnitContext(fuId) (100KB cap, 2-tier truncation)
    Comp->>Svc: getLatestDocuments(phase, mode)
    Comp-->>U: SseEmitter returned immediately (timeout = 2*300s + 60s)
    Note over Comp: async on taskExecutor
    Comp->>Svc: callAiModel(sessionId, msg, phase, mode, ctx, docs, scope, amToken)
    Svc->>Svc: buildPriorConversationHistory (full history minus current msg)
    Svc->>Svc: AiPromptBuilder.build — phase prompt (classpath ai-prompts/*.txt) + context sections
    Svc->>GW: POST <GROUP_AI_GATEWAY_URL> Authorization: Bearer <AMToken><br/>{messages:[{role:"user", content:prompt}]}
    GW-->>Svc: OpenAI response; choices[0].message.content carries the<br/>---REQUIREMENTS_DOC/DESIGN_DOC/GENERATED_DATA/PHASE_COMPLETE--- markers
    Svc->>Svc: AiResponseParser.parse → {reply, document, documentType, phaseComplete, generatedData}<br/>(+ field-metadata/configJson/BPMN normalization)
    Svc-->>Comp: response map (retry once on TIMEOUT/CALL_FAILED, +2s; model-side failures are not retried)
    Comp-->>U: SSE events: session → token → document → phase_complete → generated_data(+qualityScore) → done | error{errorCode, degradationOptions}
    U->>C: POST /ai-generation/{fuId}/apply {generatedData, sessionId, regenerateScope}
    C->>Comp: applyGeneratedData → normalize (MANY_TO_ONE→ONE_TO_MANY, crossFieldRules.targetField default) → validate → undo snapshot (30s TTL) → AiWriteService writes all entities
    Comp-->>U: event-SSE write_success / write_error; optional POST /{fuId}/undo within 30s
```

### 1.3 Backend anatomy (current working tree)

| Concern | Where | Notes |
|---|---|---|
| Controller | `backend/developer-workstation/src/main/java/com/developer/controller/AiGenerationController.java` | `/ai-generation/chat/stream` (SSE POST, L49), `/events/{fuId}` (long-lived SSE, L59), lock acquire/release/force-unlock (L69-118), sessions/messages (L120-134), phase update (L136), document versions/save (L148-181), `/{fuId}/apply` (L183), `/{fuId}/undo` (L195). All gated by `@RequireDeveloperPermission("FUNCTION_UNIT_UPDATE|VIEW")`. |
| Orchestrator | `component/impl/AiGenerationComponentImpl.java` | `chatStream` L84-266: lock renew → session → save user msg → load context/docs **in main thread** (transaction-bound; graceful degrade to null/empty on failure L117-122) → async webhook call with **superseded-emitter guard** (`emitIfCurrent` L134-141, prevents a stopped/restarted turn's stale events leaking into the new stream). Apply flow L324-384 with undo snapshot cache (`undoSnapshots`, 30s TTL, L55-58, L342-351). LLM-output normalizers: `normalizeTableRelations` (MANY_TO_ONE→swap→ONE_TO_MANY, L490) and `normalizeCrossFieldRules` (default `targetField` = last of `fields[]`, L456). Quality score computed and attached to `generated_data` event (L196-214). |
| Service | `service/impl/AiGenerationServiceImpl.java` | Session CRUD w/ status machine (ACTIVE→COMPLETED/CANCELLED only, L731-743); mode detection (any existing component ⇒ MODIFY, L195-212); message persistence (`dw_ai_messages`); document versioning (auto-increment per fuId+type, L249-269); context serialization w/ 100KB cap and 2-tier truncation (bpmnXml → form configJson rule arrays → whole configJson, L289-376); webhook call w/ history rebuild (L452-482); SSE via `AiSseEmitterManager` collaborator. |
| Prompt builder | `service/impl/AiPromptBuilder.java` + `src/main/resources/ai-prompts/{requirements,design,generation}.txt` | Java port of the retired AP flow's "Build Prompt" step (`GenAI/build_prompt.md`). The three system prompts are **verbatim** classpath resources (GENERATION already has the BPMN-constraint block the flow appended in code); the builder renders the same request-body map into one prompt string, so the model sees identical text before and after the migration. Missing/unknown phase falls back to REQUIREMENTS. Tests: `AiPromptBuilderTest.java`. |
| Gateway client | `service/impl/AiGatewayClient.java` | Non-streaming `POST <ai-generation.gateway.url>` with `Authorization: Bearer <AMToken>` and a standard OpenAI body `{messages:[{role:"user",content:prompt}]}` (`model` only if `ai-generation.gateway.model` is set — the URL path already selects the model). Dedicated RestTemplate, connect+read timeout = `ai-generation.gateway.timeout-seconds` (300s). Error handler is a no-op so 4xx/5xx bodies reach the parser; returns `{status, body}` (same shape the AP HTTP piece produced). |
| Credential | `AiGenerationController.resolveAmToken` + `frontend/.../utils/amToken.ts` | Per-user DSP **AMToken**, taken from the `X-AM-Token` header the frontend sets (falling back to the `AMToken` cookie on the request). The backend holds **no** shared AI key. Absent token ⇒ `AI_GATEWAY_TOKEN_MISSING` (fail-closed, never an anonymous call). Token stays in memory: not persisted, not logged. |
| SSRF guard | `AiGatewayClient.init()` | `@PostConstruct` validates the gateway URL against `ssrf.allowed-hosts` via `com.platform.common.security.SsrfProtection`. A blank URL only warns (the feature is switchable and DW must still boot); calls then fail with `AI_GATEWAY_NOT_CONFIGURED`. **If the gateway FQDN resolves to a private IP inside the cluster it must be added to `SSRF_ALLOWED_HOSTS`, or DW fails to start.** |
| Response parser | `service/impl/AiResponseParser.java` | Java port of the flow's "Parse Response" step (`GenAI/parse_response.md`), keeping every rule AP had earned: doc/marker extraction, 3-tier generatedData JSON parsing, length/precision/scale/defaultValue coerced to String (AiWriteService parses Strings), configJson string→object with invalid dropped to null, `formType TASK→MAIN`, BPMN Base64 decode + deterministic fallback XML, and the fail-loud rejection of BPMN with no task nodes. Two deliberate fixes vs. the JS: the ```` ``` ```` fence regex now matches a plain fence, and an empty `choices` no longer dumps the raw envelope into the chat (`AI_GATEWAY_EMPTY_RESPONSE`). Tests: `AiResponseParserTest.java`. |
| Stateless history | `AiGenerationServiceImpl.callAiModel` | `chat/completions` has no server-side session, so **every** call carries full prior conversation history (`buildPriorConversationHistory`, dedupes the just-saved current user message). The old AP-era `isSessionNotFoundError` sniffing + rebuild-and-resend branch was removed as dead. Test: `AiSessionMemoryRebuildTest.java`. |
| Resilience | `doCallAiWithRetry` | One retry after 2s sleep for `AI_WEBHOOK_TIMEOUT`/`AI_WEBHOOK_CALL_FAILED` (kept under the old names: the frontend's retryable-code list and i18n key both use them). Model-side failures (`AI_GATEWAY_HTTP_ERROR`/`_EMPTY_RESPONSE`/`_BAD_RESPONSE`, `AI_BPMN_NO_TASK_NODES`) are **not** retried — resending the same prompt earns the same refusal. On second failure throws with `extraData = {lastSuccessTime, degradationOptions: [SAVE_DRAFT, MANUAL_CREATE]}` → SSE `error` event. `lastAiCallSuccessTime` tracked. **No circuit breaker.** Tests: `AiGatewayResilienceProperties.java`. |
| SSE mgmt | `service/impl/AiSseEmitterManager.java` | Chat emitter timeout computed in ServiceImpl L667: `timeoutSeconds*2*1000 + 60s` = 660s; event emitter fixed 300s (`EVENT_EMITTER_TIMEOUT` L28). Superseded-emitter check `isChatEmitterSuperseded`. Tests: `AiSseEmitterManagementTest.java`, `AiSseTimeoutProperties.java`. |
| Exceptions | `exception/AiExceptionHandler.java` | `@Order(HIGHEST_PRECEDENCE)`: lock conflict→409, validation failed→422 (with `errors` detail), `AiGenerationException`→status by code (L89-98): NOT_FOUND codes→404, `AI_CONTEXT_TOO_LARGE`→413, `AI_WEBHOOK_TIMEOUT`→504, `AI_WEBHOOK_CALL_FAILED`/`EMPTY_RESPONSE`→502, else 500. 8-char traceId per error. |
| Validation | `service/impl/AiValidationServiceImpl.java` + `AiStructureValidator`/`AiReferenceValidator`/`AiSecurityValidator` + `AiQualityScorer` | Runs before apply; failures → 422 with error list; warnings ride along in `write_success` event. |
| Locking | `service/impl/AiLockServiceImpl.java` | Per-FU edit lock, TTL 1800s, force-unlock request/response over the event SSE channel (config `ai-generation.lock.*`). |

### 1.4 Request-body contract (verified by property tests)

`buildAiRequestBody` (`AiGenerationServiceImpl`) always includes: `sessionId`, `message`, `phase`, `mode`, `functionUnitId`, `context` (pre-serialized JSON **string**, a habit inherited from AP expression rendering; `AiPromptBuilder` serializes non-String values anyway), `existingDocuments` (plain-text formatted, 50k-char/doc cap, L508-511 + L758-785), `conversationHistory`, `schemaMetadata` (enum lists FormType/TableType/ActionType + configJson extension specs + visibilityCondition operator list + newEntities incl. the "MANY_TO_ONE is NOT valid" instruction, L535-573), `includeExplanations: true`, `regenerateScope` (default `ALL`; enum ALL/TABLES/FORMS/ACTIONS/DECISIONS/PROCESS/TABLE_RELATIONS).

Note this body is now an **internal** structure — it never leaves the JVM. It is what `AiPromptBuilder` renders into the prompt; only the rendered prompt goes over the wire.

Tests (renamed 2026-07-29 off the retired "webhook" terminology):
- `src/test/java/com/developer/service/AiRequestBodyProperties.java` — jqwik properties: schemaMetadata completeness, `includeExplanations` always true, regenerateScope defaulting.
- `src/test/java/com/developer/service/AiGatewayResilienceProperties.java` — success updates `lastAiCallSuccessTime`; non-retryable failure leaves it untouched.
- `AiPromptBuilderTest.java` / `AiResponseParserTest.java` — the two ports' behaviour (see §1.3).
- **Still missing:** no test pins the gateway URL/timeout config wiring (the old `AiN8NWorkflowConfigTest` was deleted, not renamed).

### 1.5 Config inventory (application.yml)

`backend/developer-workstation/src/main/resources/application.yml` (+ `application-docker.yml`):

| Key | Default | Env |
|---|---|---|
| `ai-generation.enabled` | `false` (dev compose + k8s configmaps set `true`) | `AI_GENERATION_ENABLED` |
| `ai-generation.gateway.url` | *(empty ⇒ `AI_GATEWAY_NOT_CONFIGURED`)* | `GROUP_AI_GATEWAY_URL` |
| `ai-generation.gateway.model` | *(empty ⇒ no `model` field in the body)* | `AI_GATEWAY_MODEL` |
| `ai-generation.gateway.api-key` | *(empty ⇒ per-user AMToken; non-empty overrides it and the gateway can no longer audit per user — local development only)* | `AI_GATEWAY_API_KEY` |
| `ai-generation.gateway.timeout-seconds` | `300` | `AI_GATEWAY_TIMEOUT_SECONDS` |
| `ai-generation.gateway.am-token-name` | `AMToken` | `DSP_AM_TOKEN_NAME` |
| `ssrf.allowed-hosts` | `localhost,activepieces` (docker: `activepieces` only) | `SSRF_ALLOWED_HOSTS` |
| `ai-generation.lock.ttl-seconds` | `1800` | `AI_GENERATION_LOCK_TTL` |
| `ai-generation.lock.force-unlock-timeout-seconds` | `60` | `AI_GENERATION_FORCE_UNLOCK_TIMEOUT` |
| `ai-generation.context.max-size-bytes` | `102400` | `AI_GENERATION_CONTEXT_MAX_SIZE` |

The feature needs **both** switches on: `ai-generation.enabled` (else `AiGenerationController` is not registered and `/ai-generation/**` 404s) and the frontend compile-time constant `AI_GENERATION_ENABLED` in `frontend/developer-workstation/src/utils/featureFlags.ts` (else no entry point renders). Both are `true` as of 2026-07-29.

---

## 2. Frontend AI UI (frontend/developer-workstation) — Status: Confirmed

- **`src/components/ai/AiPanel.vue`** (723 L): slide-in panel (docked or detached/draggable/resizable), header w/ session-history dropdown (status/mode/phase tags), lock-conflict overlay with "request force unlock" button. Body = ChatDialog (left) + DocumentPanel (right). `handleApply` (L514) → `aiGenerationApi.applyGeneratedData` → success toast + `dataApplied` emit (design canvas refresh); `write_success` from the *event* SSE avoids double-toast for the actor (L433-437).
- **`src/components/ai/ChatDialog.vue`** (987 L): message list (`ChatMessage.vue` + `MarkdownRenderer.vue`), phase indicator (`PhaseIndicator.vue`), regenerate-scope selector (L316), GenerationPreview with Apply/Regenerate (L158-159), **undo button with 30s countdown** (L166-173), inline document viewer.
- **`src/components/ai/GenerationPreview.vue`** (568 L): structured preview of generatedData (tables/forms/actions/decisions/relations/process incl. XML tree view via `XmlTreeView.vue`) + quality score; Apply/Regenerate emit up.
- **`src/composables/useAiChat.ts`** (400 L): SSE over **fetch + ReadableStream** (POST SSE; EventSource is GET-only, L58-61). Parses events `session/token/document/phase_complete/generated_data/validation_warning/error/done`; a 6-step generation progress value; retryable error codes `AI_WEBHOOK_TIMEOUT`/`AI_WEBHOOK_CALL_FAILED` (L272); degradation info captured (L277-282); **drafts of generatedData auto-saved to localStorage with 24h expiry** (L14-55, saved on each `generated_data` event L246-254, cleared after apply). Sends `X-User-Id` from the local user object plus `X-AM-Token` from `utils/amToken.ts` (`?am_token=` then the `AMToken` cookie) — the latter is the AI gateway's Bearer credential.
- Companion composables: `useAiLock`, `useAiSession`, `useAiEvents` (event SSE), `aiPanel/useAiPanelLayout|Sidebar`, `chatDialog/useChatDialogDraft|Preview`. API base: `src/api/aiGeneration.ts` (`AI_CHAT_STREAM_URL`, apply L31, undo L34).

User-visible flow: open AI panel on a function unit → lock acquired → chat through REQUIREMENTS (doc streamed into DocumentPanel, versioned, user-editable) → DESIGN → GENERATION → structured preview + quality score → Apply (validation errors 422 shown) → 30s undo window → design canvas refreshes.

---

## 3. Activepieces integration — Status: Confirmed (dev end-to-end; k8s manifests ready, cluster-side Partial/unverified)

Docs: `deploy/ACTIVEPIECES_INTEGRATION.md` (401 L, authoritative), `deploy/ACTIVEPIECES_USER_GUIDE.md`.

### 3.1 AP gateway ("shared account bridge", dev :8085)
Non-prod only: platform users enter the AP UI via a **shared AP account** through a cross-domain SSO handshake ("方案 B", 2026-06-30): admin-domain `GET /api/v1/admin/internal/ap/launch` (platform JWT cookie valid there) → server-side shared-account sign-in to AP → one-time Redis nonce (60s TTL) → browser jumps to AP domain `/__ap/bridge#nonce=…` → `GET /__ap/token?nonce=` exchanges for `{token, projectId}` → written to AP's localStorage → user is in as AP ADMIN. No platform cookie ever needed on the AP domain; AP token never in a URL. Backend lives in admin-center `com.admin.ap` (ApTokenController, ApBridgeNonceStore, ActivepiecesApiClient) per `ACTIVEPIECES_INTEGRATION.md` §2-3. K8s: `deploy/k8s/ap-gateway.yaml` (Istio Gateway+VirtualService rewriting `/__ap/bridge`,`/__ap/token` → admin-center-service, rest → activepieces-service; L23-79). Bootstrap: `deploy/k8s/ap-bootstrap-job.yaml` + `deploy/scripts/ap-bootstrap-shared-account.js` — idempotent sign-up **plus** `POST /api/v1/platforms` to finish onboarding (AP 0.84 CE sign-up alone leaves the account in ONBOARDING with `projectId=null`, §6). Prod = **runtime only**: `deploy/k8s/activepieces.yaml` exposes **only** `/api/v1/webhooks` through Istio (L225-246 comment "Webhook-only ingress"), no UI, no shared account.

### 3.2 AP deployment hardening (k8s/activepieces.yaml)
Image `…/activepieces:0.84.0` (L48); `AP_WEBHOOK_TIMEOUT_SECONDS=300` (L121-123, comment ties it to DW's 300s: keep AP ≤ DW so AP answers first — note the memory of "120" is outdated; current manifest says 300); offline policy `AP_PIECES_SYNC_MODE=NONE` + `AP_TELEMETRY_ENABLED=false` (0.84 has no `AP_PIECES_SOURCE` var — the once-configured `AP_PIECES_SOURCE=DB` was never read and has been removed) — piece catalog only from `piece_metadata` table. Approved-piece supply chain: `deploy/pieces/` (whitelist `pieces.json`, metadata seed SQL, tarballs, Dockerfile that pre-installs pieces into the worker's bun workspace — runtime zero-network; `NPM_CONFIG_REGISTRY` fail-closed `.invalid` default in uat/preprod because empty silently falls back to public npm).

### 3.3 Flows under git (`deploy/ap-flows/`, git-as-source publish channel)
- ~~`ai-function-unit-gen.json`~~ — **deleted 2026-07-29** along with `deploy/scripts/build-ai-fu-flow.js` and `deploy/pieces/AI Function Unit Generation.json`. It described the AI generation flow as `Catch Webhook → Code "Build Prompt" → piece-ai run_agent (deepseek-v4-pro) → Code "Parse Response" → Return Response`, but `piece-ai` no longer exists in the vendor tree, so any run of that flow would fail. **2026-07-29: AI Generate no longer goes through AP at all** — it calls the group AI gateway directly (§1), so nothing needs to be re-published to `ap-flows/`; the two Code steps live on as `AiPromptBuilder`/`AiResponseParser`, and their sources are kept for reference in `GenAI/build_prompt.md` and `GenAI/parse_response.md`. Leaving the file in `ap-flows/` was the actual hazard: `Jenkinsfile.ap-flows-publish` defaults to `all`, i.e. `ls deploy/ap-flows/*.json`, so a routine publish would have pushed a dead flow into production. The three phase prompts survive in git — `git show 6436f537:deploy/scripts/build-ai-fu-flow.js`. See VT-15 in `docs/ap-integration/VENDOR_TRIM_CHECKLIST.md`.
- `aptest.json` — demo flow for the service-task channel; `deploy/ap-demo/aptest-service-task.bpmn` is the matching demo BPMN.
- Publish pipeline: `deploy/scripts/ap-export.js` / `ap-import.js` (idempotent create→IMPORT_FLOW→LOCK_AND_PUBLISH→CHANGE_STATUS=ENABLED; AP CE has no Git Sync — EE-only, tested 404), Jenkins templates `deploy/ci/Jenkinsfile.ap-flows-export|publish`. Caveats: connections (credentials) never travel with flows — prod must pre-create same-named connections; flowId changes per environment (BPMN `ap:flowId` must be re-pointed).

### 3.4 Workflow service tasks → AP (Path B) — Status: Confirmed (dev e2e 2026-06-26)
BPMN service task with extension properties `serviceType=ap`, `ap:flowId`, optional `ap:webhookUrl|inputMapping|outputMapping|timeoutSeconds(120)|retryCount(3)`. At **deploy time** `ProcessDeploymentManager#bindActivepiecesServiceTasks` injects `flowable:delegateExpression=${apTaskExecutor}` (the missing link n8n never had — its designer wrote extension attrs but never a delegate, so the n8n service-task path never actually ran from the designer). Runtime: `com.workflow.component.ApTaskExecutor` (workflow-engine-core) builds `<activepieces.webhook-base-url>/api/v1/webhooks/<flowId>/sync` (SSRF-validated), POSTs synchronously with exponential-backoff retries, records to `wf_ap_execution_record`, maps the Return-Response JSON back to process variables via `ApVariableMappingUtil` (dot-path nesting). Designer UI: `ServiceTaskProperties.vue` type `ap` → `ApTaskPropertiesPanel.vue`. Action-mode REST (`POST /api/v1/ap/execute`) exists backend-side but **has no user-facing UI** (Partial, §11.6).

---

## 4. n8n — Status: Removed (2026-07; only dormant seed data remains)

- Engine/service-task channel: **removed** — "n8n 已整体移除，仅 developer-workstation 的 AI 生成 webhook 例外保留" (`ACTIVEPIECES_INTEGRATION.md` §11 preamble), and that exception has itself since been migrated to AP (the `AiN8N*` → `AiWebhook*` test renames in this working tree are the tail end of the cleanup).
- Remaining code traces: enum constant `N8N_ACTION` in `backend/developer-workstation/src/main/java/com/developer/enums/ActionType.java:37` — **intentionally kept**: the name is persisted in `dw_action_definitions.action_type` and in seed scripts, so renaming needs a data migration; the runtime is now carried by Activepieces. No other live n8n code remains (`AdminCenterClient#getN8nConfig` was deleted 2026-07 as dead code — the `/api/v1/admin/n8n-config` endpoint it called never existed in admin-center).
- Remaining infra: **none**. No `deploy/k8s/n8n.yaml`, no entry in `deploy/k8s/kustomization.yaml`, no service in `deploy/environments/dev/docker-compose.dev.yml`; no build or mirror script pulls an n8n image.
- Remaining files: **none**. `deploy/n8n-workflows/` and the dormant seed package `deploy/init-scripts/14-travel-expense-reimbursement/` were deleted 2026-07 (the latter was never invoked by `00-init-all.sh`, and its `N8N_ACTION` config pointed at `localhost:5678`), together with `TravelExpenseReimbursementUnitTest`, which only asserted on their text. Historical mentions survive in `12-simple-approval/03-form-table-bindings.sql:74` (a comment — init-scripts are append-only) and in `deploy/ACTIVEPIECES_INTEGRATION.md`, where the n8n→AP contrast is deliberate.

---

## 5. Superset / BI — Status: Confirmed in dev, Partial in prod (covered in depth by another agent)

`deploy/superset/` (Dockerfile, `superset_config.py`, `superset_security_manager.py`, `author-proxy/`), `deploy/k8s/workflow-station-superset.yaml`, `deploy/k8s/SUPERSET_SSO_GATEWAY.md`, doc `deploy/SUPERSET_SSO_INTEGRATION.md`. Superset 6.0, unified SSO: dev converged to single FQDN + path `localhost:3000/bi` (Superset reads `SUPERSET_APP_ROOT`); only `/bi/login/` is gated via nginx `auth_request` → admin-center `/internal/bi/superset/authorize` (platform JWT + `bi_rbac_mapping` → `X-Remote-*`/REMOTE_USER injection); all other paths pass with forged-header stripping. Prod still runs the older dual-subdomain split (author gateway via nginx reverse proxy + DENY) and is slated to converge to `/bi`. Embedded dashboards (user-portal iframe, guest tokens minted by admin-center with `BI_SUPERSET_USERNAME`) are gated on the admin-center side since 2026-09-15: dashboard sync copies Superset `dashboard_roles` into `bi_dashboard_registry.superset_role_ids`, and `BiDashboardAssignmentService.getUserDashboards` hides a role-restricted dashboard unless the user's `bi_rbac_mapping` roles intersect it (or include Superset's Admin role); Superset's guest-token API itself carries no roles. `SUPERSET_HOST` must include `SUPERSET_APP_ROOT` (dev: `…:8088/bi`) or guest-token minting 404s. Not part of the AI request path.

---

## 6. Kong — Status: Confirmed (dedicated SSE route for AI)

`deploy/kong/kong.yml.template:54-65`: a **separate Kong service** `developer-workstation-sse-service` routes `/api/v1/ai-generation` to `developer-workstation:8080` with `write_timeout`/`read_timeout` **300000 ms** (vs. the generic route), sized for the long SSE chat stream. Kong does not sit between DW and the AI gateway — that call is a direct outbound HTTPS request from the DW pod. Note the Kong read timeout (300s) < chat SSE emitter timeout (660s incl. retry headroom): a model call that exhausts timeout+retry (~600s) would have its SSE cut by Kong first — worst case the client sees a dropped stream instead of the structured `error` event.

---

## 7. Risks

1. **Unauthenticated webhook** — AP CE sync webhooks carry no auth ("AP CE webhook 免鉴权，无需 apiKey", `ACTIVEPIECES_INTEGRATION.md` §11.1). This no longer touches AI Generate (which left AP on 2026-07-29) but still applies to every service-task flow: in k8s `/api/v1/webhooks` is *deliberately* exposed externally for third-party triggers, so any flow is invokable by whoever learns its flowId.
2. **Secrets** — AI Generate holds **no** AI credential at all: the Bearer is the caller's own DSP AMToken, forwarded per request and never persisted or logged. AP's own `ACTIVEPIECES_{ENCRYPTION_KEY,JWT_SECRET,SHARED_PASSWORD,…}` live in k8s secrets (`secret/{uat,preprod}`). Shared-account password immutability is an operational foot-gun (§6 "铁律"). Residual: the AMToken is client-supplied, so a caller can only ever spend their own gateway quota — but DW does not validate it before forwarding, so a bad token surfaces as a gateway 401 rather than a local error.
3. **Prompt injection surface** — the request body concatenates *user message*, *stored conversation history*, *existing documents* (previously AI- or user-authored, user-editable via `POST /documents`), and the *function-unit context* (names/descriptions user-controlled) into one agent prompt. The Build-Prompt code adds a "data below is authoritative / you cannot call external tools" preamble, and defense-in-depth exists at the *output* boundary (AiStructureValidator/AiReferenceValidator/AiSecurityValidator + normalization before write, apply gated by `@RequireDeveloperPermission` + workspace access + validation), so injected instructions can at worst distort generated design data the same user then previews and applies. Residual risk is acceptable-by-design but real for markdown rendering of replies (MarkdownRenderer sanitization not audited here).
4. **AP down / slow** — DW: 300s connect/read timeout → `AI_WEBHOOK_TIMEOUT` → 1 retry after 2s → structured SSE `error` with `degradationOptions [SAVE_DRAFT, MANUAL_CREATE]` + `lastSuccessTime`; frontend shows retry button + degradation choices; generated-data drafts survive in localStorage 24h. No circuit breaker/bulkhead: concurrent chats each hold a taskExecutor thread up to ~10 min (timeout×2+2s); engine service tasks similarly block a Flowable thread (RestTemplate read timeout 10 min). A wedged AP degrades thread pools before failing fast.
5. **Timeout chain coherence** — now two knobs instead of three: DW `AI_GATEWAY_TIMEOUT_SECONDS` (300) vs. Kong SSE read timeout (300). They are equal, and Kong's does *not* cover the retry path (see §6), so a call that needs the retry loses its SSE stream first. The old AP 204-empty hazard is gone with AP.
6. **`X-User-Id` header** — `useAiChat.getAuthHeaders()` sends a client-supplied `X-User-Id`; authorization relies on the JWT-backed `SecurityContextUtils`/permission annotations, but any server code trusting the raw header would be spoofable — worth an audit.
7. **Test coverage gap** — no test pins the gateway URL/timeout config wiring (§1.4).
8. **Unverified end-to-end** — the gateway is only reachable from the corporate cluster, so the rewrite has unit/property coverage but **no** live run. First deploy should check, in order: DW boots (SSRF whitelist covers the gateway FQDN), the chat request carries `X-AM-Token`, and the gateway returns 200 rather than 401.
9. **AMToken lifetime** — the token is read per request from the browser; if it expires mid-session the next turn fails with a gateway 401 (`AI_GATEWAY_HTTP_ERROR`) rather than a refresh. No refresh path exists on the DW side.
