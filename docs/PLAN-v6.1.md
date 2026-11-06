# 「信息小agent」安卓 App 实施计划（v6.1：评审 P0×5 定稿 + P1/P2 落位版）

> 修订说明：本版即开工版。已回改 P0-1~P0-5 五条必修项；P1-6~P1-13 八条已落到对应章节并在所属阶段入口设"必补门禁"；P2-14~P2-19 六条一致性修正已并入；§20 为「留给 spike」清单，**明确不再写进文档**；§19 为开工前自查表。此后不再新增缺陷条目，真实代码暴露的问题在对应阶段再修。

## 0. 缺陷覆盖矩阵（同步 P2-14 字段名修正）

同 v6 §0；其中 D04 行落点改注 §8.7（`dedup_key + content` 哈希，与 §7 DDL 字段一致）。

## 1. 术语 / 2. 产品与核心循环 / 3. 需求全景

- 术语：简报 / 自动整理 / 记忆事实 / 岛 / 保活，定义同 v4。
- 核心循环：微信通知 → 入库 → [自动整理]→ 临时事件备忘(带来源) ；三段简报(10:00/13:30/22:00) → 时间线+推荐 → 关键时间 → 岛推送；记忆引擎(夜间增/周深) → 事实库 → 注入简报推荐 → 👍👎回写。
- FR-01~28 编号不变；FR-10 验收口径以 §9.6（v6.1 修正）为准。

## 4. 架构

同 v6（AgentClient 接口、SSE 心跳/续传降级、ProviderConfig 能力位、串行队列、诊断包、feature flag）。变更：MCP 网关健康检查增加跳过开关（P1-8，见 §16）。

## 5. 设计系统与动效

- 色彩：主色 `#4C5BFF`/深 `#AEB6FF`；次级 `#7C6DFF`；surface `#F7F8FC`/`#1A1B22`；分隔 `#E7E9F2`/`#2A2C36`；微信来源 `#07C160`；AI 徽标渐变 `#4C5BFF→#8A5CFF`；错误 `#E5484D`；成功 `#2FBE6B`；警示 `#F5A623`。壁纸取色可关。
- 字阶 sp：Display 32/600 · Headline 24/600 · Title 20/600 · Body 16/400 · Body 14/400 · Label 12/500 · Caption 11/400；行高 1.4；数字 tabular-nums。
- 几何：卡片圆角 16 / 按钮 28 / 胶囊 18 / 弹层 20；触区 ≥64dp；页边距 20dp；卡内距 16dp；图标 24dp stroke 1.8。
- 组件库：AppTopBar / SortableChecklistItem / AiBadge / SourceChip / SectionCard / TimelineItem / BriefingAccordion / EmptyState / PermissionBanner / CountdownCapsuleRow / SwitchMatrixRow / StreakCard。
- 动效谱系：页面/抽屉 270/300ms 标准缓动；触感 120ms linear；列表新增 250ms 淡入+12dp；删除 200ms 塌缩；拖拽 spring(380/32)；勾选 240ms spring 缩放+划线；流式 16ms/字 linear；倒计时 1000ms linear；streak 400ms linear 滚动；AI 徽标 300ms 渐变扫过。"移除动画"全 0ms；同屏动效 ≤2 处。
- 无障碍：图标 contentDescription；字号 200% 不破版；TalkBack 顺序=视觉顺序；对比度 ≥4.5:1。
- 页面线框（抽屉：消息/备忘/Agent/设置/隐私；消息页：搜索+会话列表+日分组流+长按操作；备忘页：每日必做卡[勾选+排序+添加+历史] + 临时事件卡[AI 徽标/来源芯片/升级]；Agent 页：分段控+StreakCard+简报手风琴[要点/整合/时间线/推荐👍👎/追问] + 记忆 Tab + 设置 Tab）同 v4 §5.5。
- 状态设计：骨架 shimmer / 空态+CTA / 错误态重试 / 离线灰条 / 补发·stale·排队·倒计时时间轴徽标。

## 6. 安全、数据规则与隐私

- URL 校验：HTTPS 强制；HTTP 仅 localhost/局域网字面量且红字+二确；保存前连通性+证书校验。
- 首调同意：向第三方 API 发送正文前一次性同意；撤回=停用 Agent。
- 脱敏矩阵：锁屏/岛/组件=「某会话·N 条」不露正文姓名；日志仅 id/hash/时间；导出不含 api_key；群聊只发必要行。
- 导出加密：AES-256-GCM + PBKDF2-HMAC-SHA256(SHA256, 210k iteration)，文件头 version/salt/nonce。
- D2D：`data-extraction-rules.xml` 排除 EncryptedSharedPreferences 与 Keystore 绑定密文；密文不可解→引导重配不崩溃。
- 导出选择：含非敏感设置、不含 key；导入后提示重配 key。
- 同意撤回：隐藏 Agent 页简报与自动任务，备忘保留。
- 升级规则：同 keystore 覆盖安装；换签名前先导出。

## 7. 数据模型 v3.1（完整可执行 DDL）

```sql
CREATE TABLE wechat_message(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  peer_id TEXT NOT NULL, peer_name TEXT NOT NULL DEFAULT '',
  sender TEXT NOT NULL DEFAULT '',
  direction TEXT NOT NULL CHECK(direction IN ('IN','OUT','OTHER')),
  msg_type TEXT NOT NULL, content TEXT NOT NULL DEFAULT '',
  ts INTEGER NOT NULL, first_ts INTEGER NOT NULL, last_ts INTEGER NOT NULL,
  source TEXT NOT NULL DEFAULT 'notification',
  app_source TEXT NOT NULL DEFAULT 'wechat',
  dedup_key TEXT NOT NULL UNIQUE,           -- sha256(app_source|peer_id|sender|content|notification_key)
  recalled INTEGER NOT NULL DEFAULT 0,
  archived INTEGER NOT NULL DEFAULT 0,
  flagged INTEGER NOT NULL DEFAULT 0);
CREATE INDEX idx_msg_peer_ts ON wechat_message(peer_id, ts);
CREATE INDEX idx_msg_ts ON wechat_message(ts);
CREATE INDEX idx_msg_flagged ON wechat_message(flagged);
CREATE TABLE conversation(peer_id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '',
  is_group INTEGER NOT NULL DEFAULT 0, last_ts INTEGER,
  priority INTEGER NOT NULL DEFAULT 0,      -- watch_rule 的缓存列，规则变更时重算（D50）
  muted INTEGER NOT NULL DEFAULT 0);
CREATE INDEX idx_conv_last_ts ON conversation(last_ts);
CREATE TABLE watch_rule(id INTEGER PRIMARY KEY AUTOINCREMENT,
  rule_type TEXT NOT NULL CHECK(rule_type IN ('PEER','KEYWORD')),
  value TEXT NOT NULL, action TEXT NOT NULL CHECK(action IN ('FOCUS','IGNORE')), created_at INTEGER);
CREATE TABLE attach_item(id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER NOT NULL,
  type TEXT NOT NULL CHECK(type IN ('URL','OCR','FILE')), content TEXT, ts INTEGER,
  FOREIGN KEY(msg_id) REFERENCES wechat_message(id) ON DELETE CASCADE);
CREATE INDEX idx_attach_msg ON attach_item(msg_id);
CREATE TABLE memo_item(id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL CHECK(kind IN ('DAILY','TEMP')), text TEXT NOT NULL,
  sort_order INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0,
  cycle_date TEXT NOT NULL,                 -- 'yyyy-MM-dd' 本地日，日界 00:00
  assigned_time INTEGER,                    -- epoch ms 本地时区展示，可空
  origin TEXT NOT NULL DEFAULT 'MANUAL' CHECK(origin IN ('MANUAL','AI','SHARE')),
  source_app TEXT, source_conv TEXT, source_msg_id INTEGER,
  repeat_rule_id INTEGER, calendar_event_id INTEGER,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX idx_memo_cycle ON memo_item(cycle_date, kind);
CREATE TABLE memo_source(memo_id INTEGER NOT NULL, msg_id INTEGER NOT NULL,
  PRIMARY KEY(memo_id, msg_id));            -- AI 备忘多来源回跳（D17，唯一事实源）
CREATE TABLE memo_archive(id INTEGER PRIMARY KEY AUTOINCREMENT, memo_id INTEGER,
  archived_date TEXT NOT NULL, text TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0,
  origin TEXT, source_conv TEXT);
CREATE INDEX idx_arch_date ON memo_archive(archived_date);
CREATE TABLE auto_memo_job(id INTEGER PRIMARY KEY AUTOINCREMENT, batch_hash TEXT UNIQUE,
  contract_version TEXT, prompt_version TEXT, model TEXT,
  state TEXT NOT NULL CHECK(state IN ('PENDING','RUNNING','DONE','FAILED','WAITING_RETRY')),
  result_json TEXT, tokens INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE agent_run(id INTEGER PRIMARY KEY AUTOINCREMENT, biz_date TEXT,
  window TEXT CHECK(window IN ('MORNING','AFTERNOON','EVENING','MANUAL','FOLLOWUP')),
  input_hash TEXT, cache_key TEXT, contract_version TEXT, prompt_version TEXT,
  model TEXT, temperature REAL, max_tokens INTEGER, brief_json TEXT,
  stale INTEGER NOT NULL DEFAULT 0, tokens_used INTEGER, cost_est REAL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX idx_run_window ON agent_run(biz_date, window);
CREATE TABLE followup_chat(id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER,
  role TEXT NOT NULL CHECK(role IN ('user','assistant')), content TEXT, ts INTEGER);
CREATE TABLE scheduled_run(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT,
  expected_at INTEGER NOT NULL, fired_at INTEGER, reason TEXT,
  state TEXT NOT NULL CHECK(state IN ('PENDING','FIRED','MISSED','RESENT','DEGRADED')),
  run_id INTEGER, updated_at INTEGER NOT NULL);
CREATE INDEX idx_sched_expected ON scheduled_run(expected_at);
CREATE TABLE reminder(id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL CHECK(kind IN ('MEMO','BRIEF_KEYTIME','REPEAT')),
  memo_id INTEGER, run_id INTEGER, title TEXT, fire_at INTEGER NOT NULL,
  fired INTEGER NOT NULL DEFAULT 0, fired_at INTEGER);
CREATE INDEX idx_rem_fire ON reminder(fire_at);
CREATE TABLE memory_fact(id INTEGER PRIMARY KEY AUTOINCREMENT,
  category TEXT NOT NULL CHECK(category IN ('HABIT','PREFERENCE','PATTERN','RELATION')),
  subject TEXT, fact_text TEXT, confidence INTEGER NOT NULL, evidence_count INTEGER NOT NULL,
  last_seen INTEGER, source_refs TEXT, period_hint TEXT,
  status TEXT NOT NULL CHECK(status IN ('ACTIVE','DISMISSED','EDITED')),
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE streak(date TEXT PRIMARY KEY, plan_total INTEGER, done_total INTEGER,
  temp_total INTEGER, done_rate REAL, updated_at INTEGER);
CREATE TABLE repeat_rule(id INTEGER PRIMARY KEY AUTOINCREMENT, memo_text TEXT,
  freq TEXT NOT NULL CHECK(freq IN ('DAILY','WEEKLY','MONTHLY')), weekdays TEXT,
  active INTEGER NOT NULL DEFAULT 1, created_at INTEGER);
CREATE TABLE recycle(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT,
  payload TEXT, deleted_at INTEGER NOT NULL, expire_at INTEGER NOT NULL);
CREATE TABLE message_archive(day TEXT NOT NULL, conv TEXT NOT NULL, data_blob BLOB,
  sha TEXT, count INTEGER, PRIMARY KEY(day, conv));
CREATE TABLE budget_counter(biz_date TEXT NOT NULL, use_case TEXT NOT NULL,
  count INTEGER NOT NULL DEFAULT 0, tokens_in INTEGER DEFAULT 0, tokens_out INTEGER DEFAULT 0,
  cost REAL DEFAULT 0, PRIMARY KEY(biz_date, use_case));
CREATE TABLE provider_config(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT,
  base_url TEXT NOT NULL, endpoint TEXT NOT NULL DEFAULT '/chat/completions',
  model TEXT, tier INTEGER NOT NULL DEFAULT 2, capabilities TEXT NOT NULL DEFAULT '{}',
  retry_after_until INTEGER NOT NULL DEFAULT 0,
  is_active INTEGER NOT NULL DEFAULT 0);
CREATE TABLE model_route(use_case TEXT NOT NULL, provider_id INTEGER NOT NULL,
  model TEXT NOT NULL, tier INTEGER NOT NULL, priority INTEGER NOT NULL,
  PRIMARY KEY(use_case, priority));          -- use_case∈auto_memo/brief/memory/followup
CREATE TABLE agent_trace(call_id TEXT PRIMARY KEY, use_case TEXT, provider_id INTEGER, model TEXT,
  prompt_version TEXT, contract_version TEXT, tokens_in INTEGER, tokens_out INTEGER,
  latency_ms INTEGER, parse_result TEXT, fallback_reason TEXT, created_at INTEGER);
CREATE INDEX idx_trace_ts ON agent_trace(created_at);
CREATE TABLE brief_feedback(id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER,
  section TEXT, item_index INTEGER, vote INTEGER, reason TEXT, ts INTEGER);
CREATE TABLE agent_lock(name TEXT PRIMARY KEY, owner TEXT, heartbeat_at INTEGER,
  lease_until INTEGER);
CREATE TABLE notification_id_map(entity_key TEXT PRIMARY KEY, id INTEGER);
CREATE VIRTUAL TABLE IF NOT EXISTS wechat_message_fts USING fts4(content_tokens, sender_tokens, peer_name_tokens);
```

FTS 同步机制（P0-1，应用层写入）：`*_tokens` 为影子列（主表不存），Kotlin 中文 bigram（连续二字组）+ASCII 分词，空格分隔；`withTransaction` 内消息 insert/delete 同步写 FTS；触发器方案废弃；`onOpen` 仅「不存在则 CREATE」（每次启动禁止 DROP）；迁移后全量重建（读主表→bigram→重写 FTS）；查询侧同法 bigram 后 `MATCH`（AND），未命中且关键词 ≥2 字回退主表 LIKE（近 30 天）；归档检索=按 day 解压后内存 bigram 匹配；FTS 列变更=DROP/重建/rebuild（禁 ALTER）。

- 语义：`dedup_key` 不含 ts 但含 `notification_key=tag+":"+category`（空写 "none"）；重复命中只刷新 `last_ts`。
- `source_msg_id` 为「首来源」缓存，事实源=`memo_source`（P1-10）。
- `recycle.payload = {table, row_json, schema_version}`；恢复按主键 upsert；每日清理 Worker。
- 一键清空事务序（P1-12）：停采集 → 清主表 → 清 FTS → 清 archive → 清 recycle → VACUUM。
- 迁移链：显式 Migration + 测试；升级前 checkpoint + 轻量备份。

## 8. Agent 契约 v3.1

- 8.1 契约仓库：`app/src/main/assets/contracts/`（auto_memo_v1 / brief_v1 / memory_v1 / followup_v1 `.schema.json`，运行时与 CI 同源）；逐字段类型/枚举/可空/最大长度；评估集 20 条 + CI mock 十场景；SafeParser 流式增量校验（可部分渲染字段合法即渲染，关键枚举非法丢段+标记；整契约失败→原文缓存+Markdown 回退+徽标）；BusinessValidator 后置（due 过去/source_msg_ids 不存在/memory_refs→DISMISSED → 标记不崩）。
- 8.2 自动整理：末消息后 5 分钟防抖或简报前，批 ≤20；请求行 `[#123][HH:mm] 发送者: 内容(会话)`，消息内容 JSON 字符串传入+系统提示声明不可信；响应 `{items:[{title≤120, due, urgency:ENUM, source_msg_ids:[int]}], skip}`；memo_source 全量关联；标题规范化 hash+7 天未完成去重；来源回填自消息表。
- 8.3 简报：上午=前日22:00–10:00 / 下午=10:00–13:30 / 晚间=13:30–22:00；MANUAL=0:00–now 独立 run；输入=窗口消息按会话+今日备忘快照+`biz_date-1` 归档完成率+Top≤10 记忆（confidence 降序、排除 DISMISSED、并列取 last_seen 新者）+链接聚合清单；timeline.date 跨天必带；`memo_status.done`=当日完成计数。
  - `window` / `generated_at` 由**客户端注入**（v1.4.3，prompt `brief_p5`）：模型不回显，回显写错不再算失败；请求窗口优先。
  - 提示词卫生：不许出现契约外字段（如 `continued`）、不许写死地点/日期例子、`BRIEF_SYSTEM` ≤2200 字符；由 `PromptContractTest` 守。
- 8.4 追问：契约 followup_v1{role, content, ts, refs}；截断=最近 4 轮+该段简报全文+当日摘要按优先级；预算=每日 8k token；超限 UI「今日追问 token 额度已用完（8k）」。
- 8.5 记忆：`confidence = clamp(50 + 8×log2(1+evidence_count) + Σfeedback − decay×days_since_last_seen, 0, 100)`（decay=2）；evidence 仅同 subject 新证据 +1 且每次命中 `last_seen=now`；DISMISSED 后 evidence 冻结；重提需新证据 ≥2 且 confidence>40；subject 规范化=小写/去空格/同义词表；👍+2/👎−5；refs 存 id 失效不删；card 级反馈落 `brief_feedback`。
  - **只增 + 时间有效性（v1.4.4 / DB v6）**：`memory_fact` 加 `valid_from`（何时开始为真）、`valid_to`（何时失效，null=仍有效）+ `(subject, valid_to)` 索引；老数据 `valid_from=created_at`、默认有效。
    写入不再覆盖：同一件事再出现 → 累加证据；**事情变了 → 旧行 `valid_to=now`、新事实另起一行**（历史可查，判断挪到取用侧）。
    「是否同一件事」分类型：`PREFERENCE`/`HABIT` 变了就换新值；`RELATION`/`PATTERN` 用二元组重合度 ≥0.6 判定"只是说得更细"，按同一件事累加。
    注入只取 `valid_to IS NULL`；失效项在「备忘 → 长期记忆 → 已过期」折叠展示，可一键恢复（`restore`）。理由：mem0 2026 版已把 UPDATE/DELETE 砍成 ADD-only，Graphiti 也是"旧边失效不删"——写入时不判生死，取用时排序。- 8.6 预算/失败：日预算=自动整理 8 / 简报 3 / 记忆 2 / 追问 8k token（可配）；手动日上限 10 次（不计自动预算但记账）；重试计入；401→停自动+PENDING 标 MISSED(AUTH)+修 key 后「补跑今日简报」；429→释锁+WAITING_RETRY+退避 ≤3 共享 `retry_after_until`；5xx/超时→重试 1 次+stale 缓存；降级链=`model_route`（整理 tier1/简报记忆 tier2/追问 tier3），429/预算紧张降级备用模型；能力不符走文本分支。
- 8.7 缓存键：`input_hash = sha256(canonicalJson{biz_date, window, contract_version, prompt_version, model, temperature, max_tokens, sorted_msg{id, dedup_key, content_hash/*=sha256(content)*/, recalled, last_ts}, memo_snapshot, top_facts})`；消息内容不可编辑（v1 仅撤回改 recalled）；未来编辑则更新 content_hash 并失效缓存。
- 8.8 解析/对账：合并通知逐行拆解（BIG_TEXT）·群聊 `发送者: ` 前缀·@引用前缀保留·媒体占位符·撤回正则·hash 幂等；折叠通知无正文→占位行「N 条新消息（需打开微信查看）」不生成 AI 备忘；撤回匹配=同会话+sender 同+direction=IN+同 msg_type+≤2 分钟窗，否则记系统行不猜；关键词规则变更→重扫近 30 天置 flagged；对账前检查监听连接，断连跳过并记 `LISTENER_DISCONNECTED`+UI 提示。
- 8.9 成本/截断：优先级=关注会话>「带时间」（时间正则表：`\d{1,2}[:：]\d{2}`、上午/下午/中午/晚上/明天/周X/日期，或 due 已提取）>今日消息>其他；超长群聊分块摘要；max_tokens=简报 **3072**（2048 装不下 advice+topics+refs，v1.4.2 上调）/提取 512/记忆 1024/追问 1024；单价可配+日估算+agent_trace 对账。
  - 截断处理（v1.4.2）：客户端读 `finish_reason`；`length` 视为「被截断」而非「格式不合法」→ 自动把 max_tokens 翻倍重试 1 次（上限 8192）；解析侧做**截断自愈**（补未闭合引号/括号、必要时丢半个成员再闭合），`memo_status`/`plan` 缺失降级为空+warning，不让整份简报作废。报错文案必须能指导动作（调大 max_tokens / 换非推理模型 / 回显模型开头原话）。
- 8.10 外部信息（v1.4 起）：简报生成前「查证」一次，用的是**用户自己的** key（和风天气 + 搜索服务，均可在设置里填，也可关）。
  - 顺序：聊天素材 →（模型，temperature=0，max_tokens=300）输出查询计划 `{places:[{city,date,why}], queries:[…]}` → 天气/搜索（带缓存）→ 事实行 → 作为【外部信息】进入主 prompt。
  - 契约 `ENRICH_PLAN`：places≤2（必须带 yyyy-MM-dd，缺日期不查）、queries≤2；不查隐私（手机号/住址/证件）；消息里没提到的城市不许写。
  - 缓存：查询计划 40 分钟、同城市+同日期天气 3 小时、同查询搜索 12 小时，落 `enrich_cache`（v5 表，7 天清理）——防止连点「补一份」烧用户额度。
  - 硬约束：查不到就不写（返回空 facts，主 prompt 里不出现【外部信息】节），**绝不假装联网**；模型引用外部事实必须带地点与时间、不许改写数字；与消息冲突时以消息为准并在 watch 标注。
  - 透明可核：本次用到的事实写进 `brief_slice.context_json`（v5 列），UI 可展示「这条简报参考了什么」。
  - 生活指数只取与出行/穿衣相关的 8 类（穿衣/紫外线/雨伞/舒适度/交通/旅游/运动/过敏），其余丢弃，避免简报里堆无用信息。
  - 契约版本：`brief_v3`（v1 纯消息要点 → v2 加 advice/refs → v3 加 topics 按事项聚合），资产同目录同步。

## 9. 通知与岛

- 通知 ID：`notification_id_map(entity_key→int)` 自增持久分配，碰撞重新分配，同实体复用不堆叠；分组按事件类型；deep link 至备忘/简报/会话。
- ChannelDoctor：节流 ≤1 次/天，仅 key_time_reminder/briefing_push，提供「不再提示」。
- FR-10 验收口径（P0-3 定稿）：**基线通过（标准通知+chronometer 倒计时）= FR-10 P0 通过**；OEM 岛展示=增强验收，P2 官方对接前不阻塞。阶段 8/10 验收表「基线通过/OEM 岛通过」两列，P0 只勾基线。
- 倒计时时间轴：fire_at−60min 出现、−15min 关键提醒、+5min 撤；完成/删除即撤。
- OEM：feature flag + try/catch 隔离；键名 spike 阶段 0/1；全屏 Intent 仅关键提醒 + canUseFullScreenIntent；FGS 与常驻胶囊默认同关（保活模式才开）。

## 10. 调度

- 手动/自动语义（P0-2）：自动任务唯一名 KEEP；手动 `ExistingWorkPolicy.APPEND_OR_REPLACE`（仅替换同 kind PENDING 链，RUNNING 不被取消）；Agent 服务单队列；UI「前面有任务，预计等待」。
- 租约：agent_lock 每 2 分钟续租；看门狗只清「租约过期且心跳停」的 RUNNING→FAILED 可重试。
- 补偿：DEGRADED（±15 分钟）显示「补发·原定 HH:mm」；TIMEZONE_CHANGED/TIME_CHANGED 重校准全部 exact alarm 与 expected_at；401 停任务 MISSED(AUTH)，修 key 后补跑→RESENT；超 24h 不补但记录。
- 日界：00:00；00:05 Worker 归档 `cycle_date < today`；先查 scheduled_run(EVENING) 是否 FIRED/DEGRADED，未完成每 10 分钟重试、2 小时后放弃并归档+`ARCHIVE_DEFERRED_TIMEOUT`。
- 记忆任务：默认充电+Wi-Fi；手动「立即提炼」+每周兜底（任意网络+充电 或 灭屏 30 分钟）。
- 读取一致性：简报只消费 auto_memo_job.state=DONE 与已提交备忘，事务内一致快照。

## 11. 保活引导器

分 ROM 步骤文案（自启动/省电/通知）+ 设置深链 + 30 分钟自检；结合 DEGRADED 统计展示「上周准时率」。

## 12. 数据生命周期

保留策略默认永久，可选 90/180/365 天滚动清理（联动归档与回收站）；一键清空双确认含「含归档/回收站」，二选「仅清主表/清全部」（见 §7 事务序）；回收站总入口（设置）+消息页入口，默认 30 天自动清；锁屏脱敏默认开。

## 13. P1 需求规格库（每条=触发/UI/数据/契约/验收要点）

- FR-13 重点关注：唯一事实源=watch_rule；priority 为缓存随规则重算；验收=两处操作状态一致+简报置前。
- FR-14 关键词：命中置 flagged；规则变更重扫近 30 天；验收=新增「合同」后历史命中。
- FR-15 聚合：仅通知正文可见完整 URL（正则+可选短链展开）；截断 URL 不入库并标记。
- FR-16 OCR：ML Kit Text Recognition v2 中文离线（或等价，spike 阶段 11 前定）；APK 增量 ≤6MB；队列并发=1 低峰串行；EXTRA_PICTURE 可用直接 OCR，否则「手动选择图片」；验收=选图 ≤3 秒出文字。
- FR-17 撤回：§8.8 规则。
- FR-18 重复习惯：记忆 period 事实→建议卡→repeat_rule 确认后自动提醒。
- FR-19 小组件：Glance 三款=今日必做 4×2 / 接下来 4×1 / 速记 2×2（ActionCallback 打开 App 输入页，桌面不嵌输入框）；刷新=数据变更+15min；空态+锁屏「解锁后查看」。
- FR-20 快捷方式：Static+Dynamic（+临时事件/立即简报）。
- FR-21 分享接入：来源=EXTRA_REFERRER→clipData→手选「未知应用」可编辑。
- FR-22 追问：§8.4。
- FR-23 倒计时胶囊：§9 时间轴。
- FR-24 streak：实时计算（当日 done/plan），00:05 归档只做快照；22:00 简报展示实时值。
- FR-25 回收站：设置总入口+消息页入口；三类实体可恢复，恢复按主键 upsert。
- FR-26 日历同步：单向写入；App 删除→删事件、改时→更新、权限拒→置灰+引导。
- FR-27 TTS：段落分块入队、音频焦点、失焦暂停、停止=tts.stop()+shutdown()；3000 字可暂停停止无残留。
- FR-28 QQ 采集：包名白名单 com.tencent.mobileqq；合并通知拆解、群@前缀规则、20 条样例；来源「来自：QQ」。

## 14. 测试计划

- 契约 mock 十场景：200/401/429/5xx/非 JSON/缺字段/语义非法/SSE 心跳/重复事件/Last-Event-ID 无效。
- fuzz oracle：输出长度 ≤ 原文、无控制字符、时间 ≤ now+1 年、占位符枚举合法；1 万次全满足。
- 单测 ≥80%：解析/导入/日界/token/契约/去重/置信/streat/关键词/回收站过期/迁移链/FTS 重建。
- 仪器：通知构造（OEM 异常回退）、ID 不堆叠、补偿状态机、FGS、Glance、日历。
- UI 自动化：三页 CRUD/拖拽/勾选动画/TalkBack/200% 字号/移除动画零残留。
- 真机矩阵：标准 Android 15、Redmi/HyperOS、OPPO/ColorOS（后补荣耀/vivo）+ D2D 换机、Keystore 失效、时区变化、低内存杀监听四类异常注入；三天稳定性；成本对账。
- 性能：Macrobenchmark+bateline profile；release 出包必须附带性能报告（自托管真机或 Firebase Test Lab），无报告阻断 release；日常 PR 无设备仅警告。

## 15. 风险登记

v6 全表：微信源风险（导入兜底/检测提示）；国产 ROM 冻结（保活+三重调度）；OEM 非公开（flag 回退+FR-10 基线口径）；自动整理误报（徽标/确认模式/负反馈）；API 费用（预算/缓存/熔断）；工作区权限（阶段 0）；隐私（本地+声明+锁屏脱敏）；迁移失败（备份+回滚）；Keystore 失效（重配引导）；模型下线（ProviderConfig 切换）；监听撤销（检测横幅）；导出泄露（加密+脱敏）；OEM 审核（flag 可关）；维护成本（P1 分批回滚）；成本估算偏差（对账修正）；provider 差异（能力位）；升级/换签名丢数据（同 keystore+导出前置）；通知碰撞（id_map）；租约泄漏（续租+看门狗）；SSE 中断（降级重生成）。每行含检测信号与回退动作。

## 16. 变更管理与质量门禁

- 契约统一 `app/src/main/assets/contracts/`，CI 与运行时同源；契约/提示变更升版本先改文档；CHANGELOG.md。
- CI 门禁：detekt（含 `sk-` 前缀规则）+ lint + 单测 + 契约十场景 + assembleDebug。
- MCP 网关健康检查（P1-8）：默认开（CI/开发机），失败即构建失败；`-PskipMcpHealth=true` 跳过；阶段 0 无网关用 skip 出空壳。
- 签名：keystore 离线备份+密码说明；版本语义化；旧版可回滚。

## 17. 分阶段实施

| 阶段 | 内容 | 覆盖验收 |
|---|---|---|
| 0 | 权限解除/换目录 → 工具链 → MCP 健康（含 skip）→ OEM spike → 空壳出包（gradlew assembleDebug+校验和） | D53, D60 |
| 1 | 脚手架+主题+Drawer+组件基座+加密设置前置 | D38 |
| 2 | §7 DDL v3.1 落地（sqlite CLI 自检）、FTS 应用层写入+迁移重建、budget_counter、导出/导入恢复；P2-19 产物：assets/regex/time_patterns.json、assets/regex/memory_synonyms.json、assets/eval/qq_parse_cases.json | A04, D01–D03, D06, D17, D18, D39, D40, D59 |
| 3 | 采集：解析规则+样例+撤回+折叠占位+导入+消息页+权限横幅；种子数据/时间旅行 | A17, D07, D48 |
| 4 | 备忘双区 CRUD/排序/时间/归档/AI 样式 | FR-04/05 |
| 5 | Agent 核心：契约仓库+SafeParser+CanonicalHasher+SSE+能力位+简报 UI+预算闸门；429/401 字段与参数先落地 | A01–A02, A07–A08, A13, D04, D14–D21, D24, D34, D47, D57 |
| 6 | 自动整理：源映射/去重/对账（监听断连检查） | A03, D05, D49, D55, D56 |
| 7 | 记忆：公式/冻结复活/反馈/last_seen | A09, D20, D58 |
| 8 | 调度+岛：租约/APPEND_OR_REPLACE/DEGRADED/时区/补偿状态机(D22/D23)/ID map/ChannelDoctor/FR-10 双口径/时间轴/归档延迟 | A05, A10, D08–D12, D22, D23, D25, D26, D44, D51, D52 |
| 9 | 打磨：动效/向导/设置/隐私/导出加密 UI/状态徽标/清空事务序 | A14, D41–D43, D46 |
| 10 | 验收：§14 全量+真机+三天稳定性+release 性能报告+签名出包；A01–A20 总表（基线/OEM 两列） | A01–A20, D34–D36, D54 |
| 11–14 | P1：采集扩展(13-17)→接入面(19-21/25/26)→Agent 扩展(22/23/24/27)→多来源习惯(18/28)；入口门禁：OCR spike(11)、Glance spike(12) | A12, D27–D33, D45, D49, D50 |

## 18. 假设与待定

自用/侧载/单用户/中文/包名 com.tencent.mm|mobileqq/晚 22:00 可调/提前 15min/记忆凌晨 2 点/导出口令自设/HTTP 仅开发者选项/OEM spike 失败=基线口径不阻塞/补发默认开/同 keystore 升级硬规则（D46）/时间正则表·同义词表·QQ 样例具名路径见阶段 2。

## 19. 开工前自查表

| 风险域 | 最坏情况 | 兜底章节 |
|---|---|---|
| 数据不可恢复 | 换机/迁移失败 | §7 导出/导入/迁移回滚/§6 D2D |
| 采集脆弱 | 微信改通知格式 | §8.8 未知格式→占位不崩 |
| 定时不可靠 | 简报永远晚到 | §10 DEGRADED/补发 |
| 密钥/隐私泄露 | key 进 VCS/导出泄露 | §6 脱敏/§16 detekt+网关 |
| 成本失控 | 账单暴涨/模型下线 | §8.6 预算/降级链 |
| 交互摩擦 | 用两周卸载 | §5 核心循环三步内可达 |
| 供应商依赖 | API 涨价/关停 | §4 ProviderConfig 能力位 |
| 开发链断裂 | MCP 挂/AS 升级 | §16 健康检查+skip |

## 20. 留给 spike 的事项清单（不再写进正文）

| 事项 | spike 时机 | 判定标准 |
|---|---|---|
| FTS 触发器 vs 应用层写入 | 阶段 2 | 100 行 demo 跑通中文搜索（已定应用层，spike 验性能） |
| WorkManager APPEND vs 自建队列 | 阶段 8 | demo 验证 RUNNING 不被取消（已定 APPEND_OR_REPLACE） |
| OCR 选型 | 阶段 11 前 | 体积 ≤6MB/速度/离线达标 |
| TTS 分块参数 | 阶段 13 前 | 3000 字可暂停/停止无残留 |
| Glance 速记输入能力 | 阶段 12 前 | 三款可加/刷新/直达 |
| 各 ROM 深链与岛键名 | 阶段 0/1 | 真机核对，失败走基线 |
| 性能具体数字 | 阶段 10 | Macrobenchmark 实测，回归 >10% 阻断 |