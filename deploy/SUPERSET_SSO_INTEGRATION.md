# Superset 统一 SSO 整改总览

把 Apache Superset（6.0）的登录接入平台统一 SSO：**作者/管理员**用平台账号一步进 Superset（不再单独登录），**嵌入看板查看者**不受影响。Superset 是 Apache-2.0，这套能力**零授权费**。

> 生产部署运维细节见 [k8s/SUPERSET_SSO_GATEWAY.md](k8s/SUPERSET_SSO_GATEWAY.md)。

---

## 1. 架构

同一个 Superset 实例，作者鉴权 + 嵌入不鉴权两类流量共存 + 封掉裸端口。区分方式在 **dev 与 prod 不同**：

- **dev（已收敛到单 FQDN + path，2026-07）**：作者 UI 与嵌入都挂在 `http://localhost:3000/bi`（和 `/admin`、`/portal`、`/dev` 同一个边缘、同一个模型），用**路径**区分——**只 gate `/bi/login/`**（注入 X-Remote-*），其余路径一律放行（剥离伪造头）。
- **prod（仍双子域，待收敛到 /bi）**：作者与嵌入分两个 host（下方 ASCII），用**主机名**区分。※ IKP 生产 FQDN 只给一个固定域名、子域难落地，故 prod 亦应比照 dev 收敛到 `INGRESS_HOST/bi`（见 §6）。

```
dev（单 FQDN + path）
  浏览器 → http://localhost:3000/bi/...  (nginx-edge :80)
            ├─ /bi/login/           → auth_request 到 admin-center /internal/bi/superset/authorize
            │                          → 校验平台 JWT，查 bi_rbac_mapping，注入 X-Remote-* → REMOTE_USER 登录
            └─ 其余 /bi/*（含 /embedded、/api、/static）→ 放行、剥离伪造 X-Remote-*
                                       （嵌入 guest token 自证；已登录作者靠 Superset session cookie）

prod（双子域，待改）
            ┌─ 作者 UI（JWT 门禁）  hermes-workflow-superset-author.*
            │    nginx 反代 Deployment superset-author-proxy（不用 Istio ext_authz——provider 需注册
浏览器 ─────┤    meshConfig(mesh 管理员)，公司策略卡）→ admin-center authorize → 注入 → REMOTE_USER 登录
            └─ 嵌入（不鉴权，guest token）  hermes-workflow-superset-internal-proxy.*
                 = SUPERSET_PUBLIC_HOST，user-portal iframe 用；剥离客户端伪造的 X-Remote-*

裸 Superset 端口 8088：关闭（容器内 expose，不对外发布）。
所有入口都剥离客户端 X-Remote-*；只有经鉴权后（dev 的 /bi/login/、prod 的作者 host）才注入校验过的值。
```

**关键点**：Superset 在 `AUTH_REMOTE_USER` 下「只要带 X-Remote-User 头就认你」——所以裸端口**必须**封死，否则任何人 `curl -H "X-Remote-User: admin"` 就能冒充登录。

**子路径怎么实现的（dev /bi 的核心）**：Superset 6.0 **原生读环境变量 `SUPERSET_APP_ROOT`**（见镜像内 `/app/superset/app.py`），把它赋给 `APPLICATION_ROOT`，自己把整个应用挂到 `/bi` 下——路由、静态资源、重定向全部带 `/bi` 前缀。所以 edge **原样透传** `/bi/*`（不剥前缀、不需 `X-Forwarded-Prefix`、更不要设 `STATIC_ASSETS_PREFIX`，否则变成 `/bi/bi/static` 双前缀）。这样 Superset 的 `/static`、`/api` 不会再和边缘上的 n8n `/static`、kong `/api` 撞车。

**角色映射**：平台角色 → `ac_bi_rbac_mappings`(实体表 `bi_rbac_mapping`) → Superset 角色名 → `X-Remote-Roles`。用户在映射表里**没有角色 → authorize 返回 403**（不是作者，拒绝；避免账号泛滥）。

**嵌入链路（portal iframe）的权限模型（2026-09-15 起 RBAC Mapping 也在这里生效）**

guest token 不认平台用户、也不带角色：Superset `/api/v1/security/guest_token/` 只收 `resources`（哪张看板）和 `rls`（行级过滤**子句**，不是角色），guest 身份固定为 `GUEST_ROLE_NAME`(Gamma)。所以"这个用户能看哪几张看板"完全在 admin-center 侧判定，Superset 只负责按 token 里的 `resources` 放行那一张：

```
user-portal → GET /bi/assignments/user/{id}     → 可见集合
           → POST /bi/guest-token {dashboardId} → 不在可见集合 → 403；在 → 用服务账号向 Superset 换 token

可见集合 = (USER ∪ ROLE ∪ BU 分配，USER > ROLE > BU 去重)  ∩  status=ACTIVE  ∩  角色门禁
角色门禁 = 看板在 Superset 没设角色           → 放行（存量看板行为不变）
         = 看板设了角色（dashboard_roles）    → 用户 sys_role 经 bi_rbac_mapping 解析出的 ACTIVE Superset 角色
                                               与看板角色有交集，或含 Superset 管理员角色（bi.superset.admin-role-name，默认 Admin）
看板角色来源 = Superset 作者在 Dashboard properties → Access → Roles 授予（需 FEATURE_FLAGS.DASHBOARD_RBAC）
             → admin-center「Sync Dashboards」写入 bi_dashboard_registry.superset_role_ids（排序去重 CSV）
             → Admin Center「Dashboard Registry」页「Superset Roles」列可对照
```

两条链路对 RBAC Mapping 的用法因此不同：作者 SSO 用映射决定「你在 Superset 里是什么角色」（JIT 建号、每次登录同步）；嵌入用映射决定「你能看哪些设了角色的看板」。改映射立即影响下一次列表/换 token，不需要重新同步。

---

## 2. 改动清单（按区域）

### 2.1 Superset 镜像与配置 `deploy/superset/`

| 文件 | 改动 |
|---|---|
| `Dockerfile` | 删除焊死的弱密钥 `ENV SUPERSET_SECRET_KEY=replace_…`；`COPY superset_security_manager.py` |
| `superset_config.py` | `SECRET_KEY` 改为 fail-closed 读 env；CORS `*`→门户白名单(`SUPERSET_CORS_ORIGINS`)；`X-Frame-Options: ALLOWALL`→CSP `frame-ancestors`；`AUTH_TYPE=AUTH_REMOTE_USER` + `CUSTOM_SECURITY_MANAGER`；`RECAPTCHA_PUBLIC_KEY/PRIVATE_KEY`；`LOGOUT_REDIRECT_URL`；**子路径部署**：`SUPERSET_APP_ROOT` 非空时开 `ENABLE_PROXY_FIX`（供 prod https 下正确 scheme）——`APPLICATION_ROOT` 本身由 Superset 6.0 原生读该 env 设置，故**不设** `STATIC_ASSETS_PREFIX`（会双前缀）；**`FEATURE_FLAGS.DASHBOARD_RBAC=True`（2026-09）**：作者可在看板属性里授予角色，是嵌入角色门禁的数据来源（见 §1） |
| `superset_security_manager.py` 🆕 | 自定义 `PlatformRemoteUserSecurityManager`：`register_views()` 完整镜像 Superset 逻辑但把 `/login` 换成 REMOTE_USER 子类；`auth_user_remote_user()` JIT 建号 + 每次登录同步**角色 + email + 姓名**（firstname 用 `unquote_plus` 解码，匹配 Java URLEncoder 的 `+`=空格）；**重写 `sync_role_definitions()` 自愈钩子**：每次 `superset init` 给 `GUEST_ROLE_NAME`(Gamma) 补 `can_read on CurrentUserRestApi`（嵌入 SDK 调 /me/roles 需要），扛 init 重置 / 新库 / 升级 |
| `author-proxy/` 🆕（Dockerfile + default.conf.template） | **prod 作者网关镜像 `superset-author-proxy`**（`FROM nginx:alpine`）= dev `nginx-edge` 里那道作者门（dev 现为 `/bi/login/`）的 k8s 版，替代 Istio ext_authz。stock nginx 的 envsubst 启动时渲染 `${INGRESS_HOST}`/`${POD_NAMESPACE}`（`NGINX_ENVSUBST_FILTER` 锁死只这俩，不动 nginx `$变量`）。逻辑同 dev：auth_request→admin-center authorize、注入 X-Remote-*、`Origin ""` 修复、401→302 登录、403→拒 |

### 2.2 后端 admin-center `backend/admin-center/`

| 文件 | 改动 |
|---|---|
| `bi/controller/BiSupersetAuthController.java` 🆕 | 网关鉴权端点 `GET /internal/bi/superset/authorize`（另含 `/authorize/**`——原为 Istio ext_authz 追加路径设计，改 nginx 后 auth_request 打的是精确路径、此通配已无用但无害保留）。校验 JWT → 映射角色 → 返回 `X-Remote-User`/`X-Remote-Roles`/`X-Remote-Email`/`X-Remote-Firstname`（200）/ 401 / 403。email+displayName 从 `sys_users` 查（**平台 JWT 不含 email**）；firstname URL 编码防中文乱码 |
| `bi/service/BiRbacMappingService(+Impl).java` | 新增 `getEffectiveSupersetRoleNames(sysRoleIds)`（返回 Superset 角色**名**；原有只返回 ID） |
| `controller/AuthController.java` | 新增 `GET /auth/logout-redirect`：清 `ac_access_token` cookie + 拉黑 token + 302 到登录页（供 Superset 的 `LOGOUT_REDIRECT_URL`） |
| `bi/config/BiProperties.java` | 修正误导注释（实际 env 是 `BI_SUPERSET_USERNAME`，非 `BI_SUPERSET_ADMIN_USERNAME`）；**2026-09** 新增 `bi.superset.admin-role-name`（`application.yml` 默认 `Admin`，对应 Superset `AUTH_ROLE_ADMIN`，映射到它的用户绕过嵌入角色门禁） |
| `bi/component/DashboardSyncComponent.java` | **2026-09** 同步时多读 `superset.dashboard_roles` → `bi_dashboard_registry.superset_role_ids`（排序去重 CSV，空=不限制；角色变化计入 `updated`）。该表读不到按同步失败处理，**不**静默当作"无限制" |
| `bi/service/impl/BiDashboardAssignmentServiceImpl.java` | **2026-09** `getUserDashboards` 在合并分配、过滤 ACTIVE 之后加 `DashboardRoleFilter`（规则见 §1）；只在遇到第一张受限看板时才查一次映射；被隐藏的看板 debug 日志带 `superset_role_ids` 便于排查 |
| `bi/service/BiRbacMappingService(+Impl).java` | 再加 `getEffectiveSupersetRoles(sysRoleIds)`（返回实体，门禁同时要 ID 求交、要名字识别 Admin） |
| `bi/service/impl/BiGuestTokenServiceImpl.java`、`bi/client/SupersetApiClient.java` | **2026-09** 删掉 `supersetRoleIds`：它被算出来传给 client 后从未写进 guest_token 请求体（API 本就不收）。`getGuestToken(embedId)` 只以 `resources` 限定该看板；授权判定全靠 `getUserDashboards` |
| `bi/component/BiDashboardRegistryResponseAssembler.java` 🆕、`list/BiDashboardColumnSpec.java`、`bi/support/SupersetRoleIdCsv.java` 🆕 | 注册表响应加 `supersetRoleIds` / `supersetRoleNames`（一次批量查 `bi_superset_role` 解析名字）；列表新增可筛选列 `supersetRoleNames`（SQL 子查询 `string_agg(r.name)` 对 `string_to_array(superset_role_ids)`） |

### 2.3 前端 admin-center `frontend/admin-center/`

| 文件 | 改动 |
|---|---|
| `src/views/sso/SsoCallback.vue` | 加 `SSO_EXTERNAL_RETURNS` 白名单：当 `state=superset-author` 时，换码种 cookie 后 `window.location` 跳回 Superset（`VITE_SUPERSET_AUTHOR_URL` 或 dev 回退 `http://localhost:3000/bi/`），实现作者一步登录 |
| `src/views/bi-management/DashboardRegistry.vue`、`src/api/biManagement.ts`、`i18n/locales/{en,zh-CN,zh-TW}.ts` | **2026-09** Registry 表新增「Superset Roles」列：显示同步到的角色名，没设角色显示 `-` 并悬停提示"未限制"。截图 `verification-screenshots/2026-09-15_bi-registry-superset-roles-column.png` |

### 2.4 dev 本地 `deploy/environments/dev/`

> **2026-07 收敛到 `http://localhost:3000/bi`**：作者 UI + 嵌入都挂在共享边缘的 `/bi` 子路径下（和 `/admin`、`/portal`、`/dev` 同模型），原独立端口 `:8087`（作者）/`:8089`（嵌入源）已退休。

| 文件 | 改动 |
|---|---|
| `nginx-edge.conf` | 在 `:80` server 内加 `/bi` 路由：`= /_superset_authz`（内部 auth_request 到 admin-center authorize，含 **`proxy_set_header Origin ""`**）；**`^~ /bi/login/`** 门禁（注入 X-Remote-* + `error_page 401→@superset_login`(带 SSO 参数)/`403→@superset_forbidden`）；**`^~ /bi/`** 放行（剥离伪造 X-Remote-*）；两者都**原样透传**给 `superset_upstream`（不剥 `/bi` 前缀，Superset 原生在 `/bi` 下）；`location = /bi` 加 `absolute_redirect off`（308 补斜杠不掉端口）；legacy `/superset` → `/bi/`。**删除**原 `:8087`/`:8089` 两个 server。 |
| `docker-compose.dev.yml` | superset 封裸 8088（`expose` 不 `ports`）；**superset 加 `SUPERSET_APP_ROOT=/bi`**；superset healthcheck 由 `wget`（镜像里没有→一直 unhealthy）改用镜像自带 `/app/.venv/bin/python`；admin-center 注入 `SUPERSET_PUBLIC_HOST=http://localhost:3000/bi`、`APP_SECURITY_LOGOUT_REDIRECT_TARGET`；edge **删除** 8087/8089 端口映射 |
| `.env` | `SUPERSET_PUBLIC_HOST`→`http://localhost:3000/bi`；**加 `SUPERSET_APP_ROOT=/bi`**；`SUPERSET_SECRET_KEY`(dev真值)、`SUPERSET_CORS_ORIGINS` 保留；**2026-09-15 `SUPERSET_HOST`→`http://superset-final:8088/bi`**（漏改导致 guest token 404，见 §3） |
| `.env.example`、`docker-compose.dev.yml` | **2026-09-15** `SUPERSET_HOST` 示例值与 compose 默认值同样带 `/bi`；`.env.example` 注释写明 `BI_SUPERSET_USERNAME` 是 Superset DB 用户及 `fab create-admin` 建法 |
| `../../scripts/superset-init.sh` | **2026-09-15** 除 `admin` 外，同时按 `.env`（或环境变量）里的 `BI_SUPERSET_USERNAME/PASSWORD` 建 guest token 服务账号，再 `superset init`。Superset 元数据库重建后跑一次即可 |
| `../../init-scripts/00-schema/82-bi-dashboard-registry-superset-roles.sql` 🆕、`00-init-all.sh` | **2026-09** `bi_dashboard_registry.superset_role_ids TEXT`（已有 dev 库手工 `psql -f` 应用） |

### 2.5 k8s 生产 `deploy/k8s/`

| 文件 | 改动 |
|---|---|
| `workflow-station-superset.yaml` | 嵌入 VirtualService 加 `headers.request.remove`(剥离 X-Remote-*)；**新增作者 host**：Istio Gateway + VirtualService → **nginx 反代 Deployment `superset-author-proxy`**（ServiceAccount/Service/Sidecar 同名；注意与 Istio `Gateway` 资源 `...-author-gateway` 区分）。**弃用 Istio ext_authz/meshConfig**（公司策略卡 mesh 层）；防绕过改用普通 **`AuthorizationPolicy(action:DENY)`** `...-deny-forged-remote-user`：拒任何带 `X-Remote-User` 且来源 `notPrincipals`≠proxy SA 的请求直达 Superset（**需命名空间 mTLS STRICT**，否则 source principal 为空、规则失效） |
| `config_map/preprod/superset-config.yml` | 与 dev 对齐（密钥/CORS/CSP/guest token/RECAPTCHA/LOGOUT）；**2026-09** 加 `DASHBOARD_RBAC`（配置烘进镜像，需重建 superset 镜像） |
| `init-data/init-platform-schema/all-in-one-for-gui.sql` | **2026-09** 追加 82 段（`superset_role_ids` 列） |
| `config_map/{preprod,uat}/configmap-workflow-platform-config.yml` | 加 `SUPERSET_CORS_ORIGINS`、`SUPERSET_LOGOUT_REDIRECT_URL`、`APP_SECURITY_LOGOUT_REDIRECT_TARGET` |
| `secret/{preprod,uat}/secret-…yml` | `SUPERSET_SECRET_KEY` 占位符→真值；`BI_SUPERSET_ADMIN_*` 改名为 app 实际读取的 `BI_SUPERSET_USERNAME/PASSWORD` |
| `SUPERSET_SSO_GATEWAY.md` 🆕 | 生产部署 runbook：nginx 反代镜像构建、apply/verify、mTLS 前提（**已重写，删去 meshConfig ext_authz 章节**） |

### 2.6 环境变量一览

> dev 值在 `deploy/environments/dev/.env` + `docker-compose.dev.yml`；prod 值在
> `deploy/k8s/config_map/<env>/`（非敏感）与 `deploy/k8s/secret/<env>/`（敏感）。
> `__INGRESS_HOST__`/`__BASE_DOMAIN__`/`__NAMESPACE__` 由部署脚本替换。

**A. Superset 容器消费**（`superset_config.py` / k8s `superset-config.yml` 读取）

| 变量 | 必填 | dev 值 | prod 值（preprod 示例） | 用途 |
|---|:---:|---|---|---|
| `SUPERSET_SECRET_KEY` | ✅ | `dev-superset-secret-key-…`（.env） | Secret，真值（勿用占位符） | Flask `SECRET_KEY` + guest token 签名；**fail-closed**，缺失则启动失败 |
| `SUPERSET_CORS_ORIGINS` | ✅ | `http://localhost:3000,http://127.0.0.1:3000` | `https://__INGRESS_HOST__,http://__INGRESS_HOST__,…` | CORS 白名单 + CSP `frame-ancestors`（谁能调 API / iframe 嵌入） |
| `SUPERSET_APP_ROOT` | 否 | `/bi` | 未设（prod 仍 root+双子域；收敛后设 `/bi`，见 §6） | **Superset 6.0 原生读**→`APPLICATION_ROOT`，把整个应用挂到该子路径（单 FQDN + path）。空=root 服务。设了别再设 `STATIC_ASSETS_PREFIX`（双前缀） |
| `SUPERSET_RECAPTCHA_PUBLIC_KEY` | 否 | `""`（默认） | `""` | 不设会导致嵌入视图 500（见 bug 表）；置空即可 |
| `SUPERSET_RECAPTCHA_PRIVATE_KEY` | 否 | `""` | `""` | 同上 |
| `SUPERSET_LOGOUT_REDIRECT_URL` | 否 | 默认 `http://localhost:3000/api/v1/admin/auth/logout-redirect` | `https://__INGRESS_HOST__/api/v1/admin/auth/logout-redirect` | Superset Logout 跳向的平台登出端点（k8s 默认 `/login/`） |
| `SUPERSET_GUEST_ROLE_NAME` | 否 | 默认 `Gamma` | `Gamma` | 嵌入 guest token 使用的 Superset 角色 |
| `SUPERSET_GUEST_TOKEN_JWT_SECRET` | 否 | 默认=`SECRET_KEY` | 同 | 想独立轮换 guest token 密钥时才设 |
| `SUPERSET_SESSION_COOKIE_SAMESITE` | 否 | （dev 未用） | 默认 `Lax` | 仅 k8s superset-config 读取 |
| `SUPERSET_SESSION_COOKIE_SECURE` | 否 | （dev 未用） | 默认 `false` | 仅 k8s；HTTPS 下应为 `true` |
| `SQLALCHEMY_DATABASE_URI` / `SUPERSET_DATABASE_URI` | ✅ | compose 拼接（含 `?search_path=…schema`） | configmap 提供 | Superset 元数据库连接 |
| `SUPERSET_CONFIG_PATH` | ✅ | 镜像内置 | `/app/pythonpath/superset_config.py` | 加载自定义配置 |
| `MAPBOX_API_KEY` | 否 | — | `""` | 仅 k8s，可选地图 |

**B. admin-center 消费**（BI 模块铸 guest token + 登出）

| 变量 | dev 值 | prod 值 | 用途 |
|---|---|---|---|
| `SUPERSET_HOST` | `http://superset-final:8088/bi` | `http://hase-hermes-workflow-superset.__NAMESPACE__:80` | **内部** Superset 地址（后端→Superset 调 API 铸 guest token）。**必须带 `SUPERSET_APP_ROOT`**：Superset 把 API 也挂在 app root 下，dev 收敛到 `/bi` 后曾漏改，`/api/v1/security/login` 一律 404、guest token 铸不出来（2026-09-15 修）。prod 改 `/bi` 时同样要补 |
| `SUPERSET_PUBLIC_HOST` | `http://localhost:3000/bi` | `http://hermes-workflow-superset-internal-proxy.__BASE_DOMAIN__/`（收敛后 `https://__INGRESS_HOST__/bi`） | **浏览器**侧嵌入基址，回给 portal iframe。embedded SDK 拼 `${supersetDomain}/embedded/<id>`，故 dev 走 `.../bi/embedded/<id>` |
| `SUPERSET_DB_SCHEMA` | `superset` | `superset` | 元数据 schema（`bi.superset.db-schema`） |
| `BI_SUPERSET_USERNAME` | `adama` | Secret，真实 Superset 管理账号 | guest token 铸造用的 Superset 服务账号（**注意：app 读这个，不是 `BI_SUPERSET_ADMIN_USERNAME`**）。这是 Superset **DB 用户**（`provider=db`），`superset init` 不会建、SSO JIT 也不会建；新库要手工 `superset fab create-admin --username adama …`（dev 2026-09-15 补建过一次，DB 重建后需再跑） |
| `BI_SUPERSET_PASSWORD` | `admin123` | Secret | 同上密码 |
| `APP_SECURITY_LOGOUT_REDIRECT_TARGET` | `/login/?client_id=admin&redirect_uri=…/admin/sso/callback&state=superset-author`（URL编码） | 同结构，`redirect_uri` 用 `__INGRESS_HOST__` | `/auth/logout-redirect` 清完 cookie 后跳的登录页（带 SSO 参数，能再登；state 命中后 SsoCallback 跳回 `/bi/`） |

> 不是 env 但同属 BI 配置：`bi.superset.admin-role-name`（`application.yml`，默认 `Admin`）——嵌入角色门禁把映射到该 Superset 角色的用户视为可看所有受限看板，与 Superset `AUTH_ROLE_ADMIN` 保持同名即可，一般不需要改。

**C. dev 端口 / 镜像 / 前端构建**

| 变量 | 默认 | 用途 |
|---|---|---|
| ~~`SUPERSET_GATE_PORT`~~ / ~~`SUPERSET_EMBED_PORT`~~ | ~~8087/8089~~ | **已退休**（2026-07 收敛到 `/bi`，不再有独立端口） |
| `SUPERSET_BASE_IMAGE` | nexus 镜像引用 | 部署用 Superset 镜像 |
| `VITE_SUPERSET_AUTHOR_URL` | dev 回退 `http://localhost:3000/bi/` | admin **前端构建参数**：作者一步登录跳回的 Superset 地址（prod 设为作者 host 或收敛后 `.../bi/`） |

> **已删除（无用，勿再用）**：`BI_SUPERSET_ADMIN_USERNAME` / `BI_SUPERSET_ADMIN_PASSWORD` —— application.yml 实际绑定的是 `BI_SUPERSET_USERNAME/PASSWORD`，ADMIN 变体全仓库无引用，已清理。

---

## 3. 排查中发现并修复的真实 bug

| Bug | 现象 | 根因 | 修复 |
|---|---|---|---|
| **嵌入 500 黑屏** | 看板 iframe 显示 Superset「Internal server error 500」 | Superset 6.0 嵌入视图 `common_bootstrap_payload` 无条件读 `RECAPTCHA_PUBLIC_KEY`，原配置没设 → `KeyError` | `superset_config.py` + k8s configmap 加 `RECAPTCHA_PUBLIC_KEY/PRIVATE_KEY=""` |
| **List Users 空白页** | Settings 菜单出现「List Users」，点开空白 | 自定义 SM 的 `register_views` 跳过了 Superset 的逻辑（Superset 6.0 本应移除遗留 FAB user/role/group 视图+菜单） | `register_views` 完整镜像 Superset 逻辑，只把 `/login` 换成 REMOTE_USER 子类。注意 `superset.views.auth` 须**惰性导入**（配置加载极早，顶层导入会 "App not initialized yet"） |
| **登录后「Unexpected error」** | 作者登入 Superset 弹错误 toast（对**所有**用户、与角色无关） | Superset 前端埋点 POST `/superset/log/` 带 `Origin`（dev 现为 `http://localhost:3000`）；auth_request 子请求把 Origin 转发给 admin-center → CORS 白名单不含该源 → admin-center 返回 403 → 网关拦下 | `_superset_authz` 加 `proxy_set_header Origin ""`（内部调用不需 Origin）。**prod nginx 反代同样靠此修复**（author-proxy conf 也带 `Origin ""`）；旧 Istio ext_authz 方案才不转发 Origin |
| **superset 一直 unhealthy**（dev） | compose 里 superset 容器长期 `unhealthy` | healthcheck 用 `wget`，但 `apache/superset:6.0.0` 镜像**没有 wget/curl** | healthcheck 改用镜像自带 `/app/.venv/bin/python -c "urllib.request…/health"` |
| **`/bi/login/` 500 `relation "themes" does not exist`**（dev） | 切 `/bi` 后作者登录 500 | 元数据库停在旧 schema，Superset 6.0 的 `themes` 等表没建（日志 `Pending database migrations`） | `docker exec … superset db upgrade` + `superset init`（后者也触发自定义 SM 的 Gamma 权限自愈钩子） |
| **左上角 logo 点击 → `/` 404 + logo 图断**（子路径） | 点 Apache Superset logo 跳裸 `/`（edge 404），且 logo 图片 404（断图） | Superset 6.0 的 logo 由 **Ant 主题 token** 渲染，默认 `brandLogoHref="/"`、`brandLogoUrl="/static/…"` **均不带 app root**；而生效主题是 legacy HSBC `THEME_OVERRIDES`（装不下 Ant token），加上 DB 里的主题会盖过 config `THEME_DEFAULT`——**在主题层改不可靠**。（菜单项等 React-Router 链接都对，只有这个纯 `<a href>`/`<img src>` logo 组件漏了前缀） | 改在 **edge**（可靠、与主题内部无关）：`location = /` 当 `Referer` 来自 `/bi` → 302 `/bi/superset/welcome/`（其余裸根仍 404）；logo 图 exact-match → 302 `/bi/static/…`；均 `absolute_redirect off` 保端口。真机 Playwright 验证：点击落在 welcome、`logoImgComplete=true` |
| **`BI_SUPERSET_ADMIN_*` 死变量** | — | app 实际读 `BI_SUPERSET_USERNAME`（application.yml），`BI_SUPERSET_ADMIN_*` 无人引用；且 k8s 只有 ADMIN 名、缺真正读的名 → 生产 guest token 凭据失效 | dev 删除、k8s 改名为 `BI_SUPERSET_USERNAME/PASSWORD` |
| **嵌入「embedded authentication」失败** | guest token 已签发但嵌入报认证失败（**生产换新库后出现，dev 不复现**） | embed SDK 拿到 guest token 后调 `/api/v1/me/roles`(CurrentUserRestApi.can_read) 做会话校验；新库 `superset init` 出来的 Gamma 默认无此权限 → 403。dev 6.0.0 该端点权限项没生成、未设防 → 侥幸 200，所以 dev 看不到 | SM `sync_role_definitions()` 自愈钩子：每次 init 给 Gamma 补 `can_read on CurrentUserRestApi`（比一次性 SQL 稳——init 会重置 Gamma，钩子紧跟着重授）|
| **登出回不去** | Superset Logout 无效 / 登出后落到裸 `/login` 登不回 | 网关 SSO 下 Superset 自带登出无效（cookie 还在会被登回）；裸 `/login` 缺 SSO 参数无法提交 | 新增 `/auth/logout-redirect`(清 cookie)；`LOGOUT_REDIRECT_URL` 指向它；登出目标设为带 SSO 参数的登录页 |
| **RBAC Mapping 对嵌入链路无效**（2026-09-15） | 在 Admin Center 改映射，portal 里用户能看到的看板一张不少 | `BiGuestTokenServiceImpl` 按映射算出 `supersetRoleIds` 交给 `SupersetApiClient`，后者组装请求体时根本没用它；Superset guest_token API 也不收角色列表，guest 固定 Gamma。原需求 7.14 写的"作为 rls 角色参数传给 Superset"在 Superset 里不存在 | 改成 admin-center 侧角色门禁（§1）：同步 `dashboard_roles`、`getUserDashboards` 按映射求交、guest token 复用该判定；删死参数；需求/设计文档 7.14-7.15、Property 17→18/19 同步改写 |
| **dev guest token 一律 `SUPERSET_API_ERROR … 404`**（2026-09-15） | `POST /bi/guest-token` 对任何看板都 400，message 里是 Superset `404 NOT FOUND on POST` | dev 2026-07 收敛到 `/bi` 时 `SUPERSET_HOST` 没跟着改；Superset 6.0 把 **API 也挂在** `SUPERSET_APP_ROOT` 下，`http://superset-final:8088/api/v1/security/login` 404、`/bi/api/v1/...` 才通。作者 SSO 走 edge 不受影响，所以两个月没人发现 | `SUPERSET_HOST=http://superset-final:8088/bi`（`.env` / `.env.example` / compose 默认值）。**规则：`SUPERSET_HOST` 必须带 `SUPERSET_APP_ROOT`**，prod 收敛 `/bi` 时同样要补 |
| **服务账号不存在 → Superset login 401 `Not authorized`**（2026-09-15） | 路径修好后 guest token 仍失败，Superset 返回 401 | `BI_SUPERSET_USERNAME=adama` 是 Superset **DB 用户**（`provider=db`），`superset init` 只建角色、SSO JIT 只建登录过的作者，`superset-init.sh` 只建 `admin`；元数据库重建后 `ab_user` 里没有 adama | `superset fab create-admin --username adama …` 补建（Admin 角色，含 `can_grant_guest_token`）；`superset-init.sh` 改为按 `.env` 一并建服务账号 |

---

## 4. 登录 / 登出流程（dev，`http://localhost:3000/bi`）

**作者登录（一步到位）**
```
/bi/ (无会话) → 放行 → Superset 302 → /bi/login/ → 网关 auth_request 401（无 JWT）
  → 302 /login/?client_id=admin&redirect_uri=…/admin/sso/callback&state=superset-author
  → admin/admin123 → /admin/sso/callback（换码种 ac_access_token cookie）
  → SsoCallback 识别 state=superset-author → window.location 跳回 http://localhost:3000/bi/
  → /bi/login/ 网关 authz 200 + 注入 X-Remote-* → Superset REMOTE_USER 登录 → /bi/superset/welcome/
```

**登出**
```
Superset Settings → Logout → /bi/logout/（清 Superset 会话）
  → 302 LOGOUT_REDIRECT_URL = /api/v1/admin/auth/logout-redirect
  → 清 ac_access_token cookie + 拉黑 → 302 带 SSO 参数的 /login/（可直接再登）
```

**嵌入查看**
```
user-portal 首页 → GET /api/v1/admin/bi/assignments/user/{userId}
  → admin-center 合并 USER/ROLE/BU 分配 → 过滤 ACTIVE → 角色门禁（映射角色 ∩ 看板 dashboard_roles，见 §1）→ 可见列表
用户点开某张 → POST /api/v1/admin/bi/guest-token {dashboardId}
  → 不在可见列表 → 403 "Dashboard not assigned to user"
  → 在 → admin-center 用 BI_SUPERSET_USERNAME 向 SUPERSET_HOST(/bi)/api/v1/security/login(provider=db) 取 access token
       → /api/v1/security/csrf_token/ → POST /api/v1/security/guest_token/ {user:guest, resources:[{dashboard, embedId}], rls:[1=1]}
  → 返回 {token, dashboardEmbedId, supersetDomain=SUPERSET_PUBLIC_HOST}
embedded SDK → ${supersetDomain}/embedded/<embedId>（= http://localhost:3000/bi/embedded/…，走 /bi/ 放行路径，不经作者门禁）
  → Superset 校验 guest token 签名(SECRET_KEY) + resources，以 GUEST_ROLE_NAME 渲染这一张看板
```

---

## 5. 已完成的验证（dev，截图见 `frontend/admin-center/verification-screenshots/`）

- ✅ 安全加固：CSP frame-ancestors 生效、CORS 放行门户/拒绝恶意源、SECRET_KEY 非弱值
- ✅ 网关：无/无效 JWT、伪造 X-Remote-* 一律拒；裸 8088 不可达；嵌入源剥离伪造头
- ✅ 作者 SSO：平台 JWT → authz 200 → 注入 → REMOTE_USER 登录 → 建号带映射角色
- ✅ 一步登录 + 登出 + 再登入闭环
- ✅ 嵌入看板渲染（`2026-06-25_superset-embed-c2-rendered.png`）
- ✅ List Users 不再空白、`/superset/log/` 不再 403
- ✅ **嵌入角色门禁（2026-09-15）**：SQL 直插两张看板（一张 `dashboard_roles`=Alpha，一张不设）→ Sync → 分配给 admin → 无映射 / 映射 Gamma / 映射 Public：只见不限那张、受限看板 guest-token 403；映射 Alpha / 映射 Admin：两张都见、guest-token 200。BI 单测 45 绿（新增 Property 18/19 + 角色名筛选 SQL）。Registry 页新列截图 `2026-09-15_bi-registry-superset-roles-column.png`
- ✅ **dev guest token 修复（2026-09-15）**：`SUPERSET_HOST` 带 `/bi` + 补建 `adama` 后，`POST /bi/guest-token` 200，解码 token：`user=guest`、`resources=[{dashboard, <该看板 embed uuid>}]`、`rls=null`、`type=guest`，与 `superset.embedded_dashboards.uuid` 一致
- ✅ **单 FQDN + path 收敛（2026-07）**：`/bi/login/` 无 JWT→302 统一登录；`/bi/`→302 `/bi/superset/welcome/`；`/bi/static/*` 200 而裸 `/static` 404（不撞 n8n）；注入 `X-Remote-User` 直连 → REMOTE_USER 登录 200+session，登录态 HTML 22 处 `/bi/static`、0 处裸 `/static`/`/api/v1`，bootstrap `application_root":"/bi"`

---

## 6. 生产部署待办（详见 runbook）

> **⚠️ 建议 prod 也收敛到 `INGRESS_HOST/bi`（同 dev）**：现 k8s manifest 仍是双子域（`superset-author.*` / `-internal-proxy.*`），而 **IKP 生产 FQDN 只给一个固定域名、子域难落地——这正是 dev 已解决、prod 待办的冲突**。收敛做法：把 `deploy/k8s/workflow-station-superset.yaml` 的两个 Gateway/VirtualService 改成复用主 `workflow-platform-ingress-gateway` + 一条 `/bi` prefix 路由到 `superset-author-proxy`（proxy 内只 gate `/bi/login/`、其余放行），configmap 加 `SUPERSET_APP_ROOT=/bi`、`SUPERSET_PUBLIC_HOST=https://__INGRESS_HOST__/bi`，`author-proxy/default.conf.template` 比照 dev nginx-edge 的 `/bi` 段（原样透传、不剥前缀）。收敛后不再需要作者/嵌入两个 DNS/TLS。防绕过 `action:DENY` 策略 + mTLS 前提不变。

1. **重建并推送镜像**（含本仓库改动，建议同 tag）：`superset`（新 SM/config）、`admin-center`（authorize 端点 + logout-redirect）、`admin-center-frontend`（SsoCallback）、**`superset-author-proxy` 🆕**（nginx 作者网关，源 `deploy/superset/author-proxy/`）。
2. **确认命名空间 mTLS = STRICT**（防绕过 DENY 策略按来源 SA 匹配，无 mTLS 则 source principal 为空、规则失效）。〔已不再需要运维在 meshConfig 注册 ext_authz provider〕
3. apply manifest + 同步 configmap/secret；作者/嵌入两个 host 配 DNS/TLS；确认 `INGRESS_HOST` 在 configmap 正确（proxy 401 跳登录用）。
4. 设 `VITE_SUPERSET_AUTHOR_URL` = 生产作者 host（admin 前端构建参数）。
5. 按 runbook 验证（含集群内伪造 `X-Remote-User` 直打 Superset service 应被 DENY）。
6. **嵌入角色门禁上线三件套（2026-09）**：(a) superset 镜像含 `DASHBOARD_RBAC`（preprod configmap 已加，configmap 烘进镜像的环境要重建）；(b) 平台库执行 `00-schema/82-*.sql`（或 all-in-one 末段）加 `superset_role_ids` 列，否则 admin-center JPA 启动报列不存在；(c) 上线后作者在 Superset 给看板授角色前，所有看板行为不变。
7. **guest token 前置**：`SUPERSET_HOST` 必须带 Superset 的 app root（prod 收敛到 `/bi` 时 configmap 同步改成 `http://hase-hermes-workflow-superset.__NAMESPACE__:80/bi`）；prod Superset 元数据库里要有 `BI_SUPERSET_USERNAME` 这个 DB 用户（`kubectl exec <superset pod> -- superset fab create-admin --username <BI_SUPERSET_USERNAME> --password <BI_SUPERSET_PASSWORD> …`），Secret 里的用户名/密码与之一致。两条任一不满足，portal 嵌入全部失败而作者 SSO 照常，容易漏查。

---

## 7. 注意事项

- **谁能进作者 UI**：只有在 `bi_rbac_mapping` 有角色映射的用户。当前 dev 映射示例：`role-sys-admin/role-tech-lead→Admin`、`role-developer→Gamma`。无映射 → 403。
- **Gamma 看不到看板**：Superset 的 Gamma/Public 角色默认无任何 dashboard 权限，需在 Superset 给角色授权对应 dashboard，否则登入后是空的（「No results」）。这是 Superset 权限模型，非 bug。
- **嵌入链路的可见性由 RBAC Mapping × Superset dashboard_roles 决定（2026-09）**：规则见 §1，排查顺序：Registry 页「Superset Roles」列 → RBAC Mapping 页该用户角色映射到哪些 Superset 角色 → 有交集或含 Admin 才可见。没设角色的看板永远不受影响。
- **guest token 失败先看两处**：`SUPERSET_API_ERROR` 带 404 → `SUPERSET_HOST` 少了 app root；带 401 → `superset.ab_user` 里没有 `BI_SUPERSET_USERNAME`（跑 `deploy/scripts/superset-init.sh`）。这两条都只坏嵌入、不坏作者 SSO。
- **DASHBOARD_RBAC 对作者侧的影响**：Superset 原生语义——设了角色的看板对这些角色可见（不再要求 dataset 权限），没设角色的看板行为不变。
- **Superset 用户不在 Superset 里管**：首次 SSO 登录自动 JIT 建号，角色由平台 `bi_rbac_mapping` 决定；故 Superset 的 `/users/list/` 已（按原版 Superset）移除，访问返回 Access Denied。
- **后端构建用 JDK17**：本机 Maven 默认 JDK25 会让 Lombok 静默失效；`export JAVA_HOME=/opt/homebrew/opt/openjdk@17` 再构建。
