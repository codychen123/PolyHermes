package com.wrbug.polymarketbot.dto

data class LeaderAutopilotStatusRequest(
    val accountId: Long
)

data class LeaderAutopilotUpdateRequest(
    val accountId: Long,
    val enabled: Boolean? = null,
    val resume: Boolean = false,
    val confirm: Boolean = false,
    val maxBudget: String? = null,
    val singleLeaderMaxAmount: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val maxPositionValue: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null
)

data class LeaderAutopilotConvertToManualRequest(
    val copyTradingId: Long,
    val accountId: Long? = null,
    val confirm: Boolean = false
)

data class LeaderAutopilotPolicyDto(
    val id: Long?,
    val accountId: Long,
    val state: String,
    val globalKillSwitch: Boolean,
    val maxBudget: String,
    val singleLeaderMaxAmount: String,
    val maxDailyLoss: String,
    val maxDailyOrders: Int,
    val maxPositionValue: String,
    val minPrice: String?,
    val maxPrice: String?,
    val pauseReason: String?,
    val lastDecisionAt: Long?,
    val createdAt: Long?,
    val updatedAt: Long?
)

data class LeaderAutopilotDecisionEventDto(
    val id: Long,
    val actionType: String,
    val decision: String,
    val reasonCode: String,
    val reason: String?,
    val accountId: Long?,
    val candidateId: Long?,
    val leaderId: Long?,
    val copyTradingId: Long?,
    val reservationId: Long?,
    val createdAt: Long
)

data class LeaderAutopilotStatusDto(
    val policy: LeaderAutopilotPolicyDto,
    val managedConfigCount: Int,
    val enabledManagedConfigCount: Int,
    val recentEvents: List<LeaderAutopilotDecisionEventDto>
)

data class LeaderResearchShortlistRequest(
    val accountId: Long? = null,
    val limit: Int = 10
)

data class LeaderResearchShortlistResponse(
    val autopilot: LeaderAutopilotPolicyDto?,
    val readyToTrial: List<LeaderResearchShortlistCardDto>,
    val promisingPaper: List<LeaderResearchShortlistCardDto>,
    val newCandidates: List<LeaderResearchShortlistCardDto>,
    val blockedOrCooling: List<LeaderResearchShortlistCardDto>,
    val emptyReasons: List<String>,
    val generatedAt: Long
)

data class LeaderResearchShortlistCardDto(
    val candidate: LeaderResearchCandidateDto,
    val group: String,
    val priorityRank: Int,
    val recommendationReason: String,
    val riskReason: String?,
    val evidence: List<LeaderResearchEvidenceMetricDto>,
    val cta: String,
    val canCreateDisabledTrial: Boolean,
    val canAutopilotTrial: Boolean,
    val suggestedConfig: LeaderResearchSuggestedConfigDto
)

data class LeaderResearchEvidenceMetricDto(
    val label: String,
    val value: String,
    val tone: String = "neutral"
)

data class LeaderResearchSuggestedConfigDto(
    val fixedAmount: String?,
    val maxDailyLoss: String?,
    val maxDailyOrders: Int?,
    val minPrice: String?,
    val maxPrice: String?,
    val maxPositionValue: String?
)

data class LeaderResearchWatchlistPreviewRequest(
    val rawWallets: String
)

data class LeaderResearchWatchlistSaveRequest(
    val rawWallets: String,
    val confirm: Boolean = false
)

data class LeaderResearchWatchlistResponse(
    val wallets: List<String>,
    val updatedAt: Long?
)

data class LeaderResearchWatchlistPreviewResponse(
    val valid: List<String>,
    val invalid: List<String>,
    val duplicate: List<String>,
    val existingCandidates: List<String>,
    val lockedCandidates: List<String>,
    val retiredCandidates: List<String>
)
