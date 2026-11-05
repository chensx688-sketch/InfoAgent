# 信息小agent（InfoAgent）

> 个人微信信息官：静默收集微信通知 → Agent 整理成备忘 → 每天一条「增量简报时间轴」 → 关键时间提醒。
> Android 原生（Kotlin + Jetpack Compose），数据全部留在本机。

## 它做什么

- **收集**：通过 `NotificationListenerService` 读取微信/QQ 通知，去重、过滤广告与噪音，落本地 Room（含 FTS4 中文检索）。
- **备忘**：每日必做 / 阶段事件 / 临时事件三段；每日必做跨天顺延而不是清零；支持拖拽排序、提醒。
- **简报**：一天一条时间轴，每次生成只覆盖「上一条成功片段 → 现在」的增量；已完成的事项不重复叙述；当天可随时「现在补一份」。
- **记忆**：每晚从备忘完成情况与推荐反馈中提炼长期事实，用于下一次简报推荐；👍/👎 会回写置信度。
- **提醒**：精确闹钟 + 系统通知；HyperOS/ColorOS 会尝试附带厂商焦点/胶囊 extras，是否显示成「岛」由系统决定，不支持时就是普通通知。
- **数据自主**：导出/导入 JSON、回收站、清空数据；API Key 使用加密存储；没有云端账号。

## 技术栈

Kotlin 2.0 · Jetpack Compose (Material 3) · Room 2.6（DB v3）· WorkManager + AlarmManager · OkHttp · DataStore/EncryptedSharedPreferences · minSdk 26 / targetSdk 35

## 目录

```
app/src/main/java/com/infoagent/
  capture/     通知监听、解析、噪音过滤、看门狗
  data/        Room 实体/DAO、仓库、导出导入
  agent/       简报/自动备忘/记忆流水线、Prompt、契约解析、预算
  remind/      三段定时、提醒、通知渠道、开机恢复
  island/      厂商 extras 适配器 + 通知工厂（只装饰，不另发通知）
  ui/          Compose 页面（消息 / 备忘 / Agent 时间轴 / 设置）
docs/          计划、验收、部署说明
scripts/       本地构建/测试脚本
```

## 构建

需要 JDK 17 与 Android SDK Platform 35。`local.properties` 不提交，请本地创建：

```properties
sdk.dir=C:\\path\\to\\Android\\Sdk
```

```powershell
# 单测 + debug 包
./gradlew.bat testDebugUnitTest assembleDebug

# 正式签名包（需要自备 keystore，见下）
./gradlew.bat assembleRelease
```

Gradle wrapper 默认走腾讯云镜像（国内网络更稳）；海外网络可把 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 换回 `https://services.gradle.org/distributions/gradle-8.9-bin.zip`。

### 签名（可选）

在 `local.properties` 中配置（不要提交）：

```properties
RELEASE_STORE_FILE=C:\\path\\to\\your.jks
RELEASE_STORE_PASSWORD=******
RELEASE_KEY_ALIAS=******
RELEASE_KEY_PASSWORD=******
```

没有配置时只能出 debug 包，不影响开发与测试。

## 测试

```powershell
./gradlew.bat testDebugUnitTest
```

当前 125 项单测全绿，覆盖：去重键、群聊判定、简报增量首尾相接、迁移（v1→v3）、导出导入、预算与缓存等。

## 参与开发

1. Fork 或新建分支：`git checkout -b feat/your-idea`
2. 改动请附带单测；涉及 DB 结构必须新增迁移并更新 `app/schemas/`
3. 提交前跑 `./gradlew.bat testDebugUnitTest`
4. 提 PR，描述「改了什么 / 为什么 / 怎么验」

数据模型、契约与验收标准见 `docs/`。提交信息建议 `feat:` / `fix:` / `docs:` 前缀。

## 隐私与安全

- 不采集通讯录、位置、相册；只读取通知文本。
- 消息、备忘、记忆均存本机 Room，不上传；调用大模型时只发送简报所需的摘要素材。
- 仓库内不含任何密钥、签名文件或真实聊天数据；`local.properties`、`*.jks` 已被忽略。

## 许可证

MIT，见 [LICENSE](LICENSE)。欢迎 Fork、提 PR；想直接推送请让维护者把你加为协作者。