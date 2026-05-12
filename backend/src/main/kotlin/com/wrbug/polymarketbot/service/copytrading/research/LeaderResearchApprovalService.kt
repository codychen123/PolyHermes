package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.dto.CopyTradingCreateRequest
import com.wrbug.polymarketbot.dto.LeaderAutopilotUpdateRequest
import com.wrbug.polymarketbot.dto.LeaderResearchApprovalRequest
import com.wrbug.polymarketbot.dto.LeaderResearchApprovalResponse
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.enums.LeaderPoolStatus
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.enums.AutopilotActionType
import com.wrbug.polymarketbot.enums.AutopilotDecision
import com.wrbug.polymarketbot.enums.CopyTradingManagementMode
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

class LeaderResearchCandidateNotReadyException : RuntimeException("候选尚未进入 TRIAL_READY，不能创建试跟配置")
class LeaderResearchApprovalConfirmRequiredException : RuntimeException("创建禁用试跟配置需要显式确认")
class LeaderResearchDuplicateTrialConfigException : RuntimeException("该账户已存在此 Leader 的跟单配置")
class LeaderResearchRealMoneyForbiddenException : RuntimeException("真钱 Autopilot 必须由用户显式开启并通过后端风控")
class LeaderResearchCandidateLockedException : RuntimeException("研究候选已锁定")

@Service
class LeaderResearchApprovalService(
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderPoolRepository: LeaderPoolRepository,
    private val copyTradingService: CopyTradingService,
    private val poolMappingService: LeaderResearchPoolMappingService,
    private val eventService: LeaderResearchEventService,
    private val autopilotService: LeaderAutopilotDecisionService
) {
    private val logger = LoggerFactory.getLogger(LeaderResearchApprovalService::class.java)

    @Transactional
    fun createDisabledTrialConfig(request: LeaderResearchApprovalRequest): Result<LeaderResearchApprovalResponse> {
        return try {
            if (!request.confirm) {
                return Result.failure(LeaderResearchApprovalConfirmRequiredException())
            }
            val candidate = candidateRepository.findById(request.candidateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("候选不存在"))
            if (candidate.locked) {
                eventService.record(
                    type = LeaderResearchEventType.APPROVAL_REJECTED,
                    candidateId = candidate.id,
                    reason = "Candidate is locked; manual unlock is required before approval"
                )
                return Result.failure(LeaderResearchCandidateLockedException())
            }
            if (candidate.researchState != LeaderResearchState.TRIAL_READY) {
                eventService.record(
                    type = LeaderResearchEventType.APPROVAL_REJECTED,
                    candidateId = candidate.id,
                    reason = "Candidate state is ${candidate.researchState}, not TRIAL_READY"
                )
                return Result.failure(LeaderResearchCandidateNotReadyException())
            }
            val account = accountRepository.findByIdForUpdate(request.accountId)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))
            val synced = poolMappingService.syncCandidate(candidate)
            val pool = synced.poolId?.let { leaderPoolRepository.findById(it).orElse(null) }
                ?: return Result.failure(IllegalStateException("Leader Pool 同步失败"))
            val leaderId = synced.leaderId ?: pool.leaderId
            val managementMode = if (request.autopilotEnabled) CopyTradingManagementMode.AUTOPILOT else CopyTradingManagementMode.MANUAL
            val duplicate = if (request.autopilotEnabled) {
                copyTradingRepository.findByAccountIdAndLeaderIdAndManagementMode(
                    account.id ?: request.accountId,
                    leaderId,
                    CopyTradingManagementMode.AUTOPILOT
                )
            } else {
                copyTradingRepository.findByAccountIdAndLeaderId(account.id ?: request.accountId, leaderId)
            }
            if (duplicate.isNotEmpty()) {
                eventService.record(
                    type = LeaderResearchEventType.DUPLICATE_APPROVAL,
                    candidateId = candidate.id,
                    reason = "Duplicate copy trading config for account=${account.id}, leader=$leaderId"
                )
                return Result.failure(LeaderResearchDuplicateTrialConfigException())
            }

            val policy = if (request.autopilotEnabled) {
                autopilotService.updatePolicy(
                    LeaderAutopilotUpdateRequest(
                        accountId = request.accountId,
                        enabled = true,
                        confirm = true,
                        maxBudget = request.maxBudget,
                        singleLeaderMaxAmount = request.singleLeaderMaxAmount,
                        maxDailyLoss = request.maxDailyLoss,
                        maxDailyOrders = request.maxDailyOrders,
                        maxPositionValue = request.maxPositionValue,
                        minPrice = request.minPrice,
                        maxPrice = request.maxPrice
                    )
                ).policy
            } else {
                null
            }
            if (request.autopilotEnabled) {
                val fixedAmount = pool.suggestedFixedAmount.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("1.00000000")
                val singleLeaderMaxAmount = policy?.singleLeaderMaxAmount?.toBigDecimalOrNull()
                    ?: return Result.failure(LeaderAutopilotDecisionDeniedException("缺少账户 Autopilot 单 leader 上限"))
                if (fixedAmount > singleLeaderMaxAmount) {
                    return Result.failure(LeaderAutopilotDecisionDeniedException("建议 fixed amount 超过账户 Autopilot 单 leader 上限"))
                }
            }
            val copyRequest = buildCopyTradingRequest(
                pool = pool,
                accountId = request.accountId,
                leaderId = leaderId,
                enabled = request.autopilotEnabled,
                managementMode = managementMode,
                autopilotPolicyId = policy?.id,
                autopilotCandidateId = candidate.id
            )
            if (copyRequest.enabled && !request.autopilotEnabled) {
                eventService.record(
                    type = LeaderResearchEventType.REAL_MONEY_ACTIVATION_FORBIDDEN,
                    candidateId = candidate.id,
                    reason = "Research approval attempted to create enabled copy trading config",
                    dedupeKey = "approval-real-money-forbidden:${candidate.id}:${request.accountId}"
                )
                return Result.failure(LeaderResearchRealMoneyForbiddenException())
            }
            if (request.autopilotEnabled) {
                val decision = autopilotService.decide(
                    LeaderAutopilotDecisionRequest(
                        actionType = AutopilotActionType.CREATE_CONFIG,
                        accountId = request.accountId,
                        candidateId = candidate.id,
                        leaderId = leaderId,
                        requestedAmount = pool.suggestedFixedAmount,
                        inputSnapshot = mapOf(
                            "source" to "leader_research_approval",
                            "candidateState" to candidate.researchState.name,
                            "fixedAmount" to pool.suggestedFixedAmount.strip()
                        )
                    )
                )
                if (decision.decision != AutopilotDecision.ALLOW) {
                    return Result.failure(LeaderAutopilotDecisionDeniedException(decision.reason))
                }
            }
            val copyTrading = createCopyTradingOrDuplicate(copyRequest).getOrThrow()
            val now = System.currentTimeMillis()
            leaderPoolRepository.save(
                pool.copy(
                    status = LeaderPoolStatus.TRIAL,
                    lastPromotedAt = now,
                    lastReviewedAt = now,
                    researchState = LeaderResearchState.TRIAL_READY,
                    researchBadge = "DISABLED_TRIAL_CREATED",
                    researchUpdatedAt = now,
                    updatedAt = now
                )
            )
            eventService.record(
                type = if (request.autopilotEnabled) LeaderResearchEventType.APPROVAL_CREATED_AUTOPILOT_CONFIG else LeaderResearchEventType.APPROVAL_CREATED_DISABLED_CONFIG,
                candidateId = candidate.id,
                reason = if (request.autopilotEnabled) {
                    "Created enabled Autopilot copy trading config id=${copyTrading.id}"
                } else {
                    "Created disabled copy trading config id=${copyTrading.id}; manual enable required"
                },
                payloadSummary = "accountId=${request.accountId}, leaderId=$leaderId",
                dedupeKey = "approval:${if (request.autopilotEnabled) "autopilot" else "disabled"}:${candidate.id}:${request.accountId}"
            )
            Result.success(
                LeaderResearchApprovalResponse(
                    copyTrading = copyTrading,
                    warning = if (request.autopilotEnabled) {
                        "已创建 Autopilot 小额真钱试跟配置；系统会按预算和暂停规则执行。"
                    } else {
                        "已创建禁用状态的试跟配置；需要你手动启用后才会真钱跟单。"
                    },
                    autopilotDecision = if (request.autopilotEnabled) "ALLOW" else null,
                    autopilotReason = if (request.autopilotEnabled) "CREATE_CONFIG allowed" else null
                )
            )
        } catch (e: Exception) {
            logger.error("Leader research approval failed: candidateId=${request.candidateId}", e)
            Result.failure(e)
        }
    }

    private fun buildCopyTradingRequest(
        pool: LeaderPool,
        accountId: Long,
        leaderId: Long,
        enabled: Boolean,
        managementMode: CopyTradingManagementMode,
        autopilotPolicyId: Long?,
        autopilotCandidateId: Long?
    ): CopyTradingCreateRequest {
        val fixedAmount = pool.suggestedFixedAmount.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("1.00000000")
        return CopyTradingCreateRequest(
            accountId = accountId,
            leaderId = leaderId,
            enabled = enabled,
            copyMode = "FIXED",
            copyRatio = "1",
            fixedAmount = fixedAmount.strip(),
            maxOrderSize = fixedAmount.strip(),
            minOrderSize = "1",
            maxDailyLoss = (pool.suggestedMaxDailyLoss.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("5.00000000")).strip(),
            maxDailyOrders = pool.suggestedMaxDailyOrders.coerceIn(1, 10),
            priceTolerance = "1",
            delaySeconds = 0,
            pollIntervalSeconds = 5,
            useWebSocket = true,
            websocketReconnectInterval = 5000,
            websocketMaxRetries = 10,
            supportSell = true,
            minPrice = pool.suggestedMinPrice?.strip() ?: "0.1",
            maxPrice = pool.suggestedMaxPrice?.strip() ?: "0.8",
            maxPositionValue = pool.suggestedMaxPositionValue?.strip() ?: "5",
            keywordFilterMode = "DISABLED",
            keywords = null,
            configName = "Research试跟-${pool.researchCandidateId ?: pool.leaderId}",
            pushFailedOrders = true,
            pushFilteredOrders = true,
            managementMode = managementMode.name,
            autopilotPolicyId = autopilotPolicyId,
            autopilotCandidateId = autopilotCandidateId
        )
    }

    private fun createCopyTradingOrDuplicate(
        request: CopyTradingCreateRequest
    ): Result<com.wrbug.polymarketbot.dto.CopyTradingDto> {
        val result = copyTradingService.createCopyTrading(request)
        if (result.isSuccess) return result
        val error = result.exceptionOrNull()
        if (error is DataIntegrityViolationException || error?.message?.contains("Duplicate", ignoreCase = true) == true) {
            throw LeaderResearchDuplicateTrialConfigException()
        }
        return result
    }

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()
}
