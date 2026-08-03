package bbbbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bbbbot")
public class AppProperties {

    private Storage storage = new Storage();
    private Bots bots = new Bots();
    private Media media = new Media();
    private Auth auth = new Auth();

    public static class Storage {
        private String rootDir = "./data/recordings";
        public String getRootDir() { return rootDir; }
        public void setRootDir(String rootDir) { this.rootDir = rootDir; }
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
    public void setAuth(Auth auth) { this.auth = auth; }
}
