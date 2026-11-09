package bbbbot.activity;

import bbbbot.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Leerlauf-Meldung fuer den Betrieb (z.B. eine Lambda, die den Dienst bei
 * Leerlauf auf null skaliert). Geschuetzt durch ACTIVITY_TOKEN im Header
 * {@code X-Activity-Token}; ohne konfiguriertes Token gibt es den Endpunkt nicht.
 */
@RestController
public class ActivityController {

    public static final String PATH = "/api/internal/activity";

    private final ActivityService activity;
    private final byte[] token;

    public ActivityController(ActivityService activity, AppProperties props) {
        this.activity = activity;
        String configured = props.getActivity().getToken();
        this.token = configured == null || configured.isBlank()
                ? null : configured.trim().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping(PATH)
    public ActivityService.Report report(@RequestHeader(value = "X-Activity-Token", required = false) String given) {
        if (token == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (given == null || !MessageDigest.isEqual(token, given.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token fehlt oder ist falsch");
        }
        return activity.report();
    }
}
