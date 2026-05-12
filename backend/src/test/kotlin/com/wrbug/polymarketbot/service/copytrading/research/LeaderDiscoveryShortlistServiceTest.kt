package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.dto.LeaderAutopilotPolicyDto
import com.wrbug.polymarketbot.dto.LeaderResearchShortlistRequest
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.LeaderDiscoveryShortlistSnapshot
import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.CopyTradingManagementMode
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchNotificationStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderDiscoveryShortlistSnapshotRepository
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.LeaderResearchSourceStateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

class LeaderDiscoveryShortlistServiceTest {
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val leaderRepository: LeaderRepository = mock()
    private val leaderPoolRepository: LeaderPoolRepository = mock()
    private val paperSessionRepository: LeaderPaperSessionRepository = mock()
    private val sourceStateRepository: LeaderResearchSourceStateRepository = mock()
    private val copyTradingRepository: CopyTradingRepository = mock()
    private val snapshotRepository: LeaderDiscoveryShortlistSnapshotRepository = mock()
    private val mapper = LeaderResearchMapper(leaderRepository, leaderPoolRepository, sourceStateRepository)
    private val autopilotService: LeaderAutopilotDecisionService = mock()
    private val eventService: LeaderResearchEventService = mock()

    @Test
    fun `shortlist groups candidates and respects top limit`() {
        val readyFast = candidate(1L, LeaderResearchState.TRIAL_READY, sourceRank = 1, score = "90", poolId = 10L)
        val readySlow = candidate(2L, LeaderResearchState.TRIAL_READY, sourceRank = 5, score = "80", poolId = 11L)
        val paper = candidate(3L, LeaderResearchState.PAPER, sourceRank = 2, score = "75")
        val fresh = candidate(4L, LeaderResearchState.DISCOVERED, sourceRank = 3, score = "60")
        val blocked = candidate(5L, LeaderResearchState.TRIAL_READY, sourceRank = 4, score = "95", locked = true)
        val existingEnabled = candidate(6L, LeaderResearchState.TRIAL_READY, leaderId = 60L, sourceRank = 6, score = "99")
        Mockito.`when`(candidateRepository.findByResearchStateIn(Mockito.anyCollection())).thenReturn(
            listOf(readyFast, readySlow, paper, fresh, blocked, existingEnabled)
        )
        Mockito.`when`(leaderRepository.findByIdIn(Mockito.anyCollection())).thenReturn(emptyList())
        Mockito.`when`(leaderPoolRepository.findByIdIn(Mockito.anyCollection())).thenReturn(
            listOf(pool(10L, 100L, "1.5"), pool(11L, 101L, "2"))
        )
        Mockito.`when`(paperSessionRepository.findLatestByCandidateIds(Mockito.anyCollection())).thenReturn(
            listOf(session(3L, pnl = "7", trades = 5))
        )
        Mockito.`when`(copyTradingRepository.findByEnabledTrue()).thenReturn(
            listOf(CopyTrading(id = 200L, accountId = 2L, leaderId = 60L, managementMode = CopyTradingManagementMode.MANUAL))
        )
        Mockito.`when`(snapshotRepository.save(anySnapshot())).thenAnswer { it.arguments[0] }
        Mockito.`when`(autopilotService.policyDtoForAccount(2L)).thenReturn(policyDto())

        val result = service().shortlist(LeaderResearchShortlistRequest(accountId = 2L, limit = 3))

        assertEquals(listOf(1L, 2L), result.readyToTrial.map { it.candidate.id })
        assertEquals("1.5", result.readyToTrial.first().suggestedConfig.fixedAmount)
        assertEquals(1, result.promisingPaper.size)
        assertEquals(3L, result.promisingPaper.single().candidate.id)
        assertEquals(1, result.newCandidates.size)
        assertEquals(4L, result.newCandidates.single().candidate.id)
        assertEquals(setOf(5L, 6L), result.blockedOrCooling.map { it.candidate.id }.toSet())
        assertTrue(result.blockedOrCooling.first { it.candidate.id == 5L }.riskReason!!.contains("锁定"))
        assertTrue(result.blockedOrCooling.first { it.candidate.id == 6L }.riskReason!!.contains("已有启用跟单"))
        assertEquals("ON", result.autopilot!!.state)
        Mockito.verify(snapshotRepository, Mockito.times(6)).save(anySnapshot())
        Mockito.verify(eventService).record(
            eventType(LeaderResearchEventType.SHORTLIST_GENERATED),
            Mockito.isNull(),
            Mockito.isNull(),
            Mockito.contains("ready=2"),
            Mockito.anyString(),
            Mockito.anyString(),
            notificationStatus(LeaderResearchNotificationStatus.PENDING)
        )
        Mockito.verify(eventService).record(
            eventType(LeaderResearchEventType.TRIAL_READY),
            Mockito.eq(1L),
            Mockito.isNull(),
            Mockito.contains("readyToTrial"),
            Mockito.anyString(),
            Mockito.eq("shortlist-ready:1"),
            notificationStatus(LeaderResearchNotificationStatus.PENDING)
        )
        Mockito.verify(eventService).record(
            eventType(LeaderResearchEventType.TRIAL_READY),
            Mockito.eq(2L),
            Mockito.isNull(),
            Mockito.contains("readyToTrial"),
            Mockito.anyString(),
            Mockito.eq("shortlist-ready:2"),
            notificationStatus(LeaderResearchNotificationStatus.PENDING)
        )
        Mockito.verify(eventService).record(
            eventType(LeaderResearchEventType.SHORTLIST_GENERATED),
            Mockito.eq(1L),
            Mockito.isNull(),
            Mockito.contains("Top recommendation"),
            Mockito.anyString(),
            Mockito.eq("shortlist-top:1:0"),
            notificationStatus(LeaderResearchNotificationStatus.PENDING)
        )
    }

    @Test
    fun `shortlist returns empty reasons when no candidate can be shown`() {
        Mockito.`when`(candidateRepository.findByResearchStateIn(Mockito.anyCollection())).thenReturn(emptyList())
        Mockito.`when`(candidateRepository.count()).thenReturn(0L)
        Mockito.`when`(sourceStateRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(emptyList())

        val result = service().shortlist(LeaderResearchShortlistRequest(limit = 10))

        assertEquals(emptyList<Any>(), result.readyToTrial)
        assertTrue(result.emptyReasons.contains("暂无研究候选"))
    }

    private fun service() = LeaderDiscoveryShortlistService(
        candidateRepository = candidateRepository,
        leaderRepository = leaderRepository,
        leaderPoolRepository = leaderPoolRepository,
        paperSessionRepository = paperSessionRepository,
        sourceStateRepository = sourceStateRepository,
        copyTradingRepository = copyTradingRepository,
        snapshotRepository = snapshotRepository,
        mapper = mapper,
        autopilotService = autopilotService,
        eventService = eventService,
        gson = Gson()
    )

    private fun candidate(
        id: Long,
        state: LeaderResearchState,
        leaderId: Long? = id + 100,
        poolId: Long? = null,
        sourceRank: Int,
        score: String,
        locked: Boolean = false
    ) = LeaderResearchCandidate(
        id = id,
        normalizedWallet = "0x${id.toString(16).padStart(40, '0')}",
        leaderId = leaderId,
        poolId = poolId,
        researchState = state,
        sourceRank = sourceRank,
        score = BigDecimal(score),
        locked = locked
    )

    private fun pool(id: Long, leaderId: Long, fixedAmount: String) = LeaderPool(
        id = id,
        leaderId = leaderId,
        suggestedFixedAmount = BigDecimal(fixedAmount)
    )

    private fun session(candidateId: Long, pnl: String, trades: Int) = LeaderPaperSession(
        id = candidateId + 1000,
        candidateId = candidateId,
        tradeCount = trades,
        copyablePnl = BigDecimal(pnl),
        unknownValuationExposure = BigDecimal.ZERO
    )

    private fun policyDto() = LeaderAutopilotPolicyDto(
        id = 1L,
        accountId = 2L,
        state = "ON",
        globalKillSwitch = false,
        maxBudget = "25",
        singleLeaderMaxAmount = "5",
        maxDailyLoss = "5",
        maxDailyOrders = 5,
        maxPositionValue = "10",
        minPrice = "0.1",
        maxPrice = "0.8",
        pauseReason = null,
        lastDecisionAt = null,
        createdAt = null,
        updatedAt = null
    )

    private fun anySnapshot(): LeaderDiscoveryShortlistSnapshot {
        Mockito.any(LeaderDiscoveryShortlistSnapshot::class.java)
        return LeaderDiscoveryShortlistSnapshot(candidateId = 1L, shortlistGroup = "readyToTrial")
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
