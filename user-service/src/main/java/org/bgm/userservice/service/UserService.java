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
            throw new DuplicateEmailException(request.email());
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
     */
    @Transactional
    public User getOrCreateByKeycloakSubject(String keycloakSubjectId, String email, String name, Set<Role> roles) {
        return userRepository.findByKeycloakSubjectId(keycloakSubjectId).orElseGet(() -> {
            User user = new User();
            user.setKeycloakSubjectId(keycloakSubjectId);
            user.setName(name);
            user.setEmail(email);
            user.setStatus(UserStatus.ACTIVE);
            user.setRoles(roles == null || roles.isEmpty() ? Set.of(Role.CUSTOMER) : roles);
            Instant now = Instant.now();
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            return userRepository.save(user);
        });
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
