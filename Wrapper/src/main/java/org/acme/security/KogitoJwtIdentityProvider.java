package org.acme.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.acme.security.jwt.UserContextHolder;
import org.acme.security.jwt.dto.UserDetailsJwt;
import org.acme.security.jwt.dto.UserGroupDTO;
import org.kie.kogito.auth.IdentityProvider;
import org.kie.kogito.services.identity.NoOpIdentityProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Supplies the Kogito identity from the JWT placed in {@link UserContextHolder}
 * by the {@code JwtFilter}. This is what the engine uses to populate
 * CREATED_BY / UPDATED_BY and to evaluate task assignment.
 *
 * <p>The JWT logic applies <b>only while a wrapper-authenticated request is being
 * processed</b> (a JWT context is present on the thread). Outside of that — engine
 * calls without a token, the management console, background threads like the Jobs
 * Service — every method delegates to the default identity resolution: the first
 * other {@code IdentityProvider} bean in the context (e.g. the starter's Spring
 * Security provider when security is enabled), or Kogito's {@code NoOpIdentityProvider}
 * when none exists.</p>
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

    private final ApplicationContext applicationContext;
    private volatile IdentityProvider fallback;

    public KogitoJwtIdentityProvider(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public String getName() {
        UserDetailsJwt context = UserContextHolder.getContext();
        if (context == null) {
            return fallback().getName();
        }
        return context.getUsername();
    }

    @Override
    public Collection<String> getRoles() {
        UserDetailsJwt context = UserContextHolder.getContext();
        if (context == null) {
            return fallback().getRoles();
        }
        List<String> roles = new ArrayList<>();
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
        return roles;
    }

    @Override
    public boolean hasRole(String role) {
        UserDetailsJwt context = UserContextHolder.getContext();
        if (context == null) {
            return fallback().hasRole(role);
        }
        return getRoles().contains(role);
    }

    /**
     * The "super" behavior when no JWT context is present: the first other
     * IdentityProvider bean (resolved lazily to avoid a circular dependency),
     * or the engine's NoOp default.
     */
    private IdentityProvider fallback() {
        IdentityProvider resolved = fallback;
        if (resolved == null) {
            synchronized (this) {
                if (fallback == null) {
                    fallback = applicationContext.getBeansOfType(IdentityProvider.class).values().stream()
                            .filter(provider -> provider != this)
                            .findFirst()
                            .orElseGet(NoOpIdentityProvider::new);
                }
                resolved = fallback;
            }
        }
        return resolved;
    }
}
