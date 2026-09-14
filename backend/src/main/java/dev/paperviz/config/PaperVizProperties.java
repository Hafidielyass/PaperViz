package dev.paperviz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * All PaperViz-specific configuration, bound from the {@code paperviz.*} prefix.
 */
@ConfigurationProperties(prefix = "paperviz")
public class PaperVizProperties {

    /** Filesystem root for uploaded PDFs and rendered media. Never served directly. */
    private String mediaRoot = "./media";

    /** v1 is single-user: every paper is owned by this seeded user. */
    private UUID defaultOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final Service grobid = new Service("http://localhost:8070");
    private final Service render = new Service("http://localhost:8000");
    private final Unpaywall unpaywall = new Unpaywall();

    public String getMediaRoot() {
        return mediaRoot;
    }

    public void setMediaRoot(String mediaRoot) {
        this.mediaRoot = mediaRoot;
    }

    public UUID getDefaultOwnerId() {
        return defaultOwnerId;
    }

    public void setDefaultOwnerId(UUID defaultOwnerId) {
        this.defaultOwnerId = defaultOwnerId;
    }

    public Service getGrobid() {
        return grobid;
    }

    public Service getRender() {
        return render;
    }

    public Unpaywall getUnpaywall() {
        return unpaywall;
    }

    /**
     * Unpaywall integration. The email is required by Unpaywall's terms, is what
     * gets us into their polite pool, and doubles as the contact address in the
     * outbound User-Agent. Uploads never touch any of this.
     */
    public static class Unpaywall {
        private String baseUrl = "https://api.unpaywall.org";
        private String email = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    public static class Service {
        private String baseUrl;

        Service(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
