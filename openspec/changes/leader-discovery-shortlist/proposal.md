## Why

当前 `Leader 池` 和 `Leader 研究` 已经能管理已有 leader、纸跟、评分和禁用试跟审批，但它们仍像“已知地址研究台”，没有真正替用户自动发现新的优秀 leader。用户的真实诉求是：系统每天主动发现值得研究/试跟的 leader，默认先模拟观察；当用户显式打开真钱 Autopilot 开关后，系统能在硬风控下自动小额跟单，并通过真实表现持续调节、暂停或淘汰 leader，目标是风险受控的资产增长。

现在需要把 Leader Research 从状态机控制台纠偏为 discovery-first 的推荐和 Autopilot 产品：先补真实外部候选供给，再把结果组织成“今日推荐” shortlist；真钱模式默认关闭，开启后也只能按账户预算、单 leader 限额、亏损暂停和 kill switch 执行受限实盘试跟，而不是让用户自己从 source JSON、状态表和纸跟明细里推理或手动启用。

## What Changes

- 新增真实外部 discovery source，用于从 Polymarket leaderboard、trader search、Data API 排行榜或等价公开来源拉取候选钱包，并进入现有 research candidate 管线。
- 新增 discovery source contract 和 source health 语义，记录 endpoint、字段、分页、限流、失败类型、候选数、source rank 和样例 evidence。
- 新增候选 intake 质量门：去重、排除 locked/retired/已有 active config/黑名单候选、保存 source stats，并防止原始 `DISCOVERED` 候选直接污染 Leader 池。
- 新增 shortlist 决策 API，把候选按 `readyToTrial`、`promisingPaper`、`newCandidates`、`blockedOrCooling` 分组，返回 Top 3-10 推荐卡片所需的理由、证据、风险、建议配置和 Autopilot 可执行性。
- 改造 Leader Research 页面首屏，把“今日推荐/暂无推荐原因”放到运行状态和候选表之前，降低用户读状态机的负担。
- 新增用户级真钱 Autopilot 开关。关闭时系统只做外部发现、模拟/纸跟、推荐和禁用配置；打开时系统 MAY 对 `TRIAL_READY` 候选创建并启用小额真钱试跟配置。
- 新增 `LeaderAutopilotDecisionService` 作为所有真钱自动动作的唯一后端闸门，在任何真钱启用、复制买入或恢复前强制检查账户预算、单 leader 上限、每日最大亏损、每日最大订单数、最大仓位、价格范围、候选状态、冷却状态、资金预留和 kill switch。
- 新增真实跟单反馈闭环，用实盘 PnL、回撤、拒单、滑点或未知估值更新候选状态，达到亏损或异常条件时自动暂停或降级。
- 新增结构化真钱决策审计和账户级风险预留，保证自动跟单既能追溯每次允许/拒绝/暂停原因，也不会在并发订单下超过用户预算。
- 增强空态和 source limitation 文案，让用户知道没有推荐是因为外部 source 不可用、样本不足、纸跟亏损、全部冷却还是安全阈值阻断。
- 补充 watchlist 管理作为外部 source 不稳定时的兜底入口，支持批量导入、校验、去重和 dry-run preview。
- 保持真钱安全边界：默认不自动启用真钱；只有用户打开 Autopilot 开关后，系统才允许自动创建并启用受限小额试跟；不得自动加仓、不得自动放宽风控、不得绕过 existing approval safety 和审计。
- 不归档 `add-leader-pool` 和 `add-leader-research-agent`，直到本 change 验收并确认用户诉求已经闭环。

## Capabilities

### New Capabilities

- `leader-discovery-shortlist`: 自动发现外部 leader 候选、进入 paper-first 研究管线，向用户展示可执行的今日推荐 shortlist，并在用户显式开启 Autopilot 后执行受限小额真钱试跟。

### Modified Capabilities

无。

## Impact

- 后端会影响 `LeaderResearchSourceService`、source health、candidate intake、research run 统计、候选 DTO/API、Leader Research controller/service/mapper，以及可能新增外部 Polymarket discovery client、Autopilot decision/policy/risk/reservation service 和 live PnL feedback job。
- 前端会影响 `LeaderResearch.tsx`、leader research API service、类型定义、中文/英文/繁中文文案和推荐卡片交互。
- 数据层可能新增 discovery source stats、shortlist snapshot、黑名单/排除原因、watchlist metadata、Autopilot 用户偏好、风险预算、资金预留、暂停原因、结构化决策审计和实盘反馈快照相关迁移；如果复用现有字段，必须保证 source evidence 和真钱决策可审计。
- 运维会新增外部 source 配置、限流、Autopilot kill switch、账户级真钱开关、source failure 可观测项和 QA 数据验证步骤。
- 安全上继续强制默认模拟/禁用；只有用户显式打开 Autopilot 且候选和账户都通过硬风控时，才允许 `copy_trading.enabled=true` 的小额试跟配置被自动创建或启用。
