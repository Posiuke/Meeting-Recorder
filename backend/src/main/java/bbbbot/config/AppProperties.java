package bbbbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bbbbot")
public class AppProperties {

    private Storage storage = new Storage();
    private Bots bots = new Bots();
    private Media media = new Media();
    private Auth auth = new Auth();
    private Bbb bbb = new Bbb();
    private Activity activity = new Activity();

    /** Leerlauf-Meldung fuer Scale-to-zero (ACTIVITY_TOKEN). Leer = Endpunkt aus. */
    public static class Activity {
        private String token = "";
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public Activity getActivity() { return activity; }
    public void setActivity(Activity activity) { this.activity = activity; }

    /**
     * BigBlueButton-Anbindung (Bots, Bot-Vorlagen, Zeitplaene, Stopp-Link).
     * Standard: aus - der Recorder ist dann ein reines Upload-/Bildschirm-
     * aufnahme-Werkzeug. Einschalten per Umgebungsvariable BBB_ON=true.
     */
    public static class Bbb {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Storage {
        private String rootDir = "./data/recordings";
        /**
         * "local" (Standard): Dateien liegen nur unter rootDir. "s3": rootDir ist
         * nur noch Arbeitsverzeichnis/Zwischenspeicher, massgeblich ist der Bucket.
         */
        private String backend = "local";
        /** Wie lange eine nach S3 gesicherte Datei unbenutzt lokal liegen bleibt. */
        private int cacheHours = 24;
        private S3 s3 = new S3();

        public String getRootDir() { return rootDir; }
        public void setRootDir(String rootDir) { this.rootDir = rootDir; }
        public String getBackend() { return backend; }
        public void setBackend(String backend) { this.backend = backend; }
        public int getCacheHours() { return cacheHours; }
        public void setCacheHours(int cacheHours) { this.cacheHours = cacheHours; }
        public S3 getS3() { return s3; }
        public void setS3(S3 s3) { this.s3 = s3; }
    }

    /**
     * S3 oder S3-kompatibler Speicher (z.B. MinIO). Ohne Zugangsschluessel gilt
     * die Standard-Kette des AWS-SDK (Task-Rolle, Instanzprofil, Umgebung).
     */
    public static class S3 {
        private String bucket = "";
        private String prefix = "";
        private String region = "";
        /** Leer = AWS. Fuer MinIO o.ae. die Adresse, z.B. http://minio:9000 */
        private String endpoint = "";
        private boolean pathStyle = false;
        private String accessKey = "";
        private String secretKey = "";

        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public boolean isPathStyle() { return pathStyle; }
        public void setPathStyle(boolean pathStyle) { this.pathStyle = pathStyle; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    }

    public static class Bots {
        private int maxConcurrent = 5;
        private String chromePath = "";
        private boolean headless = true;
        private boolean insecureTls = true;
        private long joinTimeoutMs = 120_000;
        private long audioReadyTimeoutMs = 90_000;

        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public String getChromePath() { return chromePath; }
        public void setChromePath(String chromePath) { this.chromePath = chromePath; }
        public boolean isHeadless() { return headless; }
        public void setHeadless(boolean headless) { this.headless = headless; }
        public boolean isInsecureTls() { return insecureTls; }
        public void setInsecureTls(boolean insecureTls) { this.insecureTls = insecureTls; }
        public long getJoinTimeoutMs() { return joinTimeoutMs; }
        public void setJoinTimeoutMs(long joinTimeoutMs) { this.joinTimeoutMs = joinTimeoutMs; }
        public long getAudioReadyTimeoutMs() { return audioReadyTimeoutMs; }
        public void setAudioReadyTimeoutMs(long audioReadyTimeoutMs) { this.audioReadyTimeoutMs = audioReadyTimeoutMs; }
    }

    public static class Media {
        private String ffmpegPath = "ffmpeg";
        private String ffprobePath = "ffprobe";
        public String getFfmpegPath() { return ffmpegPath; }
        public void setFfmpegPath(String ffmpegPath) { this.ffmpegPath = ffmpegPath; }
        public String getFfprobePath() { return ffprobePath; }
        public void setFfprobePath(String ffprobePath) { this.ffprobePath = ffprobePath; }
    }

    public static class Auth {
        // JWT-Signatur (Boot-Secret) - bleibt in der Env.
        private String jwtSecret = "";
        // Oeffentliche Adresse (z.B. https://rec.example.com) fuer OIDC-Redirect-URIs.
        // Leer = aus der Anfrage (inkl. X-Forwarded-* des Proxys).
        private String publicUrl = "";
        public String getPublicUrl() { return publicUrl; }
        public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
        // Login-Session-Dauer (Standard 168 h = 7 Tage)
        private int jwtTtlHours = 168;
        // Lokales Admin-Konto (immer aktiv, unabhaengig von LDAP). Nur beim
        // allerersten Start wirksam: legt den Admin mit Initialpasswort an.
        // LDAP wird komplett im Admin-Bereich (DB) konfiguriert.
        private String adminUsername = "admin";
        private String adminInitialPassword = "admin";

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public int getJwtTtlHours() { return jwtTtlHours; }
        public void setJwtTtlHours(int jwtTtlHours) { this.jwtTtlHours = jwtTtlHours; }
        public String getAdminUsername() { return adminUsername; }
        public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
        public String getAdminInitialPassword() { return adminInitialPassword; }
        public void setAdminInitialPassword(String adminInitialPassword) { this.adminInitialPassword = adminInitialPassword; }
    }

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }
    public Bots getBots() { return bots; }
    public void setBots(Bots bots) { this.bots = bots; }
    public Media getMedia() { return media; }
    public void setMedia(Media media) { this.media = media; }
    public Auth getAuth() { return auth; }
    public Bbb getBbb() { return bbb; }
    public void setBbb(Bbb bbb) { this.bbb = bbb; }
    public void setAuth(Auth auth) { this.auth = auth; }
}
