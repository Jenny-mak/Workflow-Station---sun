# 邮件发送实现与排障指南 (Developer Workstation)

## 1. 目的与范围

本文说明 Developer Workstation 中 **Email Connection** 的配置、测试、运行时发信路径，以及常见 SMTP 错误处理（含内网认证中继、STARTTLS、证书信任）。

覆盖路径：

- Connection 测试：`EmailConnectionComponentImpl` → `SmtpMailSender` → `SmtpTransportProperties`
- 流程发信：`SendEmailTaskDelegate` → `EmailSenderService`（凭据来自 admin-center `sys_email_connections`）
- 独立冒烟：`scripts/email-smtp-test`

## 2. 端到端架构

1. 用户在 `/dev/` → Function Unit → **Connections** 配置 Email Connection。
2. 后端持久化到 `public.dw_email_connections`。
3. 点击「测试连接」时调用链：
   - `EmailConnectionComponentImpl.testConnection(...)`
   - `SmtpMailSender.send(...)`
   - `SmtpTransportProperties.apply(...)`
4. JavaMail 按 host/port/TLS 连接 SMTP 并发送测试邮件。

关键代码：

| 模块 | 路径 |
|------|------|
| Connection CRUD / 测试 | `backend/developer-workstation/.../EmailConnectionComponentImpl.java` |
| SMTP 发送 | `backend/developer-workstation/.../util/SmtpMailSender.java` |
| 传输属性 | `backend/platform-common/.../mail/SmtpTransportProperties.java` |
| 流程发信 | `backend/workflow-engine-core/.../EmailSenderService.java` |
| 前端表单 | `frontend/developer-workstation/.../ConnectionDesigner.vue` |

## 3. UI 字段与后端映射

### 3.1 UI 字段

- **Email Address**（连接名 / 发件人地址）
- **Email Provider**：自定义 SMTP 选 `SMTP`
- **SMTP Host**、**Port**
- **Use TLS**
- **Username**、**Password**
- **From Name**（可选）
- **Direction**（通常 `OUTBOUND`）

### 3.2 后端映射

| UI | 字段 |
|----|------|
| Email Address | `name`、`fromEmail` |
| SMTP Host | `host` |
| Port | `port` |
| Use TLS | `useTls` |
| Username | `username`（留空表示无 SMTP 认证） |
| Password | `credentialEncrypted`（加密存储；库列 `credential_encrypted`） |
| From Name | `fromName` |

### 3.3 认证规则

- **仅当 username 与 password 均存在时**启用 `mail.smtp.auth`。
- 用户名留空：后端不保存认证凭据，按**匿名中继**发送。
- 填写用户名但密码为空：创建时校验失败；编辑时保留原密码（若已有）。
- 用户名与发件邮箱**通常不同**（如服务账号 `ADRES-SVC-HMS-NP`）；勿把邮箱地址当作 SMTP 登录名，除非 IT 明确说明相同。

## 4. SMTP 运行时行为

传输模式由 `port` + `useTls` 决定（`SmtpTransportProperties`）：

| 条件 | 模式 | JavaMail 要点 |
|------|------|----------------|
| `useTls = false` | **PLAIN** | `starttls.enable=false`，`ssl.enable=false` |
| `useTls = true` 且 `port = 465` | **SSL** | 隐式 SSL |
| `useTls = true` 且 `port ≠ 465` | **STARTTLS** | `starttls.enable=true`，`starttls.required=true`；**含 port 25** |

TLS 启用时自动将 SMTP host 加入 `mail.smtp.ssl.trust`；可通过环境变量扩展：

- `SMTP_SSL_TRUST` — 额外信任主机名（逗号分隔）
- `SMTP_SSL_CHECK_SERVER_IDENTITY=false` — 仅内网中继 CN 不匹配时使用

## 5. 推荐配置

### 5.1 内网认证中继（Microsoft ESMTP / HSBC 等，port 25 或 587）

适用于 EHLO 含 `STARTTLS`、且 `MAIL FROM` 前要求 TLS 的中继。

| 字段 | 值 |
|------|-----|
| Provider | Custom SMTP |
| Host | IT 提供的中继主机名 |
| Port | `25` 或 `587` |
| **Use TLS** | **是** |
| Username | 服务账号（≠ 发件邮箱） |
| Password | 对应 SMTP 密码 |
| Direction | OUTBOUND |

> **注意：** 即使端口为 25，只要中继要求 STARTTLS，也必须 **Use TLS = 是**。仅当 IT 书面确认该源 IP 允许**明文匿名中继**时，才使用 §5.2。

### 5.2 内网明文中继（匿名，IT 明确允许）

| 字段 | 值 |
|------|-----|
| Port | `25` |
| Use TLS | **否** |
| Username / Password | 留空 |

### 5.3 公网 STARTTLS（587）

| 字段 | 值 |
|------|-----|
| Port | `587` |
| Use TLS | **是** |
| 认证 | 通常为是 |

若 JVM 不信任证书链，STARTTLS 握手阶段失败，见 §6.6。

## 6. 常见错误与含义

### 6.1 `AuthenticationFailedException: 535 5.7.3 Authentication unsuccessful`

**含义：** 已尝试 AUTH，但用户名/密码错误或账号无 SMTP 权限。

**处理：**

1. 与 SMTP 团队确认用户名、密码。
2. 确认该 host/port 是否允许 SMTP AUTH。
3. 确认登录名不是误用发件邮箱（服务账号与邮箱地址常不同）。

### 6.2 `530 5.7.57 Client was not authenticated to send anonymous mail during MAIL FROM`

**含义：** 服务器拒绝匿名发信。

**处理：**

1. 填写有效 Username + Password。
2. 或请 SMTP 团队将应用源 IP 加入匿名中继白名单。

### 6.3 `MessagingException: Got bad greeting ... 421 4.3.2 Service not available`

**含义：** 问候阶段服务不可用（限流、维护、实例故障）。

**处理：** 等待 1–5 分钟重试；向 SMTP 团队提供时间戳与完整错误。

### 6.4 `SMTPSendFailedException: 451 5.7.3 STARTTLS is required to send mail`

**含义：** 服务器策略要求先 STARTTLS 再发信。

**处理：**

1. **Use TLS = 是**（port 25 同样适用）。
2. 优先使用 port `587`（或 IT 指定端口）。
3. 若随后出现证书错误，按 §6.6 处理信任链。

### 6.5 `SocketTimeoutException: Connect timed out`

**含义：** 无法建立到 `host:port` 的 TCP 连接。

**处理：**

1. 确认 host/port。
2. 请网络团队放行**应用容器源**到 SMTP 端口的流量。
3. 必须在**实际运行容器/节点**上测试，不能仅在桌面环境验证。

### 6.6 `SunCertPathBuilderException / PKIX path building failed`

**含义：** JVM 不信任 SMTP 服务器返回的证书链。

**处理：**

1. 向 SMTP/安全团队获取完整证书链。
2. 导入应用使用的 JVM truststore，或配置 `SMTP_SSL_TRUST`。
3. CN 与连接主机名不一致时，可临时设 `SMTP_SSL_CHECK_SERVER_IDENTITY=false`（仅内网中继）。

## 7. 测试前依赖服务健康要求

测试前须保证以下服务健康：

- `developer-workstation`
- `admin-center`
- `kong`
- `redis`（Kong 插件可能依赖）

登录或 API 返回 `502` 时，先修复上游健康与 Kong 路由。

## 8. 快速验证流程

1. 浏览器访问 `http://localhost:3000/dev/`（或 `http://localhost:3102/dev/`）。
2. 创建或更新 Email Connection。
3. 保存配置。
4. 测试连接：填写真实收件人并发送。
5. 查看 `developer-workstation` 日志，关键字：
   - `[SMTP-TEST] request ...`
   - `[SMTP-TEST] begin ... mode=STARTTLS|PLAIN|SSL ...`
   - `[SMTP-CFG] host=... mode=...`
   - 成功或失败原因链

**成功标准：** UI 提示成功，且收件人收到测试邮件。

独立脚本（VPN 连通后）：

```powershell
cd scripts/email-smtp-test
.\run-email-test.ps1
```

## 9. 运维建议

- 各环境（DEV/UAT/PROD）维护标准 SMTP 参数表，避免随意改动。
- 记录已验证组合：`host`、`port`、`TLS`、`auth`、`username`。
- 维护 `535` / `530` / `421` / `451` / 超时 / 证书等 runbook。
- 文档中保留 SMTP 团队与 relay 负责人联系方式。

## 10. 附录：查看当前连接配置 SQL

```sql
select id,
       name,
       from_email,
       from_name,
       host,
       port,
       use_tls,
       username,
       (credential_encrypted is not null) as has_password
from public.dw_email_connections
order by id desc;
```

流程发信同步表（FU Deploy 后写入）：

```sql
select id, name, host, port, use_tls, username,
       (credential_encrypted is not null) as has_password
from public.sys_email_connections
order by id desc;
```

**已有库列迁移（`password_encrypted` → `credential_encrypted`）**：Docker 首次 init 会自动跑 `00-schema/80-rename-email-connection-credential.sql`；已有 Postgres 卷需手工执行该脚本（或 `deploy/init-scripts/init-database.ps1` 会按列表执行）。K8s GUI 离线 schema（`deploy/k8s/init-data/init-platform-schema/all-in-one-for-gui.sql`）的 CREATE 已用新列名，文末含同一套幂等 RENAME/ADD，单独执行该文件即可，不必再手工跑 80。

## 11. 前端访问路径

独立前端挂载在 `/dev/`：

- `http://localhost:3102/dev/`
- `http://localhost:3000/dev/`（经 Edge / 统一入口）

直接访问 `http://localhost:3102/` 可能显示 Nginx 默认页，属预期行为。

## 12. 环境变量（Docker / 本地）

| 变量 | 用途 |
|------|------|
| `SSRF_ALLOWED_HOSTS` | 允许 Connection 测试连接的 SMTP 主机名（内网中继解析到私网 IP 时必填） |
| `SMTP_SSL_TRUST` | 额外信任的 SMTP 主机名 |
| `SMTP_SSL_CHECK_SERVER_IDENTITY` | 设为 `false` 可关闭主机名/CN 校验（仅内网） |

示例（`deploy/environments/dev/.env`）：

```env
SSRF_ALLOWED_HOSTS=localhost,activepieces,your-internal-smtp-relay.example.com
SMTP_SSL_TRUST=your-internal-smtp-relay.example.com
```

修改后重建 `developer-workstation` 与 `workflow-engine`。
