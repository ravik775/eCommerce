package org.bgm.userservice.dto;

import org.bgm.userservice.model.Role;
import org.bgm.userservice.model.User;
import org.bgm.userservice.model.UserStatus;

import java.util.Set;

public record UserResponse(
        long id,
        String name,
        String email,
        UserStatus status,
        Set<Role> roles
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getStatus(), user.getRoles());
    }
}
