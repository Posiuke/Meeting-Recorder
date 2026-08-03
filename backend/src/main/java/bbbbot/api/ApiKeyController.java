package bbbbot.api;

import bbbbot.auth.ApiKeyService;
import bbbbot.domain.ApiKey;
import bbbbot.domain.AppUser;
import bbbbot.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Verwaltung der eigenen API-Schluessel. Erreichbar nur mit Login (Weboberflaeche) -
 * per API-Schluessel ist dieser Bereich gesperrt, siehe
 * {@link bbbbot.auth.ApiKeyAuthFilter}.
 */
@RestController
@RequestMapping("/api/api-keys")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public List<Dtos.ApiKeyView> list() {
        AppUser user = CurrentUser.get();
        return service.keysOf(user.getId()).stream().map(Dtos.ApiKeyView::of).toList();
    }

    /**
     * Legt einen Schluessel an. Das Token steht NUR in dieser Antwort - danach
     * ist es nicht mehr abrufbar (gespeichert wird nur sein Abdruck).
     */
    @PostMapping
    public Dtos.ApiKeyCreated create(@RequestBody Dtos.ApiKeyRequest request) {
        AppUser user = CurrentUser.get();
        String name = requireName(request.name());
        if (service.countOf(user.getId()) >= ApiKeyService.MAX_KEYS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Maximal " + ApiKeyService.MAX_KEYS_PER_USER + " API-Schluessel pro Nutzer");
        }
        Instant expiresAt = parseExpiry(request.expiresAt());
        ApiKeyService.NewKey created = service.create(user.getId(), name,
                Boolean.TRUE.equals(request.readOnly()), expiresAt);
        return new Dtos.ApiKeyCreated(Dtos.ApiKeyView.of(created.key()), created.token());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        ApiKey key = service.findOwn(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "API-Schluessel nicht gefunden"));
        service.delete(key);
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bitte einen Namen angeben, damit der Schluessel zuordenbar bleibt");
        }
        if (name.length() > ApiKey.MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Name ist zu lang (max. " + ApiKey.MAX_NAME_LENGTH + " Zeichen)");
        }
        return name;
    }

    /** Ablaufdatum als ISO-Zeitpunkt; leer bedeutet unbegrenzt gueltig. */
    private static Instant parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ablaufdatum ist kein gueltiger Zeitpunkt (erwartet z.B. 2026-12-31T23:59:59Z)");
        }
        if (!expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das Ablaufdatum liegt in der Vergangenheit");
        }
        return expiresAt;
    }
}
