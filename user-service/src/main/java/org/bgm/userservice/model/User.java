package org.bgm.userservice.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

// "user" is a reserved SQL keyword — table named "users" per Notes.md.
//
// ADR-0001 (doc/adr/ADR-0001-idp-keycloak.md): Keycloak owns credentials.
// This entity stores only profile data linked to the Keycloak subject ID
// once Phase 4 wires OIDC — no password field exists here by design, and
// Notes.md's original POST /users/login endpoint is intentionally NOT
// implemented in this phase, since authentication happens via Keycloak,
// not this service.
@Getter
@Setter
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    // Nullable until Phase 4 links this profile to a real Keycloak subject.
    private String keycloakSubjectId;

    private String name;
    private String email;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    private Instant createdAt;
    private Instant updatedAt;
}
