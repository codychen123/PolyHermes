## ADDED Requirements

### Requirement: 接入真实外部 Leader 发现源
系统 SHALL 支持至少一个真实外部 discovery source，用于发现不在已有 Leader 管理记录中的 Polymarket 钱包候选。

#### Scenario: 外部来源发现候选
- **WHEN** 研究任务运行外部 discovery source
- **THEN** 系统 MUST 从 Polymarket leaderboard、trader search、Data API 排行榜或等价公开来源拉取候选钱包，并 MUST 创建或更新研究候选

#### Scenario: 记录来源契约
- **WHEN** 外部 discovery source 完成一次运行
- **THEN** 系统 MUST 记录 source type、endpoint 或来源标识、排序方式、分页范围、候选数量、source rank、source evidence 和运行时间

#### Scenario: 先验证来源契约再实现正式客户端
- **WHEN** 开发第一版外部 discovery source
- **THEN** 实现 MUST 先产出 source contract 或等价记录，包含 endpoint、字段、分页、排序、限流、错误码和样例 payload，并 MUST 在契约验证前避免将未验证 source 作为生产候选来源

#### Scenario: 外部来源返回空结果
- **WHEN** 外部 discovery source 成功运行但没有候选
- **THEN** 系统 MUST 将该来源标记为成功且候选数为 0，并 MUST 在 shortlist 空态中解释没有外部候选

#### Scenario: 外部来源失败
- **WHEN** 外部 discovery source 超时、限流、认证失败、解析失败或返回异常
- **THEN** 系统 MUST 更新 source health 和 research run，并 MUST NOT 删除已有候选或自动降级候选

#### Scenario: 外部来源被配置禁用
- **WHEN** 外部 discovery source 被 kill switch 或配置显式关闭
- **THEN** 系统 MUST 展示该来源为 disabled，并 MUST 区分“配置禁用”和“运行失败”

### Requirement: 对外部候选执行 Intake 质量门
系统 SHALL 在外部候选进入研究状态机前执行 intake 质量门，防止噪声钱包、重复钱包和不安全候选污染研究结果。

#### Scenario: 标准化和去重候选
- **WHEN** 外部 discovery source 返回多个钱包地址
- **THEN** 系统 MUST 标准化钱包地址，并 MUST 对同一钱包只创建或更新一个研究候选

#### Scenario: 排除已有启用跟单候选
- **WHEN** 外部候选已经存在启用中的真实跟单配置
- **THEN** 系统 MUST 不把该候选展示为新发现推荐，并 MUST 记录排除原因

#### Scenario: 尊重锁定和淘汰候选
- **WHEN** 外部候选已被锁定、淘汰或加入黑名单
- **THEN** 系统 MUST NOT 自动推进该候选研究状态，并 MUST NOT 覆盖人工决策字段

#### Scenario: 应用最低质量阈值
- **WHEN** 外部候选缺少钱包、交易数过低、近期无活动、source rank 超出上限或关键字段缺失
- **THEN** 系统 MUST 拒绝或降级该候选，并 MUST 保存可读的过滤原因

#### Scenario: 保存来源证据
- **WHEN** 外部候选通过 intake 质量门
- **THEN** 系统 MUST 保存 source evidence、source rank、来源统计和首次/最近发现时间，以便候选详情和 shortlist 解释推荐原因

### Requirement: 防止原始发现候选污染 Leader 池
系统 SHALL 保持 Leader Research 和 Leader 池的边界，只有达到展示条件的研究候选才能同步到 Leader 池。

#### Scenario: DISCOVERED 不进入 Leader 池
- **WHEN** 外部候选仅处于 `DISCOVERED` 状态
- **THEN** 系统 MUST NOT 创建或更新对应 Leader 池项

#### Scenario: CANDIDATE 及以上可同步
- **WHEN** 外部候选达到 `CANDIDATE` 或更高研究状态且未锁定
- **THEN** 系统 MAY 创建或更新对应 Leader 池项，并 MUST 保留研究状态和 Leader 池状态的区别

#### Scenario: 研究推荐不等于真实试跟
- **WHEN** 候选进入 `TRIAL_READY`
- **THEN** 系统 MUST NOT 仅因为研究状态变化就把 Leader 池状态改为 `TRIAL` 或 `ACTIVE`，除非用户已显式开启 Autopilot 且真钱风控检查通过

### Requirement: 提供 Leader Discovery Shortlist API
系统 SHALL 提供受保护的 shortlist API，将研究候选整理成用户可以直接决策的今日推荐结果。

#### Scenario: 返回推荐分组
- **WHEN** 前端请求 Leader Discovery Shortlist
- **THEN** 后端 MUST 返回 `readyToTrial`、`promisingPaper`、`newCandidates` 和 `blockedOrCooling` 分组

#### Scenario: 返回推荐证据
- **WHEN** shortlist 返回任一候选卡片
- **THEN** 卡片 MUST 包含钱包地址、来源、研究状态、推荐理由、关键证据指标、风险或阻断原因、建议配置和下一步操作

#### Scenario: 限制推荐数量
- **WHEN** shortlist 中候选数量超过页面可读范围
- **THEN** 系统 MUST 按推荐优先级返回 Top 3-10，并 MUST 保留完整候选列表入口

#### Scenario: 不由前端推导推荐
- **WHEN** 前端展示 shortlist
- **THEN** 前端 MUST 使用后端 shortlist API 的分组、理由和 CTA，不得仅靠候选列表在浏览器中自行推导推荐状态

### Requirement: 解释没有推荐的原因
系统 SHALL 在没有可执行推荐时展示明确原因，避免用户误以为系统已经自动发现但没有优秀 leader。

#### Scenario: 外部来源不可用
- **WHEN** 没有推荐且外部 discovery source 失败或被禁用
- **THEN** 页面 MUST 明确展示外部发现源不可用，并 MUST 展示最近失败或禁用原因

#### Scenario: 样本不足
- **WHEN** 候选存在但纸跟天数、交易数或数据新鲜度不足
- **THEN** 页面 MUST 展示样本不足原因，并 MUST 告知候选仍在观察中

#### Scenario: 风险阻断
- **WHEN** 候选因亏损、回撤、过滤比例、UNKNOWN valuation、source stale 或冷却被阻断
- **THEN** 页面 MUST 展示阻断原因和对应候选数量

#### Scenario: 全部候选被过滤
- **WHEN** 外部 source 返回候选但全部被 intake 质量门过滤
- **THEN** 页面 MUST 展示过滤摘要，并 MUST 提供查看 source health 或候选事件的入口

### Requirement: 重构 Leader Research 首屏为今日推荐
系统 SHALL 将 Leader Research 页面的首屏重点从运行诊断改为今日推荐和下一步行动。

#### Scenario: 打开 Leader Research 页面
- **WHEN** 已登录用户打开 Leader Research 页面
- **THEN** 页面 MUST 优先展示今日推荐 shortlist 或暂无推荐原因，再展示运行状态、候选统计、source health 和候选表

#### Scenario: 展示可试跟候选
- **WHEN** shortlist 中存在 `readyToTrial` 候选
- **THEN** 页面 MUST 展示当前 Autopilot 模式、可执行下一步、固定金额、每日最大亏损、每日最大订单数、价格范围和最大仓位

#### Scenario: 展示观察中候选
- **WHEN** shortlist 中存在 `promisingPaper` 或 `newCandidates`
- **THEN** 页面 MUST 展示候选正在等待更多纸跟证据，并 MUST NOT 暗示已经可以真钱跟单

#### Scenario: 异步运行提示
- **WHEN** 用户点击立即运行研究且后端返回 RUNNING
- **THEN** 页面 MUST 提示研究已开始或后台运行中，不得提示研究已经完成

### Requirement: 提供 Watchlist 兜底管理
系统 SHALL 提供 watchlist 编辑和批量导入能力，作为外部 discovery source 不稳定时的兜底候选入口。

#### Scenario: 查看 Watchlist
- **WHEN** 用户打开 watchlist 管理入口
- **THEN** 系统 MUST 展示当前 watchlist 钱包、备注或标签、来源说明和最近更新时间

#### Scenario: 批量导入 Watchlist
- **WHEN** 用户粘贴多个钱包地址并提交 preview
- **THEN** 系统 MUST 返回有效地址、无效地址、重复地址、已存在候选、已锁定候选和已淘汰候选的数量与明细

#### Scenario: 确认保存 Watchlist
- **WHEN** 用户确认保存 watchlist 变更
- **THEN** 系统 MUST 保存变更并 MUST 记录操作日志或 research event

#### Scenario: Watchlist 不等于自动发现
- **WHEN** 页面展示 watchlist 兜底入口
- **THEN** 系统 MUST 明确说明 watchlist 是手动补种来源，不代表外部自动发现已启用

### Requirement: 提供用户级 Autopilot 模式开关
系统 SHALL 提供用户级 Autopilot 开关，用于决定 Leader Research 只做模拟/禁用配置，还是允许系统在硬风控下自动启用小额真钱试跟。

#### Scenario: Autopilot 账户级状态机
- **WHEN** 系统保存 Autopilot 用户偏好
- **THEN** 系统 MUST 使用账户级状态 `OFF`、`ON`、`PAUSED`，并 MUST 将全局 kill switch 作为覆盖态处理

#### Scenario: Autopilot 默认关闭
- **WHEN** 用户尚未显式打开 Autopilot
- **THEN** 系统 MUST 只执行外部发现、paper trading、shortlist 推荐和 `enabled=false` 配置创建，并 MUST NOT 自动启用真钱跟单

#### Scenario: 打开 Autopilot
- **WHEN** 用户打开 Autopilot
- **THEN** 系统 MUST 要求用户确认账户级最大预算、单 leader 最大试跟金额、每日最大亏损、每日最大订单数、最大仓位、价格范围和自动暂停条件，并 MUST 将账户状态从 `OFF` 变为 `ON`

#### Scenario: 风险触发暂停
- **WHEN** Autopilot 触发风险暂停、source 暂停或系统暂停
- **THEN** 系统 MUST 将账户级 Autopilot 状态置为 `PAUSED` 或记录配置级 pause reason，并 MUST NOT 自动恢复为 `ON`

#### Scenario: 恢复 Autopilot
- **WHEN** 用户从 `PAUSED` 恢复 Autopilot
- **THEN** 系统 MUST 要求用户显式确认，并 MUST 重新运行 Autopilot decision service；只有通过时才可恢复为 `ON`

#### Scenario: 关闭 Autopilot
- **WHEN** 用户关闭 Autopilot
- **THEN** 系统 MUST 阻止新的自动真钱启用，并 MUST 暂停或禁用由 Autopilot 管理的启用配置，除非用户明确将某个配置转为手动管理

#### Scenario: 全局 kill switch 覆盖所有状态
- **WHEN** 全局 Autopilot kill switch 生效
- **THEN** decision service MUST 对所有账户返回 `DENY` 或 `PAUSE`，并 MUST NOT 因 kill switch 解除而自动恢复任何 `PAUSED` 账户

#### Scenario: 暂停状态允许减仓
- **WHEN** 账户处于 `PAUSED` 或全局 kill switch 生效且 Autopilot 管理配置需要执行卖出
- **THEN** decision service MUST 区分 `SELL` 动作是否为 reduce-only，并 SHOULD 允许减少或关闭既有仓位的卖出继续执行，同时 MUST 拒绝新的买入、自动启用或恢复动作

#### Scenario: 区分手动配置和 Autopilot 配置
- **WHEN** 系统创建、更新或展示 copy-trading 配置
- **THEN** 系统 MUST 保存并返回该配置的 management mode，且 MUST 区分 `MANUAL` 和 `AUTOPILOT`

#### Scenario: 关闭 Autopilot 不影响手动配置
- **WHEN** 用户关闭 Autopilot 或全局 kill switch 生效
- **THEN** 系统 MUST 只暂停、禁用或跳过 `AUTOPILOT` 管理的配置，并 MUST NOT 自动禁用 `MANUAL` 配置

#### Scenario: Autopilot 配置转为手动管理
- **WHEN** 用户选择保留某个 Autopilot 创建的配置并转为手动管理
- **THEN** 系统 MUST 将该配置标记为 `MANUAL`，清除或冻结 Autopilot policy 关联，并 MUST 记录审计事件

#### Scenario: 展示 Autopilot 当前模式
- **WHEN** 用户打开 Leader Research 或 shortlist 页面
- **THEN** 页面 MUST 明确展示 Autopilot 是关闭、开启、暂停还是 kill switch 状态，并 MUST 展示该状态对下一步 CTA 的影响

### Requirement: 保持真钱跟单安全边界
系统 SHALL 保证 discovery、shortlist 和 watchlist 功能不会在没有明确用户开关和风控通过的情况下启用真钱跟单。

#### Scenario: Shortlist 不自动创建配置
- **WHEN** 系统生成或刷新 shortlist 且 Autopilot 关闭
- **THEN** 系统 MUST NOT 自动创建、启用或修改任何真实跟单配置

#### Scenario: 创建禁用试跟配置
- **WHEN** 用户从 shortlist 中确认创建试跟配置且 Autopilot 关闭
- **THEN** 后端 MUST 创建 `enabled=false` 的保守配置，并 MUST 复用现有 approval safety

#### Scenario: Autopilot 自动创建小额真钱试跟
- **WHEN** Autopilot 开启且 shortlist 中存在 `TRIAL_READY` 候选
- **THEN** 后端 MAY 自动创建或启用 `enabled=true` 的小额试跟配置，并 MUST 在创建前通过 `LeaderAutopilotDecisionService`

#### Scenario: 并发自动创建同一配置
- **WHEN** 用户双击、多个浏览器标签、后台 research run 或定时任务同时尝试为同一 account 和 leader 创建 Autopilot 配置
- **THEN** 系统 MUST 通过数据库唯一约束或幂等表保证同一 `account_id + leader_id + management_mode=AUTOPILOT` 最多创建一条可管理配置，并 MUST 返回已有配置或重复结果

#### Scenario: 拒绝绕过风控的立即启用
- **WHEN** shortlist、watchlist 或前端请求中包含立即启用、放大金额或放宽风控字段
- **THEN** 后端 MUST 只接受不超过用户 Autopilot 风控预算的保守值，并 MUST 拒绝或忽略任何绕过候选状态、预算、暂停或 kill switch 的字段

#### Scenario: 审计真实配置创建
- **WHEN** 用户从 shortlist 创建禁用配置或 Autopilot 创建启用配置成功或失败
- **THEN** 系统 MUST 记录候选、账户、leader、配置 ID、Autopilot 模式、风控输入、风控结论或失败原因，并 MUST 可从研究事件或日志追溯

### Requirement: 执行唯一 Autopilot 决策闸门
系统 SHALL 在任何真钱自动启用、复制订单、恢复暂停配置或扩大 Autopilot 覆盖范围前调用同一个 Autopilot decision service，并以 `ALLOW`、`DENY` 或 `PAUSE` 结果作为唯一执行依据。

#### Scenario: 创建或更新启用配置前调用决策服务
- **WHEN** shortlist、approval、`CopyTradingService.createCopyTrading` 或 `CopyTradingService.updateCopyTrading` 试图保存 Autopilot 管理的 `enabled=true` 配置
- **THEN** 后端 MUST 先调用 Autopilot decision service，并 MUST 在非 `ALLOW` 时拒绝启用或保存为 `enabled=false`

#### Scenario: 真实复制订单前调用决策服务
- **WHEN** `CopyOrderTrackingService.processBuyTrade` 准备为 Autopilot 管理的配置创建真实买入订单
- **THEN** 后端 MUST 先调用 Autopilot decision service，并 MUST 在 `DENY` 或 `PAUSE` 时跳过下单、记录原因，且在 `PAUSE` 时暂停相关配置或账户级 Autopilot

#### Scenario: 决策请求包含动作类型
- **WHEN** 后端调用 Autopilot decision service
- **THEN** 请求 MUST 使用明确 action type 区分 `CREATE_CONFIG`、`ENABLE_CONFIG`、`BUY`、`SELL`、`RESUME` 和 `CONVERT_TO_MANUAL`，且后续执行 MUST 与该 action type 的安全语义一致

#### Scenario: 使用强类型状态和原因
- **WHEN** 后端保存或判断 Autopilot 状态、管理归属、决策或暂停原因
- **THEN** 实现 MUST 使用 `AutopilotAccountState`、`CopyTradingManagementMode`、`AutopilotActionType`、`AutopilotDecision` 和 `AutopilotPauseReason` 或等价 enum，不得依赖散落字符串作为核心分支条件

#### Scenario: 候选不符合真钱条件
- **WHEN** 候选不是 `TRIAL_READY`、已 locked、retired、blacklisted、cooling、source stale 或存在 UNKNOWN/UNAVAILABLE valuation 阻断
- **THEN** Autopilot MUST NOT 启用真钱跟单，并 MUST 记录阻断原因

#### Scenario: 超过账户级预算
- **WHEN** 自动试跟会超过账户级 Autopilot 预算、单 leader 金额上限、每日最大亏损、每日最大订单数、最大仓位或价格范围
- **THEN** Autopilot MUST 拒绝创建或执行该真钱动作，并 MUST 在 shortlist 或事件中展示预算阻断原因

#### Scenario: 买入前预留账户风险预算
- **WHEN** Autopilot decision service 对真实 `BUY` 返回 `ALLOW`
- **THEN** 系统 MUST 在创建真实订单前完成账户级预算和当日订单额度预留，并 MUST 在订单成功后确认预留、订单失败或被过滤后释放预留

#### Scenario: 并发买入不超过预算
- **WHEN** 多个 leader trade 或多个后台任务同时触发同一账户的 Autopilot `BUY`
- **THEN** 系统 MUST 通过数据库事务、账户级锁、唯一幂等键或等价机制保证预留后的总风险暴露不超过账户预算、单 leader 上限、每日订单数和每日亏损阈值

#### Scenario: 风险窗口可解释
- **WHEN** 系统计算每日订单数、每日亏损或每日预算预留
- **THEN** 系统 MUST 使用明确的风险窗口，第一版默认使用 UTC 自然日，且 MUST 在文档或配置中说明该窗口

#### Scenario: 触发自动暂停
- **WHEN** 已启用的 Autopilot 配置触发日亏损、最大回撤、连续亏损、拒单率、source stale、quote unavailable 或 position unavailable 阈值
- **THEN** 系统 MUST 自动暂停该配置或账户级 Autopilot，并 MUST 记录暂停原因

#### Scenario: 全局 kill switch 生效
- **WHEN** 运维或配置关闭 Autopilot kill switch
- **THEN** 系统 MUST 阻止所有新的自动真钱启用，并 MUST 暂停或跳过 Autopilot 管理的复制动作

#### Scenario: Autopilot API 校验账户归属
- **WHEN** 用户读取或修改 Autopilot status、预算、enable、disable、resume、convert-to-manual 或 shortlist CTA
- **THEN** 后端 MUST 校验当前用户拥有对应 account、copy trading config 和 candidate 的操作权限，并 MUST 拒绝跨账户访问或修改

#### Scenario: 结构化审计真钱决策
- **WHEN** Autopilot decision service 允许、拒绝、暂停、预留、释放、恢复或转手动
- **THEN** 系统 MUST 保存结构化审计事件，至少包含 action type、decision、reason code、accountId、candidateId、leaderId、copyTradingId、reservationId、policy version、输入快照摘要和创建时间

#### Scenario: 审计不泄露敏感凭证
- **WHEN** 系统保存 Autopilot 决策输入快照或事件 payload
- **THEN** 系统 MUST 使用字段白名单或脱敏规则，并 MUST NOT 保存 API key、wallet secret、完整交易凭证、session token 或可用于登录/下单的敏感值

### Requirement: 用真实跟单反馈更新 Leader 评分
系统 SHALL 将 Autopilot 小额真钱试跟产生的真实表现反馈到 leader 研究状态和 shortlist 推荐。

#### Scenario: 采集真实跟单表现
- **WHEN** Autopilot 配置产生真实订单、成交、拒单、PnL、费用、滑点或 valuation unknown
- **THEN** 系统 MUST 保存这些反馈摘要，并 MUST 将其关联到候选、leader、account 和 copy trading config

#### Scenario: 真实表现弱于模拟表现
- **WHEN** 真实跟单 PnL、回撤、滑点或拒单表现显著弱于 paper trading 证据
- **THEN** 系统 MUST 降低推荐置信度、进入冷却或暂停 Autopilot 配置，并 MUST 在 shortlist 中解释原因

#### Scenario: 真实表现稳定
- **WHEN** 真实小额试跟在足够样本内表现稳定且未触发风险阈值
- **THEN** 系统 MAY 保持小额 Autopilot 试跟或继续观察，但 MUST NOT 自动放大金额或放宽风控

### Requirement: 提供上线和回滚开关
系统 SHALL 为外部 discovery 和 shortlist 提供可配置开关、限流和回滚路径。

#### Scenario: 外部 Discovery 默认可关闭
- **WHEN** 新版本部署到生产环境
- **THEN** 运维 MUST 能通过配置关闭外部 discovery source，并 MUST 保持已有 Leader Research 页面可读

#### Scenario: 限制外部候选规模
- **WHEN** 外部 discovery source 返回大量候选
- **THEN** 系统 MUST 应用每次运行候选上限、分页上限和运行时间上限

#### Scenario: 回滚外部 Discovery
- **WHEN** 外部 source 噪声过高、性能异常或接口失败率过高
- **THEN** 运维 MUST 能关闭外部 discovery，并 MUST NOT 删除已经存在的研究候选、纸跟账本或 Leader 池记录
