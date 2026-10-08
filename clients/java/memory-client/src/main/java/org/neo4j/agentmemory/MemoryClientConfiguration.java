package org.neo4j.agentmemory;

import java.net.URI;

/**
 * Immutable settings for constructing a hosted memory client.
 * Build with {@link #builder()}; environment settings are resolved and validated
 * by {@link Builder#build()}. Authentication requires a Workspace API key;
 * Admin keys with an explicit workspace ID are not yet supported.
 */
public final class MemoryClientConfiguration {
    private final URI baseUrl;
    private final String apiKey;

    private MemoryClientConfiguration(URI baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** Returns the service base URL, including its version path. */
    public URI baseUrl() {
        return baseUrl;
    }

    /** Returns the Workspace API key used for hosted operations. */
    public String apiKey() {
        return apiKey;
    }

    /** Returns a new builder with no explicit overrides. */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for client settings. */
    public static final class Builder {
        private String baseUrl;
        private String apiKey;
        private boolean baseUrlSet;
        private boolean apiKeySet;

        private Builder() {}

        /**
         * Overrides NAMS_BASE_URL with a service URL including its version path.
         * Leading and trailing whitespace is trimmed when building; a trailing
         * slash is supported. Explicit null or blank values are invalid.
         *
         * @param baseUrl an absolute HTTP(S) URL with a host and no user info,
         *        query, or fragment
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            this.baseUrlSet = true;
            return this;
        }

        /**
         * Overrides NAMS_API_KEY with a Workspace API key.
         * Explicit null or blank values are invalid.
         *
         * @param apiKey the nonblank Workspace API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            this.apiKeySet = true;
            return this;
        }

        /**
         * Resolves omitted settings from NAMS_API_KEY and NAMS_BASE_URL and
         * validates them without creating a client or making a remote request.
         * An absent or blank environment base URL defaults to
         * https://memory.neo4jlabs.com/v1. The key is required.
         *
         * @return a new immutable configuration, independent of later builder changes
         * @throws IllegalArgumentException if the key is null or blank, or the
         *         base URL is not an absolute HTTP(S) URL with a host and no
         *         user info, query, or fragment
         */
        public MemoryClientConfiguration build() {
            var resolvedKey = apiKeySet ? apiKey : System.getenv("NAMS_API_KEY");
            var resolvedUrl = baseUrlSet ? baseUrl : System.getenv("NAMS_BASE_URL");
            if (!baseUrlSet && (resolvedUrl == null || resolvedUrl.isBlank())) {
                resolvedUrl = "https://memory.neo4jlabs.com/v1";
            }
            if (resolvedKey == null || resolvedKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must not be blank");
            }
            URI url;
            try {
                url = resolvedUrl == null ? null : URI.create(resolvedUrl.trim());
            } catch (IllegalArgumentException invalidUrl) {
                throw invalidBaseUrl();
            }
            if (url == null || url.getHost() == null
                    || !("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                    || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
                throw invalidBaseUrl();
            }
            return new MemoryClientConfiguration(url, resolvedKey);
        }

        private static IllegalArgumentException invalidBaseUrl() {
            return new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) URL without user info, query, or fragment");
        }
    }
}
