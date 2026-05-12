## 1. Discovery Source Spike 与契约确认

- [x] 1.1 调研 Polymarket 可用的外部候选来源，至少验证 leaderboard、trader search、Data API 排行榜或等价公开来源中的一个可稳定返回钱包地址。
- [x] 1.2 保存 source contract 文档，记录 endpoint、请求参数、分页方式、排序字段、返回字段、限流表现、失败码和样例 payload。
- [x] 1.3 用脚本或临时命令拉取 10-50 个候选钱包样本，检查是否包含钱包、交易数、收益或排名、近 7/30 天活跃等可用于 intake 的字段。
- [x] 1.4 对样本做离线质量检查，确认候选是否有足够 activity 可用于 paper trading，排除明显无效、重复、机器人或无法归因的钱包。
- [x] 1.5 明确第一版 source enum 策略：复用 `PUBLIC_LEADERBOARD` 还是新增 `EXTERNAL_DISCOVERY`，并在设计中记录选择理由。
- [x] 1.6 在 source contract 验证前，不实现或启用正式 production discovery client；如果 spike 发现 Polymarket source 不稳定，先保留 source failure/disabled 可观测能力并记录替代来源选择。

## 2. 后端数据模型与配置

- [x] 2.1 新增或扩展 Flyway 迁移，支持保存 discovery source stats、source evidence、source rank、intake exclusion reason、shortlist snapshot 或 watchlist metadata。
- [x] 2.2 为新增字段或表添加必要索引，覆盖 source type、source rank、research state、last source seen、shortlist group 和候选查询热路径。
- [x] 2.3 增加外部 discovery 配置项，包括 enabled kill switch、每次运行候选上限、分页上限、超时、限流退避和最小质量阈值。
- [x] 2.4 确保新增配置默认保守，生产环境可以关闭外部 discovery 且不影响已有 Leader Research 历史数据读取。
- [x] 2.5 如果 watchlist 从 `system_config` 字符串迁移到结构化表，增加兼容读取和一次性迁移逻辑；如果不迁移，补充长度、格式和审计限制。
- [x] 2.6 新增 Autopilot 用户偏好、账户级风险预算、暂停状态、kill switch 状态、真钱动作审计和实盘反馈摘要所需的字段或表。
- [x] 2.6.0 新增账户级 Autopilot 状态字段或表，状态只能为 `OFF`、`ON`、`PAUSED`；全局 kill switch 作为覆盖态，不直接改写账户状态。
- [x] 2.6.1 扩展 `copy_trading`，新增 `management_mode`（`MANUAL`/`AUTOPILOT`）、`autopilot_policy_id`、`autopilot_paused_reason` 和 `autopilot_last_decision_at`，用于区分用户手动配置和 Autopilot 管理配置。
- [x] 2.6.2 为既有 `copy_trading` 记录迁移默认 `management_mode=MANUAL`，确保上线后关闭 Autopilot 不会误停历史手动配置。
- [x] 2.6.3 新增数据库级幂等保护，使用 MySQL 唯一约束或幂等表保证同一 `account_id + leader_id + management_mode=AUTOPILOT` 最多有一条可管理配置；并发冲突时返回已有配置或明确重复结果。
- [x] 2.6.4 新增 Autopilot 相关 enum 或等价强类型定义：`AutopilotAccountState`、`CopyTradingManagementMode`、`AutopilotActionType`、`AutopilotDecision` 和 `AutopilotPauseReason`，避免核心分支依赖散落字符串。
- [x] 2.6.5 新增 `leader_autopilot_decision_event` 或等价结构化审计表，保存 action type、decision、reason code、accountId、candidateId、leaderId、copyTradingId、reservationId、policy version、脱敏输入快照和 createdAt。
- [x] 2.6.6 新增账户级 Autopilot 风险预留表或 ledger，支持 BUY 前预留、订单成功确认、订单失败/过滤释放、幂等 token 和过期清理。
- [x] 2.6.7 明确 Autopilot 每日风险窗口，第一版默认使用 UTC 自然日，并让订单数、日亏损和预算预留使用同一窗口。
- [x] 2.7 增加 Autopilot 配置项，包括全局 enabled kill switch、默认关闭、默认小额 fixed amount 上限、账户预算上限、单 leader 上限、日亏损阈值、最大订单数、最大仓位和自动暂停阈值。
- [x] 2.8 确保 Autopilot 相关配置默认不启用真钱，生产环境可以关闭 Autopilot 且不影响已有手动 copy-trading 配置读取。

## 3. 外部 Discovery Client

- [x] 3.1 新增外部 Polymarket discovery client，封装请求、分页、超时、错误分类、限流退避和响应解析。
- [x] 3.2 将外部 source 响应转换为统一 discovery candidate DTO，包含 wallet、source rank、source stats、source evidence 和原始摘要。
- [x] 3.3 对超时、限流、认证失败、解析失败、空结果和部分字段缺失返回 typed result，不得用异常吞掉真实失败。
- [x] 3.4 为 discovery client 增加单元测试，覆盖成功、空结果、限流、解析失败、字段缺失和分页终止。
- [x] 3.5 增加 bounded backoff 或短期缓存，避免手动连续运行研究时打爆外部 source。

## 4. Candidate Intake 与质量门

- [x] 4.1 新增 `LeaderDiscoveryIntakeService` 或等价服务，统一处理钱包标准化、去重、source rank、source evidence 和候选 upsert。
- [x] 4.2 实现 locked、retired、blacklisted、已有启用 copy-trading config、无效钱包、交易数不足、近期无活动和缺关键字段的排除规则。
- [x] 4.3 将 intake 通过和排除结果写入 research event 或候选证据字段，确保候选详情和 source health 可解释。
- [x] 4.4 保证 `DISCOVERED` 外部候选不会创建或更新 Leader 池项，只有 `CANDIDATE+` 且未锁定候选才允许同步。
- [x] 4.5 为 intake 增加并发安全测试，覆盖同一钱包在多个 source 或多次运行中最多创建一个候选。
- [x] 4.6 为 locked/retired/blacklisted 候选增加回归测试，证明自动 source 只能追加只读证据，不会改写人工决策字段。

## 5. Source Health 与 Research Run 集成

- [x] 5.1 将外部 discovery source 接入 `LeaderResearchSourceService.discoverCandidates` 和 preview 路径。
- [x] 5.2 让外部 source 的成功、空结果、disabled、degraded 和 failure 正确写入 source health。
- [x] 5.3 更新 research run sourceCounts 和 partialFailure 逻辑，确保未预期的外部 source failure 不会被标记为成功。
- [x] 5.4 保留 configured disabled 作为 expected limitation，并在页面和 run record 中与真实 failure 区分。
- [x] 5.5 增加 source health 测试，覆盖外部 source 成功、空结果、限流、失败、禁用和恢复成功。
- [x] 5.6 增加 run overlap 测试，确保外部 discovery 慢请求不会导致并发运行污染 run 状态。

## 6. Shortlist 后端 API

- [x] 6.1 新增 shortlist DTO，覆盖分组、候选卡片、推荐理由、证据指标、风险/阻断原因、建议配置、CTA、更新时间和空态原因。
- [x] 6.2 新增 `LeaderDiscoveryShortlistService`，按 `readyToTrial`、`promisingPaper`、`newCandidates`、`blockedOrCooling` 生成 Top 3-10。
- [x] 6.3 定义 shortlist 排序规则，优先 `TRIAL_READY`、纸跟 PnL 为正且样本接近达标、source rank 高且新鲜、风险可解释的候选。
- [x] 6.4 实现无推荐原因聚合，区分外部 source 不可用、样本不足、纸跟亏损、全部冷却、UNKNOWN valuation、intake 全部过滤。
- [x] 6.5 新增受保护 API `POST /api/copy-trading/leader-research/shortlist` 或等价路径。
- [x] 6.6 确保 shortlist API 使用批量查询组装 leader、pool、paper session、score、events 和 source evidence，避免候选列表 N+1 查询。
- [x] 6.7 为 shortlist service 增加单元测试，覆盖四个分组、排序、Top 上限、空态原因和 locked/retired 排除。
- [x] 6.8 为 shortlist controller 增加测试，覆盖成功、无推荐、source failure 摘要和权限/参数错误。

## 7. Autopilot 模式与 Approval 安全复用

- [x] 7.1 新增 Autopilot 用户级模式读取/更新 API，支持 `off`、`on`、`paused` 和全局 kill switch 展示。
- [x] 7.1.1 实现账户级 Autopilot 状态机：`OFF --user_enable_with_budget--> ON`、`ON --risk_pause/system_pause--> PAUSED`、`ON --user_disable--> OFF`、`PAUSED --user_resume_confirm--> ON`、`PAUSED --user_disable--> OFF`。
- [x] 7.1.2 确保全局 kill switch 覆盖所有账户状态，但解除 kill switch 不会自动恢复 `PAUSED` 账户或配置，恢复必须用户确认并重新通过 decision service。
- [x] 7.1.3 为所有 Autopilot status/update/enable/disable/resume/convert-to-manual/shortlist CTA API 增加 account ownership 校验，拒绝跨账户 accountId、copyTradingId 或 candidateId 操作。
- [x] 7.2 Autopilot 默认关闭；关闭时 shortlist 的创建试跟 CTA 继续复用现有 `LeaderResearchApprovalService.createDisabledTrialConfig` 或同等 disabled-only helper。
- [x] 7.3 打开 Autopilot 时要求用户确认账户级最大预算、单 leader fixed amount 上限、每日最大亏损、每日最大订单数、最大仓位、价格范围和自动暂停条件。
- [x] 7.4 新增 `LeaderAutopilotDecisionService`，作为所有 Autopilot 钱真动作的唯一闸门，返回 `ALLOW`、`DENY` 或 `PAUSE`，并封装 policy、risk、kill switch、pause 和 audit 决策。
- [x] 7.4.1 将 `LeaderAutopilotDecisionService` 接入 shortlist/approval 自动启用路径，任何 `enabled=true` 小额试跟配置创建前都必须先得到 `ALLOW`。
- [x] 7.4.2 将 `LeaderAutopilotDecisionService` 接入 `CopyTradingService.createCopyTrading` 和 `CopyTradingService.updateCopyTrading`，防止 Autopilot 管理配置通过普通配置 API 绕过预算和 kill switch。
- [x] 7.4.3 实现 Autopilot 配置转手动管理 API 或 service 方法，要求用户显式确认，并记录 `AUTOPILOT_CONFIG_CONVERTED_TO_MANUAL` 审计事件。
- [x] 7.4.4 让 `LeaderAutopilotDecisionService` 的请求显式包含 action type（`CREATE_CONFIG`、`ENABLE_CONFIG`、`BUY`、`SELL`、`RESUME`、`CONVERT_TO_MANUAL`），并按 action type 执行不同安全语义。
- [x] 7.4.5 实现 PAUSED/kill switch 下的 reduce-only SELL 语义：拒绝新 BUY、自动启用和恢复，但允许减少或关闭既有 Autopilot 仓位的卖出继续执行。
- [x] 7.4.6 所有 `ALLOW`、`DENY`、`PAUSE`、预留、释放、恢复和转手动都写入结构化审计事件，且输入快照必须脱敏，不保存 API key、wallet secret、session token 或交易凭证。
- [x] 7.5 后端继续强制候选必须为 `TRIAL_READY` 且未 locked/cooling/retired/blacklisted，才能从 shortlist 创建任何试跟配置。
- [x] 7.6 Autopilot 开启且风控通过时，允许 shortlist 或后台研究创建 `enabled=true` 的小额试跟配置；关闭或风控失败时只能创建 `enabled=false` 配置或拒绝。
- [x] 7.7 后端拒绝或忽略放大 fixed amount、放宽 max daily loss、放宽 price range、超预算、绕过暂停或绕过 kill switch 的字段。
- [x] 7.8 记录 shortlist 创建禁用配置、Autopilot 创建启用配置、风控拒绝和暂停事件，包含 candidateId、accountId、leaderId、copyTradingId、Autopilot 模式、风控输入、结论或失败原因。
- [x] 7.9 增加安全测试，证明 discovery、shortlist 刷新、watchlist 导入和 source run 在 Autopilot 关闭时不会自动创建或启用真实跟单配置。
- [x] 7.10 增加安全测试，证明 Autopilot 开启时只有 `TRIAL_READY` 且风控通过的候选才能创建 `enabled=true` 小额试跟配置。
- [x] 7.11 增加重复审批测试，证明同账户同 leader 并发点击 shortlist CTA 或后台 Autopilot 并发运行最多创建一条配置。
- [x] 7.12 增加数据库冲突回归测试，证明唯一约束或幂等表捕获并发重复创建，不依赖 service 层先查后写作为唯一保护。

## 8. Autopilot 决策服务、风控组件与真实反馈闭环

- [x] 8.1 新增 `LeaderAutopilotRiskController` 或等价内部组件，由 `LeaderAutopilotDecisionService` 调用，在自动启用、复制订单、恢复暂停配置前统一检查账户预算、单 leader 上限、日亏损、订单数、仓位、价格范围和 kill switch。
- [x] 8.2 将 Autopilot 风控闸门接入现有 copy-trading 创建/启用路径，确保任何自动真钱配置都无法绕过后端预算检查。
- [x] 8.3 将 `LeaderAutopilotDecisionService` 接入 `CopyOrderTrackingService.processBuyTrade`，确保每次真实买入订单创建前都重新检查 Autopilot 模式、候选状态、预算、亏损、订单数、仓位和 kill switch。
- [x] 8.3.1 在真实 BUY 前创建账户级风险预留，预留内容至少覆盖 accountId、copyTradingId、leaderId、tradeId 或 idempotency key、预算金额、当日订单额度和 UTC 风险窗口。
- [x] 8.3.2 订单创建成功后确认预留；订单被过滤、拒绝、失败或异常时释放预留；重复回调必须通过幂等 token 避免重复确认或重复释放。
- [x] 8.3.3 将现有 `CopyOrderTrackingService` 内部日订单/日亏损检查收敛到 decision/risk 组件，避免 Autopilot 和普通 copy-trading 出现两套不一致风控。
- [x] 8.4 实现自动暂停逻辑，覆盖日亏损、最大回撤、连续亏损、拒单率、source stale、quote unavailable 和 position unavailable。
- [x] 8.5 新增实盘反馈采集或汇总逻辑，将真实订单、成交、拒单、PnL、费用、滑点和 UNKNOWN/UNAVAILABLE valuation 关联到候选、leader、account 和 copyTradingId。
- [x] 8.6 用真实反馈更新 shortlist 和候选状态：表现弱于 paper 时降级/冷却/暂停，表现稳定时保持小额试跟但不自动放大金额。
- [x] 8.7 增加风控单元测试，覆盖预算不足、候选状态不达标、cooling/locked、全局 kill switch、日亏损、回撤、拒单率和 quote/position unavailable。
- [x] 8.8 增加反馈闭环测试，证明真实 PnL 恶化会降低推荐置信度或暂停 Autopilot，且稳定表现不会自动加仓或放宽风控。
- [x] 8.9 增加并发风险预留测试，证明多个 leader trade 同时触发同一账户 BUY 时不会超过账户预算、单 leader 上限、每日订单数或每日亏损阈值。
- [x] 8.10 增加 PAUSED/kill switch reduce-only SELL 测试，证明暂停状态拒绝新 BUY 但允许减少或关闭既有 Autopilot 仓位的卖出。

## 9. Watchlist 兜底管理

- [x] 9.1 新增 watchlist 读取 DTO/API，返回当前钱包、备注或标签、来源说明、最近更新时间和当前配置来源。
- [x] 9.2 新增 watchlist preview API，支持批量粘贴钱包并返回有效、无效、重复、已存在、locked、retired 和 blacklisted 明细。
- [x] 9.3 新增 watchlist 保存 API，校验权限、格式、数量上限和重复项，并记录操作日志或 research event。
- [x] 9.4 将 watchlist 保存结果接入现有 watchlist source，确保下一次 research run 可以发现这些钱包。
- [x] 9.5 增加 watchlist API 测试，覆盖 preview、保存、无效地址、重复地址、上限、locked/retired 提示和审计记录。
- [x] 9.6 文案明确 watchlist 是手动补种来源，不代表外部自动发现已经启用。

## 10. 前端 API、类型与状态

- [x] 10.1 在 `frontend/src/types/index.ts` 增加 shortlist、shortlist card、empty reason、evidence metric、watchlist item、watchlist preview、Autopilot mode、Autopilot risk budget 和 Autopilot action event 类型。
- [x] 10.2 在 `frontend/src/services/api.ts` 增加 `leaderResearch.shortlist`、`leaderResearch.watchlist`、`leaderResearch.previewWatchlist`、`leaderResearch.saveWatchlist`、`leaderResearch.autopilotStatus` 和 `leaderResearch.updateAutopilot` API。
- [x] 10.2.1 为 Autopilot enable、disable、resume、convert-to-manual 和 shortlist CTA API 处理 401/403/409/422 错误，确保权限失败、并发冲突、风控拒绝和预算不足不会被前端显示成成功。
- [x] 10.3 更新 i18n 文案，覆盖今日推荐、暂无推荐原因、外部 source 不可用、样本不足、风险阻断、手动补种、后台运行中、模拟模式、Autopilot 开启、Autopilot 暂停和真钱风险提示。
- [x] 10.4 修正 `leaderResearch.runStarted` 中文文案，从“研究运行完成”改为“研究已开始，后台运行中”或等价异步提示。
- [x] 10.5 确保新增前端类型不使用 `any` 绕过关键风控字段。

## 11. Leader Research 首屏改造

- [x] 11.1 在 `LeaderResearch.tsx` 顶部新增今日推荐区域，优先展示 shortlist、Autopilot 当前模式和下一步动作，再展示运行状态和 source health。
- [x] 11.2 实现 Autopilot 模式条，展示关闭/模拟、开启真钱、暂停和 kill switch 状态，以及账户预算、单 leader 上限和暂停原因。
- [x] 11.3 实现 Autopilot 开关确认流程，开启前展示真钱风险、预算输入、默认小额配置、暂停条件和“不会自动加仓/放宽风控”的说明。
- [x] 11.4 实现 `readyToTrial` 卡片，展示钱包、来源、推荐理由、纸跟 PnL、交易数、回撤、过滤比例、UNKNOWN 暴露、建议配置和当前模式下的 CTA。
- [x] 11.5 Autopilot 关闭时，`readyToTrial` CTA 创建禁用试跟配置；Autopilot 开启且风控通过时，CTA 或后台可创建小额真钱试跟配置。
- [x] 11.6 实现 `promisingPaper` 和 `newCandidates` 卡片，明确展示“仍在观察/样本不足”，不得出现真钱跟单 CTA。
- [x] 11.7 实现 `blockedOrCooling` 卡片或折叠区，展示高分但被阻断候选的风险原因和 Autopilot 拒绝原因。
- [x] 11.8 实现无推荐空态，按后端返回原因展示 source 不可用、样本不足、亏损、冷却、UNKNOWN valuation、全部过滤或 Autopilot 风控阻断。
- [x] 11.9 将运行状态、source counts、candidate counts 等诊断信息下沉，不再占据首屏主视觉。
- [x] 11.10 继续保留候选表、详情抽屉、纸跟交易、纸跟仓位和事件列表，并确保 shortlist 卡片可跳转到候选详情。
- [x] 11.11 对移动端布局做适配，确保推荐卡片、Autopilot 开关、CTA 和风险说明在窄屏可读。

## 12. Watchlist 前端管理

- [x] 12.1 在 Leader Research 页或系统管理页新增 watchlist 管理入口。
- [x] 12.2 实现批量粘贴输入、preview 结果展示、有效/无效/重复/已存在/locked/retired 分类提示。
- [x] 12.3 实现确认保存流程，保存前展示将新增、保留、忽略和拒绝的地址数量。
- [x] 12.4 保存成功后刷新 watchlist 和 source health，并提示用户可以立即运行研究。
- [x] 12.5 空态和帮助文案明确 watchlist 是兜底，不是自动发现主路径。

## 13. 通知与事件摘要

- [x] 13.1 扩展 research event 或通知摘要，记录外部候选发现、intake 过滤、shortlist 新增、shortlist 阻断、source failure、Autopilot 开启/关闭、自动启用、风控拒绝和自动暂停。
- [x] 13.2 对 `readyToTrial` 新增、Top 推荐变化、Autopilot 自动启用和自动暂停生成去重事件，避免每次 research run 重复刷屏。
- [x] 13.3 页面展示最近 shortlist/research/Autopilot 事件，并提供跳转到候选详情、source health、风险设置或 copy-trading 配置的入口。
- [x] 13.4 增加事件去重测试，覆盖同一候选同一分组在短时间内重复运行不会重复通知，同一风控暂停不会重复刷屏。

## 14. 性能、限流与保留策略

- [x] 14.1 为外部 discovery 设置每次运行候选上限、分页上限、HTTP timeout、处理 batch size 和运行时间上限。
- [x] 14.2 为 shortlist 查询增加批量加载或聚合查询，避免 50+ 候选时出现 N+1 查询。
- [x] 14.3 为 Autopilot 风控检查使用聚合查询、批量加载或账户风险快照，避免每个候选/订单全量加载历史订单、成交和仓位。
- [x] 14.3.1 为当日订单数、当日已实现/未实现亏损、当前仓位、已预留预算和剩余预算提供 repository 聚合查询，禁止在真钱交易热路径全量读取订单后内存过滤。
- [x] 14.3.2 为 Autopilot risk reservation 增加过期清理和卡住预留告警，防止失败订单长期占用预算。
- [x] 14.4 更新或新增性能脚本，覆盖 100+ 外部候选、1 万 activity event、1 万 paper trade、100 个 Autopilot managed config 下的 source intake、shortlist、候选列表和风控检查查询。
- [x] 14.5 确认新增 discovery/shortlist/Autopilot feedback 数据遵守现有 retention 策略，避免 source evidence、snapshot 或真钱反馈摘要无限增长。
- [x] 14.5.1 为 `leader_autopilot_decision_event`、风险预留 ledger 和 shortlist snapshot 制定保留或归档策略，确保审计足够可追溯但不会无限增长。
- [x] 14.6 增加外部 source 限流和失败退避测试，证明连续手动运行不会打爆 source 或长时间占用 research runner。

## 15. 文档与运维

- [x] 15.1 更新 `docs/zh/leader-research-agent.md`，说明外部 discovery source、shortlist 分组、无推荐原因、watchlist fallback、Autopilot 模式和真钱风险边界。
- [x] 15.2 更新 Leader 池文档，强调外部发现候选不会直接进入真实试跟，`TRIAL_READY` 仍不等于 `TRIAL/ACTIVE`，Autopilot 启用还需要用户开关和风控通过。
- [x] 15.3 新增运维说明，覆盖外部 source enabled 开关、Autopilot global kill switch、限流配置、source health 排查、风控暂停排查、回滚开关和 dry-run 验证。
- [x] 15.4 清理或更新“public leaderboard intentionally disabled in v1”相关文案，避免与新能力冲突。
- [x] 15.5 在文档中明确 quote/valuation UNKNOWN 或 UNAVAILABLE 不等于 confirmed zero，不得被当成真实亏损，但可以触发 Autopilot 暂停。
- [x] 15.6 在文档中明确系统不能保证盈利，“稳健增长”定义为风险阈值内的自动发现、受限小额试跟、真实反馈和自动暂停闭环。

## 16. 后端测试与安全回归

- [x] 16.1 运行并补齐外部 discovery client、source service、intake service、shortlist service、watchlist service、approval safety、Autopilot decision service、risk component 和 feedback loop 单元测试。
- [x] 16.2 运行 `./gradlew test --tests "*LeaderDiscovery*" --tests "*LeaderResearch*" --tests "*LeaderPool*" --tests "*CopyTrading*" --tests "*Autopilot*"` 或等价后端测试组合。
- [x] 16.3 如果本机没有 Java Runtime，先安装/切换 JDK 或在可运行 Java 的环境执行后端测试，不得只勾选未运行的测试任务。
- [x] 16.4 增加回归测试，证明外部 source failure 不会删除候选、不会降级 locked 候选、不会把 run 伪装成成功。
- [x] 16.5 增加回归测试，证明 UNKNOWN/UNAVAILABLE valuation 不会被当成 confirmed zero 影响 shortlist 推荐，但会按配置阻断或暂停 Autopilot。
- [x] 16.6 增加回归测试，证明 Autopilot 关闭时不会自动启用真钱，Autopilot 开启时不会绕过预算、暂停或 kill switch。
- [x] 16.7 新增 `LeaderAutopilotDecisionServiceTest`，覆盖 OFF/ON/PAUSED/kill switch、action type、候选状态、预算、daily window、quote unavailable、source stale、ALLOW/DENY/PAUSE 和结构化审计。
- [x] 16.8 新增 Autopilot 状态机测试，证明 risk pause/system pause 后不会自动恢复，kill switch 解除也不会恢复，只有用户确认 resume 且重新通过 decision service 才能回到 ON。
- [x] 16.9 新增 Autopilot account ownership/controller 测试，覆盖跨账户读取、更新、resume、disable、convert-to-manual 和 shortlist CTA 全部被拒绝。
- [x] 16.10 新增 Autopilot 配置管理归属测试，证明关闭 Autopilot 只影响 `AUTOPILOT` 配置，不影响 `MANUAL` 配置，且转手动会记录审计。
- [x] 16.11 新增 CopyOrderTracking Autopilot 测试，证明 BUY 前必须调用 decision service，DENY/PAUSE 不会触发真实 CLOB 下单，ALLOW 会先创建风险预留。
- [x] 16.12 新增风险预留并发测试，证明重复 trade、并发 BUY、订单失败释放和重复回调都不会导致预算超占或重复释放。
- [x] 16.13 新增 reduce-only SELL 测试，证明 PAUSED 或 kill switch 状态下仍允许减少既有 Autopilot 仓位，但拒绝新增暴露。
- [x] 16.14 新增结构化审计脱敏测试，证明 API key、wallet secret、session token 和交易凭证不会进入 decision event payload。

## 17. 前端验证与 QA

- [x] 17.1 运行前端 TypeScript 构建，确保新增类型、API 和页面组件通过编译。
- [x] 17.2 运行前端 lint，修复新增页面和文案相关问题。
- [x] 17.3 使用浏览器 QA 验证 Leader Research 首屏：有推荐、无推荐、source failure、样本不足、blocked/cooling、Autopilot 关闭、Autopilot 开启、Autopilot 暂停、权限错误、风控拒绝、并发冲突和移动端布局。
- [x] 17.4 验证 shortlist 创建禁用试跟配置或 Autopilot 小额试跟弹窗展示真实建议参数，不写死金额、订单数或价格范围。
- [x] 17.5 验证双击 shortlist CTA、连续切换 Autopilot 和后台 run 重叠不会创建重复配置，失败时不假更新状态。
- [x] 17.6 验证 watchlist preview/save 流程、无效地址提示、重复地址提示和保存后立即运行研究入口。
- [x] 17.7 验证用户关闭 Autopilot 后页面不再显示真钱自动启用 CTA，并展示模拟/禁用模式。
- [x] 17.8 如果项目加入前端 E2E 测试框架，新增最小 E2E 覆盖：Autopilot 关闭创建 disabled 配置、开启前确认预算、风控拒绝展示原因、PAUSED 状态禁用新 BUY CTA、双击 CTA 不重复创建。（项目当前未接入前端 E2E 框架，已用浏览器 QA 覆盖同等路径）

## 18. 部署验证与收口

- [ ] 18.1 在本地或测试环境用外部 source dry-run 验证候选数、source health、intake 过滤、shortlist 分组和无推荐原因。
- [x] 18.2 在 Mac mini 部署前确认外部 source kill switch、Autopilot kill switch、默认关闭状态、环境变量和限流配置。
- [ ] 18.3 部署后手动运行一次研究，确认 sourceCounts 中至少有外部 discovery source 候选数或明确 disabled/failure 原因。
- [ ] 18.4 部署后 QA `/leader-research`，确认用户第一眼能看到今日推荐、Autopilot 当前模式或具体无推荐原因，而不是只有运行 JSON。
- [ ] 18.5 确认 Autopilot 关闭时没有任何新流程自动启用真钱跟单，数据库中从 shortlist 创建的配置 `enabled=false`。
- [ ] 18.6 在测试账户显式打开 Autopilot 后验证只有 `TRIAL_READY` 且风控通过的候选会创建 `enabled=true` 小额试跟配置，并能被 kill switch 或亏损阈值暂停。
- [x] 18.7 验收通过后再评估是否同步 specs 并 archive `add-leader-pool`、`add-leader-research-agent` 和 `leader-discovery-shortlist`。（评估结论：本 change 尚有真实测试/部署环境 dry-run 未完成，暂不 sync/archive）
