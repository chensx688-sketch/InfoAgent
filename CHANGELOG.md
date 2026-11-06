## 1.4.6

只加了一件小事：**界面上终于能看见版本号了**。

### 背景
有反馈「1.4.5 的会话改版看不到」。查下来最可能的原因是装的不是这一版——但 App 里**没有任何地方显示版本号**，装错了也看不出来，只能靠猜。这是产品缺陷，不是用户的问题。

### 变了
- 「消息」页标题「全部会话」右侧显示当前版本（如 `1.4.6`，浅灰小字）。
- 版本号走 `PackageManager` 读，不依赖 BuildConfig，debug/release 都准。

### 自查方法
- 消息页看到 `1.4.6` → 是这一版；会话详情页点进去，标题下应有「已加载 N 条 · 最近 15:32」。
- 看到 `1.4.4` 或没有版本号 → 装的是旧包，会话改版（成组/时间分隔/撤回重排）不在那一版里。
## 1.4.5

会话详情页（点开某个会话以后的聊天视图）重排了一遍，只动「间距、对比、圆角」三件事。

### 变了
- **连续消息成组**：同一个人 3 分钟内连发几条算一簇，只有簇首显示时间；簇内间距 2dp、簇间 12dp，一簇里只有最后一条带「小尾巴」圆角——一眼是一段话，而不是几个碎气泡。
- **时间不再条条都画**：换天插日期分隔（今天 / 昨天 / 11-03），同一天内只有间隔超过 5 分钟才插一行时间；分隔改成居中细字，不再用胶囊底色。
- **宽度自适应**：气泡上限 = 屏宽 × 78%，最大 320dp（原来写死 300dp）。
- **撤回消息换成一行说明**：「对方撤回了一条消息」/「你撤回了一条消息」，不再留读不清的空泡。
- **非文字消息说人话**：`image`/`voice`/`file` 这类原始类型名翻译成 图片/语音/文件/链接/位置/表情/红包；「微信/QQ」标签从气泡里去掉。
- **标题栏加了有用信息**：名字下面一行「已加载 N 条 · 最近 15:32」；长会话往上翻时右下角浮出「回到最新」。
- **今日要点**改成一行灰底小字，点一下展开全文，不再用主色块跟气泡抢视线。
- **长按菜单**加图标与分隔线，删除用错误色；群聊里簇首显示发送者名字（自己的消息和单聊不显示）。
- **搜索命中高亮**：搜索结果里把关键词标出来，不用自己再找一遍。

### 测试
- 新增 `ChatRowsTest` 12 条，把「分簇 / 时间分隔 / 换人换簇 / 乱序输入 / 类型名翻译」这些规则钉住——这段逻辑以前没有测试，改哪都靠肉眼。
- 全量 199 个单元测试通过。
## 1.4.4

记忆从「覆盖式合并」改成「只增 + 时间有效性」（DB v6），这是这一轮唯一的行为改动。

### 变了
- **数据库 v6**：`memory_fact` 加 `valid_from` / `valid_to`（null = 仍然有效），并为 `(subject, valid_to)` 建索引。老数据按 `created_at` 回填 `valid_from`、默认仍有效，不做假精确。
- **写入只增**：同一件事再被提到才累加证据；**事情变了就旧行标失效、新事实另起一行**，不覆盖、不删除，历史仍可查。
- **冲突判定分类型**：偏好/习惯变了直接换新值；关系/规律只是"说得更细"（二元组重合度 ≥60%）时按同一件事累加，避免记忆表塞满近义条目。
- **注入只取有效**：简报里那 10 条记忆不再包含已失效的；失效的只在「备忘 → 长期记忆 → 已过期」里折叠展示，可一键恢复。
- **备忘页新增「长期记忆」区块**：分类标签 + 事实 + 「自 M月d日 起 / 出现 N 次 / 可信 N」，右上 ⋮ 里可「这条过期了 / 有用 / 不太对」。

### 没变
- 置信度公式、👍 +2 / 👎 −5、DISMISSED 冻结与复活阈值全部原样保留；这次只补时间维度，不一次改两件事。

### 测试
- 187 个单元测试全绿（新增 3 条记忆语义 + 1 条 v5→v6 迁移，迁移测试校验「老数据按 created_at 补 valid_from 且仍然有效」）。
# 变更记录（契约/提示任何变更必须先升版本并同步 docs/PLAN-v6.1.md）

## 1.4.3

**清理提示词里的垃圾（不改行为，只删废话与幻觉字段）**
- `BRIEF_SYSTEM` **3003 → 1736 字符（−42%）**，删的全是重复与无效内容：
  - **删掉幻觉字段 `continued`**：契约里从来没这个字段，却让模型「无变化就写进 continued」——
    模型只能白烧 token，或把该说的东西藏进没人读的字段。两处（系统提示 + 用户提示）都已删。
  - **不再让模型回显 `window` / `generated_at`**：模型不知道真实时间，回显只会写错，
    写错还会让整条简报失败。改为**客户端注入**（请求窗口优先，时间取本机）。
    这同时去掉了一个真实的失败源：`window 字段非法` / `缺少 generated_at` 两种报错不会再出现。
  - **删掉写死的地点例子**（11 月杭州、西湖、断桥、苏堤）：模板泄漏，等于天天暗示模型聊西湖。
  - **合并互相矛盾的两条**：原第 5 条说「你不能联网」，第 7b 条说「外部信息以它为准」→ 合成一条实时信息口径。
  - `advice` 的 a/b/c/d 四项 + watch 五类例子 + 「不要套模板」三重重复 → 压成 kind 四行 + watch 两句。
  - 广告/接龙/红利过滤与「闲聊忽略」合并成一条；6b/7b 这类编号重排为顺号。
- 用户提示：去掉与系统提示重复的季节/天气说明、「【外部信息】」后面的解释性长句。
- `ENRICH_PLAN_SYSTEM`：两条「不许造地点」合成一条（−20 字符）。
- 新增 `PromptContractTest`（4 条护栏）：提示词里出现契约外字段名、出现 `window`/`generated_at` 回显、
  出现写死地点、长度超过 2200 字符，都会直接测试失败。
- `Prompts.VERSION` → `brief_p5`（缓存键与 prompt 版本随之变化）。
## 1.4.2

**修：手动生成报「响应不是 JSON / JSON 解析失败」**
- 根因是**输出被 `max_tokens` 截断**：简报契约这版加了怎么办（advice）、按事项（topics）、原文引用（refs），
  2048 tokens 装不下，模型在最后几个字段中途被切断，JSON 尾巴没了 → 解析必败。
- 客户端现在会读 `finish_reason`：`length` 说明是截断，不再和「模型不肯按格式答」混为一谈；
  截断时**自动把 `max_tokens` 翻倍重试一次**（上限 8192），多数情况用户根本看不到报错。
- 解析器新增**截断自愈**：补未闭合的引号/括号、必要时丢掉半个成员再闭合，
  已经算出来的怎么办/要点先救回来，并留一条「模型输出到一半被截断」的提示。
- `memo_status` / `plan` 缺失不再让整份简报作废（降级为空 + 留痕），
  它们是「已有备忘快照」和「时间线」，为它们丢掉怎么办正文不划算。
- 失败信息改成能动手的：`模型返回了空内容（推理模型/流式中断）`、
  `输出被 max_tokens 截断：把 max_tokens 调到 3500 以上`、`模型没按 JSON 回答：它开头写的是「…」`。
- 新装默认 `max_tokens` 2048 → 3072。

**修：消息页那个按钮点了没反应 —— 现在真的会启动后台采集**
- 「启动后台采集」= 把通知监听真的绑上 + 起**前台保活服务**（常驻通知，可一键停），
  不再是只调一次系统 API 就算完。
- 重连逻辑变「穷举 + 验证」：请求重绑 → 等 → 不行就**把监听组件先禁用再启用**（国产 ROM 上这条最有效，
  权限不会因此丢失）→ 再等 → 最后**如实回报**「连上了」或「系统没绑上，去通知使用权里关掉再打开」。
- 状态卡新增结果行（连上/没连上的具体原因），保活中按钮显示「已保活 · 点击停止」。

**测试**：+5（截断自愈 3、管线截断重试/空内容/说人话 3 中的既有用例调整、采集按钮 3）。
## 1.4.1 (2026-11-06) — 要点能点回原话 + 按事项看
- **看原文**：每条「怎么办」和「按事项」下多了「看原文 N 条」入口，点进去是一页原话（不是弹窗）：
  被引用的那几条用「左侧色条 + 淡底 + 简报依据」三重标记并自动滚到位置，前后各 3 小时同会话上下文一并摊开 —— 上下文关系比单条消息更重要。
  消息被清理时给可读说明（「这些消息已经被清理，看不到了」），不白屏。
- **按事项**：新增 ⚑ 区块，把散在几个会话里的同一件事聚合（合同/报价/招聘/行程），带 `进行中 / 有风险 / 已了结` 状态色和跨会话来源，
  不再要求你在脑子里把三条会话拼成一份合同的来龙去脉；超过 6 件事会提示总数。
- 落地细节：`RefFocusBuilder`（纯逻辑：时间序、去重、标记、窗口 ±3h、缺失文案）+ `MessageDao.aroundPeer`（只取时间窗，不整段读会话）+ `RefFocusScreen`（整页接管、返回即回简报）。
- **验证**：167 项单测全绿（新增 `RefFocusBuilderTest` 6 项 + `按消息 id 取回原文并能拿到同会话上下文` 1 项），`lintDebug` 0 error。
## 1.4.0 (2026-11-06) — 真联网查证（天气/地点/事实）+ 按事项聚合
- **外部信息层（`enrich/`）**：生成简报前先「查证」一次，用**用户自己的 key**：
  1) 模型（temperature=0，300 token）判断该查什么 → `{places:[{city,date,why}], queries:[…]}`；
  2) 和风天气（城市解析→实况→逐日预报→生活指数）；3) 网页搜索（默认 Tavily，接口可换）。
  查到的内容作为【外部信息】进主 prompt，回复建议里才能出现「明天杭州 12~19℃、有阵雨，带伞」这种能用的判断。
- **不假装联网的三条硬约束**：没配 key/没开启→一次外部请求都不发；查不到→主 prompt 里连【外部信息】节都不出现；模型引用必须带地点时间、不许改数字，与消息冲突以消息为准。
- **缓存与额度**：查询计划 40min / 同城同日天气 3h / 同查询搜索 12h，落 `enrich_cache`（DB v5，7 天清理）；「查什么」走独立预算 `USE_ENRICH`（8 次/日），不占简报额度。连点「补一份」不会重复烧钱。
- **透明**：本次用到的事实写进 `brief_slice.context_json`（v5 列），后续 UI 可展示「这条简报参考了什么」。
- **`brief_v3` 契约**：新增 `topics[]`（按事项跨会话聚合：name/points/convs/state/refs），解决「合同散在 3 个会话里拼不出全貌」。仍是纯增量，老简报照常渲染。
- **提示词 `brief_p4`**：外部信息使用规则（只用相关几条、带时间地点引用、冲突以消息为准）+ topics 聚合规则（短名词、state=open/done/risk）。
- **设置页**新增「外部信息」：开关、常住城市、天气 Key + API Host（兼容 `devapi` 与新版 `xxx.re.qweatherapi.com`）、搜索 Key + 端点；key 存 Keystore 加密区，不导出。
- **验证**：160 项单测全绿（新增 MockWebServer 打真实 HTTP 解析：实况/预报/生活指数/搜索/429 降级；`ContextEnricherTest` 6 项：未开启零请求、缓存命中不重复请求、垃圾计划退兜底城市、城市写错给可读原因；pipeline 2 项：外部事实进 prompt 且落库、查不到则不出现该节）；`lintDebug` 0 error。
## 1.3.0 (2026-11-06) — 从「发生了什么」升级到「怎么办」+ 自己选采集范围
- **`brief_v2` 契约（纯增量）**：新增 `advice[]`（最多 3 条）与 `wechat_points[].refs[]`（依据的消息编号）。老 payload 仍按 v1 解析，历史简报照常渲染。资产：`assets/contracts/brief_v2.schema.json`。
- **`advice` 字段语义**：`who/ask/say/why/watch/kind/due/basis/refs`。`say` 是**能直接复制发出去的回复草稿**（不是"建议你回复……"）；`watch` 是**人容易疏忽的细节**（时节天气、礼数称呼、性别避嫌、时间窗口、金额风险）；`basis` 强制自报 原文/常识/推断，禁止把猜测说成事实。
- **提示词 `brief_p3`**：人格改成"贴身助理"，产出必须回答三问（发生了什么 / 我该怎么做 / 我容易漏什么）；给出具体判断路径（对方在等我回话？要提前准备？隐性礼数？有风险？）；明示**不能联网查实时天气/限行/票价**，只能给常识级建议并提醒以实时信息为准；prompt 里注入日期+星期+月份供季节判断。
- **「怎么办」卡片（`AdviceSection`）**：左侧色条分类（红=有风险 / 蓝=要回话 / 黄=要准备 / 灰=别忘）+ 到期徽标 + 回复草稿带「复制」+ 「为什么」+ 「容易被忽略」逐条列出。颜色只表达紧急性质，不做装饰。
- **采集范围开关**：设置页新增「采集范围」，逐会话开关（写 `conversation.muted`）。关掉后 `NotificationListenerService` **直接丢弃**该会话通知（`IngestResult.Muted`，不入库不花钱），且生成简报时**历史消息也一并滤掉**，否则开关等于没生效。UI 提示"已选 N 个 / 关闭 M 个"。
- **验证**：147 项单测全绿（新增 advice 解析/容错、旧简报兼容、不采集会话拦截与素材过滤、怎么办最多 3 条）；`lintDebug` 0 error；`assembleDebug` 出包 19.09MB（1.3.0 / code 22）。

## 1.2.0 (2026-11-06) — 修「再补一份还是旧内容」+ 数据新鲜度可见
- **根因**：增量片段按「上一条 OK 的 `to_ts`」切窗，而通知的 `postTime` 不可靠（部分 ROM 更新通知沿用首次投递时间、迟到投递、时钟回拨）。一旦新消息的时间戳落在水位线之前，它就被**永久跳过**——现象正是「反复生成，永远是下午那几句，最新数据进不来」。
- **修复**：`brief_slice` 新增 `covered_max_id`（DB v3→v4，仅加列、老数据 `0` 兼容），增量改为**按消息 id 推进**（`messagesAfterId`），与时间戳彻底解耦；老片段仍按时间窗取数。
- **水位只在 OK 时推进**：额度用完、解析失败、读库失败的片段不推进，避免把这批新消息吞掉（新增回归测试）。
- **手动不再被防抖吞掉**：`appendSlice/retrySlice` 传 `force=true`，pipeline 也对 `MANUAL` 免除 5 分钟限制——之前的「点了没反应」就是这么来的。
- **跳过原因能自证**：无新消息时记录「这一段没有新消息（10:00–13:30 内 0 条；今日共 138 条，最近一条 12:30）」，而不是一句「没有新消息和备忘」。
- **「新增 N」改为真实条数**：`new_count` 现在是本片段真正读进来的消息条数（原来记的是产出条目数，UI 上「新增」一直很怪）。
- **数据新鲜度条**（`FreshnessRepository` + 时间轴顶部）：最近一条消息多久前、今日已采多少条、最近入库时间；监听未授权 / 服务没绑上分别给「去开启 / 重新连接」按钮。**采不到 ≠ 总结得不好，现在能一眼分开。**
- **验证**：141 项单测全绿（`SlicePipelineTest` 6 项，含迟到消息、失败不推进水位、手动绕过防抖）；`lintDebug` 0 error；`assembleDebug` 出包。

## 1.1.0 (2026-11-06) — 简报呈现改版：先看这 3 条 / 树状图 / 时间带 / 曲线图
- **版式重做**：新增 `BriefRichView`，把「一段段平铺文字」换成固定阅读路径——⓿ 先看这 3 条 → ① 谁在说 → ② 什么时候 → ③ 消息节奏 → ④ 我要做什么。段落带编号，用户知道自己读到第几段、下一段回答什么问题。
- **⓿ 先看这 3 条**：全量要点按**可解释加权**排序（带具体钟点 +3 / 动作词 +2 / 金额 +2 / 定论词 +1 / 风险词 +1），每条挂判据 chip（带时间·要回复·涉及钱·已定论·有风险）+ 会话与时间来源。没时间时只读这一段就够；判据公开，不是黑箱排序。
- **① 会话卡片视图**（默认）：一个会话一张卡（会话名 + 条数 + 要点 + `⏰` 时间 chip），比树更适合逐条读；右上角「看关系图」切到 `MindTree` 树状图，两种视图各取所需。
- **树状图** `MindTree`：Canvas + TextMeasurer 手绘（无第三方图表库）。根=今日，分支=会话，叶子=要点。解决「平铺列表看不出哪几条是同一个会话说的」。
- **② 垂直故事线**：时间带下方用「圆点 + 竖线 + detail」把时间点串成一天，`TimelineItem.detail` 作为二级说明，比横向独占一条轴更接近人的记忆方式。
- **曲线图** `TrendChart`：今日 vs 昨日 24 小时消息密度（面积 + 平滑折线 + 峰值标注 + 虚线对比）。数据来自 Room 真实消息（`hourlyCounts` 按本机时区 `strftime` 分桶），**不由模型生成**，杜绝数字幻觉。
- **环形图** `DonutRing`：今日必做完成率（0→主色，满→绿，未开始→琥珀）。
- **时间带** `ScheduleBand`：关键时间点在 0–24h 轴上的位置 + 「现在」虚线指示；must/maybe 两色；文字清单另列，避免手机上标签压字。
- **条形图** `PeerBars`：会话热度 Top6（按当天消息量）。
- **取数层**：`MessageDao.hourlyCounts(countsByPeerBetween)` + `BriefMetricsRepository.dayMetrics`；`AgentViewModel.metrics` 随日期切换/生成完成自动刷新。
- **语义层** `BriefLens`（纯 Kotlin，可单测）：时间文本解析（`15:00` / `下午 3:00` / `晚上8点` / `明天 09:30`）、按会话聚合要点、时间线按分钟排序、要点打分排序、计数与一句话结论（只做结构化，不做推断）。
- **提示词**：`brief_p1 → brief_p2`。`wechat_points` 改为「按会话聚合、同一会话最多 3 条、写结论不转述原句、key_time 只在原文有钟点时才填」——给树/卡片提供更实的叶子。
- **测试**：+8 项（`BriefLensTest` 6 项：时间解析/聚合/排序/空态/打分排序；`BriefMetricsRepositoryTest` 2 项：真 SQLite 小时分桶与完成率，`@Config(sdk=[26,34,35])` 三个系统版本各跑一遍），全套 **137 项全绿**。
- **验证**：AGP 8.6.1 + compileSdk 35 + JDK 21 编译通过；`lintDebug` 0 error；`assembleDebug` 出包。
- **Android Studio 预览**：`ui/agent/BriefRichPreview.kt` 提供浅色/深色/空态三个 `@Preview`，Design 面板直接看效果，不用装机。

## 1.0.2 (2026-11-05) — 采集恢复完全在 App 内完成
- 状态卡改为显示**真实绑定状态**（`NotifyCaptureService.isConnected`），不再拿历史 `connected_at` 冒充"在线"；断开时显示"监听已断开"并给出提示。
- 打开消息页时若发现未绑定：自动 `requestRebind`（400ms 后一次 + 1.5s 后复核），用户无需任何操作。
- 「立即重连」改为两次 `requestRebind`（间隔 1.2s/1.5s）并刷新真实状态，按钮显示"重连中…"；仍失败时提示去「通知使用权设置」关开一次。
- 覆盖安装/开机广播继续自动重绑；**不再需要 adb 或电脑**。
- 消息页可见时每 5 秒刷新一次状态与今日入库数，连上后状态会自己变绿。

## 1.0.1 (2026-11-05) — 修复覆盖安装后采集不到消息
- 根因：重装/覆盖安装后系统仍保留「通知使用权」授权，但**没有重新绑定** `NotifyCaptureService`，`dumpsys activity services` 里没有该服务，所有通知回调都不会发生（界面却显示"监听在线"，因为读的是历史 connected_at）。
- 修复：服务增加 `isConnected` 状态；`InfoAgentApp` 启动时、`BootReceiver` 收到 `MY_PACKAGE_REPLACED` 时，若未连接则 `requestRebind()` 自愈。
- 手动兜底：消息页「立即重连」仍可用；真机验证用 `adb shell cmd notification disallow_listener/allow_listener` 可强制重绑。

## 1.0.0 (2026-11-05) — 简报改版：一天一条时间轴 + 增量片段
- **数据模型 v3**：新增 `brief_slice`（biz_date/seq/trigger/from_ts/to_ts/payload_json/new_count/tokens/state/read_at）；`MIGRATION_2_3` 建表并回填 `agent_run` 历史（seq 按天编号，历史一律已读）；`agent_run` 保留只读暂不删。
- **生成逻辑**：新增 `BriefingPipeline.generateSlice(trigger)`——区间 =「上一条 OK 的 to_ts → now」，当天首条从 00:00 起；间隔 <5 分钟直接落 `SKIPPED(too_soon)` 不调模型；prompt 增加「上一条简报摘要」与 `continued` 指令；片段无论 OK/FAILED/SKIPPED 都落库。
- **定时/补跑**：`BriefingWorker` 改写 `brief_slice`，失败按状态机 retry，SKIPPED 终态；`catchUp` 仍按 `scheduled_run.pendingOverdue` 投递，worker 走增量生成。
- **页面重做**：删除「上午/下午/晚间/手动」分段按钮；改为日期头（前后一天）+ 片段列表（状态点/时间/触发方式/区间/新增数/未读）+ 折叠展开 + 失败重试 + 空态 + 「现在补一份」；默认展开最新一条，展开即标记已读。
- **深链**：简报通知带 `slice_id`/`biz_date`，点击进入时间轴并滚到该片段。
- **导出/导入**：`brief_slice` 入表清单（schema_version=2），按 id 覆盖导入。
- **测试**：`SlicePipelineTest`（首尾相接、间隔跳过不调模型）、`MigrationTest` 扩展 v2→v3 建表可读写；125 项全绿。
- 已知未做：P1 的日历选择器、P2 的继续项分区/全天总览；旧 `generate(window)` 仅保留给单测，业务不再调用。

## 0.9.4 (2026-11-05) — P0 回归修复：通知不再 10 秒消失
- **P0-1**：删除 `ephemeralIsland()`（`setTimeoutAfter(10s)`），该逻辑在单通知模式下会让提醒 10 秒后从状态栏消失；业务通知恢复默认生命周期。
- **P2-1**：`SystemIslandAdapter.isAvailable` 改为「非小米/OPPO/realme/一加」，Noop 兜底重新可达。
- **P2-2**：`catchup=true` 的补跑跳过自动整理，省 token。
- **P2-3**：`catchUp()` 只补最近 24 小时内的过期档期。
- 单测新增断言：提醒类通知 `timeoutAfter == 0`。
- 说明口径：岛仍是「系统通知 + 厂商 extras」，是否显示为岛/胶囊由系统决定；ColorOS 无真机验证记录。

## 0.9.3 (2026-11-05) — 复审修复：岛改为「装饰已有通知」
- **1.1–1.4**：`IslandAdapter` 改为 `decorate(ctx, builder, payload)`；删除各适配器的 dismiss / 独立 notify；`NotificationFactory` 暴露 `buildReminder/buildBriefing/buildAutoMemo`（返回 `Pair<id, Builder>`），三个调用点统一「build → decorate → notify 一次」。业务渠道复用 `key_time_reminder / briefing_push / auto_memo`，删除 `island_updates`。
- **1.5**：`setOngoing(true)` + Handler 8 秒 cancel 改为系统 `setTimeoutAfter(10s)`，进程被杀也由系统收起；不再产生僵尸常驻通知。
- **1.6**：新增 `NotificationSingleTest`，断言装饰后只 notify 1 次且渠道为业务渠道。
- **P1-1**：`loadWindow(markCached)`；生成完成后传 `false`，刚生成的结果不再显示"缓存结果"。
- **P1-2**：新增 `BriefingPipeline.previewStats()`，素材条数/估算 token 用实际会进 prompt 的 `PickedLines`，不再用「窗口全部消息 × 40」；UI 区分「窗口消息」与「实际素材」。
- **P1-3**：`BriefingScheduler.catchUp()` 在 App 启动与 BootReceiver 扫描 `pendingOverdue`，按过期档期投递一次性补跑。
- **P1-4**：纠正为单聊时同时把该会话历史 sender 归并回会话名（`resetSenders`），避免前缀又把群标记写回。
- **P2-1**：`island_test` 入口加 `BuildConfig.DEBUG` 限制。
- **P2-2/P2-3/P2-4**：渠道合并为 3 条、删死渠道；不再有第二套通知 ID；删掉 Live Updates 反射与 `POST_PROMOTED_NOTIFICATIONS`（targetSdk 35 下是空转）。
- 未做：P3-1 自绘兜底（与你「不要贴图」的要求冲突，保持纯系统通知）；P3-2 的 `Action.Builder(0)` 随装饰化一并移除。

## 0.9.2 (2026-11-05) — 系统通知版岛
- 删除悬浮窗与 `SYSTEM_ALERT_WINDOW`；`SystemIslandAdapter` 仅发系统通知，HyperOS/ColorOS 改为同一条通知带 extras。

## 0.9.1 (2026-11-05) — 岛改为系统通知（去掉悬浮窗贴图）
- 删除 `IslandOverlayController` / `OverlayAdapter` / `SYSTEM_ALERT_WINDOW` / 「优先系统岛」开关与悬浮窗授权入口。
- 新增 `SystemIslandAdapter`：只用系统通知；Android 16 上反射尝试 Live Updates（`setShortCriticalText`、`setRequestPromotedOngoing`、渠道 `setAllowPromotedOngoing`），系统支持则显示为状态栏胶囊/进度，否则就是普通高优先级横幅。
- HyperOS/ColorOS 适配器改为「同一条系统通知 + extras」，不再自绘；其它厂商直接走 SystemIslandAdapter。
- 通知 8 秒后自动取消；点击通知进对应页面；Manifest 增加 `POST_PROMOTED_NOTIFICATIONS`（低版本忽略）。
- 设置页改为「系统岛提示」：说明由系统决定形态，并提供「测试系统岛」按钮；新增 `island_test` 联调入口（adb 可触发）。
- 单测更新：其它厂商→system；去掉悬浮窗相关断言。

## 0.9.0 (2026-11-05) — 岛接入（E1–E5）
- **E1 抽象**：`IslandAdapter` / `IslandPayload` / `IslandAction` / `IslandCapability` / `IslandRouter`；业务只调 `IslandRouter.show()`，降级在各适配器内部。
- **E2 自绘兜底**：`IslandOverlayController`（WindowManager 悬浮窗，圆角胶囊、进度、最多 2 按钮、5.5s 自动消失、点击深链、同 key 覆盖）；**不用前台服务**，避免再申请 FGS 权限；无悬浮窗权限时自动只用系统通知。
- **E3 HyperOS**：`HyperOsAdapter`（xiaomi/redmi/poco && SDK≥33）写 `miui.focus.*` 候选 extras + 发通知；默认并行自绘兜底（无法可靠探测是否上岛）。
- **E4 ColorOS**：`ColorOsAdapter`（oppo/realme/oneplus）写 `com.oplus.capsule.*` 候选 extras，失败降级；仅保留最小实现。
- **E5 设置**：新增「岛提示」区：`优先系统岛` 开关（true=只发系统 extras，false=并行自绘）、`授权悬浮窗`、`测试岛提示`；文案明确「若系统支持则显示，否则用应用内浮层」。
- 接线：提醒（ReminderReceiver）、简报（BriefingWorker）、AI 待办（AutoMemoWorker）统一走 Router。
- 权限：仅恢复 `SYSTEM_ALERT_WINDOW`（悬浮窗兜底）；不引入 Xposed/LSPosed/root，不做 Hook。
- 单测新增：厂商路由选择、优先系统岛开关、载荷归一化（按钮≤2、进度裁剪）。

## 0.8.2 (2026-11-05) — 简报栏目真正区分 + 缓存统计修正
- 切换「上午/下午/晚间/手动」时加载**该栏目自己的窗口与简报**（当天该 window 的 run；没有则清空并提示），不再共用同一份。
- 新增栏目信息卡：窗口区间（如「上午 · 昨天 22:00 – 10:00」）、素材条数、是否已生成；进入时按当前时间自动选中栏目。
- 修复缓存加载时「本次素材」窗口/条数为空；历史简报也过 sanitize（丢弃空会话、`（null）` 要点的来源一并处理）。
- 要点渲染不再显示 `（null）`（模型把 null 写成字符串的情况）。

## 0.8.1 (2026-11-05) — 第三轮审查 A/B/C/D 组
- **A1** 简报素材改为**从最新往回取**（关注会话优先、每会话 6 条、全局 12k 字符上限），不再保住最旧一段。
- **A2** 简报时点校验：必须 上午<下午<晚间，且上午窗口 ≤16 小时；设置页实时显示「覆盖 昨日22:00–今日10:00（12 小时）」。
- **A3/A4** 关注会话进入缓存键；`lastTs` 移出缓存键（重复通知不再击穿缓存，内容/撤回仍失效）。
- **A5** 简报页新增「本次素材」卡片：窗口区间、消息条数、估算/实际 token、今日剩余次数与成本。
- **A6** 400 且错误体含 context/token 时直接失败并提示"素材过长"，不再三次全量重发。
- **B1** 新增 `MemoryWorker`（每晚 23:30 提炼记忆，共用 agent_lock）——"记忆"功能首次真正通电。
- **B2** 群聊判定再收紧：单批多前缀只算"疑似"（切 sender 不写 isGroup），需历史命中且并集≥2 或累计≥3 才标群；会话长按可手动「标记为单聊/群聊」纠错。
- **B3** 自动整理防抖改 `KEEP`：首次消息起 5 分钟内必跑，不再被后续消息无限重置。
- **B4** 阶段事件进入简报 prompt 与记忆提炼素材。
- **B5** 会话行改为 `INSERT OR IGNORE + UPDATE`，不再整行 REPLACE 重置 priority/muted。
- **C1** 冷启动/通知进入简报页会加载当天已有简报，不再显示"还没有今天的简报"。
- **C2** 移除流式原始 JSON 渲染与 O(n²) 拼接（生成时只显示骨架屏）。
- **C3** 删除"下面显示上一次成功简报"的假文案，失败卡片改为「重试」。
- **D1** 删除自绘岛残留：`SYSTEM_ALERT_WINDOW` / `FOREGROUND_SERVICE*` 权限与岛组件全部移除。
- **D2** 迁移测试升级：按导出的 schema 建 v1 库 + v2 identityHash，让 **Room 真正打开并校验迁移**。
- **D3** `BriefRun.permanent` 取代中文文案匹配。
- **D4** 清理 6 个零调用 DAO 方法；DEPLOY 记录 SHA256。
- 单测 **117 项全绿**。

## 0.8.0 (2026-11-05) — 第二轮审查 N/R 组 + 体验修正
- **N1 群聊判定回归**：不再依赖「会话表已标群」；证据 = 系统 `EXTRA_IS_GROUP_CONVERSATION`/MessagingStyle 多人 || 会话已标群 || 历史出现 **≥2 个**不同人名前缀 || 本次多行 ≥2 前缀。单个历史前缀不再判群（避免单聊误判），群标记「只升不降」。
- **N2 定时自取消**：`scheduleAll` 先比对「时点+时区指纹」，未变化用 `UPDATE`（不再取消即将执行的任务），只有设置变更/首次才 `CANCEL_AND_REENQUEUE`。
- **N3 停止误报**：`generate()` 单独 `catch (CancellationException)` 并重抛，主动停止不再显示“生成失败”。
- **R1 回收站可用**：设置页新增「回收站」入口（列出、恢复、清空），不再只进不出。
- **R2 迁移测试**：新增 `MigrationTest`（v1→v2 补 `feedback_sum` 并校验默认 0）。
- **R3 导出不占写锁**：`exportTo` 去掉外层写事务，分表读、事务外写文件。
- **R4 不必要重试**：简报「没素材/额度/未配置/锁占用」改为 `Result.success()`，仅网络/5xx 重试。
- **R5 空表接线**：会话长按可「关注（简报优先）」真正写 `watch_rule`；简报/自动整理调用写 `agent_trace`（成本/延迟审计）。
- **体验**：
  - 备忘时间输入改为**系统时间选择器**（不用手打冒号），设置页三个简报时点同样；
  - 简报生成中只显示骨架屏，不再刷出含 `null` 的原始 JSON；
  - **设置独立成一级页面**（抽屉第 4 项），不再挂在 Agent 第三个 Tab；
  - 简报窗口边界随时点配置走（`WindowBounds`），改时间后统计区间同步；
  - **每日必做顺延**：留档后原地保留并重置勾选，不再隔天消失；
  - 新增**阶段事件（STAGE）**栏目：跨天保留、不参与日清，只能手动编辑/删除，可拖动排序；
  - 移除自绘悬浮岛（贴图观感差、后台还起不来），提醒/简报统一用系统高优先级通知横幅。
- 单测新增：群聊精度、阶段事件、日顺延、迁移、时间窗。

## 0.7.3 (2026-11-04) — 继续完善（交互/检索/防护）
- 消息流 **分页**：首次 300 条，点「加载更早的消息」每次 +300（上限 3000），不再只有 300 条。
- 导入 **大小守卫**：备份 >12MB 直接提示，避免全量读入内存 OOM。
- 简报支持 **复制** 与 **重新生成（跳过缓存）**；`BriefingPipeline.generate(force=true)`。
- 设置页新增 **关于 / 版本 / 隐私说明**（只存本机、Key 加密、导出不含 Key 等 5 条）。
- `testOptions.isReturnDefaultValues` 改为 **false**（D5：不再用默认返回值掩盖未接线）。
- 新增回归：A4 导入 id 映射、A1 产出清洗、强制忽略缓存。
- 单测 **105 项全绿**。

## 0.7.2 (2026-11-04) — 代码审查 A/B/C/D 组修复（待整套复核后出包）
- **A1** 简报产出后置清洗 `sanitize()` 真正接上（缓存命中路径也清洗）。
- **A2** `parseAutoMemo` 缺 `items` 改 **Failed**；失败批次不写 `maxMsgId`、不推进水位。
- **A3** 失败 3 次进 **死信** 并推进水位；水位查询由「按时间戳」改 **按消息 id**（`messagesAfterId`），修复队头永久阻塞。
- **A5** 只在 **已知群聊** 时按「发送者: 内容」切分；单聊「会议地点：A 座」「https://…」不再误判。
- **A6** `dedup_key` 加 **分钟桶**，同一句话隔分钟不再被吞（同分钟重复通知仍去重）。
- **A7** `memory_fact.feedback_sum` 新列 + Room v1→v2 迁移，👍👎 不再被合并抹掉。
- **A8** 简报 `expected_at` 改计划时点，`DEGRADED` 可真实出现；`scheduled_run` 接线；定时策略 `CANCEL_AND_REENQUEUE`。
- **A9** 备忘页 `today` 改 StateFlow + 回前台信号（`DayTicker`），跨零点自动刷新归档。
- **A10** 导出改 **流式写文件**（`exportTo(OutputStream)`），版本号取 `BuildConfig.VERSION_NAME`。
- **B1** 消息/备忘删除接 **回收站**；归档 Worker 清理过期回收站。
- **B2** `agent_lock` 补租约条件；`AgentLockGuard` 接入自动整理与简报 Worker。
- **B3** 简报 👍👎 真正写入 `brief_feedback`；记忆合并改用真实反馈行构造提示词。
- **B4** 三段简报时点可配置（设置页「简报时间」+ `BriefingPrefs`），保存后立即重排。
- **B5** 后台启岛失败回退 300ms 精确闹钟 → `IslandAlarmReceiver` 再起前台服务。
- **B6** 抽屉「设置」跳转 Agent 设置页（`AgentTabs`）。
- **B7** 通知深链 `open=agent/memo/messages` 落对应页（含 `onNewIntent`）。
- **B8** 临时事件超 30 天归档；**B9** `upgradeToDaily` 同步 `cycle_date`。
- **C1** 通知回调 try/catch + 协程异常兜底；**C2** 提醒/开机去 `runBlocking`；**C3** 简报读素材异常落 error、`generate` 必解 running；**C4** 共享 OkHttp、取消走协程；**C5** 半截流不回退；**C6** 仅 429/5xx 退避重试；**C7** endpoint 去重拼接 + 400 降级；**C8** 日志去正文；**C9** 冷启动兜底。
- **C10** 检索合并 FTS + LIKE（单字/跨词可搜）；**C11** 会话条数改 SQL 聚合。
- **D4** 设置页明示「导入为合并，不清空本地」。
- 单测：103 项（新增 B4 解析、A2/A3/A6/A7 回归）。

## 0.7.1 (2026-11-04) — 交付前 UI 收口
- 消息页整体重做：采集状态卡（在线/断开/今日入库/最近事件/重连）、头像色块、今日条数与「今日要点」行、消息流日期分隔、收发气泡（不对称圆角、类型徽标、来源标注）、空态与搜索空态、列表项动画。
- 备忘页整体重做：渐变「今日完成」进度卡（百分比 + 动画）、拖动手柄实时排序（越 64dp 立即换位）、勾选颜色/缩放动效、AI 渐变徽标、来源芯片、列表项动画。
- Agent 页：渐变简报头卡（要点/必做/待办/推荐四指标）、生成中骨架屏、时间线圆点与连接线、简报内容淡入展开、无简报时的引导卡片。
- 页面切换动效：抽屉三页 `AnimatedContent` 淡入 + 横向位移（220–260ms）。
- 版本号升至 0.7.0 / versionCode 7。
- 95 项单测全绿；release 签名包重新构建。

## 0.7.0 (2026-11-04) — 真机问题修复批次
- **采集断联**：微信/QQ 折叠摘要不再整条跳过（原来会丢消息）；服务记录连接/入库时间；`onListenerDisconnected` 触发重绑；新增 `CaptureWatchdogWorker`（15 分钟巡检，超 20 分钟未连接即 `requestRebind` 自愈）；消息页新增「采集状态卡」（在线/断开、今日入库、最近事件、一键重连）。
- **群名被当正文**：解析器处理「标题即 sender: content」、剥离「群名: 」前缀、丢弃整行等于会话名的情况。
- **简报垃圾消息**：新增 `NoiseFilter`（广告/砍价/接龙/纯表情/纯链接/营销群名）；流水线按会话限 6 条、关注会话优先；提示词改 `[#id] {conv|sender|time} 正文` 结构化格式并加价值约束；产出后置清洗（要点==会话名/过短/复述群名丢弃）。
- **提醒不响**：提醒从 WorkManager 改为 **AlarmManager 精确闹钟** + `ReminderReceiver`；无精确权限时退化 `setAndAllowWhileIdle`；设置页显示精确闹钟状态与入口。
- **灵动岛**：新增自绘悬浮岛 `IslandOverlayService`（悬浮窗权限 + 胶囊 UI + 淡入淡出 + 点击进 App），由提醒/简报/AI 待办触发；设置页新增「灵动岛与提醒」开关、授权入口与「测试一下」。
- **UI/动效**：抽屉页面切换改 `AnimatedContent`（淡入+横向位移 220–260ms）；会话列表展示「今日要点」。
- 已知：测试 JVM 偶发原生崩溃（`app/hs_err_pid6304.log`），重跑即绿，待观察。

## 0.6.0 (2026-11-04) — 阶段 9 打磨 + 阶段 10 出包
- 首启向导 4 步（欢迎/通知使用权/API/完成），完成标记落 DataStore，之后不再出现。
- 设置页接通「导出备份 / 导入恢复」（SAF 文件选择 + ExportService/ImportService，A04 全链路可点）。
- Release 签名：keystore `C:\Users\a\keystores\infoagent-release.jks`（口令在 local.properties，不提交）；`assembleRelease` 出签名包 12.46 MB。
- 真机验收清单：`docs/ACCEPTANCE.md`（33 条，含已知未实现项）。
- 遗留（记录在验收清单）：拖拽排序、岛实验室、倒计时胶囊、P1 扩展未做。

## 0.5.0 (2026-11-04) — 阶段 6/7/8 自动整理 · 记忆 · 调度与岛
- 阶段 6：`AutoMemoPipeline`（未处理消息 ≤20 批、batch_hash 幂等、source_msg_ids → memo_source 多来源回跳、标题规范化 7 天去重、TEMP/AI/来源前三标）+ `AutoMemoWorker`（5 分钟防抖 REPLACE）+ 轻量通知。
- 阶段 7：`MemoryMath` 固定公式（50+8·log2(1+ev)+Σfb−2·days，夹紧 0–100）、`MemoryNormalizer`（同义词表归一）、`MemoryPipeline`（幂等合并/证据累计/DISMISSED 冻结与复活门槛）、记忆 Tab（事实列表/置信条/编辑/停用）、推荐 👍👎 回写记忆。
- 阶段 8：通知渠道 5 类；`NotificationFactory`（持久通知 ID D08、deep link、HyperOS/ColorOS OEM extras 守卫回退、失败静默）；`BriefingScheduler`（10:00/13:30/22:00，nextTrigger 纯函数）+ `BriefingWorker`（自动整理→简报→推送，±15 分钟标 DEGRADED 显示补发）；`ReminderScheduler`/`ReminderWorker`（备忘时间提醒）；`BootReceiver` 重建；Android 13+ 通知权限请求。
- 测试：93 项单测全绿。

## 0.4.0 (2026-11-04) — 阶段 5 Agent 核心
- 契约：`assets/contracts/{brief,auto_memo,memory,followup}_v1.schema.json`（逐字段类型/枚举/可空/最大长度/必填）。
- 解析：`SafeParser` 容错提取 JSON、必填校验、非法枚举丢弃并记 warnings；`business` 校验留给调用方。
- 缓存：`CanonicalHasher.inputHash`（含 dedup_key/content hash/recalled/last_ts，D04）与 `batchHash`。
- 预算：`BudgetGuard`（自动整理 8 / 简报 3 / 记忆 2 / 追问 8k token / 手动 10 次，落 budget_counter 跨进程持久）。
- 客户端：`OpenAiCompatibleClient`（OkHttp SSE、空 data 帧丢弃、60s 超时、IOException 重试 1 次、URL 安全校验 §6）。
- 流水线：`BriefingPipeline`（三段窗口定义、缓存命中、预算拦截、错误映射、stale 降级、原文落库）。
- UI：Agent 页三 Tab（简报/记忆占位/设置），设置含接口地址/模型/Key（Keystore 加密）/温度/权限状态。
- 69 项单测全绿。

## 0.3.0 (2026-11-04) — 阶段 3/4 采集与备忘
- 阶段 3-3：消息页 UI（会话列表/消息流/中文搜索/长按转备忘·复制·删除/手动导入弹层/权限横幅），接入抽屉首页。
- 阶段 4：备忘页 UI（每日必做 + 临时事件双区、勾选、编辑弹层带可选时间、上移/下移、升级为必做、AI 徽标与来源芯片）+ MemoRepository + 每日 00:05 ArchiveWorker（WorkManager 未初始化时静默跳过，开屏惰性归档兜底）。
- 说明：排序当前提供「上移/下移」菜单；拖拽手势留到阶段 9 打磨（计划 §5.7 动效谱系）。已记录为偏差。
- 45 项单测全绿，APK 出包。

## 0.2.0 (2026-11-04) — 阶段 3 采集层（进行中）
- 阶段 2 闭环验收：24 表 DDL + FTS 中文检索 + 去重（D06）+ 导出/导入恢复（A04）+ 回收站（D40）+ 唯一约束核对（D01）；44 项单测全绿，APK 出包。
- 阶段 3-1：通知解析器 NotificationParser（微信/QQ、群聊前缀、撤回候选、折叠占位、媒体类型映射，§8.8）+ ImportParser（手动导入）；9+3 项解析单测通过。
- 阶段 3-2：NotifyCaptureService（静默入库、撤回 2 分钟窗匹配、折叠占位 D48）、NotificationAccess 权限引导、Manifest 声明（BIND_NOTIFICATION_LISTENER_SERVICE）。编译与全量测试通过。
- 待办：阶段 3-3 消息页 UI（会话列表/消息流/搜索/长按操作/导入入口）。

## 0.1.0 (2026-11-04) — 阶段 0/1 环境与脚手架
- 批准并落盘 v6.1 计划（docs/PLAN-v6.1.md）。
- 阶段 0 工具链：Temurin JDK 17.0.21（C:\Users\a\.jdks\jdk-17）+ Gradle 8.9（本地分发包 C:\Users\a\tools）；SDK=android-35；`android.overridePathCheck=true`（中文工作区路径）。
- 工程骨架：Gradle Kotlin DSL + version catalog、Compose M3、Room/KSP、DataStore、Security-Crypto、WorkManager；三页抽屉骨架 + 主题 token。
- 阶段 2 进行中：24 表 Room 实体（@ColumnInfo 蛇形列名=§7 DDL）、五大 DAO、MessageFtsStore（应用层 bigram 写入，P0-1）、DedupKeys/DayBoundary、P2-19 资产（time_patterns/memory_synonyms/qq_parse_cases）、JVM+Robolectric 单测基建。
- 有意延迟（随对应阶段随类声明，避免空类占位）：NotificationListener / Worker / 前台服务等组件声明。
- 图标暂用 vector drawable（阶段 9 更换正式图标）。