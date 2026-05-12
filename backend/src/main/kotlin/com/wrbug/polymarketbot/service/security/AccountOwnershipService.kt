package com.wrbug.polymarketbot.service.security

import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.User
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.UserRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service

class AccountOwnershipException(message: String) : RuntimeException(message)

@Service
class AccountOwnershipService(
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val userRepository: UserRepository
) {
    fun requireAccountAccess(accountId: Long, request: HttpServletRequest): Account {
        val user = currentUser(request)
        val account = accountRepository.findById(accountId).orElse(null)
            ?: throw IllegalArgumentException("账户不存在")
        if (!owns(account, user)) {
            throw AccountOwnershipException("无权访问该账户")
        }
        return account
    }

    fun requireCopyTradingAccess(copyTradingId: Long, request: HttpServletRequest): CopyTrading {
        val copyTrading = copyTradingRepository.findById(copyTradingId).orElse(null)
            ?: throw IllegalArgumentException("跟单配置不存在")
        requireAccountAccess(copyTrading.accountId, request)
        return copyTrading
    }

    private fun currentUser(request: HttpServletRequest): User {
        val username = request.getAttribute("username") as? String
            ?: throw AccountOwnershipException("未获取到用户信息")
        return userRepository.findByUsername(username)
            ?: throw AccountOwnershipException("用户不存在")
    }

    private fun owns(account: Account, user: User): Boolean {
        return when (val ownerId = account.userId) {
            null -> user.isDefault
            else -> ownerId == user.id
        }
    }
}
