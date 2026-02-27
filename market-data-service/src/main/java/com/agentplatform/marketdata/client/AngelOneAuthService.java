package com.agentplatform.marketdata.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles Angel One SmartAPI JWT authentication.
 *
 * <p>Login flow: clientId + password + TOTP → jwtToken
 * Token is cached in memory and refreshed automatically on expiry or 401.
 *
 * <p>TOTP is generated programmatically from the base32 secret stored in
 * ANGELONE_TOTP_SECRET env var (the same secret you scanned in your
 * authenticator app during SmartAPI setup).
 */
@Service
public class AngelOneAuthService {

    private static final Logger log = LoggerFactory.getLogger(AngelOneAuthService.class);

    private static final String LOGIN_PATH = "/rest/auth/angelbroking/user/v1/loginByPassword";

    private final WebClient authClient;
    private final ObjectMapper objectMapper;

    @Value("${angel-one.api-key:}")
    private String apiKey;

    @Value("${angel-one.client-id:}")
    private String clientId;

    @Value("${angel-one.password:}")
    private String password;

    @Value("${angel-one.totp-secret:}")
    private String totpSecret;

    /** Cached token + expiry */
    private final AtomicReference<String>  cachedToken   = new AtomicReference<>();
    private final AtomicReference<Instant> tokenExpiry   = new AtomicReference<>(Instant.EPOCH);

    public AngelOneAuthService(WebClient angelOneAuthClient, ObjectMapper objectMapper) {
        this.authClient   = angelOneAuthClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a valid JWT token, re-logging in if expired.
     */
    public Mono<String> getToken() {
        if (cachedToken.get() != null && Instant.now().isBefore(tokenExpiry.get())) {
            return Mono.just(cachedToken.get());
        }
        return login();
    }

    /** Forces a fresh login — call this when a 401 is received. */
    public Mono<String> refreshToken() {
        cachedToken.set(null);
        return login();
    }

    // ── private ───────────────────────────────────────────────────────────────

    private Mono<String> login() {
        String totp = generateTotp();
        Map<String, String> body = Map.of(
            "clientcode", clientId,
            "password",   password,
            "totp",       totp
        );

        log.info("[AngelOne] Logging in. clientId={}", clientId);

        return authClient.post()
            .uri(LOGIN_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-PrivateKey", apiKey)
            .header("X-UserType",   "USER")
            .header("X-SourceID",   "WEB")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::extractToken)
            .doOnSuccess(t -> log.info("[AngelOne] Login successful. token={}***", t.substring(0, Math.min(10, t.length()))))
            .doOnError(e -> log.error("[AngelOne] Login failed", e));
    }

    private String extractToken(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.path("status").asBoolean(false)) {
                throw new RuntimeException("Angel One login failed: " + root.path("message").asText());
            }
            String token = root.path("data").path("jwtToken").asText();
            if (token == null || token.isBlank()) {
                throw new RuntimeException("Angel One returned empty jwtToken");
            }
            cachedToken.set(token);
            // Tokens are valid for ~24h; refresh after 23h to be safe
            tokenExpiry.set(Instant.now().plusSeconds(23 * 3600));
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Angel One login response", e);
        }
    }

    private String generateTotp() {
        try {
            DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            SystemTimeProvider timeProvider = new SystemTimeProvider();
            long counter = Math.floorDiv(timeProvider.getTime(), 30);
            return generator.generate(totpSecret, counter);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate TOTP for Angel One", e);
        }
    }
}
