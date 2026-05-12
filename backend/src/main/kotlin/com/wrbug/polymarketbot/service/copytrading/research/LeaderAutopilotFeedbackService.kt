package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.LeaderAutopilotFeedbackSummary
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.AutopilotPauseReason
import com.wrbug.polymarketbot.enums.CopyTradingManagementMode
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderAutopilotFeedbackSummaryRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class LeaderAutopilotFeedbackDelta(
    val realizedPnlDelta: BigDecimal = BigDecimal.ZERO,
    val unrealizedPnlDelta: BigDecimal = BigDecimal.ZERO,
    val rejectedOrderDelta: Int = 0,
    val filteredOrderDelta: Int = 0,
    val unknownValuationExposureDelta: BigDecimal = BigDecimal.ZERO,
    val fee: BigDecimal = BigDecimal.ZERO,
    val slippage: BigDecimal = BigDecimal.ZERO,
    val pauseReason: AutopilotPauseReason? = null,
    val reason: String
)

@Service
class LeaderAutopilotFeedbackService(
    private val feedbackRepository: LeaderAutopilotFeedbackSummaryRepository,
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val eventService: LeaderResearchEventService,
    private val gson: Gson
) {
    @Transactional
    fun recordBuySubmitted(copyTrading: CopyTrading, amount: BigDecimal, orderId: String?): LeaderAutopilotFeedbackSummary? {
        return update(
            copyTrading = copyTrading,
            delta = LeaderAutopilotFeedbackDelta(
                unrealizedPnlDelta = BigDecimal.ZERO,
                reason = "buy-submitted:${orderId ?: "pending"}",
                fee = BigDecimal.ZERO,
                slippage = BigDecimal.ZERO
            ),
            extra = mapOf("buyAmount" to amount.strip(), "orderId" to orderId)
        )
    }

    @Transactional
    fun recordOrderRejected(copyTrading: CopyTrading, reason: String): LeaderAutopilotFeedbackSummary? {
        return update(
            copyTrading = copyTrading,
            delta = LeaderAutopilotFeedbackDelta(rejectedOrderDelta = 1, reason = reason),
            extra = mapOf("rejectionReason" to reason)
        )
    }

    @Transactional
    fun recordFiltered(copyTrading: CopyTrading, reason: String): LeaderAutopilotFeedbackSummary? {
        return update(
            copyTrading = copyTrading,
            delta = LeaderAutopilotFeedbackDelta(filteredOrderDelta = 1, reason = reason),
            extra = mapOf("filterReason" to reason)
        )
    }

    @Transactional
    fun recordRealizedPnl(
        copyTrading: CopyTrading,
        realizedPnlDelta: BigDecimal,
        fee: BigDecimal = BigDecimal.ZERO,
        slippage: BigDecimal = BigDecimal.ZERO,
        reason: String
    ): LeaderAutopilotFeedbackSummary? {
        return update(
            copyTrading = copyTrading,
            delta = LeaderAutopilotFeedbackDelta(
                realizedPnlDelta = realizedPnlDelta,
                fee = fee,
                slippage = slippage,
                reason = reason
            ),
            extra = mapOf(
                "realizedPnlDelta" to realizedPnlDelta.strip(),
                "fee" to fee.strip(),
                "slippage" to slippage.strip()
            )
        )
    }

    @Transactional
    fun recordUnknownValuation(
        copyTrading: CopyTrading,
        exposure: BigDecimal,
        reason: String,
        positionUnavailable: Boolean = false
    ): LeaderAutopilotFeedbackSummary? {
        return update(
            copyTrading = copyTrading,
            delta = LeaderAutopilotFeedbackDelta(
                unknownValuationExposureDelta = exposure,
                pauseReason = if (positionUnavailable) AutopilotPauseReason.POSITION_UNAVAILABLE else AutopilotPauseReason.QUOTE_UNAVAILABLE,
                reason = reason
            ),
            extra = mapOf(
                "unknownValuationExposureDelta" to exposure.strip(),
                "positionUnavailable" to positionUnavailable
            )
        )
    }

    private fun update(
        copyTrading: CopyTrading,
        delta: LeaderAutopilotFeedbackDelta,
        extra: Map<String, Any?>
    ): LeaderAutopilotFeedbackSummary? {
        if (copyTrading.managementMode != CopyTradingManagementMode.AUTOPILOT) return null
        val copyTradingId = copyTrading.id ?: return null
        val now = System.currentTimeMillis()
        val current = feedbackRepository.findByCopyTradingIdForUpdate(copyTradingId)
            ?: LeaderAutopilotFeedbackSummary(
                accountId = copyTrading.accountId,
                candidateId = copyTrading.autopilotCandidateId,
                leaderId = copyTrading.leaderId,
                copyTradingId = copyTradingId,
                createdAt = now,
                updatedAt = now
            )
        val updatedBeforePause = current.copy(
            candidateId = current.candidateId ?: copyTrading.autopilotCandidateId,
            realizedPnl = current.realizedPnl + delta.realizedPnlDelta,
            unrealizedPnl = current.unrealizedPnl + delta.unrealizedPnlDelta,
            rejectedOrderCount = current.rejectedOrderCount + delta.rejectedOrderDelta,
            filteredOrderCount = current.filteredOrderCount + delta.filteredOrderDelta,
            unknownValuationExposure = current.unknownValuationExposure + delta.unknownValuationExposureDelta,
            summaryJson = summaryJson(current, delta, extra, now),
            updatedAt = now
        )
        val pauseReason = delta.pauseReason ?: calculatePauseReason(copyTrading, updatedBeforePause)
        val saved = feedbackRepository.save(updatedBeforePause.copy(pauseReason = pauseReason))
        eventService.record(
            type = LeaderResearchEventType.AUTOPILOT_FEEDBACK_UPDATED,
            candidateId = copyTrading.autopilotCandidateId,
            reason = "Autopilot feedback updated: ${delta.reason}",
            payloadSummary = saved.summaryJson,
            dedupeKey = "autopilot-feedback:${copyTradingId}:${delta.reason}:${now / 60000}"
        )
        if (pauseReason != null) {
            applyPause(copyTrading, pauseReason, now)
        }
        return saved
    }

    private fun calculatePauseReason(copyTrading: CopyTrading, summary: LeaderAutopilotFeedbackSummary): AutopilotPauseReason? {
        if (summary.unknownValuationExposure > BigDecimal.ZERO) return AutopilotPauseReason.QUOTE_UNAVAILABLE
        if (summary.rejectedOrderCount >= REJECTION_PAUSE_COUNT) return AutopilotPauseReason.RISK_REJECTION_RATE
        if (summary.realizedPnl < BigDecimal.ZERO && summary.realizedPnl.abs() >= copyTrading.maxDailyLoss) {
            return AutopilotPauseReason.RISK_DAILY_LOSS
        }
        return null
    }

    private fun applyPause(copyTrading: CopyTrading, reason: AutopilotPauseReason, now: Long) {
        copyTradingRepository.save(
            copyTrading.copy(
                enabled = false,
                autopilotPausedReason = reason,
                autopilotLastDecisionAt = now,
                updatedAt = now
            )
        )
        copyTrading.autopilotCandidateId?.let { candidateId ->
            val candidate = candidateRepository.findById(candidateId).orElse(null)
            if (candidate != null) {
                candidateRepository.save(downgradeCandidate(candidate, reason, now))
            }
        }
        eventService.record(
            type = LeaderResearchEventType.AUTOPILOT_PAUSED,
            candidateId = copyTrading.autopilotCandidateId,
            reason = "Autopilot paused from real feedback: ${reason.name}",
            payloadSummary = "copyTradingId=${copyTrading.id}, leaderId=${copyTrading.leaderId}",
            dedupeKey = "autopilot-feedback-pause:${copyTrading.id}:${reason.name}:${now / 3600000}"
        )
    }

    private fun downgradeCandidate(
        candidate: LeaderResearchCandidate,
        reason: AutopilotPauseReason,
        now: Long
    ): LeaderResearchCandidate {
        val flags = when (reason) {
            AutopilotPauseReason.QUOTE_UNAVAILABLE -> appendFlags(candidate.riskFlags, "UNKNOWN_VALUATION", "QUOTE_UNAVAILABLE")
            AutopilotPauseReason.POSITION_UNAVAILABLE -> appendFlags(candidate.riskFlags, "UNKNOWN_VALUATION", "POSITION_UNAVAILABLE")
            AutopilotPauseReason.RISK_REJECTION_RATE -> appendFlags(candidate.riskFlags, "REAL_MONEY_REJECTION_RATE")
            else -> appendFlags(candidate.riskFlags, "REAL_MONEY_UNDERPERFORMED")
        }
        return candidate.copy(
            researchState = LeaderResearchState.COOLDOWN,
            riskFlags = flags,
            reason = listOfNotNull(candidate.reason, "Real-money feedback paused Autopilot: ${reason.name}")
                .joinToString("\n")
                .take(2000),
            cooldownUntil = now + COOLDOWN_MS,
            cooldownCount = candidate.cooldownCount + 1,
            updatedAt = now
        )
    }

    private fun appendFlags(existing: String?, vararg flags: String): String {
        return (existing.orEmpty().split(",", "\n", ";") + flags)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
    }

    private fun summaryJson(
        current: LeaderAutopilotFeedbackSummary,
        delta: LeaderAutopilotFeedbackDelta,
        extra: Map<String, Any?>,
        now: Long
    ): String {
        return gson.toJson(
            mapOf(
                "previousRealizedPnl" to current.realizedPnl.strip(),
                "realizedPnlDelta" to delta.realizedPnlDelta.strip(),
                "fee" to delta.fee.strip(),
                "slippage" to delta.slippage.strip(),
                "rejectedOrderDelta" to delta.rejectedOrderDelta,
                "filteredOrderDelta" to delta.filteredOrderDelta,
                "unknownValuationExposureDelta" to delta.unknownValuationExposureDelta.strip(),
                "reason" to delta.reason,
                "updatedAt" to now
            ) + extra
        )
    }

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()

    companion object {
        private const val REJECTION_PAUSE_COUNT = 3
        private const val COOLDOWN_MS = 24L * 60 * 60 * 1000
    }
}
