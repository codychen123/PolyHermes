package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.enums.AutopilotActionType
import com.wrbug.polymarketbot.enums.CopyTradingManagementMode
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingMonitorService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotDecisionRequest
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotDecisionDeniedException
import com.wrbug.polymarketbot.service.copytrading.research.LeaderAutopilotDecisionService
import com.google.gson.Gson
import com.wrbug.polymarketbot.util.IllegalBigDecimal
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 跟单配置管理服务（独立配置，不再绑定模板）
 */
@Service
class CopyTradingService(
    private val copyTradingRepository: CopyTradingRepository,
    private val accountRepository: AccountRepository,
    private val templateRepository: CopyTradingTemplateRepository,
    private val leaderRepository: LeaderRepository,
    private val monitorService: CopyTradingMonitorService,
    private val autopilotService: LeaderAutopilotDecisionService,
    private val jsonUtils: JsonUtils,
    private val gson: Gson
) : ApplicationContextAware {

    private val logger = LoggerFactory.getLogger(CopyTradingService::class.java)
    
    private var applicationContext: ApplicationContext? = null
    
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }
    
    /**
     * 获取代理对象，用于解决 @Transactional 自调用问题
     */
    private fun getSelf(): CopyTradingService {
        return applicationContext?.getBean(CopyTradingService::class.java)
            ?: throw IllegalStateException("ApplicationContext not initialized")
    }
    
    /**
     * 创建跟单配置
     * 支持两种方式：
     * 1. 提供 templateId：从模板填充配置，可以覆盖部分字段
     * 2. 不提供 templateId：手动输入所有配置参数
     */
    @Transactional
    open fun createCopyTrading(request: CopyTradingCreateRequest): Result<CopyTradingDto> {
        return try {
            // 1. 验证账户是否存在
            val account = accountRepository.findById(request.accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))
            
            // 2. 验证 Leader 是否存在
            val leader = leaderRepository.findById(request.leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))
            
            // 3. 验证配置名（强校验：不能为空字符串）
            val configName = request.configName?.trim()
            if (configName.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("配置名不能为空"))
            }
            
            // 5. 获取配置参数（从模板填充或手动输入）
            val config = if (request.templateId != null) {
                // 从模板填充
                val template = templateRepository.findById(request.templateId).orElse(null)
                    ?: return Result.failure(IllegalArgumentException("模板不存在"))
                
                // 使用模板值，但允许请求中的字段覆盖
                CopyTradingConfig(
                    copyMode = request.copyMode ?: template.copyMode,
                    copyRatio = request.copyRatio?.toSafeBigDecimal() ?: template.copyRatio,
                    fixedAmount = request.fixedAmount?.toSafeBigDecimal() ?: template.fixedAmount,
                    maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: template.maxOrderSize,
                    minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: template.minOrderSize,
                    maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: template.maxDailyLoss,
                    maxDailyOrders = request.maxDailyOrders ?: template.maxDailyOrders,
                    priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: template.priceTolerance,
                    delaySeconds = request.delaySeconds ?: template.delaySeconds,
                    pollIntervalSeconds = request.pollIntervalSeconds ?: template.pollIntervalSeconds,
                    useWebSocket = request.useWebSocket ?: template.useWebSocket,
                    websocketReconnectInterval = request.websocketReconnectInterval ?: template.websocketReconnectInterval,
                    websocketMaxRetries = request.websocketMaxRetries ?: template.websocketMaxRetries,
                    supportSell = request.supportSell ?: template.supportSell,
                    minOrderDepth = request.minOrderDepth?.toSafeBigDecimal() ?: template.minOrderDepth,
                    maxSpread = request.maxSpread?.toSafeBigDecimal() ?: template.maxSpread,
                    minPrice = request.minPrice?.toSafeBigDecimal() ?: template.minPrice,
                    maxPrice = request.maxPrice?.toSafeBigDecimal() ?: template.maxPrice,
                    maxPositionValue = request.maxPositionValue?.toSafeBigDecimal(),
                    keywordFilterMode = request.keywordFilterMode ?: "DISABLED",
                    keywords = convertKeywordsToJson(request.keywords),
                    maxMarketEndDate = request.maxMarketEndDate,
                    pushFilteredOrders = request.pushFilteredOrders ?: template.pushFilteredOrders
                )
            } else {
                // 手动输入（所有字段必须提供）
                if (request.copyMode == null) {
                    return Result.failure(IllegalArgumentException("copyMode 不能为空"))
                }
                
                CopyTradingConfig(
                    copyMode = request.copyMode,
                    copyRatio = request.copyRatio?.toSafeBigDecimal() ?: BigDecimal.ONE,
                    fixedAmount = request.fixedAmount?.toSafeBigDecimal(),
                    maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: "1000".toSafeBigDecimal(),
                    minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: "1".toSafeBigDecimal(),
                    maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: "10000".toSafeBigDecimal(),
                    maxDailyOrders = request.maxDailyOrders ?: 100,
                    priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: "5".toSafeBigDecimal(),
                    delaySeconds = request.delaySeconds ?: 0,
                    pollIntervalSeconds = request.pollIntervalSeconds ?: 5,
                    useWebSocket = request.useWebSocket ?: true,
                    websocketReconnectInterval = request.websocketReconnectInterval ?: 5000,
                    websocketMaxRetries = request.websocketMaxRetries ?: 10,
                    supportSell = request.supportSell ?: true,
                    minOrderDepth = request.minOrderDepth?.toSafeBigDecimal(),
                    maxSpread = request.maxSpread?.toSafeBigDecimal(),
                    minPrice = request.minPrice?.toSafeBigDecimal(),
                    maxPrice = request.maxPrice?.toSafeBigDecimal(),
                    maxPositionValue = request.maxPositionValue?.toSafeBigDecimal(),
                    keywordFilterMode = request.keywordFilterMode ?: "DISABLED",
                    keywords = convertKeywordsToJson(request.keywords),
                    maxMarketEndDate = request.maxMarketEndDate,
                    pushFilteredOrders = request.pushFilteredOrders ?: false  // 手动输入时使用请求中的值，默认为 false
                )
            }
            
            val managementMode = parseManagementMode(request.managementMode)
            val candidateId = request.autopilotCandidateId
            val policyId = request.autopilotPolicyId
            val requestedEnabled = request.enabled
            val shouldEnable = if (managementMode == CopyTradingManagementMode.AUTOPILOT && requestedEnabled) {
                val decision = autopilotService.decide(
                    LeaderAutopilotDecisionRequest(
                        actionType = AutopilotActionType.ENABLE_CONFIG,
                        accountId = request.accountId,
                        candidateId = candidateId,
                        leaderId = request.leaderId,
                        requestedAmount = config.fixedAmount,
                        inputSnapshot = mapOf(
                            "source" to "copyTrading.create",
                            "copyMode" to config.copyMode,
                            "fixedAmount" to config.fixedAmount?.toPlainString(),
                            "maxOrderSize" to config.maxOrderSize.toPlainString()
                        )
                    )
                )
                if (!decision.allowed) {
                    return Result.failure(LeaderAutopilotDecisionDeniedException("Autopilot 决策未允许启用真钱跟单配置: ${decision.reasonCode}"))
                }
                true
            } else {
                requestedEnabled
            }

            // 6. 创建跟单配置
            val copyTrading = CopyTrading(
                accountId = request.accountId,
                leaderId = request.leaderId,
                enabled = shouldEnable,
                managementMode = managementMode,
                autopilotPolicyId = policyId,
                autopilotCandidateId = candidateId,
                copyMode = config.copyMode,
                copyRatio = config.copyRatio,
                fixedAmount = config.fixedAmount,
                maxOrderSize = config.maxOrderSize,
                minOrderSize = config.minOrderSize,
                maxDailyLoss = config.maxDailyLoss,
                maxDailyOrders = config.maxDailyOrders,
                priceTolerance = config.priceTolerance,
                delaySeconds = config.delaySeconds,
                pollIntervalSeconds = config.pollIntervalSeconds,
                useWebSocket = config.useWebSocket,
                websocketReconnectInterval = config.websocketReconnectInterval,
                websocketMaxRetries = config.websocketMaxRetries,
                supportSell = config.supportSell,
                minOrderDepth = config.minOrderDepth,
                maxSpread = config.maxSpread,
                minPrice = config.minPrice,
                maxPrice = config.maxPrice,
                maxPositionValue = config.maxPositionValue,
                keywordFilterMode = config.keywordFilterMode,
                keywords = config.keywords,
                configName = configName,
                pushFailedOrders = request.pushFailedOrders ?: false,
                maxMarketEndDate = config.maxMarketEndDate,
                pushFilteredOrders = config.pushFilteredOrders
            )
            
            val saved = copyTradingRepository.save(copyTrading)
            
            // 如果跟单已启用，更新 Leader 监听和账户监听（增量更新，不重启所有监听）
            if (saved.enabled) {
                kotlinx.coroutines.runBlocking {
                    try {
                        monitorService.updateLeaderMonitoring(saved.leaderId)
                        monitorService.updateAccountMonitoring(saved.accountId)
                    } catch (e: Exception) {
                        logger.error("更新监听失败", e)
                    }
                }
            }
            
            Result.success(toDto(saved, account, leader))
        } catch (e: Exception) {
            logger.error("创建跟单失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新跟单配置
     */
    @Transactional
    open fun updateCopyTrading(request: CopyTradingUpdateRequest): Result<CopyTradingDto> {
        return try {
            val copyTrading = copyTradingRepository.findById(request.copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单配置不存在"))
            
            // 验证配置名（如果提供了配置名，进行强校验）
            val configName = if (request.configName != null) {
                val trimmed = request.configName.trim()
                if (trimmed.isBlank()) {
                    return Result.failure(IllegalArgumentException("配置名不能为空"))
                }
                trimmed
            } else {
                copyTrading.configName
            }
            
            val requestedManagementMode = parseManagementMode(request.managementMode, copyTrading.managementMode)
            val requestedEnabled = request.enabled ?: copyTrading.enabled

            // 更新字段（只更新提供的字段）
            val updatedDraft = copyTrading.copy(
                enabled = requestedEnabled,
                managementMode = requestedManagementMode,
                autopilotPolicyId = request.autopilotPolicyId ?: copyTrading.autopilotPolicyId,
                autopilotCandidateId = request.autopilotCandidateId ?: copyTrading.autopilotCandidateId,
                copyMode = request.copyMode ?: copyTrading.copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: copyTrading.copyRatio,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal() ?: copyTrading.fixedAmount,
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: copyTrading.maxOrderSize,
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: copyTrading.minOrderSize,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: copyTrading.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: copyTrading.maxDailyOrders,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: copyTrading.priceTolerance,
                delaySeconds = request.delaySeconds ?: copyTrading.delaySeconds,
                pollIntervalSeconds = request.pollIntervalSeconds ?: copyTrading.pollIntervalSeconds,
                useWebSocket = request.useWebSocket ?: copyTrading.useWebSocket,
                websocketReconnectInterval = request.websocketReconnectInterval ?: copyTrading.websocketReconnectInterval,
                websocketMaxRetries = request.websocketMaxRetries ?: copyTrading.websocketMaxRetries,
                supportSell = request.supportSell ?: copyTrading.supportSell,
                // 处理可选字段：空字符串表示要清空（设置为 null），null 表示不更新，转换失败保留旧值
                minOrderDepth = if (request.minOrderDepth != null) {
                    if (request.minOrderDepth.isBlank()) {
                        null
                    } else {
                        val converted = request.minOrderDepth.toSafeBigDecimal()
                        if (converted == IllegalBigDecimal) copyTrading.minOrderDepth else converted
                    }
                } else {
                    copyTrading.minOrderDepth
                },
                maxSpread = if (request.maxSpread != null) {
                    if (request.maxSpread.isBlank()) {
                        null
                    } else {
                        val converted = request.maxSpread.toSafeBigDecimal()
                        if (converted == IllegalBigDecimal) copyTrading.maxSpread else converted
                    }
                } else {
                    copyTrading.maxSpread
                },
                minPrice = if (request.minPrice != null) {
                    if (request.minPrice.isBlank()) {
                        null
                    } else {
                        val converted = request.minPrice.toSafeBigDecimal()
                        if (converted == IllegalBigDecimal) copyTrading.minPrice else converted
                    }
                } else {
                    copyTrading.minPrice
                },
                maxPrice = if (request.maxPrice != null) {
                    if (request.maxPrice.isBlank()) {
                        null
                    } else {
                        val converted = request.maxPrice.toSafeBigDecimal()
                        if (converted == IllegalBigDecimal) copyTrading.maxPrice else converted
                    }
                } else {
                    copyTrading.maxPrice
                },
                maxPositionValue = if (request.maxPositionValue != null) {
                    if (request.maxPositionValue.isBlank()) {
                        null
                    } else {
                        val converted = request.maxPositionValue.toSafeBigDecimal()
                        if (converted == IllegalBigDecimal) copyTrading.maxPositionValue else converted
                    }
                } else {
                    copyTrading.maxPositionValue
                },
                keywordFilterMode = request.keywordFilterMode ?: copyTrading.keywordFilterMode,
                keywords = if (request.keywords != null) {
                    convertKeywordsToJson(request.keywords)
                } else if (request.keywordFilterMode != null && request.keywordFilterMode == "DISABLED") {
                    null
                } else {
                    copyTrading.keywords
                },
                configName = configName,
                pushFailedOrders = request.pushFailedOrders ?: copyTrading.pushFailedOrders,
                pushFilteredOrders = request.pushFilteredOrders ?: copyTrading.pushFilteredOrders,
                // 处理 maxMarketEndDate：-1 表示要清空（设置为 null），null 表示不更新
                maxMarketEndDate = if (request.maxMarketEndDate != null) {
                    if (request.maxMarketEndDate == -1L) {
                        null
                    } else {
                        request.maxMarketEndDate
                    }
                } else {
                    copyTrading.maxMarketEndDate
                },
                updatedAt = System.currentTimeMillis()
            )

            val updated = if (updatedDraft.managementMode == CopyTradingManagementMode.AUTOPILOT &&
                (copyTrading.managementMode != CopyTradingManagementMode.AUTOPILOT || !copyTrading.enabled) &&
                updatedDraft.enabled
            ) {
                val decision = autopilotService.decide(
                    LeaderAutopilotDecisionRequest(
                        actionType = AutopilotActionType.ENABLE_CONFIG,
                        accountId = updatedDraft.accountId,
                        candidateId = updatedDraft.autopilotCandidateId,
                        leaderId = updatedDraft.leaderId,
                        copyTrading = updatedDraft,
                        requestedAmount = updatedDraft.fixedAmount,
                        inputSnapshot = mapOf(
                            "source" to "copyTrading.update",
                            "copyTradingId" to updatedDraft.id,
                            "copyMode" to updatedDraft.copyMode,
                            "fixedAmount" to updatedDraft.fixedAmount?.toPlainString()
                        )
                    )
                )
                if (!decision.allowed) {
                    return Result.failure(LeaderAutopilotDecisionDeniedException("Autopilot 决策未允许启用真钱跟单配置: ${decision.reasonCode}"))
                }
                updatedDraft.copy(autopilotLastDecisionAt = System.currentTimeMillis())
            } else {
                updatedDraft
            }
            
            val saved = copyTradingRepository.save(updated)
            
            // 更新 Leader 监听和账户监听（增量更新，根据 enabled 状态决定添加或移除）
            kotlinx.coroutines.runBlocking {
                try {
                    monitorService.updateLeaderMonitoring(saved.leaderId)
                    monitorService.updateAccountMonitoring(saved.accountId)
                } catch (e: Exception) {
                    logger.error("更新监听失败", e)
                }
            }
            
            val account = accountRepository.findById(saved.accountId).orElse(null)
            val leader = leaderRepository.findById(saved.leaderId).orElse(null)
            
            if (account == null || leader == null) {
                return Result.failure(IllegalStateException("跟单配置数据不完整"))
            }
            
            Result.success(toDto(saved, account, leader))
        } catch (e: Exception) {
            logger.error("更新跟单配置失败", e)
            Result.failure(e)
        }
    }

    @Transactional
    open fun applyConservativeConfig(request: ApplyConservativeConfigRequest): Result<CopyTradingDto> {
        return try {
            val copyTrading = copyTradingRepository.findById(request.copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单配置不存在"))
            val updated = CopyTradingSafetyConfigService.applyConservativeConfig(copyTrading, request)
            val saved = copyTradingRepository.save(updated)

            kotlinx.coroutines.runBlocking {
                try {
                    monitorService.updateLeaderMonitoring(saved.leaderId)
                    monitorService.updateAccountMonitoring(saved.accountId)
                } catch (e: Exception) {
                    logger.error("更新监听失败", e)
                }
            }

            val account = accountRepository.findById(saved.accountId).orElse(null)
            val leader = leaderRepository.findById(saved.leaderId).orElse(null)
            if (account == null || leader == null) {
                return Result.failure(IllegalStateException("跟单配置数据不完整"))
            }

            Result.success(toDto(saved, account, leader))
        } catch (e: Exception) {
            logger.error("应用保守配置失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新跟单状态（兼容旧接口）
     */
    @Transactional
    open fun updateCopyTradingStatus(request: CopyTradingUpdateStatusRequest): Result<CopyTradingDto> {
        return getSelf().updateCopyTrading(
            CopyTradingUpdateRequest(
                copyTradingId = request.copyTradingId,
                enabled = request.enabled
            )
        )
    }
    
    /**
     * 查询跟单列表
     */
    open fun getCopyTradingList(request: CopyTradingListRequest): Result<CopyTradingListResponse> {
        return try {
            val copyTradings = when {
                request.accountId != null && request.leaderId != null -> {
                    copyTradingRepository.findByAccountIdAndLeaderId(
                        request.accountId,
                        request.leaderId
                    )
                }
                request.accountId != null -> {
                    copyTradingRepository.findByAccountId(request.accountId)
                }
                request.leaderId != null -> {
                    copyTradingRepository.findByLeaderId(request.leaderId)
                }
                request.enabled != null && request.enabled -> {
                    copyTradingRepository.findByEnabledTrue()
                }
                else -> {
                    copyTradingRepository.findAll()
                }
            }
            
            // 过滤启用状态
            val filtered = if (request.enabled != null) {
                copyTradings.filter { it.enabled == request.enabled }
            } else {
                copyTradings
            }
            
            val dtos = filtered.mapNotNull { copyTrading ->
                val account = accountRepository.findById(copyTrading.accountId).orElse(null)
                val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
                
                if (account == null || leader == null) {
                    logger.warn("跟单配置数据不完整: ${copyTrading.id}")
                    null
                } else {
                    toDto(copyTrading, account, leader)
                }
            }
            
            Result.success(
                CopyTradingListResponse(
                    list = dtos,
                    total = dtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询跟单列表失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除跟单
     */
    @Transactional
    open fun deleteCopyTrading(copyTradingId: Long): Result<Unit> {
        return try {
            val copyTrading = copyTradingRepository.findById(copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单配置不存在"))
            
            val leaderId = copyTrading.leaderId
            val accountId = copyTrading.accountId
            copyTradingRepository.delete(copyTrading)
            
            // 更新 Leader 监听和账户监听（检查是否还有其他启用的跟单配置）
            kotlinx.coroutines.runBlocking {
                try {
                    monitorService.removeLeaderMonitoring(leaderId)
                    monitorService.updateAccountMonitoring(accountId)
                } catch (e: Exception) {
                    logger.error("更新监听失败", e)
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除跟单失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询钱包绑定的跟单配置（兼容旧接口）
     */
    open fun getAccountTemplates(accountId: Long): Result<AccountTemplatesResponse> {
        return try {
            // 验证账户是否存在
            accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))
            
            val copyTradings = copyTradingRepository.findByAccountId(accountId)
            
            val dtos = copyTradings.mapNotNull { copyTrading ->
                val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
                
                if (leader == null) {
                    logger.warn("跟单配置数据不完整: ${copyTrading.id}")
                    null
                } else {
                    AccountTemplateDto(
                        templateId = null,  // 已废弃
                        templateName = null,  // 已废弃
                        copyTradingId = copyTrading.id!!,
                        leaderId = leader.id!!,
                        leaderName = leader.leaderName,
                        leaderAddress = leader.leaderAddress,
                        enabled = copyTrading.enabled
                    )
                }
            }
            
            Result.success(
                AccountTemplatesResponse(
                    list = dtos,
                    total = dtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询钱包绑定的跟单配置失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 转换为 DTO
     */
    fun toDto(copyTrading: CopyTrading): CopyTradingDto {
        val account = accountRepository.findById(copyTrading.accountId).orElse(null)
            ?: throw IllegalStateException("账户不存在")
        val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
            ?: throw IllegalStateException("Leader 不存在")
        return toDto(copyTrading, account, leader)
    }

    private fun toDto(
        copyTrading: CopyTrading,
        account: Account,
        leader: Leader
    ): CopyTradingDto {
        return CopyTradingDto(
            id = copyTrading.id!!,
            accountId = account.id!!,
            accountName = account.accountName,
            walletAddress = account.walletAddress,
            leaderId = leader.id!!,
            leaderName = leader.leaderName,
            leaderAddress = leader.leaderAddress,
            enabled = copyTrading.enabled,
            copyMode = copyTrading.copyMode,
            copyRatio = copyTrading.copyRatio.toPlainString(),
            fixedAmount = copyTrading.fixedAmount?.toPlainString(),
            maxOrderSize = copyTrading.maxOrderSize.toPlainString(),
            minOrderSize = copyTrading.minOrderSize.toPlainString(),
            maxDailyLoss = copyTrading.maxDailyLoss.toPlainString(),
            maxDailyOrders = copyTrading.maxDailyOrders,
            priceTolerance = copyTrading.priceTolerance.toPlainString(),
            delaySeconds = copyTrading.delaySeconds,
            pollIntervalSeconds = copyTrading.pollIntervalSeconds,
            useWebSocket = copyTrading.useWebSocket,
            websocketReconnectInterval = copyTrading.websocketReconnectInterval,
            websocketMaxRetries = copyTrading.websocketMaxRetries,
            supportSell = copyTrading.supportSell,
            minOrderDepth = copyTrading.minOrderDepth?.toPlainString(),
            maxSpread = copyTrading.maxSpread?.toPlainString(),
            minPrice = copyTrading.minPrice?.toPlainString(),
            maxPrice = copyTrading.maxPrice?.toPlainString(),
            maxPositionValue = copyTrading.maxPositionValue?.toPlainString(),
            keywordFilterMode = copyTrading.keywordFilterMode,
            keywords = convertJsonToKeywords(copyTrading.keywords),
            configName = copyTrading.configName,
            pushFailedOrders = copyTrading.pushFailedOrders,
            pushFilteredOrders = copyTrading.pushFilteredOrders,
            maxMarketEndDate = copyTrading.maxMarketEndDate,
            managementMode = copyTrading.managementMode.name,
            autopilotPolicyId = copyTrading.autopilotPolicyId,
            autopilotCandidateId = copyTrading.autopilotCandidateId,
            autopilotPausedReason = copyTrading.autopilotPausedReason?.name,
            autopilotLastDecisionAt = copyTrading.autopilotLastDecisionAt,
            createdAt = copyTrading.createdAt,
            updatedAt = copyTrading.updatedAt
        )
    }

    private fun parseManagementMode(value: String?, fallback: CopyTradingManagementMode = CopyTradingManagementMode.MANUAL): CopyTradingManagementMode {
        return value?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { CopyTradingManagementMode.valueOf(it.uppercase()) }.getOrNull()
        } ?: fallback
    }
    
    /**
     * 将关键字列表转换为 JSON 字符串
     */
    private fun convertKeywordsToJson(keywords: List<String>?): String? {
        if (keywords == null || keywords.isEmpty()) {
            return null
        }
        return try {
            gson.toJson(keywords)
        } catch (e: Exception) {
            logger.error("转换关键字列表为 JSON 失败", e)
            null
        }
    }
    
    /**
     * 将 JSON 字符串转换为关键字列表
     */
    private fun convertJsonToKeywords(jsonString: String?): List<String>? {
        if (jsonString.isNullOrBlank()) {
            return null
        }
        return try {
            jsonUtils.parseStringArray(jsonString)
        } catch (e: Exception) {
            logger.error("解析关键字 JSON 失败", e)
            null
        }
    }
    
    /**
     * 内部配置类（用于构建 CopyTrading 实体）
     */
    private data class CopyTradingConfig(
        val copyMode: String,
        val copyRatio: BigDecimal,
        val fixedAmount: BigDecimal?,
        val maxOrderSize: BigDecimal,
        val minOrderSize: BigDecimal,
        val maxDailyLoss: BigDecimal,
        val maxDailyOrders: Int,
        val priceTolerance: BigDecimal,
        val delaySeconds: Int,
        val pollIntervalSeconds: Int,
        val useWebSocket: Boolean,
        val websocketReconnectInterval: Int,
        val websocketMaxRetries: Int,
        val supportSell: Boolean,
        val minOrderDepth: BigDecimal?,
        val maxSpread: BigDecimal?,
        val minPrice: BigDecimal?,
        val maxPrice: BigDecimal?,
        val maxPositionValue: BigDecimal?,
        val keywordFilterMode: String,
        val keywords: String?,  // JSON 字符串
        val maxMarketEndDate: Long?,  // 市场截止时间限制（毫秒时间戳）
        val pushFilteredOrders: Boolean  // 推送已过滤订单（默认关闭）
    )
}
