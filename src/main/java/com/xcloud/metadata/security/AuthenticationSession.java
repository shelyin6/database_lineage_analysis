package com.xcloud.metadata.security;

import jakarta.servlet.http.HttpSession;

public final class AuthenticationSession {
    public static final String USER_ATTRIBUTE = AuthenticationSession.class.getName() + ".user";

    private AuthenticationSession() {
    }

    public static AuthUser current(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(USER_ATTRIBUTE);
        return value instanceof AuthUser user ? user : null;
    }
}
