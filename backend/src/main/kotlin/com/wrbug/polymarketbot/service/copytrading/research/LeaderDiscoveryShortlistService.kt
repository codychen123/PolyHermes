package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.LeaderDiscoveryShortlistSnapshot
import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class LeaderDiscoveryShortlistService(
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val leaderRepository: LeaderRepository,
    private val leaderPoolRepository: LeaderPoolRepository,
    private val paperSessionRepository: LeaderPaperSessionRepository,
    private val sourceStateRepository: LeaderResearchSourceStateRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val snapshotRepository: LeaderDiscoveryShortlistSnapshotRepository,
    private val mapper: LeaderResearchMapper,
    private val autopilotService: LeaderAutopilotDecisionService,
    private val eventService: LeaderResearchEventService,
    private val gson: Gson
) {
    @Transactional
    fun shortlist(request: LeaderResearchShortlistRequest): LeaderResearchShortlistResponse {
        val limit = request.limit.coerceIn(3, 20)
        val states = listOf(
            LeaderResearchState.TRIAL_READY,
            LeaderResearchState.PAPER,
            LeaderResearchState.CANDIDATE,
            LeaderResearchState.DISCOVERED,
            LeaderResearchState.COOLDOWN
        )
        val candidates = candidateRepository.findByResearchStateIn(states)
        val context = context(candidates)
        val existingEnabledLeaderIds = copyTradingRepository.findByEnabledTrue().map { it.leaderId }.toSet()

        val cards = candidates
            .mapNotNull { cardFor(it, context, existingEnabledLeaderIds, request.accountId) }
            .sortedWith(compareBy<LeaderResearchShortlistCardDto> { groupRank(it.group) }.thenBy { it.priorityRank })

        val ready = cards.filter { it.group == GROUP_READY }.take(limit)
        val promising = cards.filter { it.group == GROUP_PROMISING }.take(limit)
        val fresh = cards.filter { it.group == GROUP_NEW }.take(limit)
        val blocked = cards.filter { it.group == GROUP_BLOCKED }.take(limit)
        saveSnapshots(ready + promising + fresh + blocked)
        recordShortlistEvents(ready, blocked)

        val emptyReasons = emptyReasons(ready, promising, fresh, blocked)
        eventService.record(
            type = LeaderResearchEventType.SHORTLIST_GENERATED,
            reason = "Shortlist generated: ready=${ready.size}, promising=${promising.size}, new=${fresh.size}, blocked=${blocked.size}",
            payloadSummary = emptyReasons.joinToString("; "),
            dedupeKey = "shortlist:${System.currentTimeMillis() / 300000}"
        )
        return LeaderResearchShortlistResponse(
            autopilot = request.accountId?.let { autopilotService.policyDtoForAccount(it) },
            readyToTrial = ready,
            promisingPaper = promising,
            newCandidates = fresh,
            blockedOrCooling = blocked,
            emptyReasons = emptyReasons,
            generatedAt = System.currentTimeMillis()
        )
    }

    private fun cardFor(
        candidate: LeaderResearchCandidate,
        context: LeaderResearchCandidateDtoContext,
        existingEnabledLeaderIds: Set<Long>,
        accountId: Long?
    ): LeaderResearchShortlistCardDto? {
        val candidateId = candidate.id ?: return null
        val pool = candidate.poolId?.let { context.poolsById[it] }
        val session = context.latestSessionsByCandidateId[candidateId]
        val reason = recommendationReason(candidate, session)
        val risk = riskReason(candidate, session, existingEnabledLeaderIds)
        val group = when {
            risk != null -> GROUP_BLOCKED
            candidate.researchState == LeaderResearchState.TRIAL_READY -> GROUP_READY
            candidate.researchState == LeaderResearchState.PAPER && isPromising(session) -> GROUP_PROMISING
            candidate.researchState == LeaderResearchState.DISCOVERED || candidate.researchState == LeaderResearchState.CANDIDATE -> GROUP_NEW
            else -> GROUP_BLOCKED
        }
        val suggested = suggestedConfig(pool)
        return LeaderResearchShortlistCardDto(
            candidate = mapper.candidateDto(candidate, context, session),
            group = group,
            priorityRank = priority(candidate, session),
            recommendationReason = reason,
            riskReason = risk,
            evidence = evidence(candidate, session),
            cta = cta(group, accountId),
            canCreateDisabledTrial = group == GROUP_READY,
            canAutopilotTrial = group == GROUP_READY && accountId != null,
            suggestedConfig = suggested
        )
    }

    private fun context(candidates: List<LeaderResearchCandidate>): LeaderResearchCandidateDtoContext {
        val leaderIds = candidates.mapNotNull { it.leaderId }.distinct()
        val poolIds = candidates.mapNotNull { it.poolId }.distinct()
        val candidateIds = candidates.mapNotNull { it.id }.distinct()
        return LeaderResearchCandidateDtoContext(
            leadersById = if (leaderIds.isEmpty()) emptyMap() else leaderRepository.findByIdIn(leaderIds)
                .mapNotNull { leader -> leader.id?.let { it to leader } }
                .toMap(),
            poolsById = if (poolIds.isEmpty()) emptyMap() else leaderPoolRepository.findByIdIn(poolIds)
                .mapNotNull { pool -> pool.id?.let { it to pool } }
                .toMap(),
            latestSessionsByCandidateId = if (candidateIds.isEmpty()) emptyMap() else paperSessionRepository.findLatestByCandidateIds(candidateIds)
                .associateBy { it.candidateId }
        )
    }

    private fun recommendationReason(candidate: LeaderResearchCandidate, session: LeaderPaperSession?): String {
        candidate.reason?.takeIf { it.isNotBlank() }?.let { return it }
        return when (candidate.researchState) {
            LeaderResearchState.TRIAL_READY -> "候选已通过 paper-first 研究，可进入小额试跟决策"
            LeaderResearchState.PAPER -> "纸跟样本正在积累，当前表现接近可试跟"
            LeaderResearchState.CANDIDATE -> "候选通过初筛，等待纸跟证据"
            LeaderResearchState.DISCOVERED -> "外部来源新发现候选，等待 intake 和 paper trading"
            LeaderResearchState.COOLDOWN -> "候选仍在冷却，暂不建议试跟"
            LeaderResearchState.RETIRED -> "候选已淘汰"
        } + session?.let { "，样本交易 ${it.tradeCount} 笔" }.orEmpty()
    }

    private fun riskReason(candidate: LeaderResearchCandidate, session: LeaderPaperSession?, existingEnabledLeaderIds: Set<Long>): String? {
        if (candidate.locked) return "候选已锁定，需要人工处理"
        if (candidate.retiredAt != null || candidate.researchState == LeaderResearchState.RETIRED) return "候选已淘汰"
        if (candidate.cooldownUntil != null && candidate.cooldownUntil > System.currentTimeMillis()) return "候选仍在冷却"
        if (candidate.leaderId != null && existingEnabledLeaderIds.contains(candidate.leaderId)) return "该 leader 已有启用跟单配置"
        if (candidate.riskFlags?.contains("UNKNOWN", ignoreCase = true) == true) return "存在 UNKNOWN valuation 风险"
        if (candidate.riskFlags?.contains("UNAVAILABLE", ignoreCase = true) == true) return "存在 UNAVAILABLE valuation 风险"
        if (session != null && session.unknownValuationExposure > BigDecimal.ZERO) return "纸跟存在未知估值暴露"
        if (session != null && session.copyablePnl < BigDecimal.ZERO) return "纸跟 PnL 为负"
        return null
    }

    private fun isPromising(session: LeaderPaperSession?): Boolean {
        return session != null &&
            session.tradeCount >= 3 &&
            session.copyablePnl >= BigDecimal.ZERO &&
            session.unknownValuationExposure.compareTo(BigDecimal.ZERO) == 0
    }

    private fun priority(candidate: LeaderResearchCandidate, session: LeaderPaperSession?): Int {
        val sourceRank = candidate.sourceRank ?: 999
        val scorePenalty = candidate.score?.let { BigDecimal("100").subtract(it).toInt().coerceAtLeast(0) } ?: 100
        val pnlBoost = session?.copyablePnl?.toInt()?.coerceAtMost(50) ?: 0
        return sourceRank + scorePenalty - pnlBoost
    }

    private fun evidence(candidate: LeaderResearchCandidate, session: LeaderPaperSession?): List<LeaderResearchEvidenceMetricDto> {
        val metrics = mutableListOf<LeaderResearchEvidenceMetricDto>()
        candidate.sourceRank?.let { metrics += LeaderResearchEvidenceMetricDto("Source rank", "#$it", "good") }
        candidate.score?.let { metrics += LeaderResearchEvidenceMetricDto("Research score", it.strip(), "good") }
        session?.let {
            metrics += LeaderResearchEvidenceMetricDto("Paper trades", it.tradeCount.toString(), "neutral")
            metrics += LeaderResearchEvidenceMetricDto("Paper PnL", it.copyablePnl.strip(), if (it.copyablePnl >= BigDecimal.ZERO) "good" else "danger")
            metrics += LeaderResearchEvidenceMetricDto(
                "Unknown exposure",
                it.unknownValuationExposure.strip(),
                if (it.unknownValuationExposure.compareTo(BigDecimal.ZERO) == 0) "good" else "warning"
            )
            metrics += LeaderResearchEvidenceMetricDto("Filtered ratio", it.filteredRatio.strip(), "neutral")
        }
        if (metrics.isEmpty()) {
            metrics += LeaderResearchEvidenceMetricDto("Source", candidate.source, "neutral")
        }
        return metrics
    }

    private fun suggestedConfig(pool: LeaderPool?): LeaderResearchSuggestedConfigDto {
        return LeaderResearchSuggestedConfigDto(
            fixedAmount = pool?.suggestedFixedAmount?.strip() ?: "1",
            maxDailyLoss = pool?.suggestedMaxDailyLoss?.strip() ?: "5",
            maxDailyOrders = pool?.suggestedMaxDailyOrders ?: 5,
            minPrice = pool?.suggestedMinPrice?.strip() ?: "0.1",
            maxPrice = pool?.suggestedMaxPrice?.strip() ?: "0.8",
            maxPositionValue = pool?.suggestedMaxPositionValue?.strip() ?: "5"
        )
    }

    private fun cta(group: String, accountId: Long?): String {
        return when (group) {
            GROUP_READY -> if (accountId == null) "createDisabledTrial" else "createTrialOrAutopilot"
            GROUP_PROMISING -> "observePaper"
            GROUP_NEW -> "waitForResearch"
            else -> "viewBlockReason"
        }
    }

    private fun saveSnapshots(cards: List<LeaderResearchShortlistCardDto>) {
        val now = System.currentTimeMillis()
        cards.forEachIndexed { index, card ->
            snapshotRepository.save(
                LeaderDiscoveryShortlistSnapshot(
                    candidateId = card.candidate.id,
                    shortlistGroup = card.group,
                    priorityRank = index + 1,
                    reason = card.recommendationReason,
                    riskReason = card.riskReason,
                    evidenceJson = gson.toJson(card.evidence),
                    createdAt = now
                )
            )
        }
    }

    private fun recordShortlistEvents(
        ready: List<LeaderResearchShortlistCardDto>,
        blocked: List<LeaderResearchShortlistCardDto>
    ) {
        ready.forEachIndexed { index, card ->
            eventService.record(
                type = LeaderResearchEventType.TRIAL_READY,
                candidateId = card.candidate.id,
                reason = "Candidate entered readyToTrial shortlist",
                payloadSummary = card.recommendationReason,
                dedupeKey = "shortlist-ready:${card.candidate.id}"
            )
            eventService.record(
                type = LeaderResearchEventType.SHORTLIST_GENERATED,
                candidateId = card.candidate.id,
                reason = "Top recommendation rank ${index + 1}",
                payloadSummary = card.recommendationReason,
                dedupeKey = "shortlist-top:${card.candidate.id}:$index"
            )
        }
        blocked.forEach { card ->
            eventService.record(
                type = LeaderResearchEventType.SHORTLIST_BLOCKED,
                candidateId = card.candidate.id,
                reason = card.riskReason ?: "Shortlist candidate blocked",
                payloadSummary = card.recommendationReason,
                dedupeKey = "shortlist-blocked:${card.candidate.id}:${card.riskReason.orEmpty().hashCode()}"
            )
        }
    }

    private fun emptyReasons(
        ready: List<LeaderResearchShortlistCardDto>,
        promising: List<LeaderResearchShortlistCardDto>,
        fresh: List<LeaderResearchShortlistCardDto>,
        blocked: List<LeaderResearchShortlistCardDto>
    ): List<String> {
        if (ready.isNotEmpty() || promising.isNotEmpty() || fresh.isNotEmpty()) return emptyList()
        val reasons = mutableListOf<String>()
        if (candidateRepository.count() == 0L) reasons += "暂无研究候选"
        if (blocked.isNotEmpty()) reasons += "全部候选被风险、冷却或估值问题阻断"
        sourceStateRepository.findAllByOrderByUpdatedAtDesc()
            .filter { it.status.name != "SUCCESS" }
            .forEach { reasons += "${it.sourceType.name}: ${it.disabledReason ?: it.errorMessage ?: it.status.name}" }
        return reasons.ifEmpty { listOf("暂无达到推荐条件的 leader") }
    }

    private fun groupRank(group: String): Int = when (group) {
        GROUP_READY -> 0
        GROUP_PROMISING -> 1
        GROUP_NEW -> 2
        else -> 3
    }

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()

    companion object {
        const val GROUP_READY = "readyToTrial"
        const val GROUP_PROMISING = "promisingPaper"
        const val GROUP_NEW = "newCandidates"
        const val GROUP_BLOCKED = "blockedOrCooling"
    }
}

@Service
class LeaderResearchWatchlistService(
    private val systemConfigRepository: SystemConfigRepository,
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val ingestionService: LeaderActivityIngestionService,
    private val eventService: LeaderResearchEventService
) {
    fun get(): LeaderResearchWatchlistResponse {
        val config = systemConfigRepository.findByConfigKey(LeaderResearchSourceService.CONFIG_WATCHLIST)
        return LeaderResearchWatchlistResponse(
            wallets = parse(config?.configValue),
            updatedAt = config?.updatedAt
        )
    }

    fun preview(request: LeaderResearchWatchlistPreviewRequest): LeaderResearchWatchlistPreviewResponse {
        val rawItems = request.rawWallets.split(",", "\n", ";", " ", "\t").map { it.trim() }.filter { it.isNotBlank() }
        val normalized = rawItems.mapNotNull { ingestionService.normalizeWallet(it) }
        val duplicate = normalized.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
        val invalid = rawItems.filter { ingestionService.normalizeWallet(it) == null }
        val distinct = normalized.distinct()
        val existing = distinct.mapNotNull { candidateRepository.findByNormalizedWallet(it) }
        return LeaderResearchWatchlistPreviewResponse(
            valid = distinct,
            invalid = invalid,
            duplicate = duplicate,
            existingCandidates = existing.map { it.normalizedWallet },
            lockedCandidates = existing.filter { it.locked }.map { it.normalizedWallet },
            retiredCandidates = existing.filter { it.researchState == LeaderResearchState.RETIRED }.map { it.normalizedWallet }
        )
    }

    @Transactional
    fun save(request: LeaderResearchWatchlistSaveRequest): LeaderResearchWatchlistResponse {
        if (!request.confirm) {
            throw LeaderAutopilotConfirmRequiredException("保存 watchlist 需要显式确认")
        }
        if (request.rawWallets.length > MAX_WATCHLIST_RAW_LENGTH) {
            throw IllegalArgumentException("watchlist 内容过长")
        }
        val preview = preview(LeaderResearchWatchlistPreviewRequest(request.rawWallets))
        if (preview.valid.size > MAX_WATCHLIST_WALLETS) {
            throw IllegalArgumentException("watchlist 钱包数量超过上限 $MAX_WATCHLIST_WALLETS")
        }
        val now = System.currentTimeMillis()
        val existing = systemConfigRepository.findByConfigKey(LeaderResearchSourceService.CONFIG_WATCHLIST)
        val value = preview.valid.joinToString("\n")
        if (existing == null) {
            systemConfigRepository.save(
                SystemConfig(
                    configKey = LeaderResearchSourceService.CONFIG_WATCHLIST,
                    configValue = value,
                    description = "Leader Research watchlist fallback source",
                    createdAt = now,
                    updatedAt = now
                )
            )
        } else {
            systemConfigRepository.save(existing.copy(configValue = value, updatedAt = now))
        }
        eventService.record(
            type = LeaderResearchEventType.CANDIDATE_UPDATED,
            reason = "Watchlist saved: valid=${preview.valid.size}, invalid=${preview.invalid.size}",
            payloadSummary = "watchlist fallback source",
            dedupeKey = "watchlist-save:$now"
        )
        return get()
    }

    private fun parse(raw: String?): List<String> {
        return raw.orEmpty()
            .split(",", "\n", ";", " ", "\t")
            .mapNotNull { ingestionService.normalizeWallet(it) }
            .distinct()
            .take(MAX_WATCHLIST_WALLETS)
    }

    companion object {
        const val MAX_WATCHLIST_WALLETS = 200
        const val MAX_WATCHLIST_RAW_LENGTH = 20_000
    }
}
