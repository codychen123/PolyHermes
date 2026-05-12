package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.SellMatchRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * 卖出匹配记录Repository
 */
@Repository
interface SellMatchRecordRepository : JpaRepository<SellMatchRecord, Long> {
    
    /**
     * 根据跟单关系ID查询所有卖出记录
     */
    fun findByCopyTradingId(copyTradingId: Long): List<SellMatchRecord>

    @Query("SELECT COALESCE(SUM(s.totalRealizedPnl), 0) FROM SellMatchRecord s WHERE s.copyTradingId = :copyTradingId AND s.createdAt >= :createdAt")
    fun sumRealizedPnlByCopyTradingIdAndCreatedAtGreaterThanEqual(copyTradingId: Long, createdAt: Long): BigDecimal
    
    /**
     * 根据卖出订单ID查询记录
     */
    fun findBySellOrderId(sellOrderId: String): SellMatchRecord?
    
    /**
     * 根据Leader卖出交易ID查询记录
     */
    fun findByLeaderSellTradeId(leaderSellTradeId: String): SellMatchRecord?
    
    /**
     * 查询所有价格未更新的卖出记录
     * 注意：priceUpdated 现在同时表示价格已更新和通知已发送（共用字段）
     */
    fun findByPriceUpdatedFalse(): List<SellMatchRecord>
}
