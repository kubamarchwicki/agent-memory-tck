package org.neo4j.agentmemory;

import java.net.URI;
import java.net.http.HttpClient;
import org.neo4j.agentmemory.internal.http.HttpTransport;
import org.neo4j.agentmemory.internal.http.JdkHttpTransport;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.neo4j.agentmemory.internal.http.RestClientHttpTransport;
import org.springframework.web.client.RestClient;

/**
 * Immutable settings for constructing a hosted memory client.
 * Build with {@link #builder()}; environment settings are resolved and validated
 * by {@link Builder#build()}. Authentication requires a Workspace API key;
 * Admin keys with an explicit workspace ID are not yet supported.
 */
public final class MemoryClientConfiguration {
    private static final String DEFAULT_BASE_URL = "https://memory.neo4jlabs.com/v1";
    private final URI baseUrl;
    private final String apiKey;
    private final HttpTransport httpTransport;

    private MemoryClientConfiguration(URI baseUrl, String apiKey, HttpTransport httpTransport) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpTransport = httpTransport;
    }

    /** Returns the service base URL, including its version path. */
    public URI baseUrl() {
        return baseUrl;
    }

    /** Returns the Workspace API key used for hosted operations. */
    public String apiKey() {
        return apiKey;
    }

    HttpTransport httpTransport() {
        return httpTransport;
    }

    /** Returns a new builder with no explicit overrides. */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for client settings. */
    public static final class Builder {
        private String baseUrl;
        private String apiKey;
        private HttpTransport httpTransport;

        private Builder() {}

        /**
         * Overrides NAMS_BASE_URL with a service URL including its version path.
         * Leading and trailing whitespace is trimmed when building; a trailing
         * slash is supported. Null is rejected immediately; blank values fail in build().
         *
         * @param baseUrl an absolute HTTP(S) URL with a host and no user info,
         *        query, or fragment
         * @return this builder
         * @throws NullPointerException if baseUrl is null
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /**
         * Overrides NAMS_API_KEY with a Workspace API key.
         * Null is rejected immediately; blank values fail in build().
         *
         * @param apiKey the nonblank Workspace API key
         * @return this builder
         * @throws NullPointerException if apiKey is null
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            return this;
        }

        /**
         * Uses the supplied HTTP client as configured, including its executor,
         * proxy, timeouts, and TLS settings. The client is never closed.
         * Replaces any HTTP client set earlier.
         *
         * @param httpClient the application HTTP client
         * @return this builder
         * @throws NullPointerException if httpClient is null
         */
        public Builder jdkHttpClient(HttpClient httpClient) {
            this.httpTransport = new JdkHttpTransport(Objects.requireNonNull(httpClient, "httpClient"));
            return this;
        }

        /**
         * Uses an application-built Spring client. Its interceptors, observation
         * registry, request-factory timeouts, and TLS settings apply. Its base URL
         * and status handlers do not apply to memory requests. Explicit memory
         * headers override corresponding defaults; unrelated default headers remain.
         * Blocking exchanges run on executor; neither the client nor the executor
         * is closed. Requires Spring Framework 7.0 or later.
         * Replaces any HTTP client set earlier.
         *
         * @param restClient the application Spring client
         * @param executor the application executor for blocking exchanges
         * @return this builder
         * @throws NullPointerException if restClient or executor is null
         */
        public Builder restClient(RestClient restClient, Executor executor) {
            this.httpTransport = new RestClientHttpTransport(
                    Objects.requireNonNull(restClient, "restClient"),
                    Objects.requireNonNull(executor, "executor"));
            return this;
        }

        /**
         * Resolves omitted settings from NAMS_API_KEY and NAMS_BASE_URL and
         * validates them without creating a client or making a remote request.
         * An absent or blank environment base URL defaults to
         * https://memory.neo4jlabs.com/v1 and emits a WARNING through System.Logger.
         * The key is required.
         *
         * @return a new immutable configuration, independent of later builder changes
         * @throws IllegalArgumentException if the key is null or blank, or the
         *         base URL is not an absolute HTTP(S) URL with a host and no
         *         user info, query, or fragment
         */
        public MemoryClientConfiguration build() {
            var resolvedKey = apiKey != null ? apiKey : System.getenv("NAMS_API_KEY");
            var resolvedUrl = baseUrl != null ? baseUrl : System.getenv("NAMS_BASE_URL");
            if (resolvedKey == null || resolvedKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must not be blank");
            }
            if (baseUrl == null && (resolvedUrl == null || resolvedUrl.isBlank())) {
                resolvedUrl = DEFAULT_BASE_URL;
                logDefaultBaseUrl();
            }
            URI url;
            try {
                url = URI.create(resolvedUrl.trim());
            } catch (IllegalArgumentException invalidUrl) {
                throw invalidBaseUrl();
            }
            if (url.getHost() == null
                    || !("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                    || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
                throw invalidBaseUrl();
            }
            return new MemoryClientConfiguration(url, resolvedKey, httpTransport);
        }

        private static IllegalArgumentException invalidBaseUrl() {
            return new IllegalArgumentException(
                    "baseUrl must be an absolute HTTP(S) URL without user info, query, or fragment");
        }

        private static void logDefaultBaseUrl() {
            try {
                System.getLogger(MemoryClientConfiguration.class.getName()).log(System.Logger.Level.WARNING,
                        "NAMS_BASE_URL is absent or blank; using default base URL " + DEFAULT_BASE_URL);
            } catch (RuntimeException ignored) {
                // Logging has no effect on configuration validation.
            }
        }
    }
}
