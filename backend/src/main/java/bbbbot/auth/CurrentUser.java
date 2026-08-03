package bbbbot.auth;

import bbbbot.domain.AppUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Zugriff auf den angemeldeten Nutzer (vom JwtAuthFilter gesetzt). */
public final class CurrentUser {

    private CurrentUser() {}

    public static AppUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUser user) {
            return user;
        }
        throw new IllegalStateException("Kein angemeldeter Nutzer im Kontext");
    }
}
