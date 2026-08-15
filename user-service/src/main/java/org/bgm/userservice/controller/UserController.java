package org.bgm.userservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bgm.userservice.dto.CreateUserRequest;
import org.bgm.userservice.dto.UpdateUserRequest;
import org.bgm.userservice.dto.UserResponse;
import org.bgm.userservice.model.Role;
import org.bgm.userservice.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Notes.md's original API list included POST /users/register and
// POST /users/login — intentionally NOT implemented here. Per ADR-0001,
// authentication is Keycloak's responsibility (Phase 4); this controller
// only manages the local profile record.
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        var user = userService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable("id") long id) {
        return UserResponse.from(userService.get(id));
    }

    // Phase 8: resolves (and just-in-time-provisions) the local profile
    // row for whoever the JWT belongs to — the UI needs this to learn
    // its own numeric customerId before it can call order-service, which
    // has no notion of "the current user," only an explicit customerId
    // per ADR-0007's saga design.
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        Set<Role> roles = jwt.getClaimAsMap("realm_access") == null
                ? Set.of()
                : ((List<?>) jwt.getClaimAsMap("realm_access").getOrDefault("roles", List.of())).stream()
                        .map(Object::toString)
                        .flatMap(r -> {
                            try {
                                return java.util.stream.Stream.of(Role.valueOf(r));
                            } catch (IllegalArgumentException e) {
                                return java.util.stream.Stream.empty(); // ignores Keycloak-internal roles (offline_access, uma_authorization, etc.)
                            }
                        })
                        .collect(Collectors.toSet());
        // This Keycloak deployment's tokens don't carry a "sub" claim
        // (found live — confirmed on tokens from both the master and
        // ecom realms) — jwt.getSubject() being silently null for every
        // caller meant every /me call resolved to the SAME row (the
        // first-ever user created, since a null lookup key matched a
        // null-valued column). preferred_username is present and unique
        // per user on every token actually decoded.
        var user = userService.getOrCreateByKeycloakSubject(jwt.getClaimAsString("preferred_username"), email, name, roles);
        return UserResponse.from(user);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable("id") long id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(userService.update(id, request));
    }
}
