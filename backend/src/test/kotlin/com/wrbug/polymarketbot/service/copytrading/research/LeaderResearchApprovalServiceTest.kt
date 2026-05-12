package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.dto.CopyTradingDto
import com.wrbug.polymarketbot.dto.LeaderAutopilotPolicyDto
import com.wrbug.polymarketbot.dto.LeaderAutopilotStatusDto
import com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest
import com.wrbug.polymarketbot.dto.LeaderResearchApprovalRequest
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.AutopilotActionType
import com.wrbug.polymarketbot.enums.AutopilotDecision
import com.wrbug.polymarketbot.enums.CopyTradingManagementMode
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional

class LeaderResearchApprovalServiceTest {
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val accountRepository: AccountRepository = mock()
    private val copyTradingRepository: CopyTradingRepository = mock()
    private val leaderPoolRepository: LeaderPoolRepository = mock()
    private val copyTradingService: CopyTradingService = mock()
    private val poolMappingService: LeaderResearchPoolMappingService = mock()
    private val eventService: LeaderResearchEventService = mock()
    private val autopilotService: LeaderAutopilotDecisionService = mock()
    private val service = LeaderResearchApprovalService(
        candidateRepository,
        accountRepository,
        copyTradingRepository,
        leaderPoolRepository,
        copyTradingService,
        poolMappingService,
        eventService,
        autopilotService
    )

    @Test
    fun `approval requires explicit confirm`() {
        val result = service.createDisabledTrialConfig(LeaderResearchApprovalRequest(candidateId = 1L, accountId = 2L, confirm = false))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderResearchApprovalConfirmRequiredException)
        Mockito.verify(copyTradingService, Mockito.never()).createCopyTrading(anyCreateRequest())
    }

    @Test
    fun `approval creates disabled copy trading config only`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            leaderId = 9L,
            poolId = 10L,
            researchState = LeaderResearchState.TRIAL_READY
        )
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate))
        Mockito.`when`(accountRepository.findByIdForUpdate(2L)).thenReturn(account())
        Mockito.`when`(poolMappingService.syncCandidate(candidate)).thenReturn(candidate)
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(copyTradingRepository.findByAccountIdAndLeaderId(2L, 9L)).thenReturn(emptyList())
        Mockito.`when`(copyTradingService.createCopyTrading(anyCreateRequest())).thenReturn(Result.success(copyTradingDto()))
        Mockito.`when`(leaderPoolRepository.save(anyLeaderPool())).thenAnswer { it.arguments[0] }

        val result = service.createDisabledTrialConfig(LeaderResearchApprovalRequest(candidateId = 1L, accountId = 2L, confirm = true))

        assertTrue(result.isSuccess)
        val captor = ArgumentCaptor.forClass(com.wrbug.polymarketbot.dto.CopyTradingCreateRequest::class.java)
        Mockito.verify(copyTradingService).createCopyTrading(captureCreateRequest(captor))
        assertFalse(captor.value.enabled)
        Mockito.verify(accountRepository).findByIdForUpdate(2L)
    }

    @Test
    fun `locked candidate cannot create approval config`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            leaderId = 9L,
            poolId = 10L,
            researchState = LeaderResearchState.TRIAL_READY,
            locked = true
        )
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate))

        val result = service.createDisabledTrialConfig(LeaderResearchApprovalRequest(candidateId = 1L, accountId = 2L, confirm = true))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderResearchCandidateLockedException)
        Mockito.verify(accountRepository, Mockito.never()).findByIdForUpdate(2L)
        Mockito.verify(copyTradingService, Mockito.never()).createCopyTrading(anyCreateRequest())
    }

    @Test
    fun `autopilot approval writes submitted budget into account policy`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            leaderId = 9L,
            poolId = 10L,
            researchState = LeaderResearchState.TRIAL_READY
        )
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate))
        Mockito.`when`(accountRepository.findByIdForUpdate(2L)).thenReturn(account())
        Mockito.`when`(poolMappingService.syncCandidate(candidate)).thenReturn(candidate)
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(copyTradingRepository.findByAccountIdAndLeaderIdAndManagementMode(2L, 9L, CopyTradingManagementMode.AUTOPILOT)).thenReturn(emptyList())
        Mockito.`when`(autopilotService.updatePolicy(anyAutopilotUpdateRequest())).thenReturn(
            LeaderAutopilotStatusDto(
                policy = LeaderAutopilotPolicyDto(
                    id = 40L,
                    accountId = 2L,
                    state = "ON",
                    globalKillSwitch = false,
                    maxBudget = "30",
                    singleLeaderMaxAmount = "2",
                    maxDailyLoss = "3",
                    maxDailyOrders = 4,
                    maxPositionValue = "8",
                    minPrice = "0.2",
                    maxPrice = "0.7",
                    pauseReason = null,
                    lastDecisionAt = 1L,
                    createdAt = 1L,
                    updatedAt = 1L
                ),
                managedConfigCount = 0,
                enabledManagedConfigCount = 0,
                recentEvents = emptyList()
            )
        )
        Mockito.`when`(autopilotService.decide(anyDecisionRequest())).thenReturn(
            LeaderAutopilotDecisionResult(
                decision = AutopilotDecision.ALLOW,
                reasonCode = "ALLOW",
                reason = "ok",
                policy = null
            )
        )
        Mockito.`when`(copyTradingService.createCopyTrading(anyCreateRequest())).thenReturn(Result.success(copyTradingDto(enabled = true)))
        Mockito.`when`(leaderPoolRepository.save(anyLeaderPool())).thenAnswer { it.arguments[0] }

        val result = service.createDisabledTrialConfig(
            LeaderResearchApprovalRequest(
                candidateId = 1L,
                accountId = 2L,
                confirm = true,
                autopilotEnabled = true,
                maxBudget = "30",
                singleLeaderMaxAmount = "2",
                maxDailyLoss = "3",
                maxDailyOrders = 4,
                maxPositionValue = "8",
                minPrice = "0.2",
                maxPrice = "0.7"
            )
        )

        assertTrue(result.isSuccess)
        val updateCaptor = ArgumentCaptor.forClass(LeaderAutopilotUpdateRequest::class.java)
        Mockito.verify(autopilotService).updatePolicy(captureAutopilotUpdateRequest(updateCaptor))
        assertTrue(updateCaptor.value.enabled == true)
        assertTrue(updateCaptor.value.confirm)
        assertTrue(updateCaptor.value.maxBudget == "30")
        assertTrue(updateCaptor.value.singleLeaderMaxAmount == "2")
        val createCaptor = ArgumentCaptor.forClass(com.wrbug.polymarketbot.dto.CopyTradingCreateRequest::class.java)
        Mockito.verify(copyTradingService).createCopyTrading(captureCreateRequest(createCaptor))
        assertTrue(createCaptor.value.enabled)
        assertTrue(createCaptor.value.managementMode == CopyTradingManagementMode.AUTOPILOT.name)
        Mockito.verify(autopilotService).decide(anyDecisionRequest())
    }

    @Test
    fun `autopilot approval maps database unique conflict to duplicate config`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            leaderId = 9L,
            poolId = 10L,
            researchState = LeaderResearchState.TRIAL_READY
        )
        Mockito.`when`(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate))
        Mockito.`when`(accountRepository.findByIdForUpdate(2L)).thenReturn(account())
        Mockito.`when`(poolMappingService.syncCandidate(candidate)).thenReturn(candidate)
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(copyTradingRepository.findByAccountIdAndLeaderIdAndManagementMode(2L, 9L, CopyTradingManagementMode.AUTOPILOT)).thenReturn(emptyList())
        Mockito.`when`(autopilotService.updatePolicy(anyAutopilotUpdateRequest())).thenReturn(
            LeaderAutopilotStatusDto(
                policy = LeaderAutopilotPolicyDto(
                    id = 40L,
                    accountId = 2L,
                    state = "ON",
                    globalKillSwitch = false,
                    maxBudget = "30",
                    singleLeaderMaxAmount = "2",
                    maxDailyLoss = "3",
                    maxDailyOrders = 4,
                    maxPositionValue = "8",
                    minPrice = "0.2",
                    maxPrice = "0.7",
                    pauseReason = null,
                    lastDecisionAt = 1L,
                    createdAt = 1L,
                    updatedAt = 1L
                ),
                managedConfigCount = 0,
                enabledManagedConfigCount = 0,
                recentEvents = emptyList()
            )
        )
        Mockito.`when`(autopilotService.decide(anyDecisionRequest())).thenReturn(
            LeaderAutopilotDecisionResult(
                decision = AutopilotDecision.ALLOW,
                reasonCode = "ALLOW",
                reason = "ok",
                policy = null
            )
        )
        Mockito.`when`(copyTradingService.createCopyTrading(anyCreateRequest()))
            .thenReturn(Result.failure(DataIntegrityViolationException("Duplicate entry autopilot_unique_key")))

        val result = service.createDisabledTrialConfig(
            LeaderResearchApprovalRequest(
                candidateId = 1L,
                accountId = 2L,
                confirm = true,
                autopilotEnabled = true,
                singleLeaderMaxAmount = "2"
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderResearchDuplicateTrialConfigException)
    }

    private fun account() = Account(
        id = 2L,
        privateKey = "enc",
        walletAddress = "0x2222222222222222222222222222222222222222",
        proxyAddress = "0x3333333333333333333333333333333333333333"
    )

    private fun pool() = LeaderPool(id = 10L, leaderId = 9L, researchCandidateId = 1L)

    private fun copyTradingDto(enabled: Boolean = false) = CopyTradingDto(
        id = 20L,
        accountId = 2L,
        accountName = null,
        walletAddress = "0x2222222222222222222222222222222222222222",
        leaderId = 9L,
        leaderName = null,
        leaderAddress = "0x1111111111111111111111111111111111111111",
        enabled = enabled,
        copyMode = "FIXED",
        copyRatio = "1",
        fixedAmount = "1",
        maxOrderSize = "1",
        minOrderSize = "1",
        maxDailyLoss = "5",
        maxDailyOrders = 10,
        priceTolerance = "1",
        delaySeconds = 0,
        pollIntervalSeconds = 5,
        useWebSocket = true,
        websocketReconnectInterval = 5000,
        websocketMaxRetries = 10,
        supportSell = true,
        minOrderDepth = null,
        maxSpread = null,
        minPrice = "0.1",
        maxPrice = "0.8",
        maxPositionValue = "5",
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun anyCreateRequest(): com.wrbug.polymarketbot.dto.CopyTradingCreateRequest {
        Mockito.any(com.wrbug.polymarketbot.dto.CopyTradingCreateRequest::class.java)
        return com.wrbug.polymarketbot.dto.CopyTradingCreateRequest(accountId = 2L, leaderId = 9L)
    }

    private fun anyLeaderPool(): LeaderPool {
        Mockito.any(LeaderPool::class.java)
        return pool()
    }

    private fun anyAutopilotUpdateRequest(): LeaderAutopilotUpdateRequest {
        Mockito.any(LeaderAutopilotUpdateRequest::class.java)
        return LeaderAutopilotUpdateRequest(accountId = 2L, confirm = true)
    }

    private fun anyDecisionRequest(): LeaderAutopilotDecisionRequest {
        Mockito.any(LeaderAutopilotDecisionRequest::class.java)
        return LeaderAutopilotDecisionRequest(actionType = AutopilotActionType.CREATE_CONFIG, accountId = 2L)
    }

    private fun captureAutopilotUpdateRequest(captor: ArgumentCaptor<LeaderAutopilotUpdateRequest>): LeaderAutopilotUpdateRequest {
        captor.capture()
        return LeaderAutopilotUpdateRequest(accountId = 2L, confirm = true)
    }

    private fun captureCreateRequest(captor: ArgumentCaptor<com.wrbug.polymarketbot.dto.CopyTradingCreateRequest>): com.wrbug.polymarketbot.dto.CopyTradingCreateRequest {
        captor.capture()
        return com.wrbug.polymarketbot.dto.CopyTradingCreateRequest(accountId = 2L, leaderId = 9L)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
