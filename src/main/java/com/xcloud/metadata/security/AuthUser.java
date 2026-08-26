package com.xcloud.metadata.security;

import java.io.Serializable;

public record AuthUser(String username, String displayName, String role) implements Serializable {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
