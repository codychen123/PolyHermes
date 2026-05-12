package com.wrbug.polymarketbot.enums

enum class LeaderResearchState {
    DISCOVERED,
    CANDIDATE,
    PAPER,
    TRIAL_READY,
    COOLDOWN,
    RETIRED
}

enum class LeaderResearchRunStatus {
    RUNNING,
    SUCCESS,
    PARTIAL_FAILURE,
    FAILED,
    SKIPPED
}

enum class LeaderResearchTriggerType {
    MANUAL,
    SCHEDULED,
    PREVIEW
}

enum class LeaderResearchSourceType {
    WATCHLIST,
    EXISTING_LEADER,
    ACTIVITY_DERIVED,
    GLOBAL_ACTIVITY_CAPTURE,
    PUBLIC_LEADERBOARD
}

enum class LeaderResearchSourceStatus {
    SUCCESS,
    FAILURE,
    STALE,
    DISABLED,
    DEGRADED
}

enum class LeaderCandidateProvenance {
    AGENT_CREATED,
    USER_LEADER,
    USER_POOL,
    MANUAL_LOCKED
}

enum class LeaderPaperSessionStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED
}

enum class LeaderPaperProcessingStatus {
    NEW,
    PROCESSING,
    PROCESSED,
    FILTERED,
    RETRYABLE,
    FAILED
}

enum class LeaderResearchValuationStatus {
    AVAILABLE,
    NO_MATCH,
    UNAVAILABLE,
    CONFIRMED_ZERO,
    UNKNOWN
}

enum class LeaderResearchQuoteConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
}

enum class LeaderPaperFillAssumption {
    LEADER_PRICE,
    BEST_ASK_AT_EVENT,
    MID_PRICE,
    UNKNOWN
}

enum class LeaderPaperFilterResult {
    PASSED,
    FILTERED
}

enum class LeaderResearchEventType {
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_SKIPPED,
    SOURCE_SUCCESS,
    SOURCE_FAILURE,
    SOURCE_DISABLED,
    CANDIDATE_DISCOVERED,
    CANDIDATE_UPDATED,
    PAPER_STARTED,
    PAPER_TRADE_RECORDED,
    PAPER_TRADE_FILTERED,
    PAPER_PROCESSING_FAILED,
    STATE_TRANSITION,
    TRIAL_READY,
    COOLDOWN,
    RETIRED,
    VALUATION_STALE,
    APPROVAL_CREATED_DISABLED_CONFIG,
    APPROVAL_CREATED_AUTOPILOT_CONFIG,
    APPROVAL_REJECTED,
    DUPLICATE_APPROVAL,
    REAL_MONEY_ACTIVATION_FORBIDDEN,
    SHORTLIST_GENERATED,
    SHORTLIST_BLOCKED,
    AUTOPILOT_ENABLED,
    AUTOPILOT_DISABLED,
    AUTOPILOT_PAUSED,
    AUTOPILOT_RESUMED,
    AUTOPILOT_DECISION,
    AUTOPILOT_CONFIG_CONVERTED_TO_MANUAL,
    AUTOPILOT_FEEDBACK_UPDATED,
    NOTIFICATION_SUMMARY
}

enum class LeaderResearchNotificationStatus {
    PENDING,
    SENT,
    FAILED,
    SKIPPED
}

enum class AutopilotAccountState {
    OFF,
    ON,
    PAUSED
}

enum class CopyTradingManagementMode {
    MANUAL,
    AUTOPILOT
}

enum class AutopilotActionType {
    CREATE_CONFIG,
    ENABLE_CONFIG,
    BUY,
    SELL,
    RESUME,
    CONVERT_TO_MANUAL
}

enum class AutopilotDecision {
    ALLOW,
    DENY,
    PAUSE
}

enum class AutopilotPauseReason {
    USER_DISABLED,
    USER_PAUSED,
    RISK_DAILY_LOSS,
    RISK_MAX_DRAWDOWN,
    RISK_REJECTION_RATE,
    RISK_CONSECUTIVE_LOSSES,
    RISK_DAILY_ORDERS,
    RISK_BUDGET_EXHAUSTED,
    RISK_POSITION_LIMIT,
    SOURCE_STALE,
    QUOTE_UNAVAILABLE,
    POSITION_UNAVAILABLE,
    KILL_SWITCH,
    CANDIDATE_BLOCKED,
    ACCOUNT_NOT_READY,
    DUPLICATE_CONFIG,
    UNKNOWN
}

enum class AutopilotReservationStatus {
    PENDING,
    FINALIZED,
    RELEASED,
    EXPIRED
}
