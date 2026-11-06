# 参与开发

感谢一起做这个项目。为了避免把彼此的时间浪费在环境问题上，改代码前请先看这几条。

## 环境

- JDK 17 及以上（本机用 JDK 21 验证过）、Android SDK Platform 35
- 根目录创建 `local.properties`：`sdk.dir=...`（不要提交；仓库已忽略）
- 首次同步：`./gradlew.bat testDebugUnitTest`（Gradle 走腾讯云镜像，海外网络可改回官方地址）

## 分支与提交

- 从 `main` 拉分支：`feat/xxx`、`fix/xxx`、`docs/xxx`
- 提交信息用前缀：`feat:` / `fix:` / `docs:` / `refactor:` / `test:`
- 一个 PR 只做一件事；描述里写清「改了什么 / 为什么 / 怎么验证」

## 必须遵守

1. **单测**：`./gradlew.bat testDebugUnitTest` 必须全绿；新增逻辑要有对应测试。全量构建：`./gradlew.bat testDebugUnitTest lintDebug assembleDebug`。
2. **数据库结构**：改实体必须新增 Room 迁移（参考 `MIGRATION_4_5` / `MIGRATION_5_6`），并提交 `app/schemas/` 下新版本 JSON；禁止 destructive migration。
   - **坑**：迁移里 `CREATE INDEX` 了，实体上就必须写 `@Index`（名字要对上），否则 Room 校验会报 `Migration didn't properly handle`，测试直接红。
   - **坑**：写迁移测试要把对应版本的 schema JSON 拷到 `app/src/test/resources/schemas/`，否则测不到真实升级路径。
3. **密钥**：不要提交 `local.properties`、`*.jks`、API Key、真实聊天记录；截图打码。
4. **采集边界**：只读通知文本，不申请通讯录/位置等无关权限。
5. **岛**：只装饰系统通知，不新申请悬浮窗权限，不引入 Xposed/LSPosed/root。

## 目录速览

| 目录 | 责任 |
|---|---|
| `capture/` | 通知监听、微信/QQ 解析、噪音过滤 |
| `data/` | Room、仓库、导出导入 |
| `agent/` | 简报/自动备忘/记忆流水线、Prompt、解析、预算 |
| `remind/` | 定时、提醒、通知渠道、开机恢复 |
| `island/` | 厂商 extras 适配（装饰模式） |
| `ui/` | Compose 页面 |

## 本地跑真机

```powershell
./gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要授予「通知使用权」才能采集；提醒需要精确闹钟权限。Redmi/OPPO 上还要在系统设置里允许自启动，否则定时任务可能被杀。

## 提 PR 前自查

- [ ] 单测全绿
- [ ] 没有提交敏感文件（`git status` 检查）
- [ ] 新增 UI 文案走中文、深色模式不崩
- [ ] 改了 DB 就带迁移 + schema
- [ ] 手动跑过一次受影响页面

## 记忆语义（不要改回覆盖式）

DB v6 起 `memory_fact` 是「只增 + 时间有效性」：

- 同一件事再出现 → 累加 `evidence_count`，不新增行；
- 事情变了 → 旧行 `valid_to = now`，新事实另起一行；**禁止覆盖或删除旧行**（历史要可查）；
- 注入简报只取 `valid_to IS NULL`；失效项在「备忘 → 长期记忆 → 已过期」里折叠，可恢复；
- 「是不是同一件事」在 `MemoryPipeline.sameFact()` 里分类型判断：`PREFERENCE/HABIT` 变了就换新值，`RELATION/PATTERN` 用二元组重合度 ≥0.6 判「说得更细」；
- 改这块必须带上 `MemoryPipelineTest`（冲突不覆盖、失效不进注入、恢复后可用）。

理由不是洁癖：mem0 2026 版已经放弃 UPDATE/DELETE，Graphiti 也只让旧边失效——写入时不做生死判断，判断挪到取用侧。

## Prompt 契约（别加字段、别超字数）

- `Prompts.BRIEF_SYSTEM` 有测试守着：**≤2200 字符**、不许出现契约外字段名（曾经写了个 `continued`，白烧 token 还藏内容）、不许让模型回 `window` / `generated_at`（客户端注入）。
- 改契约要同时改三处：`SafeParser.BRIEF_CONTRACT`、`app/src/main/assets/contracts/brief_v*.schema.json`、`PromptContractTest`。
- 模型输出被截断（`finish_reason == "length"`）时客户端会自动翻倍 max_tokens 重试一次，解析侧还有截断自愈；不要靠调大 max_tokens 掩盖契约膨胀。

## 会话列表渲染

分簇规则（3 分钟内同人成簇、换天/隔 5 分钟插时间、类型名翻译）在 `ui/messages/ChatRows.kt`，是纯函数、有 12 条测试。**别在 composable 里另写一套判断**，否则测试管不到。
