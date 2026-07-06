package org.acme.security.jwt;

import org.acme.security.jwt.dto.UserDetailsJwt;
import org.springframework.util.Assert;

/**
 * Stores and retrieves the current {@link UserDetailsJwt} using a ThreadLocal,
 * making the authenticated user accessible throughout the request lifecycle.
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserDetailsJwt> userContext = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void setContext(UserDetailsJwt context) {
        Assert.notNull(context, "Only non-null UserContext instances are permitted");
        userContext.set(context);
    }

    public static UserDetailsJwt getContext() {
        return userContext.get();
    }

    public static void clearContext() {
        userContext.remove();
    }
}
