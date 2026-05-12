package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.LeaderAutopilotAccountPolicy
import com.wrbug.polymarketbot.entity.LeaderAutopilotDecisionEvent
import com.wrbug.polymarketbot.entity.LeaderAutopilotFeedbackSummary
import com.wrbug.polymarketbot.entity.LeaderAutopilotRiskReservation
import com.wrbug.polymarketbot.entity.LeaderDiscoveryShortlistSnapshot
import com.wrbug.polymarketbot.enums.AutopilotReservationStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
interface LeaderAutopilotAccountPolicyRepository : JpaRepository<LeaderAutopilotAccountPolicy, Long> {
    fun findByAccountId(accountId: Long): LeaderAutopilotAccountPolicy?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LeaderAutopilotAccountPolicy p where p.accountId = :accountId")
    fun findByAccountIdForUpdate(@Param("accountId") accountId: Long): LeaderAutopilotAccountPolicy?
}

@Repository
interface LeaderAutopilotDecisionEventRepository : JpaRepository<LeaderAutopilotDecisionEvent, Long> {
    fun findByAccountIdOrderByCreatedAtDesc(accountId: Long): List<LeaderAutopilotDecisionEvent>
    fun findByCopyTradingIdOrderByCreatedAtDesc(copyTradingId: Long): List<LeaderAutopilotDecisionEvent>
}

@Repository
interface LeaderAutopilotRiskReservationRepository : JpaRepository<LeaderAutopilotRiskReservation, Long> {
    fun findByReservationKey(reservationKey: String): LeaderAutopilotRiskReservation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LeaderAutopilotRiskReservation r where r.reservationKey = :reservationKey")
    fun findByReservationKeyForUpdate(@Param("reservationKey") reservationKey: String): LeaderAutopilotRiskReservation?

    @Query(
        """
        select coalesce(sum(r.amount), 0)
        from LeaderAutopilotRiskReservation r
        where r.accountId = :accountId
          and r.riskWindowStart = :riskWindowStart
          and r.status in :statuses
        """
    )
    fun sumAmountByAccountAndWindow(
        @Param("accountId") accountId: Long,
        @Param("riskWindowStart") riskWindowStart: Long,
        @Param("statuses") statuses: Collection<AutopilotReservationStatus>
    ): BigDecimal

    @Query(
        """
        select coalesce(sum(r.orderSlots), 0)
        from LeaderAutopilotRiskReservation r
        where r.accountId = :accountId
          and r.riskWindowStart = :riskWindowStart
          and r.status in :statuses
        """
    )
    fun sumOrderSlotsByAccountAndWindow(
        @Param("accountId") accountId: Long,
        @Param("riskWindowStart") riskWindowStart: Long,
        @Param("statuses") statuses: Collection<AutopilotReservationStatus>
    ): Long

    fun findByStatusAndExpiresAtLessThan(status: AutopilotReservationStatus, expiresAt: Long): List<LeaderAutopilotRiskReservation>

    @Modifying
    @Query(
        "update LeaderAutopilotRiskReservation r set r.status = :nextStatus, r.reason = :reason, r.updatedAt = :now where r.id = :id and r.status = :expectedStatus"
    )
    fun updateStatus(
        @Param("id") id: Long,
        @Param("expectedStatus") expectedStatus: AutopilotReservationStatus,
        @Param("nextStatus") nextStatus: AutopilotReservationStatus,
        @Param("reason") reason: String?,
        @Param("now") now: Long
    ): Int
}

@Repository
interface LeaderAutopilotFeedbackSummaryRepository : JpaRepository<LeaderAutopilotFeedbackSummary, Long> {
    fun findByCopyTradingId(copyTradingId: Long): LeaderAutopilotFeedbackSummary?
    fun findByCandidateId(candidateId: Long): List<LeaderAutopilotFeedbackSummary>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from LeaderAutopilotFeedbackSummary f where f.copyTradingId = :copyTradingId")
    fun findByCopyTradingIdForUpdate(@Param("copyTradingId") copyTradingId: Long): LeaderAutopilotFeedbackSummary?
}

@Repository
interface LeaderDiscoveryShortlistSnapshotRepository : JpaRepository<LeaderDiscoveryShortlistSnapshot, Long> {
    fun findByCreatedAtGreaterThanEqualOrderByPriorityRankAsc(createdAt: Long): List<LeaderDiscoveryShortlistSnapshot>
}
