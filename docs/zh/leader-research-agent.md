# Leader Research Agent

Leader Research Agent 用来自动发现潜在优秀 leader、做纸上跟单、评分、生成今日推荐，并在用户显式打开 Autopilot 后用小额真钱试跟。默认状态仍是安全模式：不开 Autopilot 开关时，系统只做发现、模拟观察和禁用试跟配置，不会自动启用真钱跟单。

## 使用方式

1. 打开「跟单交易 -> Leader 研究」。
2. 首屏先看「今日推荐」和 Autopilot 状态；如果没有推荐，页面会说明是 source 不可用、样本不足、纸跟亏损、冷却、UNKNOWN valuation 还是全部过滤。
3. 点击「立即运行研究」拉取 public leaderboard、watchlist、已有 Leader 和已持久化 activity-derived 候选。
4. 在推荐卡片或候选表查看状态、评分、纸跟 PnL、过滤比例、UNKNOWN 暴露、推荐理由和建议参数。
5. 只有 `TRIAL_READY` 且未 locked/cooling/retired 的候选可以创建试跟。
6. Autopilot 关闭时，CTA 只创建 `enabled=false` 的禁用试跟配置，近似模拟盘/观察模式。
7. 用户在确认弹窗里打开 Autopilot 后，后端会把该账户 Autopilot 切到 `ON`，再通过风控闸门创建 `enabled=true` 的小额真钱试跟配置。

## 研究状态

- `DISCOVERED`: 已发现，但评分或新鲜度不足。
- `CANDIDATE`: 满足基础评分和新鲜度，可以进入纸跟。
- `PAPER`: 正在用独立纸跟账本模拟跟单。
- `TRIAL_READY`: 纸跟样本、PnL、回撤、未知报价暴露和过滤比例满足阈值。
- `COOLDOWN`: 回撤、亏损、来源陈旧或退出流动性风险触发冷却。
- `RETIRED`: 多轮冷却或长时间无新鲜来源后淘汰。

## 评分公式

当前版本为 `research-copyability-v1`。总分满分 100，分项权重如下：

- profit signal 20、repeatability 15、liquidity fit 10、entry price fit 10、slippage risk 10。
- drawdown risk 10、holding period fit 5、market type risk 5、exit liquidity risk 5、data freshness 5、filter pass rate 5。

缺数据会保守扣分：没有纸跟 session 或报价 UNKNOWN 时，不会把未知估值当成亏到 0，但会降低 liquidity、slippage、exit liquidity 等分项。样本不足 10 笔时总分 cap 到 59，不能进入 `TRIAL_READY`。

## 来源与自动发现

当前启用四类来源：

- watchlist: `system_config.config_key = leader_research.watchlist`，值可以用逗号、空格、换行分隔钱包地址。
- existing leader: 已在 Leader 管理里的地址。
- activity-derived: `leader_activity_event` 里已经持久化、可归因、较新的活动事件。
- public leaderboard: Polymarket Data API leaderboard 公开来源，默认保守限量拉取并写入 source evidence、source rank 和 source health。

public leaderboard 不等于“全网无遗漏发现”。它只是第一版外部候选源：source disabled、timeout、rate limit、empty result、parse failure 都会进入 source health 和 run 结果，避免把外部失败伪装成研究成功。

## Shortlist 分组

- `readyToTrial`: 已达到 `TRIAL_READY`，可以创建禁用试跟；如果 Autopilot 已打开且风控通过，也可以创建小额真钱试跟。
- `promisingPaper`: 纸跟表现接近达标，但样本、PnL、回撤或估值置信度还不够，只能继续观察。
- `newCandidates`: 新发现的候选，需要先进入纸跟或积累 activity。
- `blockedOrCooling`: 分数或来源不错，但被 locked、cooling、retired、UNKNOWN/UNAVAILABLE valuation、风险旗标或 Autopilot 风控阻断。

## Autopilot 与真钱开关

- Autopilot 账户默认 `OFF`，全局 kill switch 默认关闭真钱动作。
- 用户不开开关时，Leader Research 不会自动创建 `enabled=true` 配置，也不会自动下真钱单。
- 用户打开 Autopilot 后，系统只允许 `TRIAL_READY` 且风控通过的候选创建小额 `AUTOPILOT` 配置。
- 后端所有真钱动作必须经过 `LeaderAutopilotDecisionService`，覆盖 `CREATE_CONFIG`、`ENABLE_CONFIG`、`BUY`、`SELL`、`RESUME` 和 `CONVERT_TO_MANUAL`。
- 风控会检查账户预算、单 leader 上限、日亏损、每日订单数、最大仓位、价格范围、候选状态、暂停状态和 kill switch。
- `PAUSED` 或 kill switch 下拒绝新 BUY 和自动启用，但允许 reduce-only SELL 减少既有 Autopilot 仓位。
- 关闭 Autopilot 只暂停 `management_mode=AUTOPILOT` 的配置，不影响用户手动创建的 `MANUAL` 配置。
- 转手动管理必须显式确认，并记录审计事件。

Autopilot 不是盈利保证。这里的“稳健增长”定义为：自动发现更可能优秀的 leader，先模拟/小额试跟，在预算和亏损阈值内执行，并在数据质量或真实表现恶化时拒绝、暂停或降级。

## Watchlist 兜底

watchlist 是手动补种来源，不代表外部自动发现已经启用或稳定。可以通过 API 预览和保存钱包列表，系统会区分有效、无效、重复、已存在、locked 和 retired 地址；保存后下一次 research run 会把它作为兜底来源。

## 运维与开关

- 定时任务默认关闭：`leader.research.enabled=false`。
- 手动运行不依赖定时开关，可以在「Leader 研究」页面点击「立即运行研究」。
- 全局 activity capture 默认关闭：`leader.research.global-capture.enabled=false`。
- 全局 capture 写入上限：`leader.research.global-capture.max-writes-per-minute=120`。
- Data API bounded backfill 每个钱包默认最多拉取 `leader.research.data-api-backfill.limit=200` 条，最多处理 50 个钱包。
- public leaderboard 开关：`leader.research.public-leaderboard.enabled` 默认关闭；上线前需要运维显式设置为 `true` 才会拉取外部 discovery。关闭时不影响历史候选和页面读取，source health 会展示 disabled/限制原因。
- Autopilot 全局 kill switch：`leader.autopilot.global-kill-switch=true` 会阻断自动启用和新 BUY，仅保留 reduce-only SELL。
- kill switch: 关闭 `leader.research.enabled` 可停止自动推进；关闭 `leader.research.global-capture.enabled` 可停止地址过滤前的全局事件捕获；前端入口可以隐藏但历史研究数据仍可读。
- 性能验证脚本：`scripts/leader-research-perf-check.sql` 可在一次性测试库中生成 100 个候选、1 万条 activity event 和 1 万条 paper trade，并对热查询执行 `EXPLAIN`。

## 保留与归档策略

- `leader_autopilot_decision_event` 是真钱决策审计账本，应至少保留一个完整风控复盘周期。第一版建议按月归档到冷存储或审计表分区，生产清理前必须确认对应订单、候选和 copyTradingId 仍可追溯。
- `leader_autopilot_risk_reservation` 是预算预留 ledger。`PENDING` 预留由定时清理任务过期释放；`FINALIZED`、`RELEASED`、`EXPIRED` 记录可按 90 天滚动保留，超期归档前保留 reservationKey、accountId、copyTradingId、leaderTradeId、amount、status 和 orderId。
- `leader_discovery_shortlist_snapshot` 用于解释历史推荐，不是永久事实表。建议保留 30-90 天，或只保留每日 Top snapshot；清理不应删除 `leader_research_candidate`、paper session、decision event 或真钱订单记录。
- source evidence 和 input snapshot 都只保存解释字段和脱敏摘要，不保存 API key、wallet secret、session token、private key 或完整交易凭证。

## 排查

- source health 显示 `DISABLED`: 检查 public leaderboard、watchlist 或 global capture 是否未启用。
- source health 显示 `FAILURE`: 查看 recent research events 中的 `SOURCE_FAILURE`。
- 估值为 `UNKNOWN` 或 `UNAVAILABLE`: 代表无法确认当前市场价格，不会被当成亏到 0，但可以阻断或暂停 Autopilot。
- 重复 activity event: 依赖 `stable_event_key` 和 `leader_paper_trade(session_id, leader_trade_id, side)` 去重。
- 重复审批: 同账户同 leader 已有配置时会拒绝，不会创建第二条真钱或试跟配置。

## 安全边界

- 研究状态和 Leader Pool 状态分离。
- `TRIAL_READY` 只是推荐 badge，不代表真钱跟单已启用。
- 审批接口默认只创建禁用配置；只有用户显式打开 Autopilot 开关且后端风控 `ALLOW` 时才会创建 `enabled=true` 的小额真钱配置。
- 纸跟账本使用 `leader_paper_session`、`leader_paper_trade`、`leader_paper_position`，不写入真钱订单跟踪表。
- `UNKNOWN` 估值不会被当成 confirmed zero 计入收益。
- Autopilot 审计事件会脱敏输入快照，不保存 API key、wallet secret、session token 或交易凭证。
