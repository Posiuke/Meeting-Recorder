package bbbbot.api;

import bbbbot.auth.AuthService;
import bbbbot.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Dtos.LoginResponse login(@RequestBody Dtos.LoginRequest request) {
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Benutzername und Passwort erforderlich");
        }
        try {
            AuthService.LoginResult result = authService.login(request.username().trim(), request.password());
            return new Dtos.LoginResponse(result.token(), Dtos.UserView.of(result.user()));
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Anmeldung fehlgeschlagen");
        }
    }

    @GetMapping("/me")
    public Dtos.UserView me() {
        return Dtos.UserView.of(CurrentUser.get());
    }

    @PostMapping("/change-password")
    public Dtos.UserView changePassword(@RequestBody Dtos.ChangePasswordRequest request) {
        try {
            return Dtos.UserView.of(
                    authService.changePassword(CurrentUser.get(), request.currentPassword(), request.newPassword()));
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Aktuelles Passwort ist falsch");
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
