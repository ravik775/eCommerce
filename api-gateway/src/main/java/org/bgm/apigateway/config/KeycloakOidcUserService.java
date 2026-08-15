package org.bgm.apigateway.config;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import org.bgm.common.audit.AuditLogger;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.text.ParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Keycloak's realm_access.roles claim into ROLE_* authorities on the
 * gateway's OidcUser session, so RequireRoleGatewayFilterFactory can make
 * role decisions. Reads the claim from the access token, not the ID token
 * or the userinfo endpoint — live testing showed neither of those carries
 * realm_access with this realm's default "roles" client scope mapper
 * config (only the access token does), even though common-lib's
 * KeycloakRealmRoleConverter (the servlet resource-server side, ADR-0025)
 * reads the same claim successfully because it always operates on the
 * access token.
 */
@Service
public class KeycloakOidcUserService extends OidcReactiveOAuth2UserService {

    @Override
    public Mono<OidcUser> loadUser(OidcUserRequest userRequest) {
        return super.loadUser(userRequest).map(oidcUser -> {
            Set<GrantedAuthority> authorities = new HashSet<>(oidcUser.getAuthorities());
            try {
                JWTClaimsSet claims = JWTParser.parse(userRequest.getAccessToken().getTokenValue()).getJWTClaimsSet();
                Map<String, Object> realmAccess = claims.getJSONObjectClaim("realm_access");
                if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
                    for (Object role : roles) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                    }
                }
            } catch (ParseException e) {
                throw new IllegalStateException("Unable to parse access token for realm_access roles", e);
            }
            // Phase 7 audit trail: the login leg. Joined to the order/
            // payment legs downstream by principal name (this realm's
            // username), the same identifier order-service's customerId
            // is derived from — see AuditLogger's Javadoc for why this,
            // not the HTTP correlation ID, is the actual join key here.
            AuditLogger.log("LOGIN", AuditLogger.fields()
                    .with("principal", oidcUser.getName())
                    .with("authorities", authorities)
                    .build());
            return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
        });
    }
}
