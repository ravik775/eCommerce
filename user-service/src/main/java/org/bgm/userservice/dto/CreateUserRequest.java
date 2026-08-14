package org.bgm.userservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.bgm.userservice.model.Role;

import java.util.Set;

public record CreateUserRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        Set<Role> roles
) {
}
