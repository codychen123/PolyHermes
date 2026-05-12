## Context

当前 PolyHermes 已经有三层跟单能力：

- `Leader 管理`: 保存可被跟单的钱包地址。
- `Leader 池`: 管理候选、观察、小额试跟、冷却、淘汰和建议配置。
- `Leader 研究`: 对已知地址做来源记录、纸跟、评分、状态推进和禁用试跟审批。

现有 `add-leader-research-agent`  deliberately 保守：v1 只使用 watchlist、已有 Leader 和已持久化 activity event；`PUBLIC_LEADERBOARD` 显式 disabled；`leader.research.global-capture.enabled` 默认 false。线上也已经验证：当前候选主要来自已有 Leader，`TRIAL_READY=0`，用户看不到“系统发现了谁、为什么值得试”的结果。

本设计的重点不是重写 paper trading 或无约束自动交易，而是补齐 discovery-first Autopilot 产品闭环：真实外部候选来源 -> 安全 intake -> paper-first 研究 -> shortlist 推荐 -> 用户选择保持模拟/禁用，或显式打开真钱 Autopilot -> 小额受限试跟 -> 真实收益反馈 -> 自动暂停、降级或继续观察。

## Goals / Non-Goals

**Goals:**

- 接入至少一个真实外部 discovery source，使系统能发现不在已有 Leader 管理里的候选钱包。
- 保留 source contract、source health、source rank、source evidence 和失败原因，避免“发现失败但页面显示健康”。
- 增加候选 intake 质量门，防止噪声钱包、重复钱包、锁定/淘汰钱包、已有 active config 钱包污染研究和 Leader 池。
- 复用现有 paper trading、scoring、state machine、Leader Pool mapping 和 disabled approval safety。
- 新增 shortlist API，把候选整理成用户能直接决策的 Top 3-10 推荐卡片。
- 改造 Leader Research 首屏，让用户先看到“今日推荐”或“暂无推荐的具体原因”，再看诊断信息。
- 提供 watchlist 管理作为兜底，不把 watchlist 当成自动发现的主路径。
- 新增用户级 Autopilot 开关：关闭时只做模拟/禁用配置，打开时允许系统在硬风控下自动启用小额真钱试跟。
- 保证任何真钱自动启用都必须经过候选状态、账户预算、单 leader 上限、日亏损上限、订单数、仓位、价格范围、冷却状态、kill switch 和审计检查。
- 用真实跟单 PnL、回撤、滑点、拒单和未知估值反馈候选状态，支持自动暂停、降级、继续观察或保留小额试跟。

**Non-Goals:**

- 不做自动加仓、自动放大 fixed amount 或自动放宽风控。
- 不做无上限、无预算、无审计或无需用户显式开关的真钱跟单。
- 不把 Autopilot 开关解释为“追随所有推荐”。开启后也只允许符合硬风控的 `TRIAL_READY` 候选进入小额试跟。
- 不把 `TRIAL_READY` 直接等同于 Leader 池 `TRIAL/ACTIVE`。
- 不依赖当前 `PolymarketActivityWsService` 的已知 leader 过滤链路来发现未知钱包。
- 不把 quote、position 或 Data API 不可用当成真实亏到 0。
- 不在本 change archive 既有 `add-leader-pool` 或 `add-leader-research-agent`。

## Decisions

### 1. 先接外部 discovery source，而不是先美化页面

推荐实现 `PUBLIC_LEADERBOARD` 或等价 `EXTERNAL_DISCOVERY` source，优先使用稳定可分页、可限流、可解释字段的 Polymarket 公开数据来源。实现前先做 source spike，记录 endpoint、字段、分页、排序、限流、错误状态和样例 payload，并产出 `source-contract.md` 或等价文档。正式 discovery client 不得在 source contract 未验证前直接进入实现。

原因：当前最大缺口是候选供给。只改 UI 会让页面更好看，但仍然没有新 leader。自动发现必须先有真实 source。

替代方案是先做 watchlist 管理。它风险更低，但仍需要用户自己找钱包，不符合“自动发现”。因此 watchlist 只作为 fallback。

### 2. Discovery source 只负责发现候选，不负责推荐真钱跟单

外部 source 只产出 normalized wallet、source rank、source evidence 和初始 stats。候选必须经过现有 paper-first 管线，达到状态和证据要求后才进入 shortlist 的高置信分组。

原因：leader 自己赚钱不等于用户可以复制收益。排行榜收益、交易量或胜率都只能作为发现信号，不能作为跟单信号。

### 3. Intake 质量门放在 source 和 research candidate 之间

新增或扩展 candidate intake 层，统一处理：

- 钱包地址标准化和去重。
- 已 locked、retired、blacklisted 候选排除或只更新只读证据。
- 已存在启用跟单配置的钱包排除出“新推荐”。
- 最小交易数、近 7/30 天活跃、source rank 上限和缺字段过滤。
- source evidence 和排除原因写入事件或候选字段，便于审计。

原因：外部 source 一旦接入，会带来大量噪声。质量门比后续页面过滤更安全，因为它能保护状态机、Leader 池和用户注意力。

### 4. Shortlist 是独立决策 API，不让前端读状态机

新增后端 shortlist 服务/API，返回四类结果：

- `readyToTrial`: 已达到 `TRIAL_READY`，可创建禁用试跟配置。
- `promisingPaper`: 纸跟 PnL 为正、样本接近达标，但还不能试跟。
- `newCandidates`: 新发现且通过初筛，等待纸跟和评分。
- `blockedOrCooling`: 分数或来源较强，但因回撤、亏损、未知估值、过滤比例、source stale 或冷却被阻断。

每张卡片必须包含推荐理由、证据指标、风险/阻断原因、建议配置和下一步 CTA。前端不应通过候选列表自行拼装推荐逻辑。

原因：用户的任务是判断“今天要不要试谁”，不是理解 research state machine。

### 5. Autopilot 开关决定模拟还是受限真钱

默认模式是模拟/禁用：系统可以 discovery、paper trading、shortlist、创建 `enabled=false` 的试跟配置，但不会启用真钱。用户需要在 Leader Research 或账户风控设置中显式打开 Autopilot 开关，确认最大资金预算、单 leader 试跟金额、每日最大亏损、每日最大订单数、最大仓位、价格范围和自动暂停条件。

Autopilot 开启后，shortlist 或后台研究 MAY 对 `TRIAL_READY` 且未 locked/cooling/retired 的候选创建并启用小额真钱试跟配置。所有启用都必须通过 `LeaderAutopilotDecisionService`；内部风控组件只能由该服务调用。请求中的 `enabled=true` 不再一律被拒绝，但只有在用户级 Autopilot enabled、候选达标、预算充足且 kill switch 未触发时才会生效。

原因：用户的目标不是“看推荐”，而是“稳健增长”。如果永远 disabled-only，产品会停在控制台。正确边界不是禁止真钱，而是让真钱只在用户显式开启、金额小、风险可见、可暂停、可追溯的条件下发生。

### 6. `LeaderAutopilotDecisionService` 是所有真钱路径的唯一闸门

新增 `LeaderAutopilotDecisionService`，它拥有 policy、risk、kill switch、pause 和 audit 决策权。`LeaderAutopilotPolicyService` 和 `LeaderAutopilotRiskController` 可以作为内部组件存在，但任何外部代码不得直接绕过 decision service 判断真钱动作。

所有 copy-trading 配置必须显式标记管理归属。新增或扩展 `copy_trading` 字段：

- `management_mode`: `MANUAL` 或 `AUTOPILOT`。
- `autopilot_policy_id`: 指向创建/管理该配置的 Autopilot policy 或用户级配置。
- `autopilot_paused_reason`: 最近一次由 Autopilot 暂停或拒绝的原因。
- `autopilot_last_decision_at`: 最近一次 Autopilot decision service 做出决策的时间。

关闭 Autopilot 只允许暂停或禁用 `management_mode=AUTOPILOT` 的配置，不得误动 `MANUAL` 配置。用户若想保留某个自动创建配置，需要明确将该配置转为手动管理，并记录审计事件。

Autopilot 状态机必须是账户级主状态，并受全局 kill switch 覆盖：

```text
ACCOUNT AUTOPILOT STATE

OFF --user_enable_with_budget--> ON
ON  --risk_pause/system_pause--> PAUSED
ON  --user_disable-------------> OFF
PAUSED --user_resume_confirm---> ON
PAUSED --user_disable----------> OFF

GLOBAL KILL SWITCH

any account state + global_killed=true => decision service returns PAUSE/DENY
global_killed=false does not auto-resume; user must explicitly resume PAUSED accounts.
```

配置级状态不拥有独立 Autopilot 开关，只保存 `management_mode` 和最近 pause/decision 信息。配置级暂停从账户状态、全局 kill switch 或该配置的风险原因继承。系统不得在风险恢复、source 恢复或 kill switch 解除后自动恢复真钱下单，恢复必须用户确认。

自动创建配置必须具备数据库级幂等保护。由于现有 `copy_trading` 曾移除 `account_id + leader_id` 唯一约束，Autopilot 不能只依赖 service 层的“先查再写”。第一版必须新增适合 MySQL 的唯一约束或幂等表，保证同一 `account_id + leader_id + management_mode=AUTOPILOT` 在可管理范围内最多有一条配置。并发冲突时返回已有配置或明确重复结果，不得创建第二条启用配置。

所有 Autopilot 决策请求必须显式携带 action type，例如 `CREATE_CONFIG`、`ENABLE_CONFIG`、`BUY`、`SELL`、`RESUME`、`CONVERT_TO_MANUAL`。账户处于 `PAUSED` 或全局 kill switch 生效时，默认拒绝新的 `CREATE_CONFIG`、`ENABLE_CONFIG`、`BUY` 和 `RESUME`；但 `SELL` 必须按 reduce-only 语义处理：只要该动作减少或关闭既有 Autopilot 仓位且不创建新暴露，系统 SHOULD 允许继续执行，以便风险状态下能减仓。只有未来新增显式 hard-stop 配置时，才允许同时阻断 reduce-only sell。

每次 Autopilot 真实 `BUY` 必须先完成账户级风险预留。Decision service 的 `ALLOW` 结果 SHOULD 返回 reservation id 或等价幂等 token；订单成功后 finalize，订单失败、过滤或异常后 release。预留必须受数据库事务、账户级锁或唯一幂等键保护，不能只依赖内存检查。风险窗口默认使用 UTC 自然日，除非后续明确增加账户级 timezone 配置。

所有 Autopilot API 必须先做身份和 account ownership 校验。用户只能读取或修改自己账户的 Autopilot 状态、预算、resume、disable、convert-to-manual 和 shortlist CTA；任何 accountId、copyTradingId 或 candidateId 不匹配当前用户账户时必须拒绝，不能只靠前端隐藏。

真钱决策审计必须结构化保存，推荐新增 `leader_autopilot_decision_event` 或等价表，字段至少包含 action type、decision、reason code、accountId、candidateId、leaderId、copyTradingId、reservationId、policy version、input snapshot、createdAt。input snapshot 必须脱敏，禁止保存 API key、wallet secret、完整 credential、session token 或其它可用于交易/登录的敏感值。

以下入口在执行前必须调用同一个 decision service，并得到 `ALLOW`、`DENY` 或 `PAUSE` 结果：

- shortlist/approval 创建 `enabled=true` 小额试跟配置。
- `CopyTradingService.createCopyTrading` 或 `updateCopyTrading` 试图保存 Autopilot 管理的 `enabled=true` 配置。
- `CopyOrderTrackingService.processBuyTrade` 每次真实复制买入前。
- 自动恢复暂停配置、扩大 Autopilot 覆盖范围或任何后续自动真钱动作。

Decision service 必须统一执行：

- 使用明确 enum，而不是散落字符串：`AutopilotAccountState`、`CopyTradingManagementMode`、`AutopilotActionType`、`AutopilotDecision`、`AutopilotPauseReason`。
- 用户级 Autopilot enabled 且账户未处于 paused/killed 状态。
- 候选必须为 `TRIAL_READY`，未 locked、retired、blacklisted、cooling 或 UNKNOWN valuation 阻断。
- 建议配置不得超过用户账户预算、单 leader fixed amount 上限、每日最大亏损、每日最大订单数、最大仓位和价格范围。
- BUY 动作必须先预留账户级预算和当日订单额度，再进入真实订单创建；预留失败等价于 `DENY` 或 `PAUSE`。
- 当日已实现/未实现亏损、连续亏损交易数、拒单率、source stale 或 quote/position unavailable 达到阈值时自动拒绝或暂停。
- 每次自动启用、拒绝、暂停、恢复、预留、释放、转手动都写入结构化 audit log，并能在页面解释。

原因：Autopilot 的核心不是“自动下单”，而是“自动踩刹车”。如果创建配置和真实下单各自做风控，系统很快会出现旁路；唯一 decision service 能保证真钱路径只有一扇门。

### 7. 真实跟单反馈必须反哺 leader 评分

paper trading 仍是候选晋升的前置条件，但真钱试跟后必须采集真实成交、真实 PnL、费用、滑点、拒单、未知估值暴露和暂停原因。真实反馈用于：

- 维持小额试跟：表现稳定但样本仍不足。
- 自动暂停：触发日亏损、最大回撤、连续亏损、数据不可用或异常成交。
- 降级或冷却：真实表现显著弱于 paper 或风险指标恶化。
- 保持候选观察：表现好但不能自动放大金额，放大必须是后续单独 capability 或用户明确操作。

原因：leader 自己历史赚钱，不等于用户复制后赚钱。资产稳健增长只能基于真实复制效果闭环，而不是排行榜或 paper PnL 自嗨。

### 8. Source failure 必须可见，不能被 expected limitation 掩盖

外部 discovery source、Data API backfill、shortlist 生成失败都必须进入 source health 或 run record。只有明确配置禁用的 source 才能作为 expected limitation，不影响 run success。

原因：用户必须知道“今天没有推荐”是因为没有优秀候选，还是因为发现源坏了。

### 9. Watchlist 管理只作为 fallback

增加 watchlist 编辑/批量导入/preview 能力，复用当前 `system_config.config_key = leader_research.watchlist` 或迁移到结构化表。导入时展示有效、无效、重复、已存在、已锁定和已淘汰数量。

原因：外部 source 初期可能不稳定，watchlist 可以让用户临时喂 seed，保持系统可用。但产品主路径仍是自动发现。

## Risks / Trade-offs

- 外部 Polymarket source 字段不稳定或限流严格 → 先做 source spike 和 bounded client；失败时记录 source health，不影响已有 research 数据。
- 排行榜候选质量噪声大 → 使用 intake quality gate、paper-first 晋升、shortlist 分组和阻断原因，避免把 raw leaderboard 当推荐。
- 候选量增长导致研究任务变慢 → 设置每次 source 拉取上限、candidate intake 上限、active candidate 上限、paper processing batch 上限和运行时间上限。
- 新候选污染 Leader 池 → 保持 `DISCOVERED` 不同步 Leader 池，只允许 `CANDIDATE+` 且未锁定候选同步。
- 用户误以为推荐已经启用真钱 → UI 必须展示 Autopilot 当前模式；关闭时明确“仅模拟/禁用”，开启时明确预算、单 leader 金额、暂停条件和最近真钱动作。
- Autopilot 开关被误用成无限自动跟单 → 账户级预算、单 leader 限额、kill switch、暂停规则和审计日志必须是后端强制，不允许前端绕过。
- 并发订单同时通过风控导致超预算 → BUY 前必须使用账户级风险预留、数据库锁或幂等 token，不允许只做读后判断。
- PAUSED 状态误阻断卖出导致无法减仓 → 明确 PAUSED/kill switch 只阻断新 BUY 和自动启用，reduce-only SELL 默认允许继续执行。
- API 被越权调用修改他人 Autopilot 设置 → 所有 Autopilot status/update/resume/convert/CTA API 必须做 account ownership 校验。
- 审计日志泄露交易密钥或钱包凭证 → 结构化 input snapshot 必须脱敏并使用字段白名单，不保存 secret。
- 风控查询随订单增长退化 → 当日订单数、亏损、仓位和预留使用聚合查询或 risk snapshot，避免每次下单全量加载历史订单。
- 真实跟单亏损超预期 → 只允许小额试跟，不自动加仓；触发日亏损、回撤、拒单或数据不可用阈值时自动暂停。
- quote 或 valuation 不可用导致误判亏损 → shortlist 卡片必须展示 unknown/unavailable 暴露，并沿用不可用不等于 confirmed zero 的语义。
- Watchlist fallback 被误解为主功能 → 页面和文档明确 watchlist 是临时补种，自动发现依赖外部 source。

## Migration Plan

1. 先在本地或测试环境完成 external source spike，保存 source contract 和样例 payload。
2. 增加 Flyway 迁移或字段扩展，用于保存 discovery source stats、source evidence、shortlist snapshot 或 watchlist metadata；如果复用现有字段，必须补索引和长度保护。
3. 增加 Autopilot 账户状态、配置管理归属、风险预留、结构化决策事件和幂等保护相关迁移；既有 copy-trading 配置默认迁移为 `MANUAL`。
4. 以默认关闭或低上限方式部署外部 discovery source，保留 kill switch，例如 `leader.research.external-discovery.enabled=false`。
5. 上线后先手动运行 dry-run/preview，确认候选数、排除原因、source health 和 run record 正确。
6. 再开启真实 intake，但只推进 research candidate，不自动创建跟单配置。
7. 确认 shortlist 页面能解释“有推荐”和“无推荐”的原因后，再允许用户从 shortlist 创建禁用试跟配置。
8. 在生产默认保持 Autopilot 关闭，先验证模拟/禁用模式、风控配置读取、权限校验、预留释放和页面文案。
9. 用户显式打开 Autopilot 后，只允许 `TRIAL_READY` 候选进入小额真钱试跟，并在首日用严格上限、结构化事件审计和风险预留验证自动暂停可用。
10. 如果发现 source 噪声、性能问题或真钱试跟异常，关闭 external discovery 或 Autopilot kill switch，保留已有 Leader Research、Leader 池和历史配置可读可用。

## Open Questions

- Polymarket 当前最稳定的外部 discovery source 是 leaderboard、trader search、Data API 还是组合来源？
- 外部 source 是否需要认证、代理、限流退避或缓存？
- 是否需要独立 `EXTERNAL_DISCOVERY` source enum，还是复用并启用现有 `PUBLIC_LEADERBOARD`？
- Watchlist 是否继续使用 `system_config` 字符串，还是迁移到结构化 watchlist 表以支持备注、标签和导入审计？
- Autopilot 用户级开关放在 Leader Research 页面、Copy Trading 设置页，还是账户风控设置页作为全局开关？
- 第一版 Autopilot 的默认小额 fixed amount、账户总预算和日亏损阈值应来自现有建议配置、用户输入，还是一个后端保守默认？
