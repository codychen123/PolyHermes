package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.TraderLeaderboardResponse
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response

class LeaderPublicLeaderboardDiscoveryClientTest {
    private val retrofitFactory: RetrofitFactory = mock()
    private val systemConfigRepository: SystemConfigRepository = mock()
    private val dataApi: PolymarketDataApi = mock()
    private val ingestionService = LeaderActivityIngestionService(mock<LeaderActivityEventRepository>(), Gson())

    @Test
    fun `fetch normalizes wallets and reuses short cache`() {
        Mockito.`when`(retrofitFactory.createDataApi()).thenReturn(dataApi)
        stubConfig(LeaderPublicLeaderboardDiscoveryClient.CONFIG_CACHE_TTL_MS, "60000")
        runBlocking {
            Mockito.`when`(
                dataApi.getTraderLeaderboard(
                    category = "OVERALL",
                    timePeriod = "MONTH",
                    orderBy = "PNL",
                    limit = 25
                )
            ).thenReturn(
                Response.success(
                    listOf(
                        TraderLeaderboardResponse(
                            rank = "1",
                            proxyWallet = "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD",
                            pnl = 12.3,
                            vol = 45.6,
                            userName = "leader",
                            trades = 12,
                            lastTradeTime = System.currentTimeMillis() / 1000
                        ),
                        TraderLeaderboardResponse(rank = "2", proxyWallet = "not-a-wallet"),
                        TraderLeaderboardResponse(
                            rank = "3",
                            proxyWallet = "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD",
                            pnl = 1.0,
                            trades = 8,
                            lastTradeTime = System.currentTimeMillis() / 1000
                        )
                    )
                )
            )
        }

        val discoveryClient = client()
        val first = discoveryClient.fetch()
        val second = discoveryClient.fetch()

        assertEquals(1, first.candidates.size)
        assertEquals("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd", first.candidates.single().wallet)
        assertTrue(first.candidates.single().evidence.contains("\"pnl\":12.3"))
        assertEquals(first, second)
        runBlocking {
            Mockito.verify(dataApi, Mockito.times(1)).getTraderLeaderboard(
                category = "OVERALL",
                timePeriod = "MONTH",
                orderBy = "PNL",
                limit = 25
            )
        }
    }

    @Test
    fun `fetch enters short backoff after failed response`() {
        Mockito.`when`(retrofitFactory.createDataApi()).thenReturn(dataApi)
        stubConfig(LeaderPublicLeaderboardDiscoveryClient.CONFIG_FAILURE_BACKOFF_MS, "60000")
        runBlocking {
            Mockito.`when`(
                dataApi.getTraderLeaderboard(
                    category = "OVERALL",
                    timePeriod = "MONTH",
                    orderBy = "PNL",
                    limit = 25
                )
            ).thenReturn(Response.error(429, "rate limited".toResponseBody()))
        }

        val discoveryClient = client()
        val first = runCatching { discoveryClient.fetch() }
        val second = runCatching { discoveryClient.fetch() }

        assertTrue(first.isFailure)
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()!!.message!!.contains("backoff"))
        runBlocking {
            Mockito.verify(dataApi, Mockito.times(1)).getTraderLeaderboard(
                category = "OVERALL",
                timePeriod = "MONTH",
                orderBy = "PNL",
                limit = 25
            )
        }
    }

    @Test
    fun `fetch returns disabled result without hitting api when disabled by config`() {
        stubConfig(LeaderPublicLeaderboardDiscoveryClient.CONFIG_ENABLED, "false")

        val result = client().fetch()

        assertFalse(result.enabled)
        assertEquals(0, result.candidates.size)
        Mockito.verify(retrofitFactory, Mockito.never()).createDataApi()
    }

    @Test
    fun `fetch accepts empty result and skips rows without valid wallet`() {
        Mockito.`when`(retrofitFactory.createDataApi()).thenReturn(dataApi)
        runBlocking {
            Mockito.`when`(
                dataApi.getTraderLeaderboard(
                    category = "OVERALL",
                    timePeriod = "MONTH",
                    orderBy = "PNL",
                    limit = 25
                )
            ).thenReturn(
                Response.success(
                    listOf(
                        TraderLeaderboardResponse(rank = "bad", proxyWallet = null, wallet = null),
                        TraderLeaderboardResponse(rank = "2", proxyWallet = "not-a-wallet")
                    )
                )
            )
        }

        val result = client().fetch()

        assertTrue(result.enabled)
        assertEquals(0, result.candidates.size)
        assertEquals(2, result.exclusions.size)
    }

    @Test
    fun `fetch records intake exclusions for missing fields low trades and stale activity`() {
        Mockito.`when`(retrofitFactory.createDataApi()).thenReturn(dataApi)
        val nowSeconds = System.currentTimeMillis() / 1000
        runBlocking {
            Mockito.`when`(
                dataApi.getTraderLeaderboard(
                    category = "OVERALL",
                    timePeriod = "MONTH",
                    orderBy = "PNL",
                    limit = 25
                )
            ).thenReturn(
                Response.success(
                    listOf(
                        TraderLeaderboardResponse(
                            rank = "1",
                            proxyWallet = "0x1111111111111111111111111111111111111111",
                            trades = 5,
                            lastTradeTime = nowSeconds
                        ),
                        TraderLeaderboardResponse(
                            rank = "2",
                            proxyWallet = "0x2222222222222222222222222222222222222222",
                            trades = null,
                            lastTradeTime = nowSeconds
                        ),
                        TraderLeaderboardResponse(
                            rank = "3",
                            proxyWallet = "0x3333333333333333333333333333333333333333",
                            trades = 1,
                            lastTradeTime = nowSeconds
                        ),
                        TraderLeaderboardResponse(
                            rank = "4",
                            proxyWallet = "0x4444444444444444444444444444444444444444",
                            trades = 6,
                            lastTradeTime = nowSeconds - 40L * 24 * 60 * 60
                        )
                    )
                )
            )
        }

        val result = client().fetch()

        assertEquals(1, result.candidates.size)
        assertEquals(3, result.exclusions.size)
        assertTrue(result.exclusions.any { it.reason == "MISSING_REQUIRED_FIELDS" })
        assertTrue(result.exclusions.any { it.reason == "TOO_FEW_TRADES" })
        assertTrue(result.exclusions.any { it.reason == "RECENTLY_INACTIVE" })
    }

    private fun client() = LeaderPublicLeaderboardDiscoveryClient(
        retrofitFactory = retrofitFactory,
        systemConfigRepository = systemConfigRepository,
        ingestionService = ingestionService,
        gson = Gson()
    )

    private fun stubConfig(key: String, value: String) {
        Mockito.`when`(systemConfigRepository.findByConfigKey(key))
            .thenReturn(SystemConfig(configKey = key, configValue = value))
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
