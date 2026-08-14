package org.bgm.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ADR-0025 (doc/adr/ADR-0025-jwt-rbac-method-security.md): Keycloak puts
 * roles under the JWT's {@code realm_access.roles} claim, not where Spring
 * Security looks by default (the {@code scope} claim) — this converter
 * closes that documented integration gap by mapping each realm role to a
 * {@code ROLE_<name>} authority, the form {@code hasRole(...)} expects.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_CLAIM = "roles";

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null || !(realmAccess.get(ROLES_CLAIM) instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(String.class::cast)
                .map(role -> "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableList());
    }
}
