package com.wrbug.polymarketbot.entity

import com.wrbug.polymarketbot.enums.AutopilotAccountState
import com.wrbug.polymarketbot.enums.AutopilotActionType
import com.wrbug.polymarketbot.enums.AutopilotDecision
import com.wrbug.polymarketbot.enums.AutopilotPauseReason
import com.wrbug.polymarketbot.enums.AutopilotReservationStatus
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "leader_autopilot_account_policy")
data class LeaderAutopilotAccountPolicy(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val state: AutopilotAccountState = AutopilotAccountState.OFF,

    @Column(name = "max_budget", nullable = false, precision = 20, scale = 8)
    val maxBudget: BigDecimal = BigDecimal("25"),

    @Column(name = "single_leader_max_amount", nullable = false, precision = 20, scale = 8)
    val singleLeaderMaxAmount: BigDecimal = BigDecimal("5"),

    @Column(name = "max_daily_loss", nullable = false, precision = 20, scale = 8)
    val maxDailyLoss: BigDecimal = BigDecimal("5"),

    @Column(name = "max_daily_orders", nullable = false)
    val maxDailyOrders: Int = 5,

    @Column(name = "max_position_value", nullable = false, precision = 20, scale = 8)
    val maxPositionValue: BigDecimal = BigDecimal("10"),

    @Column(name = "min_price", precision = 20, scale = 8)
    val minPrice: BigDecimal? = BigDecimal("0.1"),

    @Column(name = "max_price", precision = 20, scale = 8)
    val maxPrice: BigDecimal? = BigDecimal("0.8"),

    @Enumerated(EnumType.STRING)
    @Column(name = "pause_reason", length = 60, columnDefinition = "VARCHAR(60)")
    val pauseReason: AutopilotPauseReason? = null,

    @Column(name = "last_decision_at")
    val lastDecisionAt: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_autopilot_decision_event")
data class LeaderAutopilotDecisionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40, columnDefinition = "VARCHAR(40)")
    val actionType: AutopilotActionType,

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val decision: AutopilotDecision,

    @Column(name = "reason_code", nullable = false, length = 80)
    val reasonCode: String,

    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,

    @Column(name = "account_id")
    val accountId: Long? = null,

    @Column(name = "candidate_id")
    val candidateId: Long? = null,

    @Column(name = "leader_id")
    val leaderId: Long? = null,

    @Column(name = "copy_trading_id")
    val copyTradingId: Long? = null,

    @Column(name = "reservation_id")
    val reservationId: Long? = null,

    @Column(name = "policy_version", nullable = false, length = 80)
    val policyVersion: String = "leader-autopilot-v1",

    @Column(name = "input_snapshot_json", columnDefinition = "TEXT")
    val inputSnapshotJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_autopilot_risk_reservation")
data class LeaderAutopilotRiskReservation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "reservation_key", nullable = false, length = 180, unique = true)
    val reservationKey: String,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,

    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,

    @Column(name = "candidate_id")
    val candidateId: Long? = null,

    @Column(name = "leader_trade_id", length = 120)
    val leaderTradeId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40, columnDefinition = "VARCHAR(40)")
    val actionType: AutopilotActionType = AutopilotActionType.BUY,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    val status: AutopilotReservationStatus = AutopilotReservationStatus.PENDING,

    @Column(name = "amount", nullable = false, precision = 20, scale = 8)
    val amount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "order_slots", nullable = false)
    val orderSlots: Int = 1,

    @Column(name = "risk_window_start", nullable = false)
    val riskWindowStart: Long,

    @Column(name = "risk_window_end", nullable = false)
    val riskWindowEnd: Long,

    @Column(name = "order_id", length = 120)
    val orderId: String? = null,

    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Long,

    @Column(name = "finalized_at")
    val finalizedAt: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_autopilot_feedback_summary")
data class LeaderAutopilotFeedbackSummary(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "candidate_id")
    val candidateId: Long? = null,

    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,

    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,

    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 8)
    val realizedPnl: BigDecimal = BigDecimal.ZERO,

    @Column(name = "unrealized_pnl", nullable = false, precision = 20, scale = 8)
    val unrealizedPnl: BigDecimal = BigDecimal.ZERO,

    @Column(name = "rejected_order_count", nullable = false)
    val rejectedOrderCount: Int = 0,

    @Column(name = "filtered_order_count", nullable = false)
    val filteredOrderCount: Int = 0,

    @Column(name = "unknown_valuation_exposure", nullable = false, precision = 20, scale = 8)
    val unknownValuationExposure: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(name = "pause_reason", length = 60, columnDefinition = "VARCHAR(60)")
    val pauseReason: AutopilotPauseReason? = null,

    @Column(name = "summary_json", columnDefinition = "TEXT")
    val summaryJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(name = "leader_discovery_shortlist_snapshot")
data class LeaderDiscoveryShortlistSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "candidate_id", nullable = false)
    val candidateId: Long,

    @Column(name = "shortlist_group", nullable = false, length = 40)
    val shortlistGroup: String,

    @Column(name = "priority_rank", nullable = false)
    val priorityRank: Int = 0,

    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,

    @Column(name = "risk_reason", columnDefinition = "TEXT")
    val riskReason: String? = null,

    @Column(name = "evidence_json", columnDefinition = "TEXT")
    val evidenceJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
