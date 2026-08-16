package org.bgm.userservice.service;

import org.bgm.userservice.model.Role;
import org.bgm.userservice.model.User;
import org.bgm.userservice.model.UserStatus;
import org.bgm.userservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0050: found live — a Google-JIT user (ravik775@gmail.com) granted
 * CAN_TRACE in Keycloak well after their first-ever login kept seeing
 * only their original CUSTOMER role from GET /user/me indefinitely,
 * confirmed via the gateway's own LOGIN audit line showing ROLE_CAN_TRACE
 * correctly present in the JWT while getOrCreateByKeycloakSubject still
 * returned the stale DB row — roles were only ever written on the create
 * branch, never re-synced for a returning user. These tests lock down
 * that every lookup branch now syncs the DB row to the roles read fresh
 * from the token on that call.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository);
    }

    @Test
    void existingUserFoundBySubjectGetsRolesSyncedFromToken() {
        User existing = existingUser(Set.of(Role.CUSTOMER));
        when(userRepository.findByKeycloakSubjectId("subject-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.getOrCreateByKeycloakSubject(
                "subject-1", "ravik775@gmail.com", "Ravi Kiran", Set.of(Role.CUSTOMER, Role.CAN_TRACE));

        assertThat(result.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.CAN_TRACE);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.CAN_TRACE);
    }

    @Test
    void existingUserFoundByEmailFallbackAlsoGetsRolesSynced() {
        User existing = existingUser(Set.of(Role.CUSTOMER));
        when(userRepository.findByKeycloakSubjectId("subject-1")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ravik775@gmail.com")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.getOrCreateByKeycloakSubject(
                "subject-1", "ravik775@gmail.com", "Ravi Kiran", Set.of(Role.CUSTOMER, Role.CAN_TRACE));

        assertThat(result.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.CAN_TRACE);
    }

    @Test
    void newUserCreatedWithRolesFromToken() {
        when(userRepository.findByKeycloakSubjectId("subject-1")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.getOrCreateByKeycloakSubject(
                "subject-1", "new@example.com", "New User", Set.of(Role.CUSTOMER));

        assertThat(result.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(result.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void newUserWithNoRolesDefaultsToCustomer() {
        when(userRepository.findByKeycloakSubjectId("subject-1")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.getOrCreateByKeycloakSubject("subject-1", "new@example.com", "New User", Set.of());

        assertThat(result.getRoles()).containsExactly(Role.CUSTOMER);
    }

    @Test
    void roleRevokedInKeycloakIsRemovedOnNextSync() {
        User existing = existingUser(Set.of(Role.CUSTOMER, Role.CAN_TRACE));
        when(userRepository.findByKeycloakSubjectId("subject-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.getOrCreateByKeycloakSubject(
                "subject-1", "ravik775@gmail.com", "Ravi Kiran", Set.of(Role.CUSTOMER));

        assertThat(result.getRoles()).containsExactly(Role.CUSTOMER);
    }

    private User existingUser(Set<Role> roles) {
        User user = new User();
        user.setId(4L);
        user.setKeycloakSubjectId("subject-1");
        user.setEmail("ravik775@gmail.com");
        user.setName("Ravi Kiran");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(new java.util.HashSet<>(roles));
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return user;
    }
}
