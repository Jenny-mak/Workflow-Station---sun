# Build & Deploy Guide — Workflow Platform

> 本文档面向 AI 助手（GPT / Claude / Kiro）和开发者，包含从零构建到部署的完整步骤。
> 所有命令均为 PowerShell (Windows)。

---

## 0. ⛔ AI 助手必读 — 关键规则

> **如果你是 GPT / Claude / Kiro 等 AI 助手，请先读完本节再执行任何操作。**

1. **Docker 多阶段构建不可用** — 本地 Docker Desktop 无法在容器内执行 `pnpm install --frozen-lockfile` 或 `mvn package`。所有环境必须使用"本地构建 + 复制"方式。
2. **前端必须使用 `Dockerfile.local`** — 前端目录下有两个 Dockerfile：`Dockerfile`（多阶段，不使用）和 `Dockerfile.local`（仅复制 dist/）。**永远使用 `Dockerfile.local`**。
3. **`pnpm run build` = `vite build`** — 前端 `package.json` 中 `build` 脚本已改为 `vite build`（无 `vue-tsc` 类型检查）。
4. **前端 `.dockerignore` 不能包含 `dist`** — 否则 `Dockerfile.local` 的 `COPY dist` 会失败。
5. **nginx 环境变量替换机制** — 前端 `nginx.conf` 为模板，`docker-entrypoint.sh` 用 `envsubst` 替换 **`${KONG_PROXY_URL}`**（以及模板中出现的其它占位符）。**必须在 `envsubst` 中显式列出变量名**，否则 nginx 的 `$host`、`$uri` 等也会被替换导致 502。
6. **URL 变量分工** — 三个前端 nginx 使用 **`KONG_PROXY_URL`**（指向 Kong，代理 `/api/*`）。后端**服务间**调用仍用 `ADMIN_CENTER_URL`、`WORKFLOW_ENGINE_URL` 等（与各服务 `application*.yml` 一致）。没有 `*_BACKEND_URL` 变量。
7. **`.sh` 和 `.sql` 文件必须是 LF 换行** — `.gitattributes` 已配置强制 LF。如果手动创建 `.sh` 文件，确保是 LF 而非 CRLF，否则容器内执行会报 `/bin/sh: bad interpreter`。
8. **admin-center 有 context-path** — healthcheck 路径是 `/api/v1/admin/actuator/health`，不是 `/actuator/health`。
9. **API 边缘为 Kong Gateway** — 与 Ingress 等入口协同；各前端 nginx 可按环境直连后端或经 Kong。Kafka 使用 KRaft 模式（无 ZooKeeper）。
10. **环境变量名必须是 `ENCRYPTION_SECRET_KEY`** — 不是 `ENCRYPTION_KEY`。
11. **PostgreSQL 不部署** — SIT/UAT/PROD 使用公司现有 PostgreSQL 数据库。Redis、Kafka 在 K8S 中自行部署。
12. **后端运行时基础镜像可覆盖** — 各后端 `Dockerfile` 使用 `ARG JAVA_BASE_IMAGE`（默认 `eclipse-temurin:17-jre`）。`deploy/environments/dev/build-and-deploy.ps1` 与 `deploy/scripts/build-and-push-k8s.ps1` 支持 **`-JavaBaseImage`**，默认优先使用 **`docker.m.daocloud.io/library/eclipse-temurin:17-jre`**：构建前预拉取，并传 `--build-arg JAVA_BASE_IMAGE=...` 与 `docker build --pull=false`，减轻对 Docker Hub 元数据的依赖。
    **Activepieces 同理但入口不同**：`automation/Dockerfile` 使用 `ARG NODE_IMAGE`（默认 `node:24.14.0-bullseye-slim`）。
    它经 Compose 构建（`docker compose build activepieces`），**两个 ps1 脚本都不管它**，故没有对应的 `-NodeBaseImage` 开关；
    构建机够不到 Docker Hub 时手工传 `--build-arg NODE_IMAGE=<nexus3 镜像>`。

---

## 1. 项目概览

| 项目 | 技术栈 | 说明 |
|------|--------|------|
| workflow-engine-core | Spring Boot 3.2 + Flowable 7 | BPMN 工作流引擎 |
| admin-center | Spring Boot 3.2 | 管理后台 API |
| developer-workstation | Spring Boot 3.2 | 开发者工作台 API |
| user-portal | Spring Boot 3.2 | 用户门户 API |
| admin-center-frontend | Vue 3 + Vite + Element Plus | 管理后台 UI |
| user-portal-frontend | Vue 3 + Vite + Element Plus | 用户门户 UI |
| developer-workstation-frontend | Vue 3 + Vite + Element Plus + BPMN.js | 开发者工作台 UI |

基础设施：PostgreSQL 16（公司现有）+ Redis 7（K8S 部署）+ Kafka 7.5（KRaft 模式，K8S 部署）。

不部署的组件：PostgreSQL（使用公司现有数据库）。API 路由与网关插件由 **Kong** 提供（见 `deploy/kong/`、`deploy/k8s/kong.yaml`）。

> **与 `README.md` 分工**：根目录 README 侧重架构速览与文档索引；**BUILD_GUIDE.md**（本文）为可执行的构建/部署步骤。  
> **Kubernetes 部署**：Istio 风格清单、按环境的 ConfigMap/Secret 与 PowerShell 脚本位于 **`deploy/k8s/`**（根目录 `*.yaml`、`config_map/<Environment>/`、`secret/<Environment>/`、`ps1/`）。**推荐**使用 **`ps1/apply-workflow-station-all.ps1`**，经 **Istio Gateway** 暴露服务；前端入口与 **`workflow-platform-ingress-gateway.yaml`** 对齐的**单域名多路径**（`/login`、`/admin`、`/portal`，可选 `/dev`）。原独立的 `k8s-istio-generated` 目录已并入此处。若仍使用自建或历史的 `ingress.yaml` + `deployment-*.yaml` 流水线，请以贵司维护的清单为准。细则见 **§9.0 / §9.3.2** 与 `deploy/k8s/README.md`。  
> **仓库不附带 Helm chart**（若需 Helm 请自建并与清单对齐）。

---

## 2. 部署架构总览

### 2.1 各环境部署方式

| 环境 | 平台 | PostgreSQL | Redis | Kafka | 后端 (×4) | 前端 (×3) |
|------|------|-----------|-------|-------|----------|----------|
| dev | Docker Desktop | 本地容器 | 本地容器 | 本地容器 | 本地容器 | 本地容器 |
| sit | 公司 K8S | ⚠️ 公司现有 | K8S Pod | K8S Pod | K8S Pod | K8S Pod |
| uat | 公司 K8S | ⚠️ 公司现有 | K8S Pod | K8S Pod | K8S Pod | K8S Pod |
| prod | 公司 K8S | ⚠️ 公司现有 | K8S Pod | K8S Pod | K8S Pod | K8S Pod |

> ⚠️ PostgreSQL 使用公司现有数据库，需要 DBA 提前创建好数据库和用户。

### 2.2 架构图

```
┌─────────────────────────────────────────────────────────┐
│                    Ingress (K8S) / Nginx                │
│         admin.company.com  portal.company.com         │
│         dev.company.com                                 │
└──────┬──────────────┬──────────────────┬────────────────┘
       │              │                  │
┌──────▼──────┐ ┌─────▼──────┐ ┌────────▼────────┐
│ Admin Center│ │ User Portal│ │ Platform Login  │
│  Frontend   │ │  Frontend  │ │  (nginx /login) │
│  (nginx)    │ │  (nginx)   │ │                 │
└──────┬──────┘ └──┬────┬────┘ └────────┬────────┘
       │           │    │               │
       └───────────┼────┼───────────────┘
                   │    │
            ┌──────▼────▼──────┐
            │  Kong Gateway    │
            │  /api → backends │
            └──────┬───────────┘
                   │
┌──────▼──────┐ ┌──▼────▼────┐ ┌───▼──────────────┐
│ Admin Center│ │ User Portal│ │ Dev Workstation   │
│  Backend    │ │  Backend   │ │  Backend *        │
└──────┬──────┘ └──┬────┬────┘ └───┬──────────────┘
       │           │    │           │
       └───────────┼────┼───────────┘
                   │
            ┌──────▼────▼──────┐
            │ Workflow Engine  │
            │   (Flowable)     │
            └──────┬───────────┘
                   │
    ┌──────────────┼──────────────┐
    │              │              │
┌───▼─────┐  ┌────▼────┐  ┌─────▼────┐
│PostgreSQL│  │  Redis  │  │  Kafka   │
│(公司现有) │  │(K8S部署) │  │(K8S部署) │
└─────────┘  └─────────┘  └──────────┘

* K8S 默认清单不部署 developer-workstation；仅本地 Dev 或 optional YAML。

```

### 2.3 需要部署的服务清单（K8S 默认）

**Ingress 经典路径**（`deploy/k8s/deploy.ps1`）：下表以 `deployment-*.yaml` 文件名为准。

**不含** `developer-workstation`（设计器仅在本地 Dev Compose 或 `deployment-developer-workstation-optional.yaml` 中启用）。`deployment-frontend.yaml` 内包含 **admin-center-frontend** 与 **user-portal-frontend** 两个 Deployment。

**Istio 路径**（`deploy/k8s`）：服务一一对应为同名风格的清单（如 `admin-center.yaml`、`workflow-engine.yaml`、`kong.yaml`），前端拆分为 `admin-center-frontend.yaml`、`user-portal-frontend.yaml`、`platform-login-frontend.yaml`、`platform-help-frontend.yaml`，入口为 `workflow-platform-ingress-gateway.yaml`；另含 `workflow-station-superset.yaml` 等。`developer-workstation` 相关清单默认不随全套部署，需 `-IncludeDeveloperWorkstation` 或 `-Select`。部署命令见 **§9.3.2**。

| # | 服务 | 类型 | K8S 清单 | 镜像 / 说明 |
|---|------|------|---------|------------|
| 1 | Redis | 基础设施 | `deployment-redis.yaml` | `redis:7.2-alpine` |
| 2 | Kafka | 基础设施 | `deployment-kafka.yaml` | `confluentinc/cp-kafka:7.5.3` |
| 3 | workflow-engine | 后端 | `deployment-workflow-engine.yaml` | 自建 JAR |
| 4 | admin-center | 后端 | `deployment-admin-center.yaml` | 自建 JAR |
| 5 | user-portal | 后端 | `deployment-user-portal.yaml` | 自建 JAR |
| 6 | Kong | API 边缘 | `deployment-kong.yaml` | 见清单 |
| 7 | admin-center-frontend | 前端 | `deployment-frontend.yaml` | `Dockerfile.local` 构建 |
| 8 | user-portal-frontend | 前端 | `deployment-frontend.yaml` | 同上 |
| 9 | platform-login-frontend | 前端（统一 `/login/`） | `deployment-platform-login-frontend.yaml` | `frontend/login` + `Dockerfile.local` |
| 10 | platform-help-frontend | 前端（指南 `/help/`） | `platform-help-frontend.yaml` | `frontend/help` + `Dockerfile.local` |

`deploy/scripts/build-and-push-k8s.ps1` 仍会构建 **developer-workstation** 与 **developer-workstation-frontend** 镜像，供本地或实验环境使用。

### 2.4 不部署的组件

| 组件 | 原因 |
|------|------|
| PostgreSQL | 使用公司现有数据库 |

> **说明**：API 边缘由 **Kong**（`deploy/k8s/kong.yaml`、`deploy/kong/`）与各业务后端分离部署。

### 2.5 Demo 数据与界面语言（固定英文）

与 **`docs/demo-data-requirements.md`** 中的外资银行 Demo 约定一致，部署与演示时注意：

| 层面 | 说明 |
|------|------|
| **种子数据 / 演示库** | 用户可见字段一律英文；细则见上述文档。 |
| **三个前端（admin-center / user-portal / developer-workstation）** | 默认界面语言在源码中固定：`frontend/<app>/src/i18n/index.ts` 内 `locale` 与 `fallbackLocale` 均为 `'en'`。**不通过** Docker / K8s 环境变量切换 i18n；改语言需改源码并重新 `pnpm run build`。 |
| **本地 Compose** | `deploy/environments/dev/docker-compose.dev.yml` 顶部注释标明本约定；Compose **无** `LOCALE` 类变量——避免误以为在 compose 里设变量即可改界面语言。 |
| **K8S 前端** | Ingress 路径：`deploy/k8s/deployment-frontend.yaml` 清单头部注释同上；Istio 路径：`deploy/k8s/*-frontend.yaml`。前端 Pod **无** `LOCALE` 环境变量。 |
| **deploy 快速参考** | `deploy/README.md` 章节 **「Demo：界面语言与种子数据（英文）」** 汇总 Compose / K8S / 构建约定。 |
| **演示账号与后端偏好** | 若库表或 JWT 中含用户 `language`（如 user-portal 用户偏好），Demo 种子建议设为 **`en`**，避免后端按中文偏好返回文案而与英文界面不一致。 |

---

## 3. 环境要求

```
Java 17+          (推荐 Eclipse Temurin / Microsoft OpenJDK)
Maven 3.9+        (mvn --version)
Node.js 18+       (node --version；推荐 20 LTS，与仓库前端 toolchain 一致)
pnpm 9+            (pnpm --version)
Docker Desktop    (docker --version, docker compose version)
kubectl           (SIT/UAT/PROD 部署需要)
```

---

## 4. 项目结构

```
Workflow-Station---sun/
├── pom.xml                          # Maven 根 POM (多模块)
├── BUILD_GUIDE.md                   # 本文档
├── README.md
├── docs/                            # 产品文档索引（技术栈、架构、Schema/Flyway、RBAC 等）
├── documentation/                   # 功能单元深度指南等
├── backend/
│   ├── platform-common/             # 公共库 (jar, 不部署)
│   ├── platform-cache/              # 缓存库 (jar, 不部署)
│   ├── platform-security/           # 安全库 (jar, 不部署)
│   ├── platform-messaging/          # 消息库 (jar, 不部署)
│   ├── workflow-engine-core/        # 工作流引擎 (Spring Boot, 可部署)
│   ├── admin-center/                # 管理后台 (Spring Boot, 可部署)
│   ├── developer-workstation/       # 开发者工作台 (Spring Boot, 可部署)
│   └── user-portal/                 # 用户门户 (Spring Boot, 可部署)
├── frontend/
│   ├── admin-center/                # 管理后台前端 (Vue 3)
│   ├── user-portal/                 # 用户门户前端 (Vue 3)
│   ├── developer-workstation/       # 开发者工作台前端 (Vue 3)
│   ├── login/                       # 统一登录壳（K8S /login/，Dockerfile.local）
│   └── help/                        # 指南门户（K8S /help/，无登录）
├── deploy/
│   ├── environments/
│   │   ├── dev/                     # 本地 Docker 开发环境
│   │   │   ├── .env
│   │   │   ├── docker-compose.dev.yml
│   │   │   └── build-and-deploy.ps1
│   │   ├── sit/.env                 # SIT 参考配置
│   │   ├── uat/.env                 # UAT 参考配置
│   │   └── prod/.env                # PROD 参考配置
│   ├── scripts/
│   │   └── build-and-push-k8s.ps1   # K8S 镜像构建推送脚本
│   ├── kong/                        # Kong 声明式配置模板
│   ├── k8s/                         # K8S 清单（Istio Gateway；PowerShell 渲染占位符）
│   │   ├── README.md
│   │   ├── kustomization.yaml
│   │   ├── workflow-platform-ingress-gateway.yaml
│   │   ├── *.yaml                   # 各服务（redis、kafka、kong、各后端与前端等）
│   │   ├── config_map/<Environment>/  # 如 preprod；应用与 Kong 等配置
│   │   ├── secret/<Environment>/
│   │   ├── init-data/               # 离线 SQL：init-flowable、init-platform-schema（DDL）、init-platform-seed（种子）；见 README
│   │   └── ps1/                     # apply-workflow-station-all.ps1 等
│   ├── init-scripts/                # 数据库初始化 SQL
│   └── README.md
└── TODO.md
```

---

## 5. 后端服务详细参数

| 服务 | Maven 模块路径 | JAR 文件名模式 | context-path | Healthcheck 路径 | JVM 内存 |
|------|---------------|---------------|-------------|-----------------|---------|
| workflow-engine | `backend/workflow-engine-core` | `workflow-engine-core-*.jar` | `/` | `/actuator/health` | 512m-1024m |
| admin-center | `backend/admin-center` | `admin-center-*.jar` | `/api/v1/admin` | `/api/v1/admin/actuator/health` | 256m-512m |
| developer-workstation | `backend/developer-workstation` | `developer-workstation-*.jar` | `/api/v1` | `/api/v1/actuator/health` | 256m-512m |
| user-portal | `backend/user-portal` | `user-portal-*.jar` | `/api/portal` | `/api/portal/actuator/health` | 256m-512m |

所有后端 Dockerfile 结构相同（**Temurin 17 JRE**，非 Alpine；用户与权限用 `groupadd` / `useradd`）：
```dockerfile
ARG JAVA_BASE_IMAGE=eclipse-temurin:17-jre
FROM ${JAVA_BASE_IMAGE}
WORKDIR /app
RUN groupadd --system platform && useradd --system --gid platform --no-create-home platform
COPY target/<service>-*.jar app.jar
RUN chown -R platform:platform /app
USER platform
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=100"
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q -T 5 -O /dev/null http://localhost:8080/<healthcheck-path> || exit 1
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

构建时可通过 **`--build-arg JAVA_BASE_IMAGE=<镜像名>`** 指定基础镜像（与 `build-and-deploy.ps1` / `build-and-push-k8s.ps1` 的 `-JavaBaseImage` 一致）。不传则使用 Dockerfile 内默认值 `eclipse-temurin:17-jre`。

---

## 6. 前端 nginx 环境变量替换机制（关键）

### 6.1 工作原理

前端容器内 **nginx** 将浏览器的 **`/api/*`** 代理到 **`KONG_PROXY_URL`（Kong）**，由 Kong 再转发到各后端。模板中主要占位符为 **`${KONG_PROXY_URL}`**（见各 `frontend/*/nginx.conf`）。

流程：
1. `Dockerfile.local` 将 `nginx.conf` 复制为 `/etc/nginx/conf.d/default.conf.template`（模板）
2. 容器启动时执行 `docker-entrypoint.sh`
3. `docker-entrypoint.sh` 校验 **`KONG_PROXY_URL`**（缺失则 `exit 1`）
4. 用 `envsubst '${KONG_PROXY_URL}'` 生成 `default.conf`
5. 启动 nginx

### 6.2 envsubst 的关键细节

`envsubst` 命令**必须显式指定要替换的变量列表**：

```sh
# ✅ 正确 — 与 frontend/*/docker-entrypoint.sh 一致
envsubst '${KONG_PROXY_URL}' < /etc/nginx/conf.d/default.conf.template > /etc/nginx/conf.d/default.conf

# ❌ 错误 — 会替换所有 $xxx，包括 nginx 的 $host, $uri, $http_upgrade 等
envsubst < template > default.conf
```

### 6.3 每个前端服务的环境变量

| 前端服务 | 需要的环境变量 | envsubst 变量列表 |
|---------|--------------|-----------------|
| admin-center-frontend | `KONG_PROXY_URL` | `'${KONG_PROXY_URL}'` |
| user-portal-frontend | `KONG_PROXY_URL` | `'${KONG_PROXY_URL}'` |
| developer-workstation-frontend | `KONG_PROXY_URL` | `'${KONG_PROXY_URL}'` |

Docker Compose / K8s 中典型值：`http://kong:8000`（开发）或 `http://kong-service:8000`（见 `deploy/k8s/configmap-sit.yaml` 等）。

---

## 7. 构建步骤（手动逐步执行）

以下命令全部在项目根目录执行。

### 7.1 后端 Maven 构建

```powershell
# 编译所有后端模块（跳过测试），生成 JAR 到各 target/ 目录
mvn clean package -DskipTests -pl backend/platform-common,backend/platform-cache,backend/platform-security,backend/platform-messaging,backend/workflow-engine-core,backend/admin-center,backend/developer-workstation,backend/user-portal -am
```

成功标志：`BUILD SUCCESS`，8 个模块全部 SUCCESS。

产物验证：
```powershell
# 确认 4 个可部署 JAR 存在
Get-ChildItem backend/workflow-engine-core/target/*.jar -Exclude *original*
Get-ChildItem backend/admin-center/target/*.jar -Exclude *original*
Get-ChildItem backend/developer-workstation/target/*.jar -Exclude *original*
Get-ChildItem backend/user-portal/target/*.jar -Exclude *original*
```

### 7.2 前端 pnpm 构建

> 前端与 `automation/` 统一用 **pnpm 9.15.9**，版本由各 `package.json` 的
> `"packageManager"` 锁定。构建机只需 `corepack enable`，corepack 会按该字段自动取到
> 正确版本，无需全局装 pnpm。仓库内**不再有** `package-lock.json`。

```powershell
# admin-center-frontend
Push-Location frontend/admin-center; pnpm install --frozen-lockfile; pnpm run build; Pop-Location

# user-portal-frontend
Push-Location frontend/user-portal; pnpm install --frozen-lockfile; pnpm run build; Pop-Location

# developer-workstation-frontend
# ⓪ 装 Activepieces workspace 依赖（干净 checkout 上没有；只需装一次，后续构建复用）
#    走 deploy/scripts/build-and-push-k8s.ps1 的话这步已内置，不用手工跑（见 7.2 末尾）。
#    必须在 activepieces 根目录装：这是 pnpm workspace，根 install 才会给 packages/web 建好链接。
#    不需要任何 NPM_TOKEN / registry 环境变量（见下方 .npmrc 说明）。
Push-Location activepieces; pnpm install --frozen-lockfile; Pop-Location
# ① 再构建内嵌的 Activepieces builder（产物 gitignore，干净 checkout 上不存在）
Push-Location automation/packages/web; pnpm exec vite build --config vite.embed.config.mts; Pop-Location
# ② 必须用 pnpm run build（不是直接调 vite）——只有前者会触发 prebuild 钩子，
#    把 ① 的产物拷进 public/service-task-builder/
Push-Location frontend/developer-workstation; pnpm install --frozen-lockfile; pnpm run build; Pop-Location

# platform-login-frontend（K8S 统一登录 /login/）
Push-Location frontend/login; pnpm install --frozen-lockfile; pnpm run build; Pop-Location
# platform-help-frontend（K8S 指南 /help/）
Push-Location frontend/help; pnpm install --frozen-lockfile; pnpm run build; Pop-Location
```

成功标志：每个前端输出 `✓ built in XXs`，`dist/` 目录生成。

> ⚠️ **⓪ 是重活也是唯一联网前置**：`automation/node_modules` 约 2.9 GB、几千个包。
> 气隙构建机在这一步会断，此时改走"别处构建 bundle + 拷 `automation/dist/packages/web-embed/` 过来"
> （见 7.2 末尾脚本行为说明）。想减负可只装 web 及其 workspace 依赖：
> `pnpm install --frozen-lockfile --filter web...`。
>
> ℹ️ **registry 完全跟随机器的默认配置**（公司 Nexus 私服直接可用，无需任何额外开关）。
> `automation/.npmrc` 原本写死 `@activepieces:registry=https://registry.npmjs.org/` 加一行
> `//registry.npmjs.org/:_authToken=${NPM_TOKEN}`，两行已删（原因见该文件顶部的 HERMES-PATCH 注释）：
> token 行只服务上游发布 `@activepieces/*` 的 CI（在本仓库根本不会触发），却让 NPM_TOKEN 未设时
> pnpm 静默丢弃整个文件；scope pin 若单独留下会首次生效、把那几个包顶到公网、绕过私服。
> 现在全部包走默认 registry（Nexus 必须能代理 npm 公共库），`pnpm-lock.yaml` 的 integrity
> 仍然逐包校验内容，私服换不了包。大装之前可先探可达性：
> `pnpm info @activepieces/import-fresh-webpack@3.3.0 dist.tarball`
> —— 这个包连同传递依赖 `pieces-framework@0.32.0`、`shared@0.95.1/0.96.2` 是 lockfile 里仅有的 4 个
> 走 registry 的 `@activepieces/*`；workspace 内的同名包是 `workspace:*` 本地链接，不走网络。
>
> ⚠️ **内网私服只部分镜像了公共 npm 时**（某个版本干脆不存在，`ERR_PNPM_FETCH_404`）：`pnpm install`
> 是 fail-fast，一轮只暴露一个包，而五个 lockfile 合计几千个包。先用
> `deploy/scripts/probe-npm-registry-coverage.ps1 -Registry <私服地址>` 一次探全，拿到完整缺口清单
> 再决定——批量申请入库，还是像 `mdurl` 那样在 `automation/package.json` 的 `pnpm.overrides` 里降到
> 私服有的版本（只有语义化范围收得住时才安全，降完必须重算并提交 lockfile）。缺口太多就别硬降，
> 改走"带 `web-embed` 产物过去"那条路。
>
> ⚠️ **developer-workstation-frontend 的三步顺序不能省**。Function Unit 的 Automation 标签
> 直接挂 AP builder，其 bundle 走「构建期拷贝」交付（AG-02.8）：AP 产物 →
> `public/service-task-builder/` → `dist/` → 镜像。产物是 gitignore 的构建物，**不入库**。
> 漏掉任一步，构建仍然全绿、镜像照推，但运行时 `/dev/service-task-builder/web.css` 返回 404。
> 打镜像前可先自查：`ls frontend/developer-workstation/dist/service-task-builder/web.css`。
> 走 `deploy/scripts/build-and-push-k8s.ps1` 则 **⓪①② 全部内置**，日常发布只需要这一条命令，
> 上面那段手工步骤只在不用脚本、单独构建某个前端时才需要照抄。脚本的 AP 相关行为：
>
> 1. **⓪ 自动装**：判定方式与四个前端一致（`automation/node_modules/.modules.yaml` 的 mtime
>    对比 `package.json` / `pnpm-lock.yaml`）—— 已是最新就打一行 `skipping pnpm install` 跳过，
>    否则在 `automation/` 跑 `pnpm install --frozen-lockfile`。**每个 checkout 只会真装一次**；
>    同一台机器换新目录会重新装（2.9 GB 不跨目录复用）。
> 2. **① 每次重建 bundle**，② 由 DW 的 `prebuild` 钩子拷贝；缺产物直接构建失败
>    （`SERVICE_TASK_BUILDER_REQUIRED`），不会静默放过。
> 3. **装不上或建不出来都不立即停**：只要 `automation/dist/packages/web-embed/ap-builder.mjs`
>    已存在（从别处拷来），就**告警并复用**该 bundle 继续构建。三种触发复用的情形：装依赖失败、
>    `node_modules` 压根不存在、以及 `node_modules` 存在但 vite 构建失败（半成品 workspace ——
>    fetch 中途死掉会留下目录，光看目录在不在会误判"能重建"）。只有"建不出来 + 也没有可复用 bundle"
>    才 FAIL 停下。
> 4. 私服注定装不成的机器可以直接 **`-SkipApWorkspaceInstall`**：跳过那十几分钟必败的 install，
>    直接用拷过来的 bundle。此时若连 bundle 也没有，仍然 FAIL（不会静默出一个缺 builder 的镜像）。
> 5. **四个前端同理**：`-UsePrebuiltFrontendDist` 让每个前端镜像直接用现成的 `dist/` 打包，
>    不跑 `pnpm install`、不跑 vite。用于私服隔离/缺失了前端 lockfile 钉的包（实测过 `axios@1.13.2`
>    被 FOSS Guard 隔离）。产物在能装的机器上构建后按 `frontend/<app>/dist/` 原路径拷过去；
>    缺 `dist/index.html` 的服务会**单独失败**，不会打出一个空壳镜像。
>    ⚠️ 这两个开关都把"产物新鲜度"的责任转移给拷产物的人 —— 脚本分不出今天构建的和一个月前的，
>    只会打一行黄色 WARNING。常态发布仍应走能联网装依赖的构建机。
> 6. **整套不部署 Activepieces 时**用 `-NoServiceTaskBuilder`：这一趟构建**完全不碰 AP** ——
>    不装 AP workspace、不构建 bundle，**并且磁盘上已有的 bundle 也不会进镜像**。
>    机制是两个环境变量，脚本对 DW 这个服务两个方向都显式赋值（thread job 共享宿主进程环境变量，
>    同一个 shell 里先跑过常规发布会把值漏给下一趟）：
>    - 清掉 `SERVICE_TASK_BUILDER_REQUIRED` —— 否则缺 bundle 时 `prebuild` 硬失败（exit 1）；
>    - 设上 `SERVICE_TASK_BUILDER_SKIP=1` —— 只清上面那个还不够：`automation/dist/packages/web-embed`
>      或上一次构建留下的 `public/service-task-builder` 仍会被钩子拷进去 / 被 vite 打进 `dist/`，
>      镜像照样带着整个 AP builder。设了它，钩子改为**删除** `public/service-task-builder` 后 exit 0。
>      （实测三条路径：SKIP 有残留时删并 exit 0、无残留时直接 exit 0、常规运行照常拷回。
>      被删的只是 gitignore 的副本，源 `automation/dist/` 不动，下次常规构建自动重建。）
>
>    与 `-UsePrebuiltFrontendDist` 同时用时不走 vite、钩子根本不跑，脚本改为直接从拷过来的
>    `frontend/developer-workstation/dist/service-task-builder` 里删掉该目录，语义两条路径一致。
>    产出的 DW 镜像**明确不带 Automation builder**，该标签报"builder 不可用"，而不是 404 白屏。
>    默认行为仍是 fail-closed —— 只有显式加这个开关才会放行。

### 7.3 Docker 镜像构建

#### 后端镜像（4 个）

建议先拉取基础镜像（与脚本默认一致时可减少构建阶段访问 Docker Hub）：

```powershell
$javaBase = "docker.m.daocloud.io/library/eclipse-temurin:17-jre"
docker pull $javaBase   # 可选；失败时可依赖本地已有层
```

```powershell
$registry = "harbor.company.com/workflow"
$tag = "latest"
$javaBase = "docker.m.daocloud.io/library/eclipse-temurin:17-jre"

docker build --build-arg "JAVA_BASE_IMAGE=$javaBase" --pull=false -t "${registry}/workflow-engine-core:${tag}" backend/workflow-engine-core
docker build --build-arg "JAVA_BASE_IMAGE=$javaBase" --pull=false -t "${registry}/admin-center:${tag}" backend/admin-center
docker build --build-arg "JAVA_BASE_IMAGE=$javaBase" --pull=false -t "${registry}/developer-workstation:${tag}" backend/developer-workstation
docker build --build-arg "JAVA_BASE_IMAGE=$javaBase" --pull=false -t "${registry}/user-portal:${tag}" backend/user-portal
```

不传 `JAVA_BASE_IMAGE` 时，Dockerfile 内默认使用 **`eclipse-temurin:17-jre`**（需本机已存在或由 Docker 自行拉取）。生产/内网构建请优先与 **`deploy/scripts/build-and-push-k8s.ps1`** 使用相同的 `-JavaBaseImage` 与 `--pull=false` 策略。

#### 前端镜像（5 个，必须使用 Dockerfile.local）

```powershell
# ⚠️ 注意 -f 参数指定 Dockerfile.local，不是默认的 Dockerfile
docker build -f frontend/admin-center/Dockerfile.local -t "${registry}/admin-center-frontend:${tag}" frontend/admin-center
docker build -f frontend/user-portal/Dockerfile.local -t "${registry}/user-portal-frontend:${tag}" frontend/user-portal
docker build -f frontend/developer-workstation/Dockerfile.local -t "${registry}/developer-workstation-frontend:${tag}" frontend/developer-workstation
docker build -f frontend/login/Dockerfile.local -t "${registry}/platform-login-frontend:${tag}" frontend/login
docker build -f frontend/help/Dockerfile.local -t "${registry}/platform-help-frontend:${tag}" frontend/help
```

验证（自建业务镜像通常 **9** 个：4 后端 + 5 前端）：
```powershell
docker images "${registry}/*" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}"
```

---

## 8. Dev 环境部署（本地 Docker Desktop）

### 8.1 一键部署

```powershell
cd deploy/environments/dev

# 完整构建 + 部署（Maven + pnpm + Docker + 启动容器）
.\build-and-deploy.ps1

# 构建并部署单个服务（Maven + Docker，不影响其他服务）
.\build-and-deploy.ps1 -Service admin-center

# 单个服务跳过 Maven（只重建 Docker 镜像并重启）
.\build-and-deploy.ps1 -Service admin-center -SkipMaven

# 构建并部署单个前端
.\build-and-deploy.ps1 -Service admin-center-frontend

# 跳过 Maven（只重建 Docker 镜像并重启）
.\build-and-deploy.ps1 -SkipMaven

# 跳过前端
.\build-and-deploy.ps1 -SkipFrontend

# 只重启服务（不构建任何东西）
.\build-and-deploy.ps1 -ServicesOnly

# 清除所有容器和数据卷，从零开始
.\build-and-deploy.ps1 -Clean

# 指定后端运行时基础镜像（默认即为 DaoCloud 镜像；与预拉取、compose build 一致）
.\build-and-deploy.ps1 -JavaBaseImage "docker.m.daocloud.io/library/eclipse-temurin:17-jre"

# 跳过基础镜像预拉取（本地已缓存时使用）
.\build-and-deploy.ps1 -SkipImagePull
```

**`-JavaBaseImage`**：传给后端镜像的 `JAVA_BASE_IMAGE` 构建参数，并用于步骤 0 中 Temurin 的 mirror 预拉取（再 `docker tag` 为 `eclipse-temurin:17-jre` 供其它依赖短名的层使用）。省略时使用脚本内置默认（DaoCloud）。**`-SkipImagePull`**：跳过整段预拉取（不仅 Java，见脚本内镜像列表）。

`-Service` 可选值：

| 类型 | `-Service` 值 | 说明 | Maven 模块 / 前端目录 |
|------|--------------|------|----------------------|
| 后端 | `workflow-engine` | 工作流引擎 | `backend/workflow-engine-core` |
| 后端 | `admin-center` | 管理后台 API | `backend/admin-center` |
| 后端 | `user-portal` | 用户门户 API | `backend/user-portal` |
| 后端 | `developer-workstation` | 开发者工作台 API | `backend/developer-workstation` |
| 前端 | `admin-center-frontend` | 管理后台 UI | `frontend/admin-center` |
| 前端 | `user-portal-frontend` | 用户门户 UI | `frontend/user-portal` |
| 前端 | `developer-workstation-frontend` | 开发者工作台 UI | `frontend/developer-workstation` |
| 前端 | `platform-login-frontend` | 统一登录 `/login/` | `frontend/login` |
| 前端 | `platform-help-frontend` | 指南门户 `/help/` | `frontend/help` |

### 8.2 服务端口映射

以下宿主机端口以 **`deploy/environments/dev/.env`** 为准（表中为仓库当前默认值）；若你修改了 `.env`，以实际映射为准。

| 服务 | 容器内端口 | 宿主机端口（默认） | URL / 说明 |
|------|-----------|-------------------|------------|
| PostgreSQL | 5432 | `POSTGRES_PORT`（5432） | `localhost:5432` |
| Redis | 6379 | `REDIS_PORT`（6379） | `localhost:6379` |
| Kafka (KRaft) | 容器内 9092 / 29092 | `KAFKA_PORT`（**19092** → 映射到容器 9092） | `localhost:19092`（宿主机客户端） |
| Kong proxy / admin | 8000 / 8001 | `KONG_PROXY_PORT` / `KONG_ADMIN_PORT`（8000 / 8001） | 例如 `http://localhost:8000` |
| workflow-engine | 8080 | `WORKFLOW_ENGINE_PORT`（8081） | `http://localhost:8081` |
| admin-center | 8080 | `ADMIN_CENTER_PORT`（8090） | `http://localhost:8090` |
| user-portal | 8080 | `USER_PORTAL_PORT`（8082） | `http://localhost:8082` |
| developer-workstation | 8080 | `DEVELOPER_WORKSTATION_PORT`（8083） | `http://localhost:8083` |
| admin-center-frontend | 80 | `ADMIN_CENTER_FRONTEND_PORT`（**3100**） | 直连静态站：`http://localhost:3100` |
| user-portal-frontend | 80 | `USER_PORTAL_FRONTEND_PORT`（**3101**） | `http://localhost:3101` |
| developer-workstation-frontend | 80 | `DEVELOPER_WORKSTATION_FRONTEND_PORT`（**3102**） | `http://localhost:3102` |
| platform-login-frontend | 80 | `PLATFORM_LOGIN_FRONTEND_PORT`（**3110**） | `http://localhost:3110` |
| platform-help-frontend | 80 | `PLATFORM_HELP_FRONTEND_PORT`（**3111**） | `http://localhost:3111` |
| edge-frontend（nginx 聚合 `/admin` `/portal` `/login` `/dev`） | 80 | `EDGE_FRONTEND_PORT`（**3000**） | **推荐本地入口**：`http://localhost:3000` |

> **Flyway**：`docker-compose.dev.yml` 对 admin-center / user-portal / developer-workstation 设置了 **`SPRING_FLYWAY_ENABLED=false`**，Dev 容器依赖 Postgres 首次初始化时的 **`deploy/init-scripts/`**；与默认 `application.yml`（Flyway 启用）及 K8S 行为可能不同，见 [docs/schema-and-migration.md](docs/schema-and-migration.md) §2.1。

### 8.3 数据库初始化

Dev 环境首次启动时，PostgreSQL 容器会自动执行 `deploy/init-scripts/` 下的 SQL 脚本（通过 Docker volume mount 到 `/docker-entrypoint-initdb.d`）。

如需手动初始化（非 Docker 场景）：
```powershell
cd deploy/init-scripts
.\init-database.ps1 -DbHost localhost -DbPort 5432 -DbName workflow_platform_dev -DbUser platform_dev -DbPassword dev_password_123
```

#### 8.3.1 开发者工作台：部署任务表 `dw_deployment_jobs`

一键部署到 **admin-center** 的异步任务状态持久化在此表（支持多实例 `developer-workstation` 与进程重启后仍可查进度/历史）。与 Flyway **`V309__create_dw_deployment_jobs.sql`** 定义一致。

| 列 | 说明 |
|----|------|
| `id` | 部署任务 UUID（与接口 `deploymentId` 一致） |
| `function_unit_id` | 关联 `dw_function_units.id`，级联删除 |
| `target_admin_url` | 本次调用的管理中心基址 |
| `status` | `PENDING` / `DEPLOYING` / `SUCCESS` / `FAILED` / `ROLLED_BACK` |
| `progress` | 0–100 |
| `message` | 摘要信息 |
| `version_number` / `change_log` | 发布版本与变更说明 |
| `steps_json` | 步骤列表 JSON |
| `started_at` / `completed_at` / `updated_at` | 时间戳（`TIMESTAMPTZ`） |

**已有数据库增量执行**（未走完整 `init-database.ps1` 时）：

```powershell
cd deploy/init-scripts
# 按实际连接修改 -h -p -U -d；密码可用环境变量 PGPASSWORD
psql -h localhost -p 5432 -U platform -d workflow_platform -v ON_ERROR_STOP=1 -f 00-schema/26-add-dw-deployment-jobs.sql
```

新环境：`00-init-all.sh` / `init-database.ps1` 已包含 `26-add-dw-deployment-jobs.sql`，一般无需单独执行。

**相关环境变量**见下文 **§12.2.1 developer-workstation 专项**。

### 8.4 默认登录账号

```
用户名: admin
密码:   password
```

### 8.5 常用 Docker Compose 命令

```powershell
$compose = "deploy/environments/dev/docker-compose.dev.yml"
$env = "deploy/environments/dev/.env"

# 查看所有容器状态
docker compose -f $compose --env-file $env ps

# 查看某个服务日志
docker compose -f $compose --env-file $env logs -f admin-center

# 重启某个服务
docker compose -f $compose --env-file $env restart admin-center

# 停止所有服务
docker compose -f $compose --env-file $env down

# 停止并删除数据卷（清空数据库）
docker compose -f $compose --env-file $env down -v
```

---

## 9. SIT/UAT/PROD 环境部署（公司 K8S）

### 9.0 部署路径选择

| 方式 | 目录与脚本 | 入口 | 说明 |
|------|-----------|------|------|
| **Istio（推荐，本仓库主线）** | `deploy/k8s/ps1/apply-workflow-station-all.ps1` 等 | `workflow-platform-ingress-gateway`（Istio Gateway，HTTP→HTTPS、TLS） | 集群已装 **Istio**；清单内 `__NAMESPACE__` / `__IMAGE_TAG__` 等由脚本渲染，**勿手写死** |
| Ingress 经典（可选） | 自建或历史分支中的 `deploy.ps1` + `deployment-*.yaml` | `ingress.yaml`（Kubernetes Ingress） | 未随本仓库提供完整套；主线以 `deploy/k8s/` 下 Istio 清单为准 |

镜像构建与推送共用 **`deploy/scripts/build-and-push-k8s.ps1`**。应用配置来自 `deploy/k8s/config_map/<Environment>/`、`secret/<Environment>/`（默认 `preprod`），完整参数、分步顺序、`-InitializeDatabase`、`-RenderOnly` 见 **`deploy/k8s/ps1/README.md`**。

### 9.1 部署前准备

#### 9.1.1 PostgreSQL 数据库准备（DBA 操作）

PostgreSQL 使用公司现有数据库，需要 DBA 提前完成以下操作：

```sql
-- 1. 创建应用数据库
CREATE DATABASE workflow_platform_{env} OWNER platform_{env};

-- 2. 创建数据库用户（如果不存在）
CREATE USER platform_{env} WITH PASSWORD 'your_strong_password';
GRANT ALL PRIVILEGES ON DATABASE workflow_platform_{env} TO platform_{env};
```

其中 `{env}` 替换为 `sit` / `uat` / `prod`。

#### 9.1.2 数据库 Schema 初始化

首次部署需要初始化数据库 schema。使用 `init-database.ps1` 脚本：

```powershell
cd deploy/init-scripts
.\init-database.ps1 -DbHost {postgres-host} -DbPort 5432 -DbName workflow_platform_{env} -DbUser platform_{env} -DbPassword {password}
```

或者手动执行 SQL：
```powershell
# 使用 standalone schema 文件
cd deploy/init-scripts
psql -h {host} -p 5432 -U platform_{env} -d workflow_platform_{env} -f 00-schema/00-init-all-schemas-standalone.sql
```

**无仓库工具链的新库**：`deploy/k8s/init-data/` 提供离线 SQL。**图形客户端**请按顺序执行 **`init-platform-schema/all-in-one-for-gui.sql`** → **`init-flowable/create/flowable.postgres.all.create.sql`** → **`init-platform-seed/all-in-one-for-gui.sql`**（纯 SQL；其中 seed **仅为 `01-admin`**，无 08/15/16 演示包）。需要完整演示数据时用 **`deploy/init-scripts/init-database.ps1`**。有 **`psql`** 时也可对上述三个文件执行 `psql -f`。详见 **`deploy/k8s/init-data/README.md`**。

若库已初始化、仅需补开发者部署任务表，可单独执行：

```powershell
cd deploy/init-scripts
psql -h {host} -p 5432 -U platform_{env} -d workflow_platform_{env} -v ON_ERROR_STOP=1 -f 00-schema/26-add-dw-deployment-jobs.sql
```

#### 9.1.3 更新 K8S 配置

**Ingress 经典路径**：按下面步骤编辑 `deploy/k8s/` 下按环境的 ConfigMap / Secret。

**Istio 路径**：连接串与密钥通常在 `deploy/k8s/config_map/<Environment>/`、`secret/<Environment>/` 中维护（与 `-Environment` 一致）；勿与 `deploy/k8s/configmap-{sit,uat,prod}.yaml` 混用同一套文件。

1. 修改 `deploy/k8s/configmap-{env}.yaml` 中的 PostgreSQL 连接地址：
   ```yaml
   SPRING_DATASOURCE_URL: "jdbc:postgresql://{your-postgres-host}:5432/workflow_platform_{env}"
   ```

2. 修改 `deploy/k8s/secret-{env}.yaml` 中所有 `CHANGE_ME` 值为真实密码。

### 9.2 构建并推送镜像

```powershell
cd deploy/scripts

# 构建并推送所有镜像到 Harbor
.\build-and-push-k8s.ps1 -Registry harbor.company.com/workflow -Tag v1.0.0 -SkipTests

# 只构建后端
.\build-and-push-k8s.ps1 -Registry harbor.company.com/workflow -Tag v1.0.0 -SkipTests -SkipFrontend

# 只构建前端
.\build-and-push-k8s.ps1 -Registry harbor.company.com/workflow -Tag v1.0.0 -SkipTests -SkipBackend

# 只推送（不重新构建）
.\build-and-push-k8s.ps1 -Registry harbor.company.com/workflow -Tag v1.0.0 -PushOnly

# 指定后端 Dockerfile 基础镜像（默认：docker.m.daocloud.io/library/eclipse-temurin:17-jre）
# 脚本会在后端构建前 docker pull 该镜像，并在 docker build 时使用 --build-arg JAVA_BASE_IMAGE=... 与 --pull=false
.\build-and-push-k8s.ps1 -Registry harbor.company.com/workflow -Tag v1.0.0 -SkipTests -JavaBaseImage "docker.m.daocloud.io/library/eclipse-temurin:17-jre"
```

省略 **`-JavaBaseImage`** 时与显式传入上述默认值等价：仍使用 DaoCloud Temurin 镜像并完成预拉取。

### 9.3 K8S 部署

#### 9.3.1 Ingress 经典（`deploy/k8s/deploy.ps1`）

```powershell
cd deploy/k8s

# 部署到 SIT
.\deploy.ps1 -Environment sit -Tag v1.0.0

# 部署到 UAT
.\deploy.ps1 -Environment uat -Tag v1.0.0

# 部署到 PROD
.\deploy.ps1 -Environment prod -Tag v1.0.0

# Dry-run（只验证不实际部署）
.\deploy.ps1 -Environment sit -Tag v1.0.0 -DryRun
```

#### 9.3.2 Istio 生成清单（`deploy/k8s`，新）

适用于集群已安装 **Istio**、且使用 **Istio ingressgateway** + TLS Secret 的场景。YAML 使用 `__NAMESPACE__`、`__IMAGE_TAG__`、`__BASE_DOMAIN__`、`__INGRESS_HOST__`、`__INGRESS_TLS_SECRET__` 等占位符，由 PowerShell 脚本按参数替换；**推荐**使用一键脚本（顺序：ConfigMap → Secret → 业务清单）：

```powershell
# 在仓库根目录执行；参数按实际 namespace、镜像 tag、域名与 TLS secret 名填写
.\deploy\k8s\ps1\apply-workflow-station-all.ps1 `
  -Namespace <your-namespace> `
  -ImageTag <tag> `
  -Environment preprod `
  -NamespaceToken <namespace-token> `
  -IngressHost <入口主机名> `
  -IngressTlsSecret <tls-secret-name> `
  -BaseDomain <base-domain>
```

- **仅渲染**到本地、不执行 `kubectl`：加 `-RenderOnly -OutputDir <目录>`（详见 `ps1/README.md`）。  
- **分步**：`apply-workflow-station-configmap.ps1` → `apply-workflow-station-secret.ps1` → `apply-workflow-station-istio-generated.ps1`。  
- **首批/分批**部署、`-Select`、`-InitializeDatabase`（可选先跑 `init-database.ps1`）、`-IncludeDeveloperWorkstation`、删除用 `delete-workflow-station-istio-generated.ps1` 等：**以 `deploy/k8s/ps1/README.md` 为准**。

### 9.4 部署顺序

**Ingress 经典**：`deploy.ps1` 会按以下顺序部署（与 `deploy.ps1` 内 `$deploymentFiles` 一致）：

1. 创建 Namespace
2. 应用 ConfigMap
3. 应用 Secret
4. 创建 Kong 声明式配置 ConfigMap（`deploy/kong/`）
5. 依次 apply：`deployment-redis` → `deployment-kafka` → `deployment-workflow-engine` → `deployment-admin-center` → `deployment-user-portal` → `deployment-kong` → `deployment-frontend`（内含 admin + user-portal 前端）→ `deployment-platform-login-frontend` → `pdb.yaml`
6. 应用 Ingress

**说明**：默认清单 **不包含** `developer-workstation`；可选清单见 `deployment-developer-workstation-optional.yaml`。

**Istio 路径**：无 `deploy.ps1` 的固定顺序；若需分阶段，建议按 `deploy/k8s/ps1/README.md` 中的批次（如 redis / kafka / superset → kong → workflow-engine → 其余后端 → 前端，Gateway 与前端同批时注意包含 `workflow-platform-ingress-gateway.yaml`）。

### 9.5 验证部署

```powershell
# 查看所有 Pod 状态
kubectl get pods -n workflow-platform-{env}

# 查看服务
kubectl get svc -n workflow-platform-{env}

# 查看 Ingress
kubectl get ingress -n workflow-platform-{env}

# 查看某个 Pod 日志
kubectl logs -f deployment/workflow-engine -n workflow-platform-{env}

# 检查 Pod 健康状态
kubectl describe pod -l app=workflow-engine -n workflow-platform-{env}
```

### 9.6 K8S Service 内部 URL

所有服务在 K8S 内部通过 Service 名称互相访问：

| Service 名称 | 端口 | 用途 |
|-------------|------|------|
| `redis-service` | 6379 | Redis 缓存 |
| `kafka-service` | 29092 | Kafka 消息队列 |
| `workflow-engine-service` | 8080 | 工作流引擎 |
| `admin-center-service` | 8080 | 管理后台 API |
| `user-portal-service` | 8080 | 用户门户 API |
| `developer-workstation-service` | 8080 | 开发者工作台 API（默认未部署） |
| `kong-service` | 8000（proxy）、8001（admin） | Kong 代理入口 |
| `admin-center-frontend-service` | 80 | 管理后台 UI |
| `user-portal-frontend-service` | 80 | 用户门户 UI |
| `platform-login-frontend-service` | 80 | 统一登录 `/login/` |
| `platform-help-frontend-service` | 80 | 指南门户 `/help/` |
| `developer-workstation-frontend-service` | 80 | 开发者工作台 UI（默认未部署） |

### 9.7 镜像 Registry

```
harbor.company.com/workflow/
├── workflow-engine-core:latest       # 后端 (自建)
├── admin-center:latest               # 后端 (自建)
├── developer-workstation:latest      # 后端 (自建)
├── user-portal:latest                # 后端 (自建)
├── admin-center-frontend:latest      # 前端 (自建)
├── user-portal-frontend:latest       # 前端 (自建)
├── developer-workstation-frontend:latest  # 前端 (自建)
└── platform-login-frontend:latest    # 统一登录 (自建)
└── platform-help-frontend:latest     # 指南门户 (自建)

# 基础设施使用官方镜像（不推送到 Harbor）
redis:7.2-alpine                      # Docker Hub
confluentinc/cp-kafka:7.5.3           # Docker Hub
```

自建后端镜像的 **JRE 层** 可通过 `JAVA_BASE_IMAGE` / `-JavaBaseImage` 指向私有 Registry 或镜像加速地址，无需与上表基础设施镜像同源。

---

## 10. 各环境配置差异对比

| 配置项 | SIT | UAT | PROD |
|--------|-----|-----|------|
| SWAGGER_ENABLED | true | false | false |
| FLOWABLE_SCHEMA_UPDATE | true | false | false |
| JWT_EXPIRATION | 24h | 12h | 8h |
| JWT_REFRESH_EXPIRATION | 7d | 3d | 1d |
| LOG_LEVEL_ROOT | INFO | INFO | WARN |
| LOG_LEVEL_SQL | WARN | WARN | ERROR |
| PASSWORD_MIN_LENGTH | 8 | 10 | 12 |
| MAX_FAILED_ATTEMPTS | 5 | 3 | 3 |
| SESSION_TIMEOUT | 30min | 30min | 15min |
| HIKARI_MAX_POOL_SIZE | 15 | 20 | 50 |
| Backend replicas | 2 | 2 | 2+ |

---

## 11. 服务间依赖关系

```
PostgreSQL(公司) ──┬── workflow-engine ──┬── admin-center ──┬── admin-center-frontend
                   │                    │                  └── developer-workstation-frontend
Redis(K8S) ────────┘                    ├── user-portal ───── user-portal-frontend
                                        └── developer-workstation ── developer-workstation-frontend
Kafka(K8S) ─────────────────────────────── workflow-engine + user-portal

```

启动顺序：Redis + Kafka → workflow-engine → admin-center → (user-portal, developer-workstation) → 前端。

---

## 12. 环境变量完整清单

> **Docker dev 与 K8s 同步**：新增或修改环境变量时，除下表外须按 [deploy/CONFIG_SYNC.md](deploy/CONFIG_SYNC.md) 同步 `deploy/environments/dev/.env`、`docker-compose.dev.yml` 与 `deploy/k8s/config_map|secret/<Environment>/`。Agent 见 `.cursor/rules/docker-k8s-config-sync.mdc`。

### 12.1 后端通用环境变量

| 变量名 | 说明 | 示例值 |
|--------|------|--------|
| `SERVER_PORT` | 服务端口 | `8080` |
| `SPRING_PROFILES_ACTIVE` | Spring Profile | `docker` / `sit` |
| `SPRING_DATASOURCE_URL` | JDBC URL | `jdbc:postgresql://postgres:5432/workflow_platform_dev` |
| `SPRING_DATASOURCE_USERNAME` | DB 用户名 | `platform_dev` |
| `SPRING_DATASOURCE_PASSWORD` | DB 密码 | `dev_password_123` |
| `SPRING_REDIS_HOST` | Redis 主机 | `redis` / `redis-service` |
| `SPRING_REDIS_PASSWORD` | Redis 密码 | `dev_redis_123` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka 连接地址 | `kafka:29092` / `kafka-service:29092` |
| `JWT_SECRET` | JWT 签名密钥 | 256-bit 字符串 |
| `JWT_EXPIRATION` | JWT 过期时间(ms) | `86400000` |
| `JWT_REFRESH_EXPIRATION` | 刷新令牌过期时间(ms) | `604800000` |
| `ENCRYPTION_SECRET_KEY` | AES-256 加密密钥 | 32 字节字符串 |
| `CORS_ALLOWED_ORIGINS` | CORS 允许的源 | `http://localhost:3000,...` |
| `SWAGGER_ENABLED` | 是否启用 Swagger | `true` / `false` |

### 12.2 后端服务间 URL 变量

| 变量名 | 使用者 | 说明 |
|--------|--------|------|
| `ADMIN_CENTER_URL` | workflow-engine, user-portal, developer-workstation | 管理后台地址 |
| `WORKFLOW_ENGINE_URL` | admin-center, user-portal, developer-workstation | 工作流引擎地址 |
| `USER_PORTAL_BASE_URL` | admin-center | user-portal 基址（含 `/api/portal`）；PortalRuntimePurge / UserPortalAudit 内部调用 |
| `PORTAL_INTERNAL_API_TOKEN` | admin-center, user-portal, workflow-engine | 调用门户 `/internal/*` 的共享密钥（须两端一致） |

#### 12.2.1 developer-workstation 专项

| 变量名 | 说明 | 示例值 |
|--------|------|--------|
| `DEVELOPER_DEPLOY_REQUIRE_ADMIN_AUTH` | 一键部署到 admin 前是否要求当前 HTTP 请求已带 `Authorization: Bearer …`（生产经 Kong 转发用户 JWT 时应为 `true`） | `true`（默认） |
| `ADMIN_CENTER_URL` | 与 `application.yml` 中 `admin-center.url` 一致；部署目标非默认时需覆盖 | `http://admin-center:8080` |
| `DW_RATE_LIMIT_ENABLED` | 进程内 Bucket4j 限流开关（`rate-limit.enabled`）。限流由 Kong 统一负责，仅在 DW 不经 Kong 暴露时打开 | `false`（默认） |
| `DW_RATE_LIMIT_PER_MINUTE` | 开启时每个 remoteAddr 每分钟请求数（`rate-limit.requests-per-minute`），每实例独立计数 | `600`（默认） |

说明：`DEVELOPER_DEPLOY_REQUIRE_ADMIN_AUTH=false` 仅建议用于本地自动化或测试；生产环境应依赖 **Kong + JWT**，由前端/网关携带令牌，服务端再转发至 admin-center 的 `function-units-import` 接口。

### 12.3 前端 nginx → Kong

| 变量名 | 使用者 | 说明 |
|--------|--------|------|
| `KONG_PROXY_URL` | 各前端 nginx 容器（含 `frontend/login`） | Kong 代理入口（HTTP），用于 `proxy_pass`；**不再**用 `*_BACKEND_URL` 直连后端 API |

---

## 13. 完整构建流程总结（复制粘贴即可）

```powershell
# ============================================
# 从零构建全部 Docker 镜像（约 3-5 分钟）
# ============================================

# 0. 确认工具版本
java -version; mvn --version; node --version; pnpm --version; docker --version

# 1. Maven 构建后端 JAR
mvn clean package -DskipTests -pl backend/platform-common,backend/platform-cache,backend/platform-security,backend/platform-messaging,backend/workflow-engine-core,backend/admin-center,backend/developer-workstation,backend/user-portal -am

# 2. pnpm 构建前端 dist
Push-Location frontend/admin-center; pnpm install; pnpm exec vite build; Pop-Location
Push-Location frontend/user-portal; pnpm install; pnpm exec vite build; Pop-Location
Push-Location frontend/developer-workstation; pnpm install; pnpm exec vite build; Pop-Location
Push-Location frontend/login; pnpm install; pnpm exec vite build; Pop-Location
Push-Location frontend/help; pnpm install; pnpm exec vite build; Pop-Location

# 3. Docker 构建后端镜像
$r = "harbor.company.com/workflow"; $t = "latest"
docker build -t "${r}/workflow-engine-core:${t}" backend/workflow-engine-core
docker build -t "${r}/admin-center:${t}" backend/admin-center
docker build -t "${r}/developer-workstation:${t}" backend/developer-workstation
docker build -t "${r}/user-portal:${t}" backend/user-portal

# 4. Docker 构建前端镜像（必须用 Dockerfile.local！）
docker build -f frontend/admin-center/Dockerfile.local -t "${r}/admin-center-frontend:${t}" frontend/admin-center
docker build -f frontend/user-portal/Dockerfile.local -t "${r}/user-portal-frontend:${t}" frontend/user-portal
docker build -f frontend/developer-workstation/Dockerfile.local -t "${r}/developer-workstation-frontend:${t}" frontend/developer-workstation
docker build -f frontend/login/Dockerfile.local -t "${r}/platform-login-frontend:${t}" frontend/login
docker build -f frontend/help/Dockerfile.local -t "${r}/platform-help-frontend:${t}" frontend/help

# 5. 验证（自建镜像通常 9 个：4 后端 + 5 前端）
docker images "${r}/*" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}"

# 6. 推送到 Harbor
docker login harbor.company.com
docker push "${r}/workflow-engine-core:${t}"
docker push "${r}/admin-center:${t}"
docker push "${r}/developer-workstation:${t}"
docker push "${r}/user-portal:${t}"
docker push "${r}/admin-center-frontend:${t}"
docker push "${r}/user-portal-frontend:${t}"
docker push "${r}/developer-workstation-frontend:${t}"
docker push "${r}/platform-login-frontend:${t}"
docker push "${r}/platform-help-frontend:${t}"

# 7. 部署到 K8S（二选一）
# 7a. Ingress 经典
cd deploy/k8s
.\deploy.ps1 -Environment sit -Tag latest

# 7b. Istio 集群（参数见 §9.3.2 与 deploy/k8s/ps1/README.md）
# .\deploy\k8s\ps1\apply-workflow-station-all.ps1 -Namespace ... -ImageTag ... -Environment preprod ...
```

---

## 14. 常见问题与故障排查

### 14.1 构建阶段

| 问题 | 原因 | 解决 |
|------|------|------|
| Docker build 前端报 `COPY failed: dist` | `.dockerignore` 包含了 `dist` | 从 `.dockerignore` 中删除 `dist` |
| Docker build 前端报 `file not found` | 没有先执行 `pnpm run build` | 先执行 `pnpm exec vite build` 生成 `dist/` |
| Maven build 报 `platform-common` 找不到 | 没有加 `-am` 参数 | 加 `-am` 自动构建依赖模块 |
| 后端容器启动后立即退出 | `target/` 下没有 JAR | 先执行 `mvn package` |

### 14.2 运行阶段

| 问题 | 原因 | 解决 |
|------|------|------|
| 前端 nginx 502 Bad Gateway | 后端容器未启动或未就绪 | 等待后端 healthcheck 通过 |
| 前端 nginx 报 `no resolver defined` | `envsubst` 没有替换 `${*_URL}` 变量 | 检查 `docker-entrypoint.sh` 中 envsubst 变量列表 |
| 前端容器启动失败 `xxx_URL is not set` | 没有传入必需的环境变量 | 在 docker-compose 或 K8S env 中添加缺失变量 |
| 前端 nginx `$host`、`$uri` 变空 | `envsubst` 没有指定变量列表 | 显式列出变量：`envsubst '${VAR1} ${VAR2}'` |
| admin-center healthcheck 失败 | context-path 是 `/api/v1/admin` | healthcheck 路径需包含 context-path |
| `.sh` 文件报 `bad interpreter` | Windows CRLF 换行 | 确保 `.sh` 文件是 LF 换行 |
| Kafka 连接失败 | K8S 中未配置 `SPRING_KAFKA_BOOTSTRAP_SERVERS` | 检查 ConfigMap 中是否有该配置 |

### 14.3 数据库相关

| 问题 | 原因 | 解决 |
|------|------|------|
| 后端启动报 `Connection refused` | PostgreSQL 地址配置错误 | 检查 `SPRING_DATASOURCE_URL` 中的主机地址 |
| Flowable 表不存在 | Schema 未初始化 | 运行 `init-database.ps1` 或设置 `FLOWABLE_SCHEMA_UPDATE=true` |

---

## 15. .gitattributes 配置

项目根目录的 `.gitattributes` 强制 `.sh` 和 `.sql` 文件使用 LF 换行：

```
*.sh text eol=lf
*.sql text eol=lf
```

这确保 Windows 上 checkout 的 shell 脚本在 Linux 容器内可以正常执行。
