# 参与开发

感谢一起做这个项目。为了避免把彼此的时间浪费在环境问题上，改代码前请先看这几条。

## 环境

- JDK 17、Android SDK Platform 35
- 根目录创建 `local.properties`：`sdk.dir=...`（不要提交；仓库已忽略）
- 首次同步：`./gradlew.bat testDebugUnitTest`（Gradle 走腾讯云镜像，海外网络可改回官方地址）

## 分支与提交

- 从 `main` 拉分支：`feat/xxx`、`fix/xxx`、`docs/xxx`
- 提交信息用前缀：`feat:` / `fix:` / `docs:` / `refactor:` / `test:`
- 一个 PR 只做一件事；描述里写清「改了什么 / 为什么 / 怎么验证」

## 必须遵守

1. **单测**：`./gradlew.bat testDebugUnitTest` 必须全绿；新增逻辑要有对应测试。
2. **数据库结构**：改实体必须新增 Room 迁移（参考 `MIGRATION_1_2` / `MIGRATION_2_3`），并提交 `app/schemas/` 下新版本 JSON；禁止 destructive migration。
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