# 项目结构说明

下面是仓库**当前的真实结构**（2026-10-23 采用 `apps/` + `packages/` 通用布局之后）。这份文件跟着目录一起改：移动任何顶层目录时，先改这里，再改 `scripts/`、`.github/workflows/` 和文档里的引用。

```text
PushBox/
├── apps/
│   ├── server/               Spring Boot 服务端
│   ├── web-admin/            Web 管理台（Vue 3 + Element Plus）
│   ├── android/              Android 客户端（Kotlin + Compose）
│   └── windows/              Windows 客户端（.NET 8 + WPF）
├── packages/
│   └── core/                 Java 加密与协议库（与平台无关）
├── contracts/
│   └── testvectors/          跨端协议向量（core 单测产出，Windows 逐字节对账）
├── infra/                    部署与运维
│   ├── env/                  prod.env.example（compose 形态的变量模板）
│   ├── probe/                端到端协议探针（Node，零依赖）
│   ├── backup/               mysqldump + age 备份脚本
│   ├── ca/                   自签 CA 签发脚本
│   ├── certs/                Caddy 用的证书落点（产物不入库）
│   ├── monitoring/           Prometheus / Grafana 配置
│   ├── Dockerfile            服务端镜像（零 ENV，安装页写配置）
│   ├── docker-compose*.yml   三套拓扑：默认 / NAS / WSL 测试
│   ├── Caddyfile             HTTP 反代与证书
│   ├── run-dev.sh            本机直跑服务端
│   ├── deploy-wsl.sh         WSL 测试栈的 build / up / setup / clean
│   └── *.md                  NAS 部署指南 / WSL 部署指南 / WSL 测试速查 / 部署快速参考
├── assets/
│   └── icons/                多端图标与导出稿（Android 自适应图标、Web favicon、商店图）
├── docs/                     设计与使用文档
│   ├── release/              发布检查清单、发布准备报告
│   └── *.md                  软件设计方案、开发/测试/用户指南、规范与审计
├── site/                     官网与文档站源码（零构建，GitHub Pages 发布）
├── scripts/                  构建与发版脚本
├── output/                   各端编译产物（不入库，由 release 脚本写入）
├── pom.xml                   根 Maven 聚合（packages/core + apps/server）
├── README.md  CHANGELOG.md  SECURITY.md  LICENSE  AGENTS.md
└── structure.md              本文件
```

## 各目录职责

- `packages/core/` — 与平台无关的协议实现：X25519 / Ed25519 / AES-GCM 帧通道 / canonical 签名 / 帧 DTO。**Android 的 `:pbcore` 直接编译这里的同一份源码**，不复制。
- `apps/server/` — 唯一的分发后端。首次启动没有 `data/config/pushbox.json` 时在同一端口起安装页；配置落盘后才进 Spring。
- `apps/web-admin/` — 管理台 SPA。构建产物由 `infra/Dockerfile` 的 node 阶段拷进 jar 的 `classpath:/static/`，由 `SpaWebConfig` 做 history 回退；不单独部署。
- `apps/android/` — Gradle 工程，`app` + `pbcore`；`:pbcore` 通过 `rootProject.file("../../packages/core/src/main/java")` 挂源码。
- `apps/windows/` — `PushBox.sln`：`PushBox.Windows`（WPF）、`PushBox.Crypto.Tests`（xunit 向量对账）、`PushBox.LiveCheck`（真服务体检控制台）、`installer/`（Inno Setup）。
- `contracts/testvectors/` — `vectors.json` 由 `CryptoVectorsTest` 按 key 排序写出。别手改，也别去掉排序：内容不变却每次换 key 顺序的 diff 会让下一版的真改动淹没在噪声里。
- `infra/` — 一切"跑起来之后"的东西。镜像本身零 `ENV`；部署要填的项都在安装页表单里。
- `assets/icons/` — 只放源头资产；**各端运行时用的是拷贝**（`apps/android/app/src/main/res/`、`site/assets/img/`），换图标要三处一起换。
- `docs/` — `docs/软件设计方案.md` 是设计侧唯一权威文档；`docs/release/` 是发布前逐项确认用的清单与报告。
- `site/` — 纯静态，无构建步骤。改 `site/**` 推到 `main` 由 `site-deploy.yml` 发布到 GitHub Pages。
- `scripts/` — `release.sh` 是汇总入口，`release-<端>.sh` 单独发一端（先跑本地测试，再写版本号，再打标签），`release-verify.sh` 是发布闸门，`set-version.sh` 只改版本号，`install-page-smoke.sh` 验 fat jar 里的安装页。
- `output/` — `server-jar/`、`android-apk/`、`windows-exe/` 三个子目录，`.gitignore` 忽略内容、只留 `.gitkeep`。产物都按**写进版本号之后**的重新构建收集，文件名与内容里的版本、标签三者一致。

## 构建入口

每套工具链各自从自己的根出发，互不穿透：

| 工具链 | 入口 | 聚合范围 |
| --- | --- | --- |
| Maven | 根 `pom.xml` | `packages/core` + `apps/server` |
| Gradle | `apps/android/settings.gradle.kts` | `:app` + `:pbcore` |
| MSBuild | `apps/windows/PushBox.sln` | 三个 C# 项目 |
| npm | `apps/web-admin/package.json` | 管理台 SPA |

## 不进仓库的东西

`infra/env/local.env`、`infra/wsl-test.env`、`infra/.env`、`infra/data/`、`infra/media/`、`infra/certs/` 下的产物、`apps/server/data/config/pushbox.json`（明文凭据）、`apps/android/dev/`（签名材料）、`mapping.txt`、各端 `target/` `build/` `bin/` `obj/`。完整清单见 `.gitignore`。
