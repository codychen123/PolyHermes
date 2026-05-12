-- ============================================
-- V43: Leader discovery shortlist + Autopilot safety rails
-- ============================================

ALTER TABLE copy_trading
    ADD COLUMN management_mode VARCHAR(30) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/AUTOPILOT',
    ADD COLUMN autopilot_policy_id BIGINT DEFAULT NULL COMMENT 'Autopilot account policy ID',
    ADD COLUMN autopilot_candidate_id BIGINT DEFAULT NULL COMMENT 'Research candidate that created/manages this config',
    ADD COLUMN autopilot_paused_reason VARCHAR(60) DEFAULT NULL COMMENT 'Latest Autopilot pause reason',
    ADD COLUMN autopilot_last_decision_at BIGINT DEFAULT NULL COMMENT 'Latest Autopilot decision timestamp',
    ADD COLUMN autopilot_unique_key VARCHAR(120) DEFAULT NULL COMMENT 'Unique key populated only for AUTOPILOT configs',
    ADD INDEX idx_copy_trading_management_mode (management_mode),
    ADD INDEX idx_copy_trading_autopilot_policy (autopilot_policy_id),
    ADD INDEX idx_copy_trading_autopilot_candidate (autopilot_candidate_id),
    ADD UNIQUE KEY uk_copy_trading_autopilot_account_leader (autopilot_unique_key);

CREATE TABLE IF NOT EXISTS leader_autopilot_account_policy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Autopilot policy ID',
    account_id BIGINT NOT NULL COMMENT 'Wallet account ID',
    state VARCHAR(30) NOT NULL DEFAULT 'OFF' COMMENT 'OFF/ON/PAUSED',
    max_budget DECIMAL(20, 8) NOT NULL DEFAULT 25 COMMENT 'Account-level Autopilot budget',
    single_leader_max_amount DECIMAL(20, 8) NOT NULL DEFAULT 5 COMMENT 'Max amount per leader trial',
    max_daily_loss DECIMAL(20, 8) NOT NULL DEFAULT 5 COMMENT 'Daily loss stop',
    max_daily_orders INT NOT NULL DEFAULT 5 COMMENT 'Daily order limit',
    max_position_value DECIMAL(20, 8) NOT NULL DEFAULT 10 COMMENT 'Max position value per copied market',
    min_price DECIMAL(20, 8) DEFAULT 0.1 COMMENT 'Min allowed buy price',
    max_price DECIMAL(20, 8) DEFAULT 0.8 COMMENT 'Max allowed buy price',
    pause_reason VARCHAR(60) DEFAULT NULL COMMENT 'Latest account-level pause reason',
    last_decision_at BIGINT DEFAULT NULL COMMENT 'Latest decision timestamp',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_leader_autopilot_policy_account (account_id),
    INDEX idx_leader_autopilot_policy_state (state),
    CONSTRAINT fk_leader_autopilot_policy_account FOREIGN KEY (account_id) REFERENCES wallet_accounts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Account-level Leader Autopilot policy and state';

CREATE TABLE IF NOT EXISTS leader_autopilot_decision_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Autopilot decision event ID',
    action_type VARCHAR(40) NOT NULL COMMENT 'CREATE_CONFIG/ENABLE_CONFIG/BUY/SELL/RESUME/CONVERT_TO_MANUAL',
    decision VARCHAR(30) NOT NULL COMMENT 'ALLOW/DENY/PAUSE',
    reason_code VARCHAR(80) NOT NULL COMMENT 'Machine-readable reason',
    reason TEXT DEFAULT NULL COMMENT 'Human-readable reason',
    account_id BIGINT DEFAULT NULL,
    candidate_id BIGINT DEFAULT NULL,
    leader_id BIGINT DEFAULT NULL,
    copy_trading_id BIGINT DEFAULT NULL,
    reservation_id BIGINT DEFAULT NULL,
    policy_version VARCHAR(80) NOT NULL DEFAULT 'leader-autopilot-v1',
    input_snapshot_json TEXT DEFAULT NULL COMMENT 'Sanitized input snapshot JSON',
    created_at BIGINT NOT NULL,
    INDEX idx_leader_autopilot_event_account_created (account_id, created_at),
    INDEX idx_leader_autopilot_event_candidate_created (candidate_id, created_at),
    INDEX idx_leader_autopilot_event_copy_trading_created (copy_trading_id, created_at),
    INDEX idx_leader_autopilot_event_action_created (action_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Structured Autopilot decisions';

CREATE TABLE IF NOT EXISTS leader_autopilot_risk_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Autopilot risk reservation ID',
    reservation_key VARCHAR(180) NOT NULL COMMENT 'Idempotency key for the reservation',
    account_id BIGINT NOT NULL,
    copy_trading_id BIGINT NOT NULL,
    leader_id BIGINT NOT NULL,
    candidate_id BIGINT DEFAULT NULL,
    leader_trade_id VARCHAR(120) DEFAULT NULL,
    action_type VARCHAR(40) NOT NULL DEFAULT 'BUY',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/FINALIZED/RELEASED/EXPIRED',
    amount DECIMAL(20, 8) NOT NULL DEFAULT 0,
    order_slots INT NOT NULL DEFAULT 1,
    risk_window_start BIGINT NOT NULL,
    risk_window_end BIGINT NOT NULL,
    order_id VARCHAR(120) DEFAULT NULL,
    reason TEXT DEFAULT NULL,
    expires_at BIGINT NOT NULL,
    finalized_at BIGINT DEFAULT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_leader_autopilot_reservation_key (reservation_key),
    INDEX idx_leader_autopilot_reservation_account_window (account_id, risk_window_start, status),
    INDEX idx_leader_autopilot_reservation_copy_trading (copy_trading_id),
    INDEX idx_leader_autopilot_reservation_expires (expires_at, status),
    CONSTRAINT fk_leader_autopilot_reservation_account FOREIGN KEY (account_id) REFERENCES wallet_accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_leader_autopilot_reservation_copy_trading FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Autopilot account-level risk reservations';

CREATE TABLE IF NOT EXISTS leader_autopilot_feedback_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Autopilot feedback summary ID',
    account_id BIGINT NOT NULL,
    candidate_id BIGINT DEFAULT NULL,
    leader_id BIGINT NOT NULL,
    copy_trading_id BIGINT NOT NULL,
    realized_pnl DECIMAL(20, 8) NOT NULL DEFAULT 0,
    unrealized_pnl DECIMAL(20, 8) NOT NULL DEFAULT 0,
    rejected_order_count INT NOT NULL DEFAULT 0,
    filtered_order_count INT NOT NULL DEFAULT 0,
    unknown_valuation_exposure DECIMAL(20, 8) NOT NULL DEFAULT 0,
    pause_reason VARCHAR(60) DEFAULT NULL,
    summary_json TEXT DEFAULT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_leader_autopilot_feedback_config (copy_trading_id),
    INDEX idx_leader_autopilot_feedback_candidate (candidate_id),
    INDEX idx_leader_autopilot_feedback_leader (leader_id),
    CONSTRAINT fk_leader_autopilot_feedback_copy_trading FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Autopilot real-money feedback summary';

CREATE TABLE IF NOT EXISTS leader_discovery_shortlist_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Shortlist snapshot ID',
    candidate_id BIGINT NOT NULL,
    shortlist_group VARCHAR(40) NOT NULL COMMENT 'readyToTrial/promisingPaper/newCandidates/blockedOrCooling',
    priority_rank INT NOT NULL DEFAULT 0,
    reason TEXT DEFAULT NULL,
    risk_reason TEXT DEFAULT NULL,
    evidence_json TEXT DEFAULT NULL,
    created_at BIGINT NOT NULL,
    INDEX idx_leader_discovery_shortlist_created (created_at),
    INDEX idx_leader_discovery_shortlist_candidate (candidate_id),
    INDEX idx_leader_discovery_shortlist_group_rank (shortlist_group, priority_rank),
    CONSTRAINT fk_leader_discovery_shortlist_candidate FOREIGN KEY (candidate_id) REFERENCES leader_research_candidate(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Leader discovery shortlist snapshots';

INSERT INTO system_config (config_key, config_value, description, created_at, updated_at)
VALUES
    ('leader.research.public-leaderboard.enabled', 'false', 'Enable Polymarket public leaderboard discovery source', UNIX_TIMESTAMP(NOW(3)) * 1000, UNIX_TIMESTAMP(NOW(3)) * 1000),
    ('leader.research.public-leaderboard.category', 'OVERALL', 'Polymarket leaderboard category', UNIX_TIMESTAMP(NOW(3)) * 1000, UNIX_TIMESTAMP(NOW(3)) * 1000),
    ('leader.research.public-leaderboard.time-period', 'MONTH', 'Polymarket leaderboard time period', UNIX_TIMESTAMP(NOW(3)) * 1000, UNIX_TIMESTAMP(NOW(3)) * 1000),
    ('leader.research.public-leaderboard.order-by', 'PNL', 'Polymarket leaderboard order field', UNIX_TIMESTAMP(NOW(3)) * 1000, UNIX_TIMESTAMP(NOW(3)) * 1000),
    ('leader.research.public-leaderboard.limit', '25', 'Polymarket leaderboard candidate limit', UNIX_TIMESTAMP(NOW(3)) * 1000, UNIX_TIMESTAMP(NOW(3)) * 1000),
    ('leader.research.public-leaderboard.cache-ttl-ms', '60000', 'Short cache TTL for public leaderboard discovery to avoid repeated manual runs hammering the source', UNIX_TIMESTAMP(NOW(3)) * 1000, UNIX_TIMESTAMP(NOW(3)) * 1000),
    ('leader.research.public-leaderboard.failure-backoff-ms', '30000', 'Short failure backoff for public leaderboard discovery after source errors', UNIX_TIMESTAMP(NOW(3)) * 1000, UNIX_TIMESTAMP(NOW(3)) * 1000),
    ('leader.autopilot.global-kill-switch', 'false', 'Global kill switch for Leader Autopilot real-money actions', UNIX_TIMESTAMP(NOW(3)) * 1000, UNIX_TIMESTAMP(NOW(3)) * 1000)
ON DUPLICATE KEY UPDATE config_key = VALUES(config_key);
