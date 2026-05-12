package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.SignedOrderObject
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.LeaderAutopilotAccountPolicy
import com.wrbug.polymarketbot.entity.LeaderAutopilotRiskReservation
import com.wrbug.polymarketbot.enums.AutopilotActionType
import com.wrbug.polymarketbot.enums.AutopilotAccountState
import com.wrbug.polymarketbot.enums.AutopilotDecision
import com.wrbug.polymarketbot.enums.CopyTradingManagementMode
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.FilteredOrderRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.ProcessedTradeRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotDecisionRequest
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotDecisionResult
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotDecisionService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotFeedbackService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import retrofit2.Response
import java.math.BigDecimal
import java.util.Optional
import org.mockito.ArgumentMatchers.any as mockitoAny

class CopyOrderTrackingAutopilotTest {
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository = mock()
    private val sellMatchRecordRepository: SellMatchRecordRepository = mock()
    private val sellMatchDetailRepository: SellMatchDetailRepository = mock()
    private val processedTradeRepository: ProcessedTradeRepository = mock()
    private val filteredOrderRepository: FilteredOrderRepository = mock()
    private val copyTradingRepository: CopyTradingRepository = mock()
    private val accountRepository: AccountRepository = mock()
    private val accountService: AccountService = mock()
    private val leaderRepository: LeaderRepository = mock()
    private val orderSigningService: OrderSigningService = mock()
    private val blockchainService: BlockchainService = mock()
    private val clobService: PolymarketClobService = mock()
    private val retrofitFactory: RetrofitFactory = mock()
    private val cryptoUtils: CryptoUtils = mock()
    private val jsonUtils: JsonUtils = mock()
    private val marketService: MarketService = mock()
    private val autopilotService: LeaderAutopilotDecisionService = mock()
    private val autopilotFeedbackService: LeaderAutopilotFeedbackService = mock()
    private val authenticatedClobApi: PolymarketClobApi = mock()
    private val filterService = CopyTradingFilterService(
        clobService = clobService,
        accountService = accountService,
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        jsonUtils = jsonUtils
    )

    @Test
    fun `autopilot deny skips real CLOB order`() = runBlocking {
        stubHappyPathBeforeDecision()
        Mockito.`when`(autopilotService.decide(anyDecisionRequest())).thenReturn(
            decision(AutopilotDecision.DENY, "BUDGET_EXHAUSTED")
        )

        service().processBuyTrade(leaderId = 9L, trade = trade(), source = "test")

        val request = captureDecisionRequest()
        assertEquals(AutopilotActionType.BUY, request.actionType)
        assertEquals(10L, request.copyTrading?.id)
        assertEquals("trade-1", request.leaderTradeId)
        runBlocking {
            Mockito.verify(authenticatedClobApi, Mockito.never()).createOrder(anyNewOrderRequest())
        }
        Mockito.verify(copyOrderTrackingRepository, Mockito.never()).save(Mockito.any())
        Mockito.verify(autopilotService, Mockito.never()).finalizeReservation(Mockito.any(), Mockito.any())
    }

    @Test
    fun `autopilot pause skips real CLOB order`() = runBlocking {
        stubHappyPathBeforeDecision()
        Mockito.`when`(autopilotService.decide(anyDecisionRequest())).thenReturn(
            decision(AutopilotDecision.PAUSE, "SOURCE_STALE")
        )

        service().processBuyTrade(leaderId = 9L, trade = trade(), source = "test")

        Mockito.verify(autopilotService).decide(anyDecisionRequest())
        runBlocking {
            Mockito.verify(authenticatedClobApi, Mockito.never()).createOrder(anyNewOrderRequest())
        }
        Mockito.verify(copyOrderTrackingRepository, Mockito.never()).save(Mockito.any())
    }

    @Test
    fun `autopilot allow creates order after reservation and finalizes it`() = runBlocking {
        stubHappyPathBeforeDecision()
        Mockito.`when`(autopilotService.decide(anyDecisionRequest())).thenReturn(
            decision(
                AutopilotDecision.ALLOW,
                "ALLOW_WITH_RESERVATION",
                LeaderAutopilotRiskReservation(
                    id = 77L,
                    reservationKey = "reservation",
                    accountId = 2L,
                    copyTradingId = 10L,
                    leaderId = 9L,
                    candidateId = 1L,
                    amount = BigDecimal("2.10"),
                    riskWindowStart = 1L,
                    riskWindowEnd = 2L,
                    expiresAt = 3L
                )
            )
        )
        runBlocking {
            Mockito.`when`(authenticatedClobApi.createOrder(anyNewOrderRequest()))
                .thenReturn(Response.success(NewOrderResponse(success = true, orderId = "0xabc123")))
        }
        Mockito.`when`(copyOrderTrackingRepository.save(Mockito.any())).thenAnswer { it.arguments[0] }

        service().processBuyTrade(leaderId = 9L, trade = trade(), source = "test")

        runBlocking {
            Mockito.verify(authenticatedClobApi).createOrder(anyNewOrderRequest())
        }
        Mockito.verify(copyOrderTrackingRepository).save(Mockito.argThat {
            it.copyTradingId == 10L &&
                it.buyOrderId == "0xabc123" &&
                it.leaderBuyTradeId == "trade-1"
        })
        Mockito.verify(autopilotService).finalizeReservation(77L, "0xabc123")
        Mockito.verify(autopilotFeedbackService).recordBuySubmitted(
            Mockito.argThat { it.id == 10L },
            Mockito.any(BigDecimal::class.java),
            Mockito.eq("0xabc123")
        )
    }

    private suspend fun stubHappyPathBeforeDecision() {
        val copyTrading = autopilotCopyTrading()
        Mockito.`when`(copyTradingRepository.findByLeaderIdAndEnabledTrue(9L)).thenReturn(listOf(copyTrading))
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(10L), Mockito.anyLong()))
            .thenReturn(0)
        Mockito.`when`(sellMatchRecordRepository.sumRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.eq(10L), Mockito.anyLong()))
            .thenReturn(BigDecimal.ZERO)
        Mockito.`when`(clobService.getOrderbookByTokenId("token-1")).thenReturn(
            Result.success(
                OrderbookResponse(
                    bids = listOf(OrderbookEntry(price = "0.50", size = "10")),
                    asks = listOf(OrderbookEntry(price = "0.52", size = "10"))
                )
            )
        )
        Mockito.`when`(cryptoUtils.decrypt("encrypted-private-key"))
            .thenReturn("0123456789012345678901234567890123456789012345678901234567890123")
        Mockito.`when`(cryptoUtils.decrypt("encrypted-secret")).thenReturn("secret")
        Mockito.`when`(cryptoUtils.decrypt("encrypted-passphrase")).thenReturn("passphrase")
        Mockito.`when`(retrofitFactory.createClobApi("api-key", "secret", "passphrase", account().walletAddress))
            .thenReturn(authenticatedClobApi)
        Mockito.`when`(marketService.getNegRiskByConditionId("market-1")).thenReturn(false)
        Mockito.`when`(orderSigningService.getExchangeContract(false)).thenReturn("exchange")
        Mockito.`when`(orderSigningService.getSignatureTypeForWalletType("magic")).thenReturn(1)
        Mockito.`when`(
            orderSigningService.createAndSignOrder(
                nonNullEq("0123456789012345678901234567890123456789012345678901234567890123"),
                nonNullEq("0x8888888888888888888888888888888888888888"),
                nonNullEq("token-1"),
                nonNullEq("BUY"),
                nonNullEq("0.525"),
                nonNullEq("4"),
                nonNullEq(1),
                nonNullEq("exchange")
            )
        ).thenReturn(signedOrder(account().walletAddress))
    }

    private fun service() = CopyOrderTrackingService(
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        sellMatchRecordRepository = sellMatchRecordRepository,
        sellMatchDetailRepository = sellMatchDetailRepository,
        processedTradeRepository = processedTradeRepository,
        filteredOrderRepository = filteredOrderRepository,
        copyTradingRepository = copyTradingRepository,
        accountRepository = accountRepository,
        filterService = filterService,
        leaderRepository = leaderRepository,
        orderSigningService = orderSigningService,
        blockchainService = blockchainService,
        clobService = clobService,
        retrofitFactory = retrofitFactory,
        cryptoUtils = cryptoUtils,
        marketService = marketService,
        autopilotService = autopilotService,
        autopilotFeedbackService = autopilotFeedbackService
    )

    private fun autopilotCopyTrading() = CopyTrading(
        id = 10L,
        accountId = 2L,
        leaderId = 9L,
        enabled = true,
        managementMode = CopyTradingManagementMode.AUTOPILOT,
        autopilotCandidateId = 1L,
        copyMode = "FIXED",
        fixedAmount = BigDecimal("2"),
        maxDailyOrders = 5,
        maxDailyLoss = BigDecimal("5"),
        minPrice = BigDecimal("0.10"),
        maxPrice = BigDecimal("0.80"),
        priceTolerance = BigDecimal("5")
    )

    private fun account() = Account(
        id = 2L,
        privateKey = "encrypted-private-key",
        walletAddress = "0x7ff36ab500000000000000000000000000000000",
        proxyAddress = "0x8888888888888888888888888888888888888888",
        apiKey = "api-key",
        apiSecret = "encrypted-secret",
        apiPassphrase = "encrypted-passphrase"
    )

    private fun trade() = TradeResponse(
        id = "trade-1",
        market = "market-1",
        side = "BUY",
        price = "0.50",
        size = "100",
        timestamp = "2026-05-12T00:00:00Z",
        user = "leader",
        outcomeIndex = 0,
        outcome = "YES",
        tokenId = "token-1"
    )

    private fun decision(
        decision: AutopilotDecision,
        reasonCode: String,
        reservation: LeaderAutopilotRiskReservation? = null
    ) = LeaderAutopilotDecisionResult(
        decision = decision,
        reasonCode = reasonCode,
        reason = reasonCode,
        policy = LeaderAutopilotAccountPolicy(accountId = 2L, state = AutopilotAccountState.ON),
        reservation = reservation
    )

    private fun signedOrder(signer: String) = SignedOrderObject(
        salt = 1L,
        maker = "0x8888888888888888888888888888888888888888",
        signer = signer,
        taker = "0x0000000000000000000000000000000000000000",
        tokenId = "token-1",
        makerAmount = "2100000",
        takerAmount = "4000000",
        side = "BUY",
        signatureType = 1,
        timestamp = "1",
        expiration = "0",
        metadata = "0x0000000000000000000000000000000000000000000000000000000000000000",
        builder = "0x0000000000000000000000000000000000000000000000000000000000000000",
        signature = "0xsignature"
    )

    private fun anyDecisionRequest(): LeaderAutopilotDecisionRequest {
        Mockito.any(LeaderAutopilotDecisionRequest::class.java)
        return LeaderAutopilotDecisionRequest(
            actionType = AutopilotActionType.BUY,
            accountId = 2L
        )
    }

    private fun anyNewOrderRequest(): NewOrderRequest {
        Mockito.any(NewOrderRequest::class.java)
        return NewOrderRequest(
            order = signedOrder(account().walletAddress),
            owner = "api-key",
            orderType = "FAK"
        )
    }

    private fun captureDecisionRequest(): LeaderAutopilotDecisionRequest {
        val captor = ArgumentCaptor.forClass(LeaderAutopilotDecisionRequest::class.java)
        Mockito.verify(autopilotService).decide(captureDecisionRequest(captor))
        return captor.value
    }

    private fun captureDecisionRequest(captor: ArgumentCaptor<LeaderAutopilotDecisionRequest>): LeaderAutopilotDecisionRequest {
        captor.capture()
        return LeaderAutopilotDecisionRequest(
            actionType = AutopilotActionType.BUY,
            accountId = 2L
        )
    }

    private fun <T> nonNullEq(value: T): T {
        Mockito.eq(value)
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
