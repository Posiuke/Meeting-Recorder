package bbbbot.auth;

import bbbbot.domain.AppUser;
import bbbbot.repository.Repositories.AppUserRepo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppUserRepo userRepo;
    private final UserActivityService activity;

    public JwtAuthFilter(JwtService jwtService, AppUserRepo userRepo, UserActivityService activity) {
        this.jwtService = jwtService;
        this.userRepo = userRepo;
        this.activity = activity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else {
            // Fallback fuer Audio-Streaming im <audio>-Tag (kein Header moeglich)
            token = request.getParameter("token");
        }
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            String username = jwtService.validate(token);
            if (username != null) {
                AppUser user = userRepo.findByUsernameIgnoreCase(username).orElse(null);
                if (user != null) {
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                    if (user.isAdmin()) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    }
                    var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    // Nur die Token-Anmeldung zaehlt als "im Frontend aktiv" -
                    // API-Schluessel sind Maschinenzugriffe.
                    activity.touch(user);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
