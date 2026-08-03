package bbbbot.settings;

import bbbbot.domain.AppSetting;
import bbbbot.repository.Repositories.AppSettingRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authentifizierungs-Einstellungen (LDAP/AD an/aus + Verbindungsdaten +
 * Bootstrap-Admins), persistiert in app_setting. Getrennt von {@link SettingsService},
 * damit Auth-Config sauber ueber den Admin-Bereich verwaltet wird und nicht mit
 * den STT/LLM/Bot-Einstellungen vermischt ist.
 */
@Service
public class AuthSettingsService {

    public static final String LDAP_ENABLED = "auth.ldapEnabled";
    public static final String LDAP_DOMAIN = "auth.ldapDomain";
    public static final String LDAP_URL = "auth.ldapUrl";
    public static final String LDAP_ROOT_DN = "auth.ldapRootDn";
    public static final String BOOTSTRAP_ADMINS = "auth.bootstrapAdmins";

    private static final List<String> KEYS =
            List.of(LDAP_ENABLED, LDAP_DOMAIN, LDAP_URL, LDAP_ROOT_DN, BOOTSTRAP_ADMINS);

    private final AppSettingRepo repo;

    public AuthSettingsService(AppSettingRepo repo) {
        this.repo = repo;
    }

    private String raw(String key, String def) {
        return repo.findById(key).map(AppSetting::getValue).filter(v -> v != null).orElse(def);
    }

    public boolean isLdapEnabled() { return Boolean.parseBoolean(raw(LDAP_ENABLED, "false").trim()); }
    public String domain() { return raw(LDAP_DOMAIN, "").trim(); }
    public String url() { return raw(LDAP_URL, "").trim(); }
    public String rootDn() { return raw(LDAP_ROOT_DN, "").trim(); }

    public Set<String> bootstrapAdmins() {
        return Arrays.stream(raw(BOOTSTRAP_ADMINS, "").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    /** Aktuelle Werte (mit Defaults) fuer die Admin-Oberflaeche. */
    @Transactional(readOnly = true)
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(LDAP_ENABLED, String.valueOf(isLdapEnabled()));
        m.put(LDAP_DOMAIN, domain());
        m.put(LDAP_URL, url());
        m.put(LDAP_ROOT_DN, rootDn());
        m.put(BOOTSTRAP_ADMINS, raw(BOOTSTRAP_ADMINS, ""));
        return m;
    }

    @Transactional
    public void update(Map<String, String> changes) {
        for (Map.Entry<String, String> e : changes.entrySet()) {
            if (!KEYS.contains(e.getKey())) {
                throw new IllegalArgumentException("Unbekannter Auth-Schluessel: " + e.getKey());
            }
            String value = e.getValue() == null ? "" : e.getValue();
            if (LDAP_ENABLED.equals(e.getKey())
                    && !value.trim().equalsIgnoreCase("true") && !value.trim().equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("auth.ldapEnabled erwartet true/false");
            }
            save(e.getKey(), value);
        }
    }

    @Transactional
    public void save(String key, String value) {
        AppSetting setting = repo.findById(key).orElse(new AppSetting(key, null));
        setting.setValue(value);
        repo.save(setting);
    }

    /** Setzt einen Wert nur, wenn der Schluessel noch nicht existiert (Erstbefuellung aus Env). */
    @Transactional
    public void seedIfAbsent(String key, String value) {
        if (repo.findById(key).isEmpty()) {
            repo.save(new AppSetting(key, value == null ? "" : value));
        }
    }
}
