-- Bind wallet accounts to application users so account-scoped Autopilot actions
-- cannot be authorized by a bare accountId alone.

ALTER TABLE wallet_accounts
    ADD COLUMN user_id BIGINT NULL COMMENT 'Owner user ID' AFTER id,
    ADD INDEX idx_wallet_accounts_user_id (user_id);

UPDATE wallet_accounts wa
JOIN (
    SELECT id
    FROM users
    WHERE is_default = TRUE
    ORDER BY id
    LIMIT 1
) u
SET wa.user_id = u.id
WHERE wa.user_id IS NULL;

UPDATE wallet_accounts wa
JOIN (
    SELECT id
    FROM users
    ORDER BY id
    LIMIT 1
) u
SET wa.user_id = u.id
WHERE wa.user_id IS NULL;

ALTER TABLE wallet_accounts
    ADD CONSTRAINT fk_wallet_accounts_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;
