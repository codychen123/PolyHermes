-- Leader Research Agent performance fixture and query plan helper.
-- Intended for a disposable local/test database after migrations have run.
-- It seeds at least 100 candidates, 10k activity events, 10k paper trades,
-- and 100 Autopilot managed configs with reservations/feedback/snapshots,
-- then runs EXPLAIN on the hot intake, shortlist, candidate list, and risk paths.

SET @now_ms = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
INSERT IGNORE INTO leader_research_candidate (
    normalized_wallet,
    research_state,
    source,
    score,
    score_version,
    agent_owned,
    provenance,
    first_seen_at,
    last_source_seen_at,
    last_transition_at,
    created_at,
    updated_at
)
SELECT
    CONCAT('0x', LPAD(HEX(n), 40, '0')),
    CASE WHEN n % 5 = 0 THEN 'TRIAL_READY' WHEN n % 3 = 0 THEN 'PAPER' ELSE 'CANDIDATE' END,
    'PERF_FIXTURE',
    60 + (n % 40),
    'research-copyability-v1',
    1,
    'AGENT_CREATED',
    @now_ms - 3600000,
    @now_ms - 60000,
    @now_ms - 60000,
    @now_ms,
    @now_ms
FROM seq
WHERE n <= 100;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
INSERT IGNORE INTO leader_activity_event (
    source,
    source_event_id,
    stable_event_key,
    normalized_wallet,
    market_id,
    asset,
    side,
    price,
    size,
    amount,
    event_time,
    raw_payload_hash,
    payload_summary,
    usable_for_discovery,
    usable_for_paper,
    paper_processing_status,
    processing_attempts,
    created_at,
    updated_at
)
SELECT
    'PERF_FIXTURE',
    CONCAT('perf-event-', n),
    CONCAT('perf-event-', n),
    CONCAT('0x', LPAD(HEX(1 + (n % 100)), 40, '0')),
    CONCAT('condition-', n % 200),
    CONCAT('asset-', n % 200),
    IF(n % 2 = 0, 'BUY', 'SELL'),
    0.45,
    10,
    4.5,
    @now_ms - (n * 1000),
    SHA2(CONCAT('perf-event-', n), 256),
    CONCAT('perf event ', n),
    1,
    1,
    'NEW',
    0,
    @now_ms,
    @now_ms
FROM seq;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT IGNORE INTO leaders (
    leader_address,
    leader_name,
    created_at,
    updated_at
)
SELECT
    CONCAT('0x', LPAD(HEX(100000 + n), 40, '0')),
    CONCAT('Perf Leader ', n),
    @now_ms,
    @now_ms
FROM seq;

UPDATE leader_research_candidate c
JOIN leaders l ON l.leader_name = CONCAT('Perf Leader ', c.id)
SET c.leader_id = l.id
WHERE c.id <= 100
  AND c.leader_id IS NULL;

INSERT IGNORE INTO wallet_accounts (
    account_name,
    private_key,
    wallet_address,
    proxy_address,
    is_enabled,
    created_at,
    updated_at
)
VALUES (
    'perf-autopilot-account',
    'encrypted-placeholder',
    '0x9999999999999999999999999999999999999999',
    '0x9999999999999999999999999999999999999998',
    1,
    @now_ms,
    @now_ms
);

SET @perf_account_id = (
    SELECT id FROM wallet_accounts WHERE wallet_address = '0x9999999999999999999999999999999999999999' LIMIT 1
);

INSERT IGNORE INTO leader_autopilot_account_policy (
    account_id,
    state,
    max_budget,
    single_leader_max_amount,
    max_daily_loss,
    max_daily_orders,
    max_position_value,
    min_price,
    max_price,
    created_at,
    updated_at
)
VALUES (
    @perf_account_id,
    'ON',
    500,
    5,
    25,
    100,
    250,
    0.1,
    0.8,
    @now_ms,
    @now_ms
);

SET @perf_policy_id = (
    SELECT id FROM leader_autopilot_account_policy WHERE account_id = @perf_account_id LIMIT 1
);

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT IGNORE INTO copy_trading (
    account_id,
    leader_id,
    enabled,
    management_mode,
    autopilot_policy_id,
    autopilot_candidate_id,
    copy_mode,
    copy_ratio,
    fixed_amount,
    max_order_size,
    min_order_size,
    max_daily_loss,
    max_daily_orders,
    price_tolerance,
    delay_seconds,
    poll_interval_seconds,
    use_websocket,
    websocket_reconnect_interval,
    websocket_max_retries,
    support_sell,
    created_at,
    updated_at
)
SELECT
    @perf_account_id,
    c.leader_id,
    1,
    'AUTOPILOT',
    @perf_policy_id,
    c.id,
    'FIXED',
    1,
    2,
    5,
    1,
    5,
    5,
    5,
    0,
    5,
    1,
    5000,
    10,
    1,
    @now_ms,
    @now_ms
FROM seq
JOIN leader_research_candidate c ON c.id = seq.n
WHERE c.leader_id IS NOT NULL;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT IGNORE INTO leader_autopilot_risk_reservation (
    reservation_key,
    account_id,
    copy_trading_id,
    leader_id,
    candidate_id,
    leader_trade_id,
    action_type,
    status,
    amount,
    order_slots,
    risk_window_start,
    risk_window_end,
    expires_at,
    created_at,
    updated_at
)
SELECT
    CONCAT('perf-reservation-', ct.id),
    ct.account_id,
    ct.id,
    ct.leader_id,
    ct.autopilot_candidate_id,
    CONCAT('perf-leader-trade-', seq.n),
    'BUY',
    IF(seq.n % 3 = 0, 'FINALIZED', 'PENDING'),
    2,
    1,
    @now_ms - MOD(@now_ms, 86400000),
    @now_ms - MOD(@now_ms, 86400000) + 86400000,
    @now_ms + 900000,
    @now_ms,
    @now_ms
FROM seq
JOIN copy_trading ct ON ct.autopilot_candidate_id = seq.n AND ct.management_mode = 'AUTOPILOT';

INSERT IGNORE INTO leader_autopilot_feedback_summary (
    account_id,
    candidate_id,
    leader_id,
    copy_trading_id,
    realized_pnl,
    unrealized_pnl,
    rejected_order_count,
    filtered_order_count,
    unknown_valuation_exposure,
    pause_reason,
    summary_json,
    created_at,
    updated_at
)
SELECT
    ct.account_id,
    ct.autopilot_candidate_id,
    ct.leader_id,
    ct.id,
    IF(ct.id % 10 = 0, -2, 1),
    0,
    ct.id % 4,
    ct.id % 3,
    IF(ct.id % 20 = 0, 5, 0),
    NULL,
    JSON_OBJECT('fixture', 'leader-research-perf-check'),
    @now_ms,
    @now_ms
FROM copy_trading ct
WHERE ct.management_mode = 'AUTOPILOT'
  AND ct.account_id = @perf_account_id;

INSERT INTO leader_discovery_shortlist_snapshot (
    candidate_id,
    shortlist_group,
    priority_rank,
    reason,
    risk_reason,
    evidence_json,
    created_at
)
SELECT
    c.id,
    CASE WHEN c.research_state = 'TRIAL_READY' THEN 'readyToTrial' ELSE 'promisingPaper' END,
    c.id,
    'perf shortlist fixture',
    NULL,
    JSON_ARRAY(JSON_OBJECT('label', 'score', 'value', c.score)),
    @now_ms
FROM leader_research_candidate c
WHERE c.id <= 100;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
INSERT IGNORE INTO leader_paper_session (
    id,
    candidate_id,
    status,
    started_at,
    trade_count,
    filtered_count,
    open_exposure,
    copyable_pnl,
    max_drawdown,
    unknown_valuation_exposure,
    confirmed_zero_exposure,
    filtered_ratio,
    created_at,
    updated_at
)
SELECT
    n,
    1 + (n % 100),
    'ACTIVE',
    @now_ms - 604800000,
    100,
    5,
    100,
    10,
    -3,
    5,
    0,
    0.0476,
    @now_ms,
    @now_ms
FROM seq
WHERE n <= 100;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
INSERT IGNORE INTO leader_paper_trade (
    session_id,
    candidate_id,
    leader_trade_id,
    market_id,
    side,
    leader_price,
    leader_size,
    simulated_price,
    simulated_size,
    simulated_amount,
    fill_assumption,
    quote_confidence,
    quote_source,
    quote_timestamp,
    filter_result,
    valuation_status,
    event_time,
    created_at
)
SELECT
    1 + (n % 100),
    1 + (n % 100),
    CONCAT('perf-trade-', n),
    CONCAT('condition-', n % 200),
    IF(n % 2 = 0, 'BUY', 'SELL'),
    0.45,
    10,
    0.45,
    2,
    1,
    'LEADER_PRICE',
    'MEDIUM',
    'perf',
    @now_ms,
    'PASSED',
    IF(n % 20 = 0, 'UNKNOWN', 'AVAILABLE'),
    @now_ms - (n * 1000),
    @now_ms
FROM seq;

EXPLAIN SELECT * FROM leader_research_candidate WHERE research_state IN ('PAPER', 'TRIAL_READY') ORDER BY updated_at DESC LIMIT 50;
EXPLAIN SELECT * FROM leader_activity_event WHERE paper_processing_status IN ('NEW', 'RETRYABLE') AND usable_for_paper = 1 ORDER BY event_time ASC LIMIT 200;
EXPLAIN SELECT * FROM leader_paper_trade WHERE session_id = 1 ORDER BY event_time DESC LIMIT 100;
EXPLAIN SELECT * FROM leader_research_event ORDER BY created_at DESC LIMIT 100;
EXPLAIN SELECT * FROM leader_discovery_shortlist_snapshot WHERE created_at >= @now_ms - 3600000 ORDER BY priority_rank ASC LIMIT 20;
EXPLAIN SELECT * FROM copy_trading WHERE account_id = @perf_account_id AND management_mode = 'AUTOPILOT' AND enabled = 1;
EXPLAIN SELECT COALESCE(SUM(amount), 0) FROM leader_autopilot_risk_reservation WHERE account_id = @perf_account_id AND risk_window_start = @now_ms - MOD(@now_ms, 86400000) AND status IN ('PENDING', 'FINALIZED');
EXPLAIN SELECT COALESCE(SUM(order_slots), 0) FROM leader_autopilot_risk_reservation WHERE account_id = @perf_account_id AND risk_window_start = @now_ms - MOD(@now_ms, 86400000) AND status IN ('PENDING', 'FINALIZED');
EXPLAIN SELECT * FROM leader_autopilot_feedback_summary WHERE copy_trading_id = (SELECT MIN(id) FROM copy_trading WHERE account_id = @perf_account_id AND management_mode = 'AUTOPILOT');
EXPLAIN SELECT * FROM leader_autopilot_feedback_summary WHERE candidate_id = 1;
