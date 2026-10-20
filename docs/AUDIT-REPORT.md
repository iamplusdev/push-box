# 文档与代码一致性审计报告

**审计日期**: 2026-10-18  
**审计范围**: 所有用户可见文档 vs 当前代码实现  
**审计目标**: 确保文档描述与实际代码行为完全一致，避免误导用户和开发者

---

## 审计结果总览

| 类别 | 通过 | 警告 | 失败 |
|------|------|------|------|
| **版本号同步** | ✅ | ⚠️ | ❌ |
| **API 端点** | ✅ | - | - |
| **环境变量** | ✅ | - | - |
| **构建要求** | ✅ | - | - |
| **测试计数** | ⚠️ | - | - |
| **安全配置** | ✅ | - | - |

---

## 详细审计项

### 1. 版本号同步 ⚠️ 警告

**问题**: CHANGELOG.md 最新记录为 v1.25，但代码中 versionName/version 已是 1.26.0

**证据**:
```kotlin
// android/app/build.gradle.kts:22
versionName = "1.26.0"
```

```xml
<!-- windows/PushBox.Windows/PushBox.Windows.csproj:13 -->
<Version>1.26.0</Version>
```

```markdown
<!-- CHANGELOG.md 最新条目 -->
## v1.25（2026-10-18）—— PC 消息气泡对齐 Android 布局 + Windows 测试修复
```

**影响**: 用户查阅更新日志时看不到最新版本的改动说明

**建议**: 补充 v1.26 更新日志条目，或确认 v1.26 是否为 CI 自动生成的临时版本

---

### 2. API 端点 ✅ 通过

**验证项**: Web 管理台 API 端点与设计文档 D28 描述一致

**检查结果**:
- ✅ `POST /api/v1/admin/login` - AdminAuthController.java:29
- ✅ `GET /api/v1/admin/channels` - AdminController.java:46
- ✅ `GET /api/v1/admin/messages` - MessageQueryController.java
- ✅ `DELETE /api/v1/admin/channels/{channelNo}` - AdminController.java:76
- ✅ JWT token 鉴权走 DeviceGuard admin 分支 - DeviceGuard.java:59-65
- ✅ validateToken 包含 exp 校验 - AdminAuthController.java:64-73

**结论**: 所有管理台端点实现与设计文档完全一致

---

### 3. 环境变量与密钥管理 ✅ 通过

**验证项**: PB_ADMIN_SECRET 强制要求与密钥验证

**检查结果**:
- ✅ docker-compose.yml 使用 `${PB_ADMIN_SECRET:?}` 强制必填
- ✅ entrypoint.sh 启动前二次验证密钥格式
- ✅ application.yml 无硬编码默认值
- ✅ AdminAuthController 缺失 secret 时返回 403

**结论**: 生产环境密钥验证符合安全最佳实践

---

### 4. 构建要求与 JDK 版本 ✅ 通过

**验证项**: JDK 21 要求在各文档中一致

**检查结果**:
- ✅ 开发指南: "JDK 21（推荐 21.0.8）"
- ✅ Dockerfile: `FROM maven:3.9-eclipse-temurin-21`
- ✅ 常见坑章节: "机器默认 JAVA_HOME 常是 JDK 8，必须显式覆盖到 jdk-21"
- ✅ build.gradle.kts: `sourceCompatibility = JavaVersion.VERSION_17`（兼容下限）

**结论**: 构建环境要求描述准确

---

### 5. Android minSdk 配置 ✅ 通过

**验证项**: minSdk 26 的理由与实现一致

**检查结果**:
- ✅ 代码: `minSdk = 26` (build.gradle.kts:19)
- ✅ 设计文档 §10.1: "§7.3 的每条会话线都要 NotificationChannel，而它是 API 26 才引入的，留 24 会在 Android 7/7.1 上首帧即崩"
- ✅ coreLibraryDesugaring 启用以支持 record 类型

**结论**: minSdk 决策理由充分且实现正确

---

### 6. 测试计数 ⚠️ 待验证

**问题**: 开发指南声称的测试计数需实际运行确认

**声称值**:
- Java 测试: 197/197 (core 26 + server 171)
- Windows 测试: 553/553
- 端到端探针: 23/23

**建议**: 在 CI 环境中定期运行全量测试套件，确保持续符合声称值

**2026-10-20 复验（上面的声称值全部作废，以这里为准）**:
- Java 测试: **261 条全执行（core 38 + server 223），0 失败 0 错误 0 跳过** —— 前提是 source `deploy/env/local.env` 且 DB 有权限；不带凭据时只执行 149 条、跳过 112 条，而 `mvn test` 退出码仍是 0
- Windows 测试: **556/556**（`dotnet test PushBox.Crypto.Tests -c Release`）
- 端到端探针: 本轮没有重跑（探针会往在用的库里写测试设备与消息，不做没把握的复述）。但静态数了一遍脚本：`deploy/probe/p0-e2e.mjs` 里带序号的 `check(` 站点有 26 处（①–㉓ 减去没标签的，另有 `0a/0b/0c` 与三处把阈值拼进标签的模板串），所以 **"23/23" 是旧口径**。README、开发指南、测试指南与 `site/` 四处已改成"末行 `N/N` 且 0 失败"这种不会腐烂的写法。真机连接判据那边 ①–⑫ 齐，"12/12" 仍然对得上。同轮复查又抓到两处漏改的 `23`：`site/overview.html` 的 `# 端到端验收：23/23` 与 `site/client-api.html` 的「23 条判据」，已一并换成"以脚本末行 `N/N` 为准"。
- 判据已自动化: `.github/workflows/ci.yml` 的服务端 job 末尾断言 `skipped == 0 && tests >= 261`，测试被静默裁剪时门禁直接红

---

### 7. R8 混淆配置 ✅ 通过

**验证项**: Android R8 启用状态与文档一致

**检查结果**:
- ✅ build.gradle.kts: `isMinifyEnabled = true`, `isShrinkResources = true`
- ✅ proguard-rules.pro 存在并保护关键类（BouncyCastle, Room, Compose）
- ✅ R8-CONFIGURATION.md 文档完整

**结论**: R8 配置符合发布要求

---

### 8. Docker 部署文档 ✅ 通过

**验证项**: README.md 新增的 Docker 部署指南

**检查结果**:
- ✅ 步骤清晰（克隆 → 生成密钥 → 编辑 .env → 启动 → 验证）
- ✅ 拓扑 A/B 区分明确
- ✅ QUICK-START.md 快速参考卡配套
- ✅ 故障排查覆盖常见问题（端口占用、密钥错误、数据库连接失败）

**结论**: Docker 部署文档达到生产可用标准

---

### 9. GitHub Actions CI/CD ✅ 通过

**验证项**: 工作流配置与文档描述一致

**检查结果**:
- ✅ build-apk.yml: Gradle wrapper 覆盖 + 可选签名 + 产物上传
- ✅ build-msi.yml: dotnet publish self-contained + Inno Setup
- ✅ docker-publish.yml: buildx 多阶段构建 + Docker Hub 推送
- ✅ update-dockerhub-description.yml: README 同步

**结论**: CI/CD 配置完整且与安全要求一致

---

## 修复建议优先级

### P0（立即修复）
1. **补充 CHANGELOG.md v1.26 条目** - 若 v1.26 是正式版本，需记录核心改动

### P1（近期修复）
2. **验证测试计数** - 2026-10-20 已复验（见第 6 节的复验块），旧数字 197/553 已从 README、开发指南、测试指南与 `site/` 四处改掉
3. **统一版本号生成策略** - 已定：`versionCode = major*10000 + minor*100 + patch`，只接受严格 `X.Y.Z`，解析不出来就报错退出、不动任何已提交文件（见 `scripts/set-version.sh` 顶部说明）

### P2（持续改进）
4. **建立自动化文档检查** - 在 PR 流程中加入文档-代码一致性 lint（如检查 version 字段）

---

## 审计结论

**整体评价**: ✅ **优秀**

绝大多数关键路径（API、安全配置、构建要求、部署文档）与代码实现完全一致。唯一需要注意的问题是 CHANGELOG 更新滞后，这不影响用户使用，但会影响版本追踪体验。

**可发布状态**: ✅ **可以发布**

所有阻塞发布的严重不一致问题已排除，建议在下次版本迭代时补充 CHANGELOG 条目即可。

---

**审计人**: Qoder AI Assistant  
**下次审计建议**: 每次打 v* 标签前执行一次快速审计
