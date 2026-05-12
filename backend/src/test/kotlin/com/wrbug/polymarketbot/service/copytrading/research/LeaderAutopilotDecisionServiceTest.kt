package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.LeaderAutopilotAccountPolicy
import com.wrbug.polymarketbot.entity.LeaderAutopilotDecisionEvent
import com.wrbug.polymarketbot.entity.LeaderAutopilotFeedbackSummary
import com.wrbug.polymarketbot.entity.LeaderAutopilotRiskReservation
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.enums.AutopilotActionType
import com.wrbug.polymarketbot.enums.AutopilotAccountState
import com.wrbug.polymarketbot.enums.AutopilotDecision
import com.wrbug.polymarketbot.enums.AutopilotPauseReason
import com.wrbug.polymarketbot.enums.AutopilotReservationStatus
import com.wrbug.polymarketbot.enums.CopyTradingManagementMode
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderAutopilotAccountPolicyRepository
import com.wrbug.polymarketbot.repository.LeaderAutopilotDecisionEventRepository
import com.wrbug.polymarketbot.repository.LeaderAutopilotFeedbackSummaryRepository
import com.wrbug.polymarketbot.repository.LeaderAutopilotRiskReservationRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.util.Optional

class LeaderAutopilotDecisionServiceTest {
    private val accountRepository: AccountRepository = mock()
    private val copyTradingRepository: CopyTradingRepository = mock()
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val policyRepository: LeaderAutopilotAccountPolicyRepository = mock()
    private val decisionEventRepository: LeaderAutopilotDecisionEventRepository = mock()
    private val reservationRepository: LeaderAutopilotRiskReservationRepository = mock()
    private val feedbackRepository: LeaderAutopilotFeedbackSummaryRepository = mock()
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository = mock()
    private val sellMatchRecordRepository: SellMatchRecordRepository = mock()
    private val systemConfigRepository: SystemConfigRepository = mock()
    private val eventService: LeaderResearchEventService = mock()
    private val service = LeaderAutopilotDecisionService(
        accountRepository,
        copyTradingRepository,
        candidateRepository,
        policyRepository,
        decisionEventRepository,
        reservationRepository,
        feedbackRepository,
        copyOrderTrackingRepository,
        sellMatchRecordRepository,
        systemConfigRepository,
        eventService,
        Gson()
    )

    @Test
    fun `turning autopilot off pauses only autopilot configs`() {
        val now = System.currentTimeMillis()
        val autopilot = CopyTrading(
            id = 10L,
            accountId = 2L,
            leaderId = 9L,
            enabled = true,
            managementMode = CopyTradingManagementMode.AUTOPILOT
        )
        val manual = CopyTrading(
            id = 11L,
            accountId = 2L,
            leaderId = 8L,
            enabled = true,
            managementMode = CopyTradingManagementMode.MANUAL
        )
        Mockito.`when`(accountRepository.findByIdForUpdate(2L)).thenReturn(account())
        Mockito.`when`(policyRepository.findByAccountIdForUpdate(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(policyRepository.save(anyPolicy())).thenAnswer { it.arguments[0] }
        Mockito.`when`(copyTradingRepository.findEnabledByAccountIdAndManagementMode(2L, CopyTradingManagementMode.AUTOPILOT))
            .thenReturn(listOf(autopilot))
        Mockito.`when`(copyTradingRepository.findByAccountIdAndManagementMode(2L, CopyTradingManagementMode.AUTOPILOT))
            .thenReturn(listOf(autopilot))
        Mockito.`when`(copyTradingRepository.save(anyCopyTrading())).thenAnswer { it.arguments[0] }
        Mockito.`when`(decisionEventRepository.findByAccountIdOrderByCreatedAtDesc(2L)).thenReturn(emptyList())

        service.updatePolicy(com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest(accountId = 2L, enabled = false, confirm = true))

        Mockito.verify(copyTradingRepository).save(Mockito.argThat {
            it.id == 10L && !it.enabled && it.autopilotPausedReason == AutopilotPauseReason.USER_DISABLED && it.updatedAt >= now
        })
        Mockito.verify(copyTradingRepository, Mockito.never()).save(Mockito.argThat { it.id == manual.id })
    }

    @Test
    fun `expired pending reservations are released`() {
        val expired = LeaderAutopilotRiskReservation(
            id = 30L,
            reservationKey = "r",
            accountId = 2L,
            copyTradingId = 10L,
            leaderId = 9L,
            amount = BigDecimal.ONE,
            riskWindowStart = 100L,
            riskWindowEnd = 200L,
            expiresAt = 150L
        )
        Mockito.`when`(reservationRepository.findByStatusAndExpiresAtLessThan(AutopilotReservationStatus.PENDING, 1_000L))
            .thenReturn(listOf(expired))
        Mockito.`when`(reservationRepository.save(anyReservation())).thenAnswer { it.arguments[0] }

        val count = service.releaseExpiredReservations(now = 1_000L)

        assertEquals(1, count)
        Mockito.verify(reservationRepository).save(Mockito.argThat {
            it.id == 30L && it.status == AutopilotReservationStatus.EXPIRED && it.reason == "reservation-expired"
        })
    }

    @Test
    fun `convert to manual rejects mismatched account`() {
        Mockito.`when`(copyTradingRepository.findById(10L)).thenReturn(
            Optional.of(
                CopyTrading(
                    id = 10L,
                    accountId = 2L,
                    leaderId = 9L,
                    managementMode = CopyTradingManagementMode.AUTOPILOT
                )
            )
        )

        val result = runCatching {
            service.convertToManual(com.wrbug.polymarketbot.dto.LeaderAutopilotConvertToManualRequest(copyTradingId = 10L, accountId = 3L, confirm = true))
        }

        assertTrue(result.isFailure)
        Mockito.verify(copyTradingRepository, Mockito.never()).save(anyCopyTrading())
    }

    @Test
    fun `buy requires on policy and creates risk reservation`() {
        val copyTrading = autopilotCopyTrading()
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(policyRepository.findByAccountId(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(trialReadyCandidate()))
        Mockito.`when`(copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(10L), Mockito.anyLong()))
            .thenReturn(0)
        Mockito.`when`(reservationRepository.sumOrderSlotsByAccountAndWindow(Mockito.eq(2L), Mockito.anyLong(), anyReservationStatuses()))
            .thenReturn(0)
        Mockito.`when`(sellMatchRecordRepository.sumRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(10L), Mockito.anyLong()))
            .thenReturn(BigDecimal.ZERO)
        Mockito.`when`(copyOrderTrackingRepository.sumOpenPositionValue(10L)).thenReturn(BigDecimal.ZERO)
        Mockito.`when`(reservationRepository.sumAmountByAccountAndWindow(Mockito.eq(2L), Mockito.anyLong(), anyReservationStatuses()))
            .thenReturn(BigDecimal.ZERO)
        Mockito.`when`(reservationRepository.findByReservationKeyForUpdate(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(reservationRepository.save(anyReservation())).thenAnswer {
            (it.arguments[0] as LeaderAutopilotRiskReservation).copy(id = 77L)
        }
        Mockito.`when`(decisionEventRepository.save(anyDecisionEvent())).thenAnswer { it.arguments[0] }

        val result = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                leaderId = 9L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal("2"),
                leaderTradeId = "trade-1",
                inputSnapshot = mapOf("apiKey" to "secret-key", "walletSecret" to "secret-wallet", "marketId" to "m1")
            )
        )

        assertEquals(AutopilotDecision.ALLOW, result.decision)
        assertEquals("ALLOW_WITH_RESERVATION", result.reasonCode)
        assertEquals(77L, result.reservation!!.id)
        Mockito.verify(reservationRepository).save(Mockito.argThat {
            it.accountId == 2L &&
                it.copyTradingId == 10L &&
                it.leaderId == 9L &&
                it.amount == BigDecimal("2") &&
                it.reservationKey.contains("trade-1")
        })
        Mockito.verify(decisionEventRepository).save(Mockito.argThat {
            it.inputSnapshotJson!!.contains("marketId") &&
                !it.inputSnapshotJson!!.contains("secret-key") &&
                !it.inputSnapshotJson!!.contains("secret-wallet")
        })
    }

    @Test
    fun `kill switch pauses buy but allows reduce only sell`() {
        val copyTrading = autopilotCopyTrading()
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(policyRepository.findByAccountId(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(policyRepository.findByAccountIdForUpdate(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(policyRepository.save(anyPolicy())).thenAnswer { it.arguments[0] }
        Mockito.`when`(systemConfigRepository.findByConfigKey(LeaderAutopilotDecisionService.CONFIG_GLOBAL_KILL_SWITCH))
            .thenReturn(SystemConfig(configKey = LeaderAutopilotDecisionService.CONFIG_GLOBAL_KILL_SWITCH, configValue = "true"))
        Mockito.`when`(decisionEventRepository.save(anyDecisionEvent())).thenAnswer { it.arguments[0] }

        val buy = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-2"
            )
        )
        val sell = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.SELL,
                accountId = 2L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-3",
                reduceOnly = true
            )
        )

        assertEquals(AutopilotDecision.PAUSE, buy.decision)
        assertEquals("GLOBAL_KILL_SWITCH", buy.reasonCode)
        assertEquals(AutopilotDecision.ALLOW, sell.decision)
        assertEquals("REDUCE_ONLY_SELL", sell.reasonCode)
        Mockito.verify(copyTradingRepository).save(Mockito.argThat {
            it.id == 10L && !it.enabled && it.autopilotPausedReason == AutopilotPauseReason.KILL_SWITCH
        })
    }

    @Test
    fun `paused policy rejects buy but allows reduce only sell`() {
        val copyTrading = autopilotCopyTrading()
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(policyRepository.findByAccountId(2L)).thenReturn(policy(state = AutopilotAccountState.PAUSED))
        Mockito.`when`(decisionEventRepository.save(anyDecisionEvent())).thenAnswer { it.arguments[0] }

        val buy = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-paused-buy"
            )
        )
        val sell = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.SELL,
                accountId = 2L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-paused-sell",
                reduceOnly = true
            )
        )

        assertEquals(AutopilotDecision.DENY, buy.decision)
        assertEquals("AUTOPILOT_NOT_ON", buy.reasonCode)
        assertEquals(AutopilotDecision.ALLOW, sell.decision)
        assertEquals("REDUCE_ONLY_SELL", sell.reasonCode)
    }

    @Test
    fun `buy pauses account when daily order limit is reached`() {
        val copyTrading = autopilotCopyTrading()
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(policyRepository.findByAccountId(2L)).thenReturn(policy(state = AutopilotAccountState.ON).copy(maxDailyOrders = 1))
        Mockito.`when`(policyRepository.findByAccountIdForUpdate(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(policyRepository.save(anyPolicy())).thenAnswer { it.arguments[0] }
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(trialReadyCandidate()))
        Mockito.`when`(copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(10L), Mockito.anyLong()))
            .thenReturn(1)
        Mockito.`when`(reservationRepository.sumOrderSlotsByAccountAndWindow(Mockito.eq(2L), Mockito.anyLong(), anyReservationStatuses()))
            .thenReturn(0)
        Mockito.`when`(decisionEventRepository.save(anyDecisionEvent())).thenAnswer { it.arguments[0] }

        val result = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-order-limit"
            )
        )

        assertEquals(AutopilotDecision.PAUSE, result.decision)
        assertEquals("DAILY_ORDER_LIMIT", result.reasonCode)
        Mockito.verify(policyRepository).save(Mockito.argThat {
            it.state == AutopilotAccountState.PAUSED && it.pauseReason == AutopilotPauseReason.RISK_DAILY_ORDERS
        })
    }

    @Test
    fun `buy denies when reserved and open exposure exceed budget`() {
        val copyTrading = autopilotCopyTrading()
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(policyRepository.findByAccountId(2L)).thenReturn(policy(state = AutopilotAccountState.ON).copy(maxBudget = BigDecimal("3")))
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(trialReadyCandidate()))
        Mockito.`when`(copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(10L), Mockito.anyLong()))
            .thenReturn(0)
        Mockito.`when`(reservationRepository.sumOrderSlotsByAccountAndWindow(Mockito.eq(2L), Mockito.anyLong(), anyReservationStatuses()))
            .thenReturn(0)
        Mockito.`when`(sellMatchRecordRepository.sumRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(10L), Mockito.anyLong()))
            .thenReturn(BigDecimal.ZERO)
        Mockito.`when`(copyOrderTrackingRepository.sumOpenPositionValue(10L)).thenReturn(BigDecimal("2"))
        Mockito.`when`(reservationRepository.sumAmountByAccountAndWindow(Mockito.eq(2L), Mockito.anyLong(), anyReservationStatuses()))
            .thenReturn(BigDecimal("1"))
        Mockito.`when`(decisionEventRepository.save(anyDecisionEvent())).thenAnswer { it.arguments[0] }

        val result = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-budget"
            )
        )

        assertEquals(AutopilotDecision.DENY, result.decision)
        assertEquals("BUDGET_EXHAUSTED", result.reasonCode)
        Mockito.verify(reservationRepository, Mockito.never()).save(anyReservation())
    }

    @Test
    fun `buy pauses when candidate source is stale`() {
        val copyTrading = autopilotCopyTrading()
        val staleCandidate = trialReadyCandidate().copy(lastSourceSeenAt = System.currentTimeMillis() - 80L * 60 * 60 * 1000)
        stubHappyBuyInputs(copyTrading, staleCandidate)
        Mockito.`when`(policyRepository.findByAccountIdForUpdate(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(policyRepository.save(anyPolicy())).thenAnswer { it.arguments[0] }

        val result = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-source-stale"
            )
        )

        assertEquals(AutopilotDecision.PAUSE, result.decision)
        assertEquals("SOURCE_STALE", result.reasonCode)
        Mockito.verify(policyRepository).save(Mockito.argThat {
            it.state == AutopilotAccountState.PAUSED && it.pauseReason == AutopilotPauseReason.SOURCE_STALE
        })
    }

    @Test
    fun `buy pauses on unknown valuation instead of treating it as confirmed zero`() {
        val copyTrading = autopilotCopyTrading()
        val candidate = trialReadyCandidate().copy(riskFlags = "UNKNOWN_VALUATION")
        stubHappyBuyInputs(copyTrading, candidate)
        Mockito.`when`(policyRepository.findByAccountIdForUpdate(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(policyRepository.save(anyPolicy())).thenAnswer { it.arguments[0] }

        val result = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-unknown-valuation"
            )
        )

        assertEquals(AutopilotDecision.PAUSE, result.decision)
        assertEquals("QUOTE_UNAVAILABLE", result.reasonCode)
        Mockito.verify(policyRepository).save(Mockito.argThat {
            it.state == AutopilotAccountState.PAUSED && it.pauseReason == AutopilotPauseReason.QUOTE_UNAVAILABLE
        })
    }

    @Test
    fun `buy pauses when real feedback shows repeated rejections`() {
        val copyTrading = autopilotCopyTrading()
        stubHappyBuyInputs(copyTrading, trialReadyCandidate())
        Mockito.`when`(feedbackRepository.findByCopyTradingId(10L)).thenReturn(
            LeaderAutopilotFeedbackSummary(
                id = 100L,
                accountId = 2L,
                candidateId = 1L,
                leaderId = 9L,
                copyTradingId = 10L,
                rejectedOrderCount = 4
            )
        )
        Mockito.`when`(policyRepository.findByAccountIdForUpdate(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(policyRepository.save(anyPolicy())).thenAnswer { it.arguments[0] }

        val result = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-rejection-rate"
            )
        )

        assertEquals(AutopilotDecision.PAUSE, result.decision)
        assertEquals("REJECTION_RATE", result.reasonCode)
        Mockito.verify(policyRepository).save(Mockito.argThat {
            it.state == AutopilotAccountState.PAUSED && it.pauseReason == AutopilotPauseReason.RISK_REJECTION_RATE
        })
    }

    @Test
    fun `buy pauses when real feedback records unknown valuation exposure`() {
        val copyTrading = autopilotCopyTrading()
        stubHappyBuyInputs(copyTrading, trialReadyCandidate())
        Mockito.`when`(feedbackRepository.findByCopyTradingId(10L)).thenReturn(
            LeaderAutopilotFeedbackSummary(
                id = 101L,
                accountId = 2L,
                candidateId = 1L,
                leaderId = 9L,
                copyTradingId = 10L,
                unknownValuationExposure = BigDecimal("3.5")
            )
        )
        Mockito.`when`(policyRepository.findByAccountIdForUpdate(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(policyRepository.save(anyPolicy())).thenAnswer { it.arguments[0] }

        val result = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal.ONE,
                leaderTradeId = "trade-feedback-unknown"
            )
        )

        assertEquals(AutopilotDecision.PAUSE, result.decision)
        assertEquals("QUOTE_UNAVAILABLE", result.reasonCode)
    }

    @Test
    fun `duplicate risk reservation conflict reloads existing reservation`() {
        val copyTrading = autopilotCopyTrading()
        val existing = LeaderAutopilotRiskReservation(
            id = 88L,
            reservationKey = "existing",
            accountId = 2L,
            copyTradingId = 10L,
            leaderId = 9L,
            amount = BigDecimal("2"),
            riskWindowStart = 100L,
            riskWindowEnd = 200L,
            expiresAt = 300L
        )
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(policyRepository.findByAccountId(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(trialReadyCandidate()))
        Mockito.`when`(copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(10L), Mockito.anyLong()))
            .thenReturn(0)
        Mockito.`when`(reservationRepository.sumOrderSlotsByAccountAndWindow(Mockito.eq(2L), Mockito.anyLong(), anyReservationStatuses()))
            .thenReturn(0)
        Mockito.`when`(sellMatchRecordRepository.sumRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(10L), Mockito.anyLong()))
            .thenReturn(BigDecimal.ZERO)
        Mockito.`when`(copyOrderTrackingRepository.sumOpenPositionValue(10L)).thenReturn(BigDecimal.ZERO)
        Mockito.`when`(reservationRepository.sumAmountByAccountAndWindow(Mockito.eq(2L), Mockito.anyLong(), anyReservationStatuses()))
            .thenReturn(BigDecimal.ZERO)
        Mockito.`when`(reservationRepository.findByReservationKeyForUpdate(Mockito.anyString()))
            .thenReturn(null)
            .thenReturn(existing)
        Mockito.`when`(reservationRepository.save(anyReservation()))
            .thenThrow(DataIntegrityViolationException("Duplicate reservation key"))
        Mockito.`when`(decisionEventRepository.save(anyDecisionEvent())).thenAnswer { it.arguments[0] }

        val result = service.decide(
            LeaderAutopilotDecisionRequest(
                actionType = AutopilotActionType.BUY,
                accountId = 2L,
                candidateId = 1L,
                leaderId = 9L,
                copyTrading = copyTrading,
                requestedAmount = BigDecimal("2"),
                leaderTradeId = "trade-duplicate"
            )
        )

        assertEquals(AutopilotDecision.ALLOW, result.decision)
        assertEquals(88L, result.reservation!!.id)
    }

    private fun stubHappyBuyInputs(copyTrading: CopyTrading, candidate: LeaderResearchCandidate) {
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(policyRepository.findByAccountId(2L)).thenReturn(policy(state = AutopilotAccountState.ON))
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate))
        Mockito.`when`(copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(copyTrading.id!!), Mockito.anyLong()))
            .thenReturn(0)
        Mockito.`when`(reservationRepository.sumOrderSlotsByAccountAndWindow(Mockito.eq(2L), Mockito.anyLong(), anyReservationStatuses()))
            .thenReturn(0)
        Mockito.`when`(sellMatchRecordRepository.sumRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(copyTrading.id!!), Mockito.anyLong()))
            .thenReturn(BigDecimal.ZERO)
        Mockito.`when`(copyOrderTrackingRepository.sumOpenPositionValue(copyTrading.id!!)).thenReturn(BigDecimal.ZERO)
        Mockito.`when`(reservationRepository.sumAmountByAccountAndWindow(Mockito.eq(2L), Mockito.anyLong(), anyReservationStatuses()))
            .thenReturn(BigDecimal.ZERO)
        Mockito.`when`(reservationRepository.findByReservationKeyForUpdate(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(reservationRepository.save(anyReservation())).thenAnswer {
            (it.arguments[0] as LeaderAutopilotRiskReservation).copy(id = 77L)
        }
        Mockito.`when`(decisionEventRepository.save(anyDecisionEvent())).thenAnswer { it.arguments[0] }
    }

    private fun account() = Account(
        id = 2L,
        privateKey = "enc",
        walletAddress = "0x2222222222222222222222222222222222222222",
        proxyAddress = "0x3333333333333333333333333333333333333333"
    )

    private fun policy(state: AutopilotAccountState) = LeaderAutopilotAccountPolicy(
        id = 1L,
        accountId = 2L,
        state = state
    )

    private fun trialReadyCandidate() = LeaderResearchCandidate(
        id = 1L,
        normalizedWallet = "0x1111111111111111111111111111111111111111",
        leaderId = 9L,
        researchState = LeaderResearchState.TRIAL_READY
    )

    private fun autopilotCopyTrading() = CopyTrading(
        id = 10L,
        accountId = 2L,
        leaderId = 9L,
        enabled = true,
        managementMode = CopyTradingManagementMode.AUTOPILOT,
        autopilotCandidateId = 1L,
        fixedAmount = BigDecimal("2"),
        minPrice = BigDecimal("0.2"),
        maxPrice = BigDecimal("0.7")
    )

    private fun anyPolicy(): LeaderAutopilotAccountPolicy {
        Mockito.any(LeaderAutopilotAccountPolicy::class.java)
        return policy(AutopilotAccountState.OFF)
    }

    private fun anyCopyTrading(): CopyTrading {
        Mockito.any(CopyTrading::class.java)
        return CopyTrading(accountId = 2L, leaderId = 9L)
    }

    private fun anyReservation(): LeaderAutopilotRiskReservation {
        Mockito.any(LeaderAutopilotRiskReservation::class.java)
        return LeaderAutopilotRiskReservation(
            reservationKey = "fallback",
            accountId = 2L,
            copyTradingId = 10L,
            leaderId = 9L,
            riskWindowStart = 0L,
            riskWindowEnd = 1L,
            expiresAt = 1L
        )
    }

    private fun anyDecisionEvent(): LeaderAutopilotDecisionEvent {
        Mockito.any(LeaderAutopilotDecisionEvent::class.java)
        return LeaderAutopilotDecisionEvent(
            actionType = AutopilotActionType.BUY,
            decision = AutopilotDecision.ALLOW,
            reasonCode = "ALLOW",
            accountId = 2L
        )
    }

    private fun anyReservationStatuses(): Collection<AutopilotReservationStatus> {
        Mockito.anyCollection<AutopilotReservationStatus>()
        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
