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

    @Transactional
    public User update(long id, UpdateUserRequest request) {
        User user = get(id);
        user.setName(request.name());
        user.setEmail(request.email());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }
}
