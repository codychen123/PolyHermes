package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.dto.LeaderResearchWatchlistPreviewRequest
import com.wrbug.polymarketbot.dto.LeaderResearchWatchlistSaveRequest
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchNotificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class LeaderResearchWatchlistServiceTest {
    private val systemConfigRepository: SystemConfigRepository = mock()
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val eventService: LeaderResearchEventService = mock()
    private val ingestionService = LeaderActivityIngestionService(mock<LeaderActivityEventRepository>(), Gson())

    @Test
    fun `preview classifies valid invalid duplicate locked and retired wallets`() {
        val valid = "0x1111111111111111111111111111111111111111"
        val locked = "0x2222222222222222222222222222222222222222"
        val retired = "0x3333333333333333333333333333333333333333"
        Mockito.`when`(candidateRepository.findByNormalizedWallet(valid)).thenReturn(null)
        Mockito.`when`(candidateRepository.findByNormalizedWallet(locked)).thenReturn(
            LeaderResearchCandidate(normalizedWallet = locked, locked = true)
        )
        Mockito.`when`(candidateRepository.findByNormalizedWallet(retired)).thenReturn(
            LeaderResearchCandidate(normalizedWallet = retired, researchState = LeaderResearchState.RETIRED)
        )

        val preview = service().preview(
            LeaderResearchWatchlistPreviewRequest("$valid\n$valid\nnot-a-wallet\n$locked\n$retired")
        )

        assertEquals(listOf(valid, locked, retired), preview.valid)
        assertEquals(listOf("not-a-wallet"), preview.invalid)
        assertEquals(listOf(valid), preview.duplicate)
        assertEquals(listOf(locked, retired), preview.existingCandidates)
        assertEquals(listOf(locked), preview.lockedCandidates)
        assertEquals(listOf(retired), preview.retiredCandidates)
    }

    @Test
    fun `save requires confirm and writes normalized unique wallets`() {
        val first = "0x1111111111111111111111111111111111111111"
        val second = "0x2222222222222222222222222222222222222222"
        var stored: SystemConfig? = null
        Mockito.`when`(candidateRepository.findByNormalizedWallet(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(systemConfigRepository.findByConfigKey(LeaderResearchSourceService.CONFIG_WATCHLIST)).thenAnswer { stored }
        Mockito.`when`(systemConfigRepository.save(anySystemConfig())).thenAnswer {
            stored = it.arguments[0] as SystemConfig
            stored
        }

        val withoutConfirm = runCatching {
            service().save(LeaderResearchWatchlistSaveRequest(rawWallets = first, confirm = false))
        }
        val saved = service().save(LeaderResearchWatchlistSaveRequest(rawWallets = "$first\n$second\n$first", confirm = true))

        assertTrue(withoutConfirm.isFailure)
        assertEquals(listOf(first, second), saved.wallets)
        Mockito.verify(systemConfigRepository).save(systemConfigMatching("$first\n$second"))
        Mockito.verify(eventService).record(
            eventType(LeaderResearchEventType.CANDIDATE_UPDATED),
            Mockito.isNull(),
            Mockito.isNull(),
            Mockito.contains("Watchlist saved"),
            Mockito.eq("watchlist fallback source"),
            Mockito.anyString(),
            notificationStatus(LeaderResearchNotificationStatus.PENDING)
        )
    }

    @Test
    fun `save rejects watchlist over wallet limit`() {
        Mockito.`when`(candidateRepository.findByNormalizedWallet(Mockito.anyString())).thenReturn(null)
        val raw = (1..201)
            .joinToString("\n") { index -> "0x${index.toString(16).padStart(40, '0')}" }

        val result = runCatching {
            service().save(LeaderResearchWatchlistSaveRequest(rawWallets = raw, confirm = true))
        }

        assertTrue(result.isFailure)
        Mockito.verify(systemConfigRepository, Mockito.never()).save(anySystemConfig())
    }

    private fun service() = LeaderResearchWatchlistService(
        systemConfigRepository = systemConfigRepository,
        candidateRepository = candidateRepository,
        ingestionService = ingestionService,
        eventService = eventService
    )

    private fun anySystemConfig(): SystemConfig {
        Mockito.any(SystemConfig::class.java)
        return SystemConfig(configKey = LeaderResearchSourceService.CONFIG_WATCHLIST)
    }

    private fun systemConfigMatching(value: String): SystemConfig {
        Mockito.argThat<SystemConfig> {
            it.configKey == LeaderResearchSourceService.CONFIG_WATCHLIST && it.configValue == value
        }
        return SystemConfig(configKey = LeaderResearchSourceService.CONFIG_WATCHLIST, configValue = value)
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
