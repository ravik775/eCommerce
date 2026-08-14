package org.bgm.userservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bgm.userservice.dto.CreateUserRequest;
import org.bgm.userservice.dto.UpdateUserRequest;
import org.bgm.userservice.dto.UserResponse;
import org.bgm.userservice.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable("id") long id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(userService.update(id, request));
    }
}
