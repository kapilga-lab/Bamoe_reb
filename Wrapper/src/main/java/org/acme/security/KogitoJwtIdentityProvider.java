package org.acme.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.acme.security.jwt.UserContextHolder;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.security.jwt.dto.UserGroupDTO;
import org.kie.kogito.auth.IdentityProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Supplies the Kogito identity from the JWT placed in {@link UserContextHolder}
 * by the {@code JwtFilter}. This is what the engine uses to populate
 * CREATED_BY / UPDATED_BY and to evaluate task assignment.
 *
 * <p>Marked {@code @Primary} so it takes precedence over the default
 * {@code SpringIdentityProvider} wired by the jBPM Spring Boot starter.</p>
 *
 * <ul>
 *   <li>{@code getName()}  → the JWT {@code sub} (e.g. {@code sachin})</li>
 *   <li>{@code getRoles()} → the JWT roles plus group names (e.g. {@code MAKER}, {@code rbi})</li>
 * </ul>
 */
@Component
@Primary
@Order(Ordered.HIGHEST_PRECEDENCE) // be first in List<IdentityProvider> so the engine uses us for eventUser (CREATED_BY/UPDATED_BY)
public class KogitoJwtIdentityProvider implements IdentityProvider {

    @Override
    public String getName() {
        UserDetailsJwt context = UserContextHolder.getContext();
        return (context != null) ? context.getUsername() : null;
    }

    @Override
    public Collection<String> getRoles() {
        UserDetailsJwt context = UserContextHolder.getContext();
        List<String> roles = new ArrayList<>();
        if (context != null) {
            if (context.getRoles() != null) {
                roles.addAll(context.getRoles());
            }
            if (context.getUserGroups() != null) {
                for (UserGroupDTO group : context.getUserGroups()) {
                    if (group.getGroupName() != null) {
                        roles.add(group.getGroupName());
                    }
                }
            }
        }
        return roles;
    }

    @Override
    public boolean hasRole(String role) {
        return getRoles().contains(role);
    }
}
