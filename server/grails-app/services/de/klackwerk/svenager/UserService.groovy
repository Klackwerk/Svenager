package de.klackwerk.svenager

import grails.gorm.transactions.Transactional
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * Admin-facing user management. Users are never deleted — they are
 * disabled, so their name stays attached to tokens/jobs they created.
 */
@Transactional
class UserService {

    PasswordEncoder passwordEncoder

    List<User> list() {
        User.list(sort: 'username')
    }

    User create(String username, String password, UserRole role) {
        if (!username?.trim()) {
            throw new IllegalArgumentException('username is required')
        }
        if (User.findByUsername(username.trim())) {
            throw new IllegalArgumentException('username is already taken')
        }
        validatePassword(password)
        new User(username: username.trim(), passwordHash: passwordEncoder.encode(password),
                role: role ?: UserRole.VIEWER).save(failOnError: true)
    }

    User update(String uuid, UserRole role, Boolean enabled, String password, String actingUsername) {
        User user = User.findByUuid(uuid)
        if (!user) {
            throw new IllegalArgumentException('user not found')
        }
        boolean self = user.username == actingUsername
        if (self && ((role != null && role != user.role) || (enabled != null && enabled != user.enabled))) {
            throw new IllegalArgumentException('you cannot change your own role or disable yourself')
        }
        if (user.source == 'OIDC' && role != null && role != user.role) {
            throw new IllegalArgumentException('the role of SSO-managed users comes from the identity provider')
        }
        if (user.source == 'OIDC' && password != null) {
            throw new IllegalArgumentException('SSO-managed users have no local password')
        }
        UserRole newRole = role != null ? role : user.role
        boolean newEnabled = enabled != null ? enabled : user.enabled
        boolean losesAdmin = user.role == UserRole.ADMIN && user.enabled &&
                !(newRole == UserRole.ADMIN && newEnabled)
        if (losesAdmin && !User.countByRoleAndEnabledAndIdNotEqual(UserRole.ADMIN, true, user.id)) {
            throw new IllegalArgumentException('at least one enabled admin must remain')
        }
        user.role = newRole
        user.enabled = newEnabled
        if (password != null) {
            validatePassword(password)
            user.passwordHash = passwordEncoder.encode(password)
        }
        user.save(failOnError: true)
    }

    private static void validatePassword(String password) {
        if (!password || password.length() < 8) {
            throw new IllegalArgumentException('password must be at least 8 characters')
        }
    }
}
