package com.ossagent.support.github;

import com.ossagent.support.secret.TokenRedactor;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub 연동 설정.
 *
 * <p>인증은 <b>classic PAT</b>({@code public_repo}) 다 — Q-1 확정(2026-09-21).
 * fine-grained PAT 과 GitHub App 설치 토큰으로 바꾸지 않는다. 더 안전해 보이지만 우리가 멤버가 아닌
 * upstream 에 PR 을 만들지 못해 PR 생성이 403 으로 죽는다.
 *
 * <p>🔴 {@link #toString()} 을 재정의했다. record 의 기본 {@code toString} 은 모든 구성요소를
 * 찍으므로 설정 객체 하나를 로그에 남기는 순간 토큰이 통째로 나간다 — S-4.
 *
 * @param baseUrl            API 기준 URL. 환경변수로 노출하지 않는다 — 테스트는 프로퍼티로 덮는다
 * @param token              classic PAT. 비어 있으면 미인증 호출(60 req/h)이 된다
 * @param connectTimeout     연결 타임아웃
 * @param readTimeout        읽기 타임아웃
 * @param maxRetries         <b>전송 계층</b> 재시도 상한. 파이프라인 재시도와 다른 축이다(아래 참조)
 * @param retryBackoff       재시도 간 기본 대기. 시도마다 배수로 늘어난다
 * @param rateLimitThreshold 남은 호출이 이 값 미만이면 경고 로그를 남긴다
 */
@ConfigurationProperties("github")
public record GitHubProperties(
        String baseUrl,
        String token,
        Duration connectTimeout,
        Duration readTimeout,
        int maxRetries,
        Duration retryBackoff,
        int rateLimitThreshold) {

    private static final String DEFAULT_BASE_URL = "https://api.github.com";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(500);
    private static final int DEFAULT_RATE_LIMIT_THRESHOLD = 100;

    public GitHubProperties {
        baseUrl = blankToDefault(baseUrl, DEFAULT_BASE_URL);
        token = token == null ? "" : token.trim();
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        readTimeout = readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout;
        retryBackoff = retryBackoff == null ? DEFAULT_RETRY_BACKOFF : retryBackoff;
        if (maxRetries < 0) {
            throw new IllegalArgumentException("github.max-retries 는 음수일 수 없습니다: " + maxRetries);
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("github.connect-timeout 은 0 보다 커야 합니다");
        }
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("github.read-timeout 은 0 보다 커야 합니다");
        }
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("github.retry-backoff 는 음수일 수 없습니다");
        }
    }

    /** 기본값으로만 채운 설정. 테스트와 기본 조립에서 쓴다. */
    public static GitHubProperties defaults() {
        return new GitHubProperties(null, null, null, null, DEFAULT_MAX_RETRIES, null,
                DEFAULT_RATE_LIMIT_THRESHOLD);
    }

    public boolean hasToken() {
        return !token.isBlank();
    }

    /**
     * 🔴 토큰을 찍지 않는다. record 기본 구현을 그대로 두면 설정 로깅 한 줄로 토큰이 유출된다.
     * 토큰은 존재 여부만 남긴다 — {@code .claude/rules/conventions/logging.md}.
     */
    @Override
    public String toString() {
        return "GitHubProperties[baseUrl=%s, token=%s, connectTimeout=%s, readTimeout=%s, maxRetries=%d, retryBackoff=%s, rateLimitThreshold=%d]"
                .formatted(baseUrl, TokenRedactor.mask(token), connectTimeout, readTimeout, maxRetries,
                        retryBackoff, rateLimitThreshold);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
