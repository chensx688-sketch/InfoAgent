# 真机部署记录

## 设备

| 项 | 值 |
|---|---|
| 厂商 / 型号 | Xiaomi / 22127RK46C（Redmi K60，代号 socrates） |
| 系统 | HyperOS 3.0.304.0.WMKCNXM / Android 16 |
| adb 序列号 | a846839e |

## 安装

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "D:\InfoAgent\app\build\outputs\apk\release\app-release.apk"
# 结果：Success；包名 com.infoagent，versionCode=1，targetSdk=35
```

## 已配置的权限（adb 预置）

| 权限 | 状态 | 命令 |
|---|---|---|
| POST_NOTIFICATIONS | allow | `pm grant com.infoagent android.permission.POST_NOTIFICATIONS` |
| 通知使用权 | 已登记且系统已绑定（live listener） | `settings put secure enabled_notification_listeners <原值>:com.infoagent/com.infoagent.capture.NotifyCaptureService` |
| 电池优化白名单 | 已在白名单 | `dumpsys deviceidle whitelist +com.infoagent` |
| 精确闹钟 | 已尝试 allow（`appops set com.infoagent SCHEDULE_EXACT_ALARM allow`） | — |
| HyperOS 自启动 | **无法经 adb 设置**（系统无该 appops），需手动：设置 → 应用设置 → 信息小agent → 自启动/后台运行 | — |

## 验证命令

```powershell
& $adb shell dumpsys notification | Select-String "live notification listener"
& $adb shell dumpsys activity services com.infoagent
& $adb shell appops get com.infoagent POST_NOTIFICATION
& $adb shell dumpsys deviceidle whitelist | Select-String com.infoagent
& $adb exec-out screencap -p > screenshot.png
```

## 当前状态（2026-11-05，1.0.0）

- 版本：`versionCode=18` / `versionName=1.0.0`
- release APK：12.57 MB，SHA256 `51E9E10CD1FEE9ACFAC1300D75C3B9B834F25FB6CF1D34FBDE479B0CB6F834D4`
- 签名证书：`CN=InfoAgent...`，SHA-256 `a24eaec36e6c5a13b46404c32da93530a1e8bbcfe3b01650f94e03ff9e00de31`
- 本版：简报改版——DB v3 `brief_slice` + `MIGRATION_2_3` 回填、增量生成 `generateSlice`（上一条 to_ts → now，<5 分钟跳过）、时间轴页面（日期切换/折叠展开/未读/失败重试/空态/现在补一份）、通知带 `slice_id` 深链、导出导入支持新表
- 未做：日历选择器（P1）、继续项分区/全天总览（P2）；`catchUp` 仍按 `scheduled_run` 投递（未预建 PENDING slice）
- 岛的口径：**尝试让系统把通知显示为岛/胶囊，不支持时保持普通通知**；ColorOS 无真机验证
- 单测：125 项全绿（`SlicePipelineTest` 首尾相接/间隔跳过、`MigrationTest` v2→v3）
- 待真机确认：时间轴视觉、迁移后历史片段、通知点击滚动（当前手机锁屏，未能截图验证）

## 历史记录（0.9.4）

- SHA256 `A071F9223B2CA3DFA401A700B065DE766AEC64A6BB46CC6EB11CA2A273BCEEA5`（已废弃，仅追溯）

## 历史记录（0.9.3）

- SHA256 `A8B794AC19DD2911E9A4A05C59489A03427626AFD0CAB83183437067262F0580`（已废弃，仅追溯）

## 历史记录（0.9.2）

- SHA256 `200ADDDA3493A08277093795C781A7CCE94C8DA52B00ED3A4614E51FC38F02CB`（已废弃，仅追溯）

## 历史记录（0.9.0）

- SHA256 `AE185CFC954FB286DC7B46E9DE38675905C04056E82FF47ED016F223AB1CA6B2`（已废弃，仅追溯）

## 历史记录（0.8.2）

- SHA256 `E21E77D96A276D9DDF4BC63A6C0C4BF26858D4BF98F62F6B55F755194D1B8DB5`（已废弃，仅追溯）

## 历史记录（0.7.3，已废弃）

- 旧包 SHA256：`C0080FB910F4E569…`（12.52 MB）
- 该记录与后续 0.8.0 不一致，仅作追溯。

## 历史记录（0.7.0，已废弃）

- 旧包 SHA256：`27989B9F1A90E206F397771E9B3F9AED0793585DF0C7A94AF4EFB05530134D0A`
- 该记录与后续 0.7.1/0.7.2 的包不一致，保留仅作追溯。

## 待用户完成

1. 向导第 3 步填接口地址、模型名、API Key → 保存并继续 → 开始使用。
2. HyperOS 手动开启「自启动」与「后台无限制」（否则三段定时可能被系统冻结）。
3. 用微信给自己发一条消息，检查「消息」页是否出现该会话。