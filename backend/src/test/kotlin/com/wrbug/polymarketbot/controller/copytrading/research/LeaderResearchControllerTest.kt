package com.wrbug.polymarketbot.controller.copytrading.research

import com.wrbug.polymarketbot.dto.LeaderResearchApprovalRequest
import com.wrbug.polymarketbot.dto.LeaderResearchShortlistCardDto
import com.wrbug.polymarketbot.dto.LeaderResearchShortlistRequest
import com.wrbug.polymarketbot.dto.LeaderResearchShortlistResponse
import com.wrbug.polymarketbot.dto.LeaderResearchSuggestedConfigDto
import com.wrbug.polymarketbot.dto.LeaderResearchRunRequest
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.LeaderResearchRun
import com.wrbug.polymarketbot.enums.LeaderResearchTriggerType
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchApprovalConfirmRequiredException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchApprovalService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchCandidateLockedException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchJobService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchMapper
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotDecisionService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderDiscoveryShortlistService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchWatchlistService
import com.wrbug.polymarketbot.service.security.AccountOwnershipException
import com.wrbug.polymarketbot.service.security.AccountOwnershipService
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.context.support.StaticMessageSource

class LeaderResearchControllerTest {
    private val jobService: LeaderResearchJobService = mock()
    private val researchService: LeaderResearchService = mock()
    private val approvalService: LeaderResearchApprovalService = mock()
    private val shortlistService: LeaderDiscoveryShortlistService = mock()
    private val watchlistService: LeaderResearchWatchlistService = mock()
    private val autopilotService: LeaderAutopilotDecisionService = mock()
    private val copyTradingService: CopyTradingService = mock()
    private val ownershipService: AccountOwnershipService = mock()
    private val mapper: LeaderResearchMapper = mock()
    private val controller = LeaderResearchController(
        jobService = jobService,
        researchService = researchService,
        approvalService = approvalService,
        shortlistService = shortlistService,
        watchlistService = watchlistService,
        autopilotService = autopilotService,
        copyTradingService = copyTradingService,
        ownershipService = ownershipService,
        mapper = mapper,
        messageSource = StaticMessageSource()
    )

    @Test
    fun `manual run queues async run and returns run dto`() {
        val run = LeaderResearchRun(id = 1L)
        Mockito.`when`(jobService.startAsync(false, LeaderResearchTriggerType.MANUAL)).thenReturn(run)
        Mockito.`when`(mapper.runDto(run)).thenReturn(
            com.wrbug.polymarketbot.dto.LeaderResearchRunDto(
                id = 1,
                status = "RUNNING",
                triggerType = "MANUAL",
                dryRun = false,
                startedAt = run.startedAt,
                finishedAt = null,
                durationMs = null,
                sourceCountsJson = null,
                candidateCountsJson = null,
                partialFailure = false,
                skippedReason = null,
                errorClass = null,
                errorMessage = null
            )
        )

        val response = controller.run(LeaderResearchRunRequest())

        assertEquals(0, response.body!!.code)
        assertEquals(1, response.body!!.data!!.id)
        Mockito.verify(jobService).startAsync(false, LeaderResearchTriggerType.MANUAL)
        Mockito.verify(jobService, Mockito.never()).runOnce(false, LeaderResearchTriggerType.MANUAL)
    }

    @Test
    fun `preview run stays synchronous`() {
        val run = LeaderResearchRun(id = 2L, dryRun = true, triggerType = LeaderResearchTriggerType.PREVIEW)
        Mockito.`when`(jobService.runOnce(true, LeaderResearchTriggerType.PREVIEW)).thenReturn(run)
        Mockito.`when`(mapper.runDto(run)).thenReturn(
            com.wrbug.polymarketbot.dto.LeaderResearchRunDto(
                id = 2,
                status = "SUCCESS",
                triggerType = "PREVIEW",
                dryRun = true,
                startedAt = run.startedAt,
                finishedAt = null,
                durationMs = null,
                sourceCountsJson = null,
                candidateCountsJson = null,
                partialFailure = false,
                skippedReason = null,
                errorClass = null,
                errorMessage = null
            )
        )

        val response = controller.run(LeaderResearchRunRequest(dryRun = true, triggerType = "PREVIEW"))

        assertEquals(0, response.body!!.code)
        assertEquals(2, response.body!!.data!!.id)
        Mockito.verify(jobService).runOnce(true, LeaderResearchTriggerType.PREVIEW)
        Mockito.verify(jobService, Mockito.never()).startAsync(true, LeaderResearchTriggerType.PREVIEW)
    }

    @Test
    fun `detail rejects invalid candidate id`() {
        val response = controller.detail(LeaderResearchDetailRequest(candidateId = 0))

        assertEquals(ErrorCode.PARAM_INVALID.code, response.body!!.code)
    }

    @Test
    fun `approval maps confirm required`() {
        Mockito.`when`(approvalService.createDisabledTrialConfig(anyApprovalRequest()))
            .thenReturn(Result.failure(LeaderResearchApprovalConfirmRequiredException()))

        val response = controller.approve(LeaderResearchApprovalRequest(candidateId = 1, accountId = 2, confirm = false), request())

        assertEquals(ErrorCode.LEADER_RESEARCH_APPROVAL_CONFIRM_REQUIRED.code, response.body!!.code)
    }

    @Test
    fun `approval maps locked candidate`() {
        Mockito.`when`(approvalService.createDisabledTrialConfig(anyApprovalRequest()))
            .thenReturn(Result.failure(LeaderResearchCandidateLockedException()))

        val response = controller.approve(LeaderResearchApprovalRequest(candidateId = 1, accountId = 2, confirm = true), request())

        assertEquals(ErrorCode.LEADER_RESEARCH_CANDIDATE_LOCKED.code, response.body!!.code)
    }

    @Test
    fun `approval rejects account ownership mismatch before service call`() {
        Mockito.`when`(ownershipService.requireAccountAccess(Mockito.eq(2L), anyHttpRequest()))
            .thenThrow(AccountOwnershipException("无权访问该账户"))

        val response = controller.approve(LeaderResearchApprovalRequest(candidateId = 1, accountId = 2, confirm = true), request())

        assertEquals(ErrorCode.AUTH_PERMISSION_DENIED.code, response.body!!.code)
        Mockito.verify(approvalService, Mockito.never()).createDisabledTrialConfig(anyApprovalRequest())
    }

    @Test
    fun `autopilot status rejects account ownership mismatch`() {
        Mockito.`when`(ownershipService.requireAccountAccess(Mockito.eq(2L), anyHttpRequest()))
            .thenThrow(AccountOwnershipException("无权访问该账户"))

        val response = controller.autopilotStatus(com.wrbug.polymarketbot.dto.LeaderAutopilotStatusRequest(accountId = 2), request())

        assertEquals(ErrorCode.AUTH_PERMISSION_DENIED.code, response.body!!.code)
        Mockito.verify(autopilotService, Mockito.never()).status(2L)
    }

    @Test
    fun `shortlist rejects account ownership mismatch`() {
        Mockito.`when`(ownershipService.requireAccountAccess(Mockito.eq(2L), anyHttpRequest()))
            .thenThrow(AccountOwnershipException("无权访问该账户"))

        val response = controller.shortlist(com.wrbug.polymarketbot.dto.LeaderResearchShortlistRequest(accountId = 2), request())

        assertEquals(ErrorCode.AUTH_PERMISSION_DENIED.code, response.body!!.code)
        Mockito.verify(shortlistService, Mockito.never()).shortlist(anyShortlistRequest())
    }

    @Test
    fun `shortlist returns recommendations from service`() {
        val responseDto = shortlistResponse(
            readyCount = 1,
            emptyReasons = emptyList()
        )
        Mockito.`when`(shortlistService.shortlist(anyShortlistRequest())).thenReturn(responseDto)

        val response = controller.shortlist(LeaderResearchShortlistRequest(accountId = null, limit = 10), request())

        assertEquals(0, response.body!!.code)
        assertEquals(1, response.body!!.data!!.readyToTrial.size)
        assertEquals("readyToTrial", response.body!!.data!!.readyToTrial.single().group)
    }

    @Test
    fun `shortlist returns no recommendation reasons including source failure summary`() {
        val responseDto = shortlistResponse(
            readyCount = 0,
            emptyReasons = listOf("PUBLIC_LEADERBOARD: timeout", "样本不足")
        )
        Mockito.`when`(shortlistService.shortlist(anyShortlistRequest())).thenReturn(responseDto)

        val response = controller.shortlist(LeaderResearchShortlistRequest(limit = 10), request())

        assertEquals(0, response.body!!.code)
        assertEquals(emptyList<LeaderResearchShortlistCardDto>(), response.body!!.data!!.readyToTrial)
        assertEquals(listOf("PUBLIC_LEADERBOARD: timeout", "样本不足"), response.body!!.data!!.emptyReasons)
    }

    @Test
    fun `shortlist rejects invalid account id`() {
        val response = controller.shortlist(LeaderResearchShortlistRequest(accountId = 0), request())

        assertEquals(ErrorCode.PARAM_ACCOUNT_ID_INVALID.code, response.body!!.code)
        Mockito.verify(shortlistService, Mockito.never()).shortlist(anyShortlistRequest())
    }

    @Test
    fun `autopilot update enable resume and disable reject account ownership mismatch`() {
        Mockito.`when`(ownershipService.requireAccountAccess(Mockito.eq(2L), anyHttpRequest()))
            .thenThrow(AccountOwnershipException("无权访问该账户"))

        val enable = controller.updateAutopilot(
            com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest(accountId = 2, enabled = true, confirm = true),
            request()
        )
        val resume = controller.updateAutopilot(
            com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest(accountId = 2, resume = true, confirm = true),
            request()
        )
        val disable = controller.updateAutopilot(
            com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest(accountId = 2, enabled = false, confirm = true),
            request()
        )

        assertEquals(ErrorCode.AUTH_PERMISSION_DENIED.code, enable.body!!.code)
        assertEquals(ErrorCode.AUTH_PERMISSION_DENIED.code, resume.body!!.code)
        assertEquals(ErrorCode.AUTH_PERMISSION_DENIED.code, disable.body!!.code)
        Mockito.verify(autopilotService, Mockito.never()).updatePolicy(anyAutopilotUpdateRequest())
    }

    @Test
    fun `convert to manual rejects copy trading ownership mismatch`() {
        Mockito.`when`(ownershipService.requireCopyTradingAccess(Mockito.eq(7L), anyHttpRequest()))
            .thenThrow(AccountOwnershipException("无权访问该账户"))

        val response = controller.convertAutopilotToManual(
            com.wrbug.polymarketbot.dto.LeaderAutopilotConvertToManualRequest(copyTradingId = 7, confirm = true),
            request()
        )

        assertEquals(ErrorCode.AUTH_PERMISSION_DENIED.code, response.body!!.code)
        Mockito.verify(autopilotService, Mockito.never()).convertToManual(anyConvertRequest())
    }

    @Test
    fun `convert to manual rejects mismatched account id`() {
        Mockito.`when`(ownershipService.requireCopyTradingAccess(Mockito.eq(7L), anyHttpRequest()))
            .thenReturn(CopyTrading(id = 7L, accountId = 2L, leaderId = 9L))

        val response = controller.convertAutopilotToManual(
            com.wrbug.polymarketbot.dto.LeaderAutopilotConvertToManualRequest(copyTradingId = 7, accountId = 3, confirm = true),
            request()
        )

        assertEquals(ErrorCode.AUTH_PERMISSION_DENIED.code, response.body!!.code)
        Mockito.verify(autopilotService, Mockito.never()).convertToManual(anyConvertRequest())
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

    private fun anyApprovalRequest(): LeaderResearchApprovalRequest {
        Mockito.any(LeaderResearchApprovalRequest::class.java)
        return LeaderResearchApprovalRequest(candidateId = 1, accountId = 2, confirm = true)
    }

    private fun anyHttpRequest(): HttpServletRequest {
        Mockito.any(HttpServletRequest::class.java)
        return request()
    }

    private fun anyShortlistRequest(): com.wrbug.polymarketbot.dto.LeaderResearchShortlistRequest {
        Mockito.any(com.wrbug.polymarketbot.dto.LeaderResearchShortlistRequest::class.java)
        return com.wrbug.polymarketbot.dto.LeaderResearchShortlistRequest(accountId = 2)
    }

    private fun shortlistResponse(
        readyCount: Int,
        emptyReasons: List<String>
    ): LeaderResearchShortlistResponse {
        val ready = (1..readyCount).map { index ->
            LeaderResearchShortlistCardDto(
                candidate = candidateDto(index.toLong()),
                group = "readyToTrial",
                priorityRank = index,
                recommendationReason = "paper-first evidence",
                riskReason = null,
                evidence = emptyList(),
                cta = "createTrialOrAutopilot",
                canCreateDisabledTrial = true,
                canAutopilotTrial = true,
                suggestedConfig = LeaderResearchSuggestedConfigDto(
                    fixedAmount = "1",
                    maxDailyLoss = "5",
                    maxDailyOrders = 5,
                    minPrice = "0.1",
                    maxPrice = "0.8",
                    maxPositionValue = "5"
                )
            )
        }
        return LeaderResearchShortlistResponse(
            autopilot = null,
            readyToTrial = ready,
            promisingPaper = emptyList(),
            newCandidates = emptyList(),
            blockedOrCooling = emptyList(),
            emptyReasons = emptyReasons,
            generatedAt = 123L
        )
    }

    private fun candidateDto(id: Long) = com.wrbug.polymarketbot.dto.LeaderResearchCandidateDto(
        id = id,
        normalizedWallet = "0x${id.toString(16).padStart(40, '0')}",
        leaderId = null,
        leaderName = null,
        poolId = null,
        poolStatus = null,
        suggestedFixedAmount = null,
        suggestedMaxDailyLoss = null,
        suggestedMaxDailyOrders = null,
        suggestedMinPrice = null,
        suggestedMaxPrice = null,
        suggestedMaxPositionValue = null,
        researchState = "TRIAL_READY",
        source = "PUBLIC_LEADERBOARD",
        sourceRank = 1,
        score = "90",
        scoreVersion = null,
        reason = null,
        riskFlags = emptyList(),
        locked = false,
        agentOwned = true,
        provenance = "AGENT_CREATED",
        sourceEvidence = null,
        firstSeenAt = 1L,
        lastSourceSeenAt = 1L,
        lastScoredAt = null,
        cooldownUntil = null,
        cooldownCount = 0,
        trialReadyAt = 1L,
        retiredAt = null,
        lastPaperSessionId = null,
        latestPaperSession = null
    )

    private fun anyAutopilotUpdateRequest(): com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest {
        Mockito.any(com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest::class.java)
        return com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest(accountId = 2, confirm = true)
    }

    private fun anyConvertRequest(): com.wrbug.polymarketbot.dto.LeaderAutopilotConvertToManualRequest {
        Mockito.any(com.wrbug.polymarketbot.dto.LeaderAutopilotConvertToManualRequest::class.java)
        return com.wrbug.polymarketbot.dto.LeaderAutopilotConvertToManualRequest(copyTradingId = 7, confirm = true)
    }

    private fun request() = MockHttpServletRequest().apply {
        setAttribute("username", "owner")
    }
}
