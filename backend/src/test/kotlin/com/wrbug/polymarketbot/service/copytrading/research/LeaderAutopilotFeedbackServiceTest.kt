package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.LeaderAutopilotFeedbackSummary
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.AutopilotPauseReason
import com.wrbug.polymarketbot.enums.CopyTradingManagementMode
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchNotificationStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderAutopilotFeedbackSummaryRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.util.Optional

class LeaderAutopilotFeedbackServiceTest {
    private val feedbackRepository: LeaderAutopilotFeedbackSummaryRepository = mock()
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val copyTradingRepository: CopyTradingRepository = mock()
    private val eventService: LeaderResearchEventService = mock()
    private val service = LeaderAutopilotFeedbackService(
        feedbackRepository = feedbackRepository,
        candidateRepository = candidateRepository,
        copyTradingRepository = copyTradingRepository,
        eventService = eventService,
        gson = Gson()
    )

    @Test
    fun `real loss pauses autopilot config and cools candidate`() {
        val copyTrading = autopilotCopyTrading(maxDailyLoss = "5")
        val candidate = candidate()
        Mockito.`when`(feedbackRepository.findByCopyTradingIdForUpdate(10L)).thenReturn(null)
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate))
        Mockito.`when`(feedbackRepository.save(anyFeedback())).thenAnswer { it.arguments[0] }
        Mockito.`when`(candidateRepository.save(anyCandidate())).thenAnswer { it.arguments[0] }
        Mockito.`when`(copyTradingRepository.save(anyCopyTrading())).thenAnswer { it.arguments[0] }

        val summary = service.recordRealizedPnl(
            copyTrading = copyTrading,
            realizedPnlDelta = BigDecimal("-6"),
            fee = BigDecimal("0.1"),
            slippage = BigDecimal("0.2"),
            reason = "sell-match"
        )!!

        assertEquals(AutopilotPauseReason.RISK_DAILY_LOSS, summary.pauseReason)
        assertEquals(BigDecimal("-6"), summary.realizedPnl)
        Mockito.verify(copyTradingRepository).save(Mockito.argThat {
            it.id == 10L && !it.enabled && it.autopilotPausedReason == AutopilotPauseReason.RISK_DAILY_LOSS
        })
        Mockito.verify(candidateRepository).save(Mockito.argThat {
            it.id == 1L &&
                it.researchState == LeaderResearchState.COOLDOWN &&
                it.riskFlags!!.contains("REAL_MONEY_UNDERPERFORMED")
        })
    }

    @Test
    fun `stable positive feedback keeps small trial without changing amount`() {
        val copyTrading = autopilotCopyTrading(maxDailyLoss = "5", fixedAmount = "2")
        Mockito.`when`(feedbackRepository.findByCopyTradingIdForUpdate(10L)).thenReturn(existingFeedback())
        Mockito.`when`(feedbackRepository.save(anyFeedback())).thenAnswer { it.arguments[0] }

        val summary = service.recordRealizedPnl(
            copyTrading = copyTrading,
            realizedPnlDelta = BigDecimal("1.25"),
            fee = BigDecimal("0.05"),
            slippage = BigDecimal.ZERO,
            reason = "sell-match"
        )!!

        assertEquals(null, summary.pauseReason)
        assertEquals(BigDecimal("1.25"), summary.realizedPnl)
        Mockito.verify(copyTradingRepository, Mockito.never()).save(anyCopyTrading())
        Mockito.verify(candidateRepository, Mockito.never()).save(anyCandidate())
    }

    @Test
    fun `unknown valuation exposure pauses and marks candidate risk`() {
        val copyTrading = autopilotCopyTrading(maxDailyLoss = "5")
        Mockito.`when`(feedbackRepository.findByCopyTradingIdForUpdate(10L)).thenReturn(null)
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate()))
        Mockito.`when`(feedbackRepository.save(anyFeedback())).thenAnswer { it.arguments[0] }
        Mockito.`when`(candidateRepository.save(anyCandidate())).thenAnswer { it.arguments[0] }
        Mockito.`when`(copyTradingRepository.save(anyCopyTrading())).thenAnswer { it.arguments[0] }

        val summary = service.recordUnknownValuation(copyTrading, BigDecimal("3.5"), "quote unavailable")!!

        assertEquals(AutopilotPauseReason.QUOTE_UNAVAILABLE, summary.pauseReason)
        assertEquals(BigDecimal("3.5"), summary.unknownValuationExposure)
        Mockito.verify(candidateRepository).save(Mockito.argThat {
            it.riskFlags!!.contains("UNKNOWN_VALUATION") && it.riskFlags!!.contains("QUOTE_UNAVAILABLE")
        })
        Mockito.verify(eventService).record(
            eventType(LeaderResearchEventType.AUTOPILOT_PAUSED),
            Mockito.eq(1L),
            Mockito.isNull(),
            Mockito.contains("QUOTE_UNAVAILABLE"),
            Mockito.anyString(),
            Mockito.anyString(),
            notificationStatus(LeaderResearchNotificationStatus.PENDING)
        )
    }

    @Test
    fun `repeated rejected orders pause autopilot`() {
        val copyTrading = autopilotCopyTrading(maxDailyLoss = "5")
        Mockito.`when`(feedbackRepository.findByCopyTradingIdForUpdate(10L)).thenReturn(
            existingFeedback().copy(rejectedOrderCount = 2)
        )
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate()))
        Mockito.`when`(feedbackRepository.save(anyFeedback())).thenAnswer { it.arguments[0] }
        Mockito.`when`(candidateRepository.save(anyCandidate())).thenAnswer { it.arguments[0] }
        Mockito.`when`(copyTradingRepository.save(anyCopyTrading())).thenAnswer { it.arguments[0] }

        val summary = service.recordOrderRejected(copyTrading, "clob rejected")!!

        assertEquals(AutopilotPauseReason.RISK_REJECTION_RATE, summary.pauseReason)
        assertEquals(3, summary.rejectedOrderCount)
        Mockito.verify(copyTradingRepository).save(Mockito.argThat {
            it.autopilotPausedReason == AutopilotPauseReason.RISK_REJECTION_RATE
        })
    }

    private fun autopilotCopyTrading(
        maxDailyLoss: String,
        fixedAmount: String = "2"
    ) = CopyTrading(
        id = 10L,
        accountId = 2L,
        leaderId = 9L,
        enabled = true,
        managementMode = CopyTradingManagementMode.AUTOPILOT,
        autopilotCandidateId = 1L,
        fixedAmount = BigDecimal(fixedAmount),
        maxDailyLoss = BigDecimal(maxDailyLoss)
    )

    private fun candidate() = LeaderResearchCandidate(
        id = 1L,
        normalizedWallet = "0x1111111111111111111111111111111111111111",
        leaderId = 9L,
        researchState = LeaderResearchState.TRIAL_READY
    )

    private fun existingFeedback() = LeaderAutopilotFeedbackSummary(
        id = 100L,
        accountId = 2L,
        candidateId = 1L,
        leaderId = 9L,
        copyTradingId = 10L
    )

    private fun anyFeedback(): LeaderAutopilotFeedbackSummary {
        Mockito.any(LeaderAutopilotFeedbackSummary::class.java)
        return existingFeedback()
    }

    private fun anyCandidate(): LeaderResearchCandidate {
        Mockito.any(LeaderResearchCandidate::class.java)
        return candidate()
    }

    private fun anyCopyTrading(): CopyTrading {
        Mockito.any(CopyTrading::class.java)
        return autopilotCopyTrading(maxDailyLoss = "5")
    }

    private fun eventType(value: LeaderResearchEventType): LeaderResearchEventType {
        Mockito.argThat<LeaderResearchEventType> { it == value }
        return value
    }

    private fun notificationStatus(value: LeaderResearchNotificationStatus): LeaderResearchNotificationStatus {
        Mockito.argThat<LeaderResearchNotificationStatus> { it == value }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
