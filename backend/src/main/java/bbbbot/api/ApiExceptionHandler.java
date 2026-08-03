package bbbbot.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Sorgt dafuer, dass API-Fehler (a) im Log auftauchen und (b) mit ihrer
 * deutschen Begruendung als {@code message} beim Frontend ankommen.
 * Ohne diesen Handler unterdrueckt Spring beides: ResponseStatusExceptions
 * werden nicht geloggt und ihre Begruendung nicht in die Antwort uebernommen -
 * das Frontend zeigt dann nur "Internal Server Error".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Value("${spring.servlet.multipart.max-file-size:4GB}")
    private String maxFileSize;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("message", "Datei zu gross - erlaubt sind maximal " + maxFileSize + "."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> statusException(ResponseStatusException e) {
        String message = e.getReason() != null ? e.getReason() : e.getStatusCode().toString();
        if (e.getStatusCode().is5xxServerError()) {
            log.error("API-Fehler {}: {}", e.getStatusCode().value(), message, e);
        }
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", message));
    }
}
