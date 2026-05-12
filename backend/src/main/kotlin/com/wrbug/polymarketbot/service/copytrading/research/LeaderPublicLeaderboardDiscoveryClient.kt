package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.TraderLeaderboardResponse
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

data class PublicLeaderboardCandidate(
    val wallet: String,
    val sourceRank: Int,
    val evidence: String
)

data class PublicLeaderboardExclusion(
    val wallet: String?,
    val sourceRank: Int?,
    val reason: String,
    val evidence: String
)

data class PublicLeaderboardDiscoveryResult(
    val enabled: Boolean,
    val candidates: List<PublicLeaderboardCandidate>,
    val exclusions: List<PublicLeaderboardExclusion> = emptyList(),
    val endpoint: String,
    val category: String,
    val timePeriod: String,
    val orderBy: String,
    val limit: Int,
    val disabledReason: String? = null
)

@Component
class LeaderPublicLeaderboardDiscoveryClient(
    private val retrofitFactory: RetrofitFactory,
    private val systemConfigRepository: SystemConfigRepository,
    private val ingestionService: LeaderActivityIngestionService,
    private val gson: Gson
) {
    private val logger = LoggerFactory.getLogger(LeaderPublicLeaderboardDiscoveryClient::class.java)
    private val cache = AtomicReference<CachedPublicLeaderboardResult?>()
    private val failureBackoffUntil = AtomicReference(0L)

    fun fetch(): PublicLeaderboardDiscoveryResult {
        val now = System.currentTimeMillis()
        val enabled = config(CONFIG_ENABLED)?.toBooleanStrictOrNull() ?: true
        val category = config(CONFIG_CATEGORY)?.takeIf { it.isNotBlank() } ?: "OVERALL"
        val timePeriod = config(CONFIG_TIME_PERIOD)?.takeIf { it.isNotBlank() } ?: "MONTH"
        val orderBy = config(CONFIG_ORDER_BY)?.takeIf { it.isNotBlank() } ?: "PNL"
        val limit = config(CONFIG_LIMIT)?.toIntOrNull()?.coerceIn(1, 100) ?: 25
        val ttlMs = config(CONFIG_CACHE_TTL_MS)?.toLongOrNull()?.coerceAtLeast(0L) ?: DEFAULT_CACHE_TTL_MS
        val backoffMs = config(CONFIG_FAILURE_BACKOFF_MS)?.toLongOrNull()?.coerceAtLeast(0L) ?: DEFAULT_FAILURE_BACKOFF_MS
        if (!enabled) {
            return PublicLeaderboardDiscoveryResult(
                enabled = false,
                candidates = emptyList(),
                endpoint = ENDPOINT,
                category = category,
                timePeriod = timePeriod,
                orderBy = orderBy,
                limit = limit,
                disabledReason = "Public leaderboard discovery is disabled by config"
            )
        }
        cache.get()
            ?.takeIf { it.matches(category, timePeriod, orderBy, limit) && now - it.createdAt <= ttlMs }
            ?.let { return it.result }
        if (now < failureBackoffUntil.get()) {
            throw IllegalStateException("Leaderboard request is in short backoff after a recent failure")
        }

        val response = try {
            runBlocking {
                retrofitFactory.createDataApi().getTraderLeaderboard(
                    category = category,
                    timePeriod = timePeriod,
                    orderBy = orderBy,
                    limit = limit
                )
            }
        } catch (e: Exception) {
            failureBackoffUntil.set(now + backoffMs)
            throw e
        }
        if (!response.isSuccessful || response.body() == null) {
            failureBackoffUntil.set(now + backoffMs)
            throw IllegalStateException("Leaderboard request failed: ${response.code()} ${response.message()}")
        }
        val intakeResults = response.body().orEmpty()
            .mapIndexed { index, item -> item.toCandidateOrExclusion(index + 1) }
        val candidates = intakeResults
            .mapNotNull { it.candidate }
            .distinctBy { it.wallet }
        val exclusions = intakeResults.mapNotNull { it.exclusion }

        logger.info(
            "Public leaderboard discovery fetched {} candidates and {} exclusions: category={}, timePeriod={}, orderBy={}, limit={}",
            candidates.size,
            exclusions.size,
            category,
            timePeriod,
            orderBy,
            limit
        )
        val result = PublicLeaderboardDiscoveryResult(
            enabled = true,
            candidates = candidates,
            exclusions = exclusions,
            endpoint = ENDPOINT,
            category = category,
            timePeriod = timePeriod,
            orderBy = orderBy,
            limit = limit
        )
        cache.set(CachedPublicLeaderboardResult(category, timePeriod, orderBy, limit, now, result))
        return result
    }

    private fun TraderLeaderboardResponse.toCandidateOrExclusion(defaultRank: Int): CandidateIntakeResult {
        val rankValue = this.rank?.toIntOrNull() ?: defaultRank
        val rawWallet = proxyWallet ?: wallet
        val normalized = ingestionService.normalizeWallet(rawWallet.orEmpty())
        if (normalized == null) {
            return exclusion(rawWallet, rankValue, "INVALID_WALLET")
        }
        if (trades == null || lastTradeTime == null) {
            return exclusion(normalized, rankValue, "MISSING_REQUIRED_FIELDS")
        }
        if (trades < MIN_TRADES) {
            return exclusion(normalized, rankValue, "TOO_FEW_TRADES")
        }
        if (normalizedLastTradeTimeMillis(lastTradeTime) < System.currentTimeMillis() - RECENT_ACTIVITY_WINDOW_MS) {
            return exclusion(normalized, rankValue, "RECENTLY_INACTIVE")
        }
        val evidence = gson.toJson(
            mapOf(
                "source" to LeaderResearchSourceType.PUBLIC_LEADERBOARD.name,
                "endpoint" to ENDPOINT,
                "rank" to rankValue,
                "pnl" to pnl,
                "volume" to (volume ?: vol),
                "amount" to amount,
                "trades" to trades,
                "winRate" to winRate,
                "pseudonym" to pseudonym,
                "name" to name,
                "userName" to userName,
                "xUsername" to xUsername,
                "verifiedBadge" to verifiedBadge,
                "lastTradeTime" to lastTradeTime
            )
        )
        return CandidateIntakeResult(candidate = PublicLeaderboardCandidate(normalized, rankValue, evidence))
    }

    private fun TraderLeaderboardResponse.exclusion(wallet: String?, rank: Int, reason: String): CandidateIntakeResult {
        return CandidateIntakeResult(
            exclusion = PublicLeaderboardExclusion(
                wallet = wallet,
                sourceRank = rank,
                reason = reason,
                evidence = gson.toJson(
                    mapOf(
                        "source" to LeaderResearchSourceType.PUBLIC_LEADERBOARD.name,
                        "endpoint" to ENDPOINT,
                        "rank" to rank,
                        "reason" to reason,
                        "trades" to trades,
                        "lastTradeTime" to lastTradeTime
                    )
                )
            )
        )
    }

    private fun normalizedLastTradeTimeMillis(lastTradeTime: Long): Long {
        return if (lastTradeTime < 10_000_000_000L) lastTradeTime * 1000 else lastTradeTime
    }

    private fun config(key: String): String? = systemConfigRepository.findByConfigKey(key)?.configValue

    companion object {
        const val ENDPOINT = "https://data-api.polymarket.com/v1/leaderboard"
        const val CONFIG_ENABLED = "leader.research.public-leaderboard.enabled"
        const val CONFIG_CATEGORY = "leader.research.public-leaderboard.category"
        const val CONFIG_TIME_PERIOD = "leader.research.public-leaderboard.time-period"
        const val CONFIG_ORDER_BY = "leader.research.public-leaderboard.order-by"
        const val CONFIG_LIMIT = "leader.research.public-leaderboard.limit"
        const val CONFIG_CACHE_TTL_MS = "leader.research.public-leaderboard.cache-ttl-ms"
        const val CONFIG_FAILURE_BACKOFF_MS = "leader.research.public-leaderboard.failure-backoff-ms"
        const val DEFAULT_CACHE_TTL_MS = 60_000L
        const val DEFAULT_FAILURE_BACKOFF_MS = 30_000L
        const val MIN_TRADES = 3
        private const val RECENT_ACTIVITY_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}

private data class CandidateIntakeResult(
    val candidate: PublicLeaderboardCandidate? = null,
    val exclusion: PublicLeaderboardExclusion? = null
)

private data class CachedPublicLeaderboardResult(
    val category: String,
    val timePeriod: String,
    val orderBy: String,
    val limit: Int,
    val createdAt: Long,
    val result: PublicLeaderboardDiscoveryResult
) {
    fun matches(category: String, timePeriod: String, orderBy: String, limit: Int): Boolean {
        return this.category == category &&
            this.timePeriod == timePeriod &&
            this.orderBy == orderBy &&
            this.limit == limit
    }
}
