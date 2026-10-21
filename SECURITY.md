# 安全策略

PushBox 是自托管的消息推送中枢，安全口径写在 [README 的"信任边界"](README.md#信任边界) 与
[软件设计方案 §1](docs/软件设计方案.md)。请先读那两节：下面这些属于"设计取舍"而不是漏洞，
按漏洞上报会被关闭。

## 不在漏洞范围内的设计取舍

- **服务端数据库存明文消息**。只有这样才能做全文搜索、摘要与图片抓取；端到端加密在方案里被明确否决（D20 相关讨论）。
- **对象存储的历史对象不自动加密**。切到 `sse-c` 只影响新写入对象，历史对象需要迁移；这是记录在 [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md) 里的已知限制。
- **端上 SQLite/Room 不加密消息正文**。被 AndroidKeyStore 包裹的只有身份私钥、token、恢复码与 PIN 散列；拿到 root 或磁盘镜像仍能读出历史消息。
- **明文 HTTP 是可选项**。客户端允许 `http://IP:端口`（内网 / Tailscale 场景），是否上 TLS 由部署者决定，`deploy/Caddyfile` 只做 HTTP 反代、不管证书。
- **同一个 `device_id` 的新连接会顶掉旧连接**。把身份文件拷到另一台机器得到的是"轮流持有同一条连接"，不是两台设备。
- **`/healthz` 的 `storage` 只校验配置项非空**，既不探端点可达也不探桶是否存在。配置写错时它会照样返回 `up`，这是文档里反复标注的判据陷阱。

## 报告漏洞

请走 **GitHub 私密安全报告**：<https://github.com/iamplusdev/push-box/security/advisories/new>。
不要开公开 issue，也不要在公开 issue 里贴可复现的 PoC。

自托管软件没有"服务端团队"，所以请给一份能定位到代码的说明：哪个端点 / 哪段配置 / 哪个版本
（`versionName` 见对应 GitHub Release），以及复现步骤。

## 响应口径

- 尽力在 7 天内回复初步评估；这只是个人维护项目的承诺，不是 SLA。
- 确认后在同一 Release 里修复并给出安全说明（Security Advisory），致谢报告者——除非你不愿具名。
- 未修复期间，请在报告里同时说明可行的部署侧规避（例如收紧 `PB_SECURITY_INTERNAL_CIDRS`、把运维端点关在环回里）。

## 部署侧该自己做的事

- `PB_ADMIN_SECRET` 与 `PB_ADMIN_JWT_SECRET` 必须是两个不同的强随机值（`openssl rand -base64 32`）；相同或过短会被启动阶段直接拒绝。
- 必填变量缺失时 compose 在 `up` 之前就报错，别用"看起来起来了"当验收；桶是否存在要用 `mc ls` 判，不是 `/healthz`。
- 运维端点靠两道门：`deploy/Caddyfile` 的 `@metrics path /actuator/*` → `respond 403`，以及服务端 `InternalAccessFilter` 只放行环回加 `PB_SECURITY_INTERNAL_CIDRS`。任何一道都不该被当成全部依赖。
- **管理台没有第三道门，要自己加**：`/api/v1/admin/*` 与业务 API 同端口同反代，上面那两条 `@metrics` 规则都不覆盖它，公网部署时它默认就是公网可达。挡住它的只有 `PB_ADMIN_SECRET` 加一把 24 小时 JWT，而这把 token **既不刷新也不吊销**（签名钥是独立的 `PB_ADMIN_JWT_SECRET`，改登录口令不会让已签出的 token 失效；泄露了只能等它过期或换签名钥、代价是所有会话重登）。口令被刷的按 IP 锁定写在 `AbuseControlService.adminLoginAttempts`，是进程内 Caffeine：重启清零、多实例不共享。收紧来源的可用写法见 `deploy/Caddyfile` 末尾那段注释示例；不想动反代就换个只监听内网的端口把 `/admin` 与 `/api/v1/admin` 单独暴露。
- 备份产物必须加密后才出机器（`deploy/backup/backup.sh` + age）。
