package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.dto.LeaderAutopilotConvertToManualRequest
import com.wrbug.polymarketbot.dto.LeaderAutopilotDecisionEventDto
import com.wrbug.polymarketbot.dto.LeaderAutopilotPolicyDto
import com.wrbug.polymarketbot.dto.LeaderAutopilotStatusDto
import com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.LeaderAutopilotAccountPolicy
import com.wrbug.polymarketbot.entity.LeaderAutopilotDecisionEvent
import com.wrbug.polymarketbot.entity.LeaderAutopilotRiskReservation
import com.wrbug.polymarketbot.enums.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class LeaderAutopilotDecisionDeniedException(message: String) : RuntimeException(message)
class LeaderAutopilotConfirmRequiredException(message: String) : RuntimeException(message)

data class LeaderAutopilotDecisionRequest(
    val actionType: AutopilotActionType,
    val accountId: Long,
    val candidateId: Long? = null,
    val leaderId: Long? = null,
    val copyTrading: CopyTrading? = null,
    val requestedAmount: BigDecimal? = null,
    val leaderTradeId: String? = null,
    val reduceOnly: Boolean = false,
    val inputSnapshot: Map<String, Any?> = emptyMap()
)

data class LeaderAutopilotDecisionResult(
    val decision: AutopilotDecision,
    val reasonCode: String,
    val reason: String,
    val policy: LeaderAutopilotAccountPolicy?,
    val reservation: LeaderAutopilotRiskReservation? = null
) {
    val allowed: Boolean = decision == AutopilotDecision.ALLOW
}

@Service
class LeaderAutopilotDecisionService(
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val policyRepository: LeaderAutopilotAccountPolicyRepository,
    private val decisionEventRepository: LeaderAutopilotDecisionEventRepository,
    private val reservationRepository: LeaderAutopilotRiskReservationRepository,
    private val feedbackRepository: LeaderAutopilotFeedbackSummaryRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val systemConfigRepository: SystemConfigRepository,
    private val eventService: LeaderResearchEventService,
    private val gson: Gson
) {
    private val logger = LoggerFactory.getLogger(LeaderAutopilotDecisionService::class.java)

    fun status(accountId: Long): LeaderAutopilotStatusDto {
        val policy = policyRepository.findByAccountId(accountId) ?: defaultPolicy(accountId)
        val managed = copyTradingRepository.findByAccountIdAndManagementMode(accountId, CopyTradingManagementMode.AUTOPILOT)
        val events = decisionEventRepository.findByAccountIdOrderByCreatedAtDesc(accountId).take(20)
        return LeaderAutopilotStatusDto(
            policy = policyDto(policy),
            managedConfigCount = managed.size,
            enabledManagedConfigCount = managed.count { it.enabled },
            recentEvents = events.map { eventDto(it) }
        )
    }

    @Transactional
    fun updatePolicy(request: LeaderAutopilotUpdateRequest): LeaderAutopilotStatusDto {
        if (!request.confirm) {
            throw LeaderAutopilotConfirmRequiredException("Autopilot 状态变更需要显式确认")
        }
        val account = accountRepository.findByIdForUpdate(request.accountId)
            ?: throw IllegalArgumentException("账户不存在")
        val now = System.currentTimeMillis()
        val current = policyRepository.findByAccountIdForUpdate(account.id!!)
            ?: defaultPolicy(account.id)

        val nextState = when {
            request.resume -> AutopilotAccountState.ON
            request.enabled == true -> AutopilotAccountState.ON
            request.enabled == false -> AutopilotAccountState.OFF
            else -> current.state
        }
        val updated = policyRepository.save(
            current.copy(
                state = nextState,
                maxBudget = request.maxBudget?.toSafeBigDecimal()?.positiveOr(current.maxBudget) ?: current.maxBudget,
                singleLeaderMaxAmount = request.singleLeaderMaxAmount?.toSafeBigDecimal()?.positiveOr(current.singleLeaderMaxAmount)
                    ?: current.singleLeaderMaxAmount,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal()?.positiveOr(current.maxDailyLoss) ?: current.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders?.coerceAtLeast(1) ?: current.maxDailyOrders,
                maxPositionValue = request.maxPositionValue?.toSafeBigDecimal()?.positiveOr(current.maxPositionValue) ?: current.maxPositionValue,
                minPrice = request.minPrice?.toSafeBigDecimal() ?: current.minPrice,
                maxPrice = request.maxPrice?.toSafeBigDecimal() ?: current.maxPrice,
                pauseReason = if (nextState == AutopilotAccountState.OFF) AutopilotPauseReason.USER_DISABLED else null,
                lastDecisionAt = now,
                updatedAt = now
            )
        )
        if (request.resume && nextState == AutopilotAccountState.ON) {
            resumeManagedConfigs(account.id, updated.id, now)
        }
        if (nextState == AutopilotAccountState.OFF) {
            pauseManagedConfigs(account.id, AutopilotPauseReason.USER_DISABLED, now)
        }
        recordDecision(
            request = LeaderAutopilotDecisionRequest(
                actionType = if (request.resume) AutopilotActionType.RESUME else AutopilotActionType.ENABLE_CONFIG,
                accountId = account.id,
                inputSnapshot = mapOf("requestedState" to nextState.name)
            ),
            result = LeaderAutopilotDecisionResult(AutopilotDecision.ALLOW, "POLICY_UPDATED", "Autopilot policy updated", updated)
        )
        return status(account.id)
    }

    @Transactional
    fun convertToManual(request: LeaderAutopilotConvertToManualRequest): CopyTrading {
        if (!request.confirm) {
            throw LeaderAutopilotConfirmRequiredException("转为手动管理需要显式确认")
        }
        val copyTrading = copyTradingRepository.findById(request.copyTradingId).orElse(null)
            ?: throw IllegalArgumentException("跟单配置不存在")
        if (request.accountId != null && copyTrading.accountId != request.accountId) {
            throw IllegalArgumentException("跟单配置不属于该账户")
        }
        if (copyTrading.managementMode == CopyTradingManagementMode.MANUAL) {
            return copyTrading
        }
        val now = System.currentTimeMillis()
        val updated = copyTradingRepository.save(
            copyTrading.copy(
                managementMode = CopyTradingManagementMode.MANUAL,
                autopilotPolicyId = null,
                autopilotPausedReason = null,
                autopilotLastDecisionAt = now,
                updatedAt = now
            )
        )
        recordDecision(
            request = LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.CONVERT_TO_MANUAL,
                accountId = copyTrading.accountId,
                leaderId = copyTrading.leaderId,
                copyTrading = copyTrading
            ),
            result = LeaderAutopilotDecisionResult(
                AutopilotDecision.ALLOW,
                "CONVERTED_TO_MANUAL",
                "Autopilot config converted to manual",
                policyRepository.findByAccountId(copyTrading.accountId)
            )
        )
        eventService.record(
            type = LeaderResearchEventType.AUTOPILOT_CONFIG_CONVERTED_TO_MANUAL,
            candidateId = copyTrading.autopilotCandidateId,
            reason = "copyTradingId=${copyTrading.id} converted to MANUAL",
            dedupeKey = "autopilot-convert:${copyTrading.id}:$now"
        )
        return updated
    }

    @Transactional
    fun decide(request: LeaderAutopilotDecisionRequest): LeaderAutopilotDecisionResult {
        val result = evaluate(request)
        recordDecision(request, result)
        if (result.decision == AutopilotDecision.PAUSE) {
            pauseAccountOrConfig(request, result.reasonCode)
        }
        return result
    }

    @Transactional
    fun releaseReservation(reservationId: Long?, reason: String) {
        if (reservationId == null) return
        val reservation = reservationRepository.findById(reservationId).orElse(null) ?: return
        if (reservation.status != AutopilotReservationStatus.PENDING) return
        reservationRepository.save(
            reservation.copy(
                status = AutopilotReservationStatus.RELEASED,
                reason = reason,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    @Transactional
    fun releaseExpiredReservations(now: Long = System.currentTimeMillis()): Int {
        val expired = reservationRepository.findByStatusAndExpiresAtLessThan(AutopilotReservationStatus.PENDING, now)
        expired.forEach {
            reservationRepository.save(
                it.copy(
                    status = AutopilotReservationStatus.EXPIRED,
                    reason = "reservation-expired",
                    updatedAt = now
                )
            )
        }
        if (expired.isNotEmpty()) {
            logger.warn("Released expired Autopilot reservations: count={}", expired.size)
        }
        return expired.size
    }

    @Transactional
    fun finalizeReservation(reservationId: Long?, orderId: String?) {
        if (reservationId == null) return
        val reservation = reservationRepository.findById(reservationId).orElse(null) ?: return
        if (reservation.status != AutopilotReservationStatus.PENDING) return
        val now = System.currentTimeMillis()
        reservationRepository.save(
            reservation.copy(
                status = AutopilotReservationStatus.FINALIZED,
                orderId = orderId,
                finalizedAt = now,
                updatedAt = now
            )
        )
    }

    private fun evaluate(request: LeaderAutopilotDecisionRequest): LeaderAutopilotDecisionResult {
        val account = accountRepository.findById(request.accountId).orElse(null)
            ?: return deny(request, "ACCOUNT_NOT_FOUND", "账户不存在")
        if (!account.isEnabled) {
            return deny(request, "ACCOUNT_DISABLED", "账户已禁用")
        }
        val policy = policyRepository.findByAccountId(request.accountId) ?: defaultPolicy(request.accountId)
        if (isGlobalKillSwitchEnabled()) {
            if (canAllowReduceOnlySell(request)) {
                return allow(policy, "REDUCE_ONLY_SELL", "Kill switch active; reduce-only sell allowed")
            }
            return pause(policy, "GLOBAL_KILL_SWITCH", "Autopilot global kill switch is active")
        }
        if (policy.state != AutopilotAccountState.ON) {
            if (canAllowReduceOnlySell(request)) {
                return allow(policy, "REDUCE_ONLY_SELL", "Paused/off account; reduce-only sell allowed")
            }
            return deny(request, "AUTOPILOT_NOT_ON", "Autopilot is ${policy.state}")
        }
        val copyTrading = request.copyTrading
        if (copyTrading != null && copyTrading.managementMode != CopyTradingManagementMode.AUTOPILOT) {
            return allow(policy, "MANUAL_CONFIG", "Manual config is outside Autopilot gate")
        }
        val candidate = request.candidateId?.let { candidateRepository.findById(it).orElse(null) }
            ?: copyTrading?.autopilotCandidateId?.let { candidateRepository.findById(it).orElse(null) }
        if (request.actionType != AutopilotActionType.SELL && request.actionType != AutopilotActionType.CONVERT_TO_MANUAL) {
            val candidatePause = candidatePauseReason(candidate)
            if (candidatePause != null) {
                return pause(policy, candidatePause.first, candidatePause.second)
            }
            val candidateBlock = candidateBlockReason(candidate)
            if (candidateBlock != null) {
                return deny(request, "CANDIDATE_BLOCKED", candidateBlock)
            }
            val feedbackPause = feedbackPauseReason(policy, copyTrading)
            if (feedbackPause != null) {
                return pause(policy, feedbackPause.first, feedbackPause.second)
            }
        }
        if (request.actionType in setOf(
                AutopilotActionType.CREATE_CONFIG,
                AutopilotActionType.ENABLE_CONFIG,
                AutopilotActionType.RESUME
            )
        ) {
            return evaluateConfigAction(request, policy, copyTrading)
        }
        if (request.actionType == AutopilotActionType.BUY && copyTrading != null) {
            return evaluateBuy(request, policy, copyTrading)
        }
        return allow(policy, "ALLOW", "Autopilot decision allowed")
    }

    private fun canAllowReduceOnlySell(request: LeaderAutopilotDecisionRequest): Boolean {
        return request.actionType == AutopilotActionType.SELL &&
            request.reduceOnly &&
            request.copyTrading?.managementMode == CopyTradingManagementMode.AUTOPILOT
    }

    private fun evaluateBuy(
        request: LeaderAutopilotDecisionRequest,
        policy: LeaderAutopilotAccountPolicy,
        copyTrading: CopyTrading
    ): LeaderAutopilotDecisionResult {
        val amount = request.requestedAmount ?: copyTrading.fixedAmount ?: BigDecimal.ZERO
        if (amount <= BigDecimal.ZERO) {
            return deny(request, "INVALID_AMOUNT", "Autopilot BUY amount must be positive")
        }
        if (amount > policy.singleLeaderMaxAmount) {
            return deny(request, "SINGLE_LEADER_LIMIT", "单 leader 金额超过上限")
        }
        val minPrice = policy.minPrice
        val maxPrice = policy.maxPrice
        if (copyTrading.minPrice != null && minPrice != null && copyTrading.minPrice < minPrice) {
            return deny(request, "PRICE_RANGE_TOO_WIDE", "配置最低价低于 Autopilot policy")
        }
        if (copyTrading.maxPrice != null && maxPrice != null && copyTrading.maxPrice > maxPrice) {
            return deny(request, "PRICE_RANGE_TOO_WIDE", "配置最高价高于 Autopilot policy")
        }
        val window = utcDayWindow()
        val todayOrders = copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(copyTrading.id!!, window.first)
        val reservedOrders = reservationRepository.sumOrderSlotsByAccountAndWindow(
            policy.accountId,
            window.first,
            listOf(AutopilotReservationStatus.PENDING, AutopilotReservationStatus.FINALIZED)
        )
        if (todayOrders + reservedOrders >= policy.maxDailyOrders.toLong()) {
            return pause(policy, "DAILY_ORDER_LIMIT", "今日订单数已达 Autopilot 上限")
        }
        val todayPnl = sellMatchRecordRepository.sumRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(copyTrading.id, window.first)
        if (todayPnl < BigDecimal.ZERO && todayPnl.abs() >= policy.maxDailyLoss) {
            return pause(policy, "DAILY_LOSS_LIMIT", "今日已实现亏损达到 Autopilot 上限")
        }
        val totalPnl = feedbackRepository.findByCopyTradingId(copyTrading.id)?.realizedPnl ?: BigDecimal.ZERO
        if (totalPnl < BigDecimal.ZERO && totalPnl.abs() >= policy.maxDailyLoss) {
            return pause(policy, "MAX_DRAWDOWN", "真实跟单累计回撤达到 Autopilot 上限")
        }
        val openExposure = copyOrderTrackingRepository.sumOpenPositionValue(copyTrading.id)
        val reservedAmount = reservationRepository.sumAmountByAccountAndWindow(
            policy.accountId,
            window.first,
            listOf(AutopilotReservationStatus.PENDING, AutopilotReservationStatus.FINALIZED)
        )
        if (openExposure + reservedAmount + amount > policy.maxBudget) {
            return deny(request, "BUDGET_EXHAUSTED", "账户级 Autopilot 预算不足")
        }
        if (openExposure + amount > policy.maxPositionValue) {
            return deny(request, "POSITION_LIMIT", "Autopilot 仓位上限不足")
        }
        val reservation = reserveBudget(request, copyTrading, amount, window)
        return allow(policy, "ALLOW_WITH_RESERVATION", "Autopilot BUY allowed", reservation)
    }

    private fun evaluateConfigAction(
        request: LeaderAutopilotDecisionRequest,
        policy: LeaderAutopilotAccountPolicy,
        copyTrading: CopyTrading?
    ): LeaderAutopilotDecisionResult {
        val amount = request.requestedAmount ?: copyTrading?.fixedAmount ?: BigDecimal.ZERO
        if (amount <= BigDecimal.ZERO) {
            return deny(request, "INVALID_AMOUNT", "Autopilot config amount must be positive")
        }
        if (amount > policy.singleLeaderMaxAmount) {
            return deny(request, "SINGLE_LEADER_LIMIT", "单 leader 金额超过 Autopilot 上限")
        }
        val minPrice = policy.minPrice
        val maxPrice = policy.maxPrice
        if (copyTrading?.minPrice != null && minPrice != null && copyTrading.minPrice < minPrice) {
            return deny(request, "PRICE_RANGE_TOO_WIDE", "配置最低价低于 Autopilot policy")
        }
        if (copyTrading?.maxPrice != null && maxPrice != null && copyTrading.maxPrice > maxPrice) {
            return deny(request, "PRICE_RANGE_TOO_WIDE", "配置最高价高于 Autopilot policy")
        }
        val window = utcDayWindow()
        val reservedAmount = reservationRepository.sumAmountByAccountAndWindow(
            policy.accountId,
            window.first,
            listOf(AutopilotReservationStatus.PENDING, AutopilotReservationStatus.FINALIZED)
        )
        if (reservedAmount + amount > policy.maxBudget) {
            return deny(request, "BUDGET_EXHAUSTED", "账户级 Autopilot 预算不足")
        }
        return allow(policy, "ALLOW", "Autopilot config action allowed")
    }

    private fun reserveBudget(
        request: LeaderAutopilotDecisionRequest,
        copyTrading: CopyTrading,
        amount: BigDecimal,
        window: Pair<Long, Long>
    ): LeaderAutopilotRiskReservation {
        val reservationKey = listOf(
            request.accountId,
            copyTrading.id,
            request.leaderTradeId ?: "manual",
            request.actionType.name,
            window.first
        ).joinToString(":")
        val existing = reservationRepository.findByReservationKeyForUpdate(reservationKey)
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        return try {
            reservationRepository.save(
                LeaderAutopilotRiskReservation(
                    reservationKey = reservationKey,
                    accountId = request.accountId,
                    copyTradingId = copyTrading.id!!,
                    leaderId = copyTrading.leaderId,
                    candidateId = copyTrading.autopilotCandidateId ?: request.candidateId,
                    leaderTradeId = request.leaderTradeId,
                    amount = amount,
                    riskWindowStart = window.first,
                    riskWindowEnd = window.second,
                    expiresAt = now + RESERVATION_TTL_MS,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } catch (e: DataIntegrityViolationException) {
            reservationRepository.findByReservationKeyForUpdate(reservationKey) ?: throw e
        }
    }

    private fun candidateBlockReason(candidate: com.wrbug.polymarketbot.entity.LeaderResearchCandidate?): String? {
        if (candidate == null) return "缺少研究候选，不能自动真钱跟单"
        if (candidate.locked) return "研究候选已锁定"
        if (candidate.researchState != LeaderResearchState.TRIAL_READY) return "研究候选尚未进入 TRIAL_READY"
        if (candidate.retiredAt != null) return "研究候选已退休"
        if (candidate.cooldownUntil != null && candidate.cooldownUntil > System.currentTimeMillis()) return "研究候选仍在冷却"
        return null
    }

    private fun candidatePauseReason(candidate: com.wrbug.polymarketbot.entity.LeaderResearchCandidate?): Pair<String, String>? {
        if (candidate == null) return null
        val riskFlags = candidate.riskFlags.orEmpty()
        if (candidate.lastSourceSeenAt != null && System.currentTimeMillis() - candidate.lastSourceSeenAt > SOURCE_STALE_MS) {
            return "SOURCE_STALE" to "候选外部来源已过期，暂停 Autopilot"
        }
        if (riskFlags.contains("POSITION_UNAVAILABLE", ignoreCase = true)) {
            return "POSITION_UNAVAILABLE" to "候选存在 position unavailable 风险"
        }
        if (riskFlags.contains("QUOTE_UNAVAILABLE", ignoreCase = true) ||
            riskFlags.contains("UNKNOWN", ignoreCase = true) ||
            riskFlags.contains("UNAVAILABLE", ignoreCase = true)
        ) {
            return "QUOTE_UNAVAILABLE" to "候选存在 UNKNOWN/UNAVAILABLE valuation 风险"
        }
        return null
    }

    private fun feedbackPauseReason(
        policy: LeaderAutopilotAccountPolicy,
        copyTrading: CopyTrading?
    ): Pair<String, String>? {
        val copyTradingId = copyTrading?.id ?: return null
        val feedback = feedbackRepository.findByCopyTradingId(copyTradingId) ?: return null
        if (feedback.pauseReason == AutopilotPauseReason.POSITION_UNAVAILABLE) {
            return "POSITION_UNAVAILABLE" to "真实反馈显示 position unavailable"
        }
        if (feedback.pauseReason == AutopilotPauseReason.QUOTE_UNAVAILABLE ||
            feedback.unknownValuationExposure > BigDecimal.ZERO
        ) {
            return "QUOTE_UNAVAILABLE" to "真实反馈存在 UNKNOWN/UNAVAILABLE valuation 暴露"
        }
        if (feedback.rejectedOrderCount >= REJECTION_PAUSE_COUNT) {
            return "REJECTION_RATE" to "真实下单拒单次数达到 Autopilot 暂停阈值"
        }
        if (feedback.realizedPnl < BigDecimal.ZERO && feedback.realizedPnl.abs() >= policy.maxDailyLoss) {
            return "DAILY_LOSS_LIMIT" to "真实跟单亏损达到 Autopilot 日亏损阈值"
        }
        return null
    }

    private fun pauseManagedConfigs(accountId: Long, reason: AutopilotPauseReason, now: Long) {
        copyTradingRepository.findEnabledByAccountIdAndManagementMode(accountId, CopyTradingManagementMode.AUTOPILOT)
            .forEach {
                copyTradingRepository.save(
                    it.copy(
                        enabled = false,
                        autopilotPausedReason = reason,
                        autopilotLastDecisionAt = now,
                        updatedAt = now
                    )
                )
            }
    }

    private fun resumeManagedConfigs(accountId: Long, policyId: Long?, now: Long) {
        copyTradingRepository.findByAccountIdAndManagementMode(accountId, CopyTradingManagementMode.AUTOPILOT)
            .filter { !it.enabled && it.autopilotPausedReason != AutopilotPauseReason.USER_DISABLED }
            .forEach {
                val request = LeaderAutopilotDecisionRequest(
                    actionType = AutopilotActionType.RESUME,
                    accountId = accountId,
                    candidateId = it.autopilotCandidateId,
                    leaderId = it.leaderId,
                    copyTrading = it,
                    requestedAmount = it.fixedAmount,
                    inputSnapshot = mapOf("source" to "autopilot.resume", "copyTradingId" to it.id)
                )
                val decision = evaluate(request)
                recordDecision(request, decision)
                if (decision.allowed) {
                    copyTradingRepository.save(
                        it.copy(
                            enabled = true,
                            autopilotPolicyId = it.autopilotPolicyId ?: policyId,
                            autopilotPausedReason = null,
                            autopilotLastDecisionAt = now,
                            updatedAt = now
                        )
                    )
                }
            }
    }

    private fun pauseAccountOrConfig(request: LeaderAutopilotDecisionRequest, reasonCode: String) {
        val now = System.currentTimeMillis()
        val reason = reasonToPause(reasonCode)
        val policy = policyRepository.findByAccountIdForUpdate(request.accountId) ?: return
        policyRepository.save(
            policy.copy(
                state = AutopilotAccountState.PAUSED,
                pauseReason = reason,
                lastDecisionAt = now,
                updatedAt = now
            )
        )
        request.copyTrading?.let {
            copyTradingRepository.save(
                it.copy(
                    enabled = false,
                    autopilotPausedReason = reason,
                    autopilotLastDecisionAt = now,
                    updatedAt = now
                )
            )
        }
        eventService.record(
            type = LeaderResearchEventType.AUTOPILOT_PAUSED,
            candidateId = request.candidateId ?: request.copyTrading?.autopilotCandidateId,
            reason = "Autopilot paused: $reasonCode",
            dedupeKey = "autopilot-pause:${request.accountId}:$reasonCode:${now / 3600000}"
        )
    }

    private fun recordDecision(request: LeaderAutopilotDecisionRequest, result: LeaderAutopilotDecisionResult) {
        val now = System.currentTimeMillis()
        decisionEventRepository.save(
            LeaderAutopilotDecisionEvent(
                actionType = request.actionType,
                decision = result.decision,
                reasonCode = result.reasonCode,
                reason = result.reason,
                accountId = request.accountId,
                candidateId = request.candidateId ?: request.copyTrading?.autopilotCandidateId,
                leaderId = request.leaderId ?: request.copyTrading?.leaderId,
                copyTradingId = request.copyTrading?.id,
                reservationId = result.reservation?.id,
                inputSnapshotJson = sanitizedSnapshot(request),
                createdAt = now
            )
        )
        eventService.record(
            type = LeaderResearchEventType.AUTOPILOT_DECISION,
            candidateId = request.candidateId ?: request.copyTrading?.autopilotCandidateId,
            reason = "${request.actionType.name} ${result.decision.name}: ${result.reasonCode}",
            payloadSummary = result.reason,
            dedupeKey = "autopilot-decision:${request.actionType.name}:${request.accountId}:${request.leaderTradeId ?: now}:${result.reasonCode}"
        )
    }

    private fun sanitizedSnapshot(request: LeaderAutopilotDecisionRequest): String {
        val allowed = request.inputSnapshot
            .filterKeys { key ->
                val lowered = key.lowercase()
                !lowered.contains("secret") &&
                    !lowered.contains("key") &&
                    !lowered.contains("passphrase") &&
                    !lowered.contains("token") &&
                    !lowered.contains("private")
            }
        return gson.toJson(
            allowed + mapOf(
                "actionType" to request.actionType.name,
                "requestedAmount" to request.requestedAmount?.stripTrailingZeros()?.toPlainString(),
                "reduceOnly" to request.reduceOnly
            )
        )
    }

    private fun defaultPolicy(accountId: Long): LeaderAutopilotAccountPolicy {
        return LeaderAutopilotAccountPolicy(accountId = accountId)
    }

    private fun policyDto(policy: LeaderAutopilotAccountPolicy): LeaderAutopilotPolicyDto {
        return LeaderAutopilotPolicyDto(
            id = policy.id,
            accountId = policy.accountId,
            state = policy.state.name,
            globalKillSwitch = isGlobalKillSwitchEnabled(),
            maxBudget = policy.maxBudget.strip(),
            singleLeaderMaxAmount = policy.singleLeaderMaxAmount.strip(),
            maxDailyLoss = policy.maxDailyLoss.strip(),
            maxDailyOrders = policy.maxDailyOrders,
            maxPositionValue = policy.maxPositionValue.strip(),
            minPrice = policy.minPrice?.strip(),
            maxPrice = policy.maxPrice?.strip(),
            pauseReason = policy.pauseReason?.name,
            lastDecisionAt = policy.lastDecisionAt,
            createdAt = policy.createdAt,
            updatedAt = policy.updatedAt
        )
    }

    fun eventDto(event: LeaderAutopilotDecisionEvent): LeaderAutopilotDecisionEventDto {
        return LeaderAutopilotDecisionEventDto(
            id = event.id ?: 0,
            actionType = event.actionType.name,
            decision = event.decision.name,
            reasonCode = event.reasonCode,
            reason = event.reason,
            accountId = event.accountId,
            candidateId = event.candidateId,
            leaderId = event.leaderId,
            copyTradingId = event.copyTradingId,
            reservationId = event.reservationId,
            createdAt = event.createdAt
        )
    }

    fun policyDtoForAccount(accountId: Long): LeaderAutopilotPolicyDto {
        return policyDto(policyRepository.findByAccountId(accountId) ?: defaultPolicy(accountId))
    }

    fun isGlobalKillSwitchEnabled(): Boolean {
        return systemConfigRepository.findByConfigKey(CONFIG_GLOBAL_KILL_SWITCH)
            ?.configValue
            ?.lowercase() == "true"
    }

    private fun allow(
        policy: LeaderAutopilotAccountPolicy,
        code: String,
        reason: String,
        reservation: LeaderAutopilotRiskReservation? = null
    ) = LeaderAutopilotDecisionResult(AutopilotDecision.ALLOW, code, reason, policy, reservation)

    private fun deny(
        request: LeaderAutopilotDecisionRequest,
        code: String,
        reason: String
    ) = LeaderAutopilotDecisionResult(
        AutopilotDecision.DENY,
        code,
        reason,
        policyRepository.findByAccountId(request.accountId) ?: defaultPolicy(request.accountId)
    )

    private fun pause(
        policy: LeaderAutopilotAccountPolicy,
        code: String,
        reason: String
    ) = LeaderAutopilotDecisionResult(AutopilotDecision.PAUSE, code, reason, policy)

    private fun reasonToPause(reasonCode: String): AutopilotPauseReason {
        return when (reasonCode) {
            "GLOBAL_KILL_SWITCH" -> AutopilotPauseReason.KILL_SWITCH
            "DAILY_ORDER_LIMIT" -> AutopilotPauseReason.RISK_DAILY_ORDERS
            "DAILY_LOSS_LIMIT" -> AutopilotPauseReason.RISK_DAILY_LOSS
            "MAX_DRAWDOWN" -> AutopilotPauseReason.RISK_MAX_DRAWDOWN
            "REJECTION_RATE" -> AutopilotPauseReason.RISK_REJECTION_RATE
            "BUDGET_EXHAUSTED" -> AutopilotPauseReason.RISK_BUDGET_EXHAUSTED
            "POSITION_LIMIT" -> AutopilotPauseReason.RISK_POSITION_LIMIT
            "SOURCE_STALE" -> AutopilotPauseReason.SOURCE_STALE
            "QUOTE_UNAVAILABLE" -> AutopilotPauseReason.QUOTE_UNAVAILABLE
            "POSITION_UNAVAILABLE" -> AutopilotPauseReason.POSITION_UNAVAILABLE
            else -> AutopilotPauseReason.UNKNOWN
        }
    }

    private fun utcDayWindow(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        return start to start + 86_400_000L
    }

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()

    private fun BigDecimal.positiveOr(fallback: BigDecimal): BigDecimal {
        return if (this > BigDecimal.ZERO) this else fallback
    }

    companion object {
        const val CONFIG_GLOBAL_KILL_SWITCH = "leader.autopilot.global-kill-switch"
        private const val RESERVATION_TTL_MS = 15L * 60 * 1000
        private const val SOURCE_STALE_MS = 72L * 60 * 60 * 1000
        private const val REJECTION_PAUSE_COUNT = 3
    }
}

@Service
class LeaderAutopilotReservationCleanupJob(
    private val autopilotService: LeaderAutopilotDecisionService
) {
    @Scheduled(fixedDelayString = "\${leader.autopilot.reservation-cleanup-delay-ms:60000}")
    fun cleanupExpiredReservations() {
        autopilotService.releaseExpiredReservations()
    }
}
