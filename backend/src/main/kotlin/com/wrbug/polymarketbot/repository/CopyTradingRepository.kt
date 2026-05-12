package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.enums.CopyTradingManagementMode
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 跟单关系 Repository
 */
@Repository
interface CopyTradingRepository : JpaRepository<CopyTrading, Long> {
    
    /**
     * 根据账户ID查找跟单列表
     */
    fun findByAccountId(accountId: Long): List<CopyTrading>
    
    /**
     * 根据 Leader ID 查找跟单列表
     */
    fun findByLeaderId(leaderId: Long): List<CopyTrading>

    /**
     * 根据 Leader ID 批量查找跟单列表，用于聚合页面避免 N+1 查询。
     */
    fun findByLeaderIdIn(leaderIds: Collection<Long>): List<CopyTrading>
    
    /**
     * 根据账户ID和Leader ID查找跟单列表
     */
    fun findByAccountIdAndLeaderId(
        accountId: Long,
        leaderId: Long
    ): List<CopyTrading>
    
    /**
     * 查找所有启用的跟单
     */
    fun findByEnabledTrue(): List<CopyTrading>
    
    /**
     * 根据账户ID查找启用的跟单
     */
    fun findByAccountIdAndEnabledTrue(accountId: Long): List<CopyTrading>
    
    /**
     * 根据Leader ID查找启用的跟单
     */
    fun findByLeaderIdAndEnabledTrue(leaderId: Long): List<CopyTrading>

    fun findByManagementMode(managementMode: CopyTradingManagementMode): List<CopyTrading>

    fun findByAccountIdAndManagementMode(accountId: Long, managementMode: CopyTradingManagementMode): List<CopyTrading>

    fun findByAccountIdAndLeaderIdAndManagementMode(
        accountId: Long,
        leaderId: Long,
        managementMode: CopyTradingManagementMode
    ): List<CopyTrading>

    @Query(
        """
        select c from CopyTrading c
        where c.accountId = :accountId
          and c.managementMode = :managementMode
          and c.enabled = true
        """
    )
    fun findEnabledByAccountIdAndManagementMode(
        @Param("accountId") accountId: Long,
        @Param("managementMode") managementMode: CopyTradingManagementMode
    ): List<CopyTrading>
    
    /**
     * 统计指定 Leader 的跟单数量
     */
    fun countByLeaderId(leaderId: Long): Long
}
