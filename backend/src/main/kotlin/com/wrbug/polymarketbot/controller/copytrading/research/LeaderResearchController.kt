package com.wrbug.polymarketbot.controller.copytrading.research

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.enums.LeaderResearchTriggerType
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchApprovalConfirmRequiredException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchApprovalService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchCandidateNotReadyException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchCandidateLockedException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchDuplicateTrialConfigException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchJobService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchMapper
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchRealMoneyForbiddenException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotDecisionService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderDiscoveryShortlistService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchWatchlistService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotConfirmRequiredException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotDecisionDeniedException
import com.wrbug.polymarketbot.service.security.AccountOwnershipException
import com.wrbug.polymarketbot.service.security.AccountOwnershipService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LeaderResearchDetailRequest(val candidateId: Long)
data class LeaderResearchEventsRequest(val page: Int = 0, val size: Int = 50)
data class LeaderResearchPaperSessionsRequest(val candidateId: Long)

@RestController
@RequestMapping("/api/copy-trading/leader-research")
class LeaderResearchController(
    private val jobService: LeaderResearchJobService,
    private val researchService: LeaderResearchService,
    private val approvalService: LeaderResearchApprovalService,
    private val shortlistService: LeaderDiscoveryShortlistService,
    private val watchlistService: LeaderResearchWatchlistService,
    private val autopilotService: LeaderAutopilotDecisionService,
    private val copyTradingService: CopyTradingService,
    private val ownershipService: AccountOwnershipService,
    private val mapper: LeaderResearchMapper,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(LeaderResearchController::class.java)

    @PostMapping("/run")
    fun run(@RequestBody request: LeaderResearchRunRequest): ResponseEntity<ApiResponse<LeaderResearchRunDto>> {
        return try {
            val trigger = runCatching { LeaderResearchTriggerType.valueOf(request.triggerType.uppercase()) }
                .getOrDefault(LeaderResearchTriggerType.MANUAL)
            val run = if (request.dryRun || trigger == LeaderResearchTriggerType.PREVIEW) {
                jobService.runOnce(request.dryRun, trigger)
            } else {
                jobService.startAsync(request.dryRun, trigger)
            }
            ResponseEntity.ok(ApiResponse.success(mapper.runDto(run)))
        } catch (e: Exception) {
            logger.error("Leader research run failed", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_RESEARCH_RUN_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/summary")
    fun summary(): ResponseEntity<ApiResponse<LeaderResearchSummaryDto>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.summary() }
    }

    @PostMapping("/candidates/list")
    fun list(@RequestBody request: LeaderResearchCandidateListRequest): ResponseEntity<ApiResponse<LeaderResearchCandidateListResponse>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.listCandidates(request) }
    }

    @PostMapping("/candidates/detail")
    fun detail(@RequestBody request: LeaderResearchDetailRequest): ResponseEntity<ApiResponse<LeaderResearchCandidateDetailDto>> {
        if (request.candidateId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "candidateId 无效", messageSource))
        }
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.detail(request.candidateId) }
    }

    @PostMapping("/paper-sessions")
    fun paperSessions(@RequestBody request: LeaderResearchPaperSessionsRequest): ResponseEntity<ApiResponse<List<LeaderPaperSessionDto>>> {
        if (request.candidateId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "candidateId 无效", messageSource))
        }
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.paperSessions(request.candidateId) }
    }

    @PostMapping("/source-health")
    fun sourceHealth(): ResponseEntity<ApiResponse<List<LeaderResearchSourceStateDto>>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.sourceHealth() }
    }

    @PostMapping("/shortlist")
    fun shortlist(
        @RequestBody request: LeaderResearchShortlistRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<LeaderResearchShortlistResponse>> {
        return guarded(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) {
            request.accountId?.let { accountId ->
                if (accountId <= 0) {
                    return@guarded ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource)
                }
                ownershipService.requireAccountAccess(accountId, httpRequest)
            }
            ApiResponse.success(shortlistService.shortlist(request))
        }
    }

    @PostMapping("/watchlist")
    fun watchlist(): ResponseEntity<ApiResponse<LeaderResearchWatchlistResponse>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { watchlistService.get() }
    }

    @PostMapping("/watchlist/preview")
    fun previewWatchlist(@RequestBody request: LeaderResearchWatchlistPreviewRequest): ResponseEntity<ApiResponse<LeaderResearchWatchlistPreviewResponse>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { watchlistService.preview(request) }
    }

    @PostMapping("/watchlist/save")
    fun saveWatchlist(@RequestBody request: LeaderResearchWatchlistSaveRequest): ResponseEntity<ApiResponse<LeaderResearchWatchlistResponse>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { watchlistService.save(request) }
    }

    @PostMapping("/autopilot/status")
    fun autopilotStatus(
        @RequestBody request: LeaderAutopilotStatusRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<LeaderAutopilotStatusDto>> {
        if (request.accountId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
        }
        return guarded(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) {
            ownershipService.requireAccountAccess(request.accountId, httpRequest)
            ApiResponse.success(autopilotService.status(request.accountId))
        }
    }

    @PostMapping("/autopilot/update")
    fun updateAutopilot(
        @RequestBody request: LeaderAutopilotUpdateRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<LeaderAutopilotStatusDto>> {
        if (request.accountId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
        }
        return guarded(ErrorCode.SERVER_LEADER_RESEARCH_APPROVAL_FAILED) {
            ownershipService.requireAccountAccess(request.accountId, httpRequest)
            ApiResponse.success(autopilotService.updatePolicy(request))
        }
    }

    @PostMapping("/autopilot/convert-to-manual")
    fun convertAutopilotToManual(
        @RequestBody request: LeaderAutopilotConvertToManualRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CopyTradingDto>> {
        if (request.copyTradingId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
        }
        return guarded(ErrorCode.SERVER_LEADER_RESEARCH_APPROVAL_FAILED) {
            val copyTrading = ownershipService.requireCopyTradingAccess(request.copyTradingId, httpRequest)
            request.accountId?.let {
                if (it != copyTrading.accountId) {
                    return@guarded ApiResponse.error(ErrorCode.AUTH_PERMISSION_DENIED, "跟单配置不属于该账户", messageSource)
                }
            }
            val converted = autopilotService.convertToManual(request.copy(accountId = copyTrading.accountId))
            copyTradingService.toDto(converted)
                .let { ApiResponse.success(it) }
        }
    }

    @PostMapping("/events/list")
    fun events(@RequestBody request: LeaderResearchEventsRequest): ResponseEntity<ApiResponse<List<LeaderResearchEventDto>>> {
        return safe(ErrorCode.SERVER_LEADER_RESEARCH_FETCH_FAILED) { researchService.events(request.page, request.size) }
    }

    @PostMapping("/approval/create-disabled-trial-config")
    fun approve(
        @RequestBody request: LeaderResearchApprovalRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<LeaderResearchApprovalResponse>> {
        if (request.candidateId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "candidateId 无效", messageSource))
        }
        if (request.accountId <= 0) {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
        }
        return try {
            ownershipService.requireAccountAccess(request.accountId, httpRequest)
            approvalService.createDisabledTrialConfig(request).fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e -> errorResponse(e, ErrorCode.SERVER_LEADER_RESEARCH_APPROVAL_FAILED) }
            )
        } catch (e: Exception) {
            errorResponse(e, ErrorCode.SERVER_LEADER_RESEARCH_APPROVAL_FAILED)
        }
    }

    private fun <T> safe(errorCode: ErrorCode, block: () -> T): ResponseEntity<ApiResponse<T>> {
        return try {
            ResponseEntity.ok(ApiResponse.success(block()))
        } catch (e: Exception) {
            logger.error("Leader research request failed", e)
            ResponseEntity.ok(ApiResponse.error(errorCode, e.message, messageSource))
        }
    }

    private fun <T> guarded(errorCode: ErrorCode, block: () -> ApiResponse<T>): ResponseEntity<ApiResponse<T>> {
        return try {
            ResponseEntity.ok(block())
        } catch (e: Exception) {
            when (e) {
                is AccountOwnershipException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.AUTH_PERMISSION_DENIED, e.message, messageSource))
                is IllegalArgumentException -> if (e.message == "账户不存在" || e.message == "跟单配置不存在") {
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.AUTH_PERMISSION_DENIED, e.message, messageSource))
                } else {
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                }
                else -> {
                    logger.error("Leader research guarded request failed", e)
                    ResponseEntity.ok(ApiResponse.error(errorCode, e.message, messageSource))
                }
            }
        }
    }

    private fun <T> errorResponse(e: Throwable, fallback: ErrorCode): ResponseEntity<ApiResponse<T>> {
        val errorCode = when (e) {
            is LeaderResearchCandidateNotReadyException -> ErrorCode.LEADER_RESEARCH_CANDIDATE_NOT_READY
            is LeaderResearchApprovalConfirmRequiredException -> ErrorCode.LEADER_RESEARCH_APPROVAL_CONFIRM_REQUIRED
            is LeaderResearchDuplicateTrialConfigException -> ErrorCode.LEADER_RESEARCH_DUPLICATE_TRIAL_CONFIG
            is LeaderResearchRealMoneyForbiddenException -> ErrorCode.LEADER_RESEARCH_REAL_MONEY_FORBIDDEN
            is LeaderResearchCandidateLockedException -> ErrorCode.LEADER_RESEARCH_CANDIDATE_LOCKED
            is LeaderAutopilotConfirmRequiredException -> ErrorCode.LEADER_RESEARCH_APPROVAL_CONFIRM_REQUIRED
            is LeaderAutopilotDecisionDeniedException -> ErrorCode.LEADER_RESEARCH_REAL_MONEY_FORBIDDEN
            is AccountOwnershipException -> ErrorCode.AUTH_PERMISSION_DENIED
            is IllegalArgumentException -> when (e.message) {
                "账户不存在" -> ErrorCode.ACCOUNT_NOT_FOUND
                "候选不存在" -> ErrorCode.LEADER_RESEARCH_CANDIDATE_NOT_FOUND
                else -> ErrorCode.PARAM_ERROR
            }
            else -> fallback
        }
        return ResponseEntity.ok(ApiResponse.error(errorCode, null, messageSource))
    }
}
