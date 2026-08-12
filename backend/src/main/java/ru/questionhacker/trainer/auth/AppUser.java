package ru.questionhacker.trainer.auth;

import java.util.Set;
import java.util.UUID;

public record AppUser(
        UUID id,
        String username,
        String email,
        Set<String> roles,
        boolean systemAccount,
        boolean enabled) {

    public AppUser {
        roles = Set.copyOf(roles);
    }

    public boolean admin() {
        return roles.contains("ADMIN");
    }
}
