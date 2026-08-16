package org.bgm.userservice.service;

import lombok.RequiredArgsConstructor;
import org.bgm.userservice.dto.CreateUserRequest;
import org.bgm.userservice.dto.UpdateUserRequest;
import org.bgm.userservice.exception.DuplicateEmailException;
import org.bgm.userservice.exception.UserNotFoundException;
import org.bgm.userservice.model.Role;
import org.bgm.userservice.model.User;
import org.bgm.userservice.model.UserStatus;
import org.bgm.userservice.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User create(CreateUserRequest request) {
        userRepository.findByEmail(request.email()).ifPresent(u -> {
            throw new DuplicateEmailException();
        });

        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setStatus(UserStatus.ACTIVE);
        Set<Role> roles = request.roles() == null || request.roles().isEmpty()
                ? Set.of(Role.CUSTOMER)
                : new HashSet<>(request.roles());
        user.setRoles(roles);
        Instant now = Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User get(long id) {
        return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    /**
     * Just-in-time provisioning for the Phase 8 UI's /users/me: the local
     * `users` row (which order-service's numeric customerId ultimately
     * points at) doesn't exist yet for a user whose only prior identity
     * is a Keycloak login (local password OR Google broker) — this
     * resolves it by keycloak_subject_id, creating it on first call
     * rather than requiring a separate registration step no route in
     * this UI ever prompts for.
     * <p>
     * Falls back to a lookup by email before creating a new row: this
     * Keycloak deployment's tokens don't carry a "sub" claim (see
     * doc/architecture/14-roles-and-permissions.md), so the identity key
     * passed here changed from the JWT subject to preferred_username
     * partway through this project — rows created under the old scheme
     * have keycloak_subject_id values that will never match a real
     * caller's preferred_username again. Without this fallback, every
     * one of those legacy rows becomes a landmine: the email-uniqueness
     * constraint rejects the "new" row this method would otherwise try
     * to create for the same real person, turning every subsequent
     * login into a 500 (found live, exactly this way). Migrating the
     * legacy row's key forward on first sight is simpler than a manual
     * data backfill and self-heals every account on its next login.
     */
    // ADR-0050: previously only set `roles` in the create branch below —
    // an existing user's roles were a snapshot frozen at whatever they
    // were the first time this method ever ran for them, never synced
    // again. Found live: a Google-JIT user granted CAN_TRACE in Keycloak
    // well after their first login kept seeing only their original
    // CUSTOMER role from /user/me indefinitely, on every subsequent call,
    // regardless of how many times they re-authenticated — confirmed via
    // the gateway's own LOGIN audit line showing ROLE_CAN_TRACE correctly
    // present in the JWT while this method still returned the stale DB
    // row. Keycloak is this system's source of truth for role
    // assignment (this method's own `roles` parameter is read fresh from
    // the JWT on every call already — see UserController#me) — every
    // branch now writes it, not just the create path.
    @Transactional
    public User getOrCreateByKeycloakSubject(String keycloakSubjectId, String email, String name, Set<Role> roles) {
        Set<Role> effectiveRoles = roles == null || roles.isEmpty() ? Set.of(Role.CUSTOMER) : roles;
        return userRepository.findByKeycloakSubjectId(keycloakSubjectId)
                .map(existing -> syncRoles(existing, effectiveRoles))
                .or(() -> userRepository.findByEmail(email).map(existing -> {
                    existing.setKeycloakSubjectId(keycloakSubjectId);
                    existing.setName(name);
                    return syncRoles(existing, effectiveRoles);
                }))
                .orElseGet(() -> {
                    User user = new User();
                    user.setKeycloakSubjectId(keycloakSubjectId);
                    user.setName(name);
                    user.setEmail(email);
                    user.setStatus(UserStatus.ACTIVE);
                    user.setRoles(effectiveRoles);
                    Instant now = Instant.now();
                    user.setCreatedAt(now);
                    user.setUpdatedAt(now);
                    return userRepository.save(user);
                });
    }

    private User syncRoles(User user, Set<Role> currentRoles) {
        if (!user.getRoles().equals(currentRoles)) {
            user.setRoles(currentRoles);
        }
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    @Transactional
    public User update(long id, UpdateUserRequest request) {
        User user = get(id);
        user.setName(request.name());
        user.setEmail(request.email());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }
}
