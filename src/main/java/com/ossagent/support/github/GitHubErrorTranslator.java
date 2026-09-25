package com.ossagent.support.github;

import com.ossagent.support.secret.TokenRedactor;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.HttpHeaders;

/**
 * 실패 응답을 타입이 있는 예외로 바꾼다. <b>403 을 구분하는 유일한 지점</b>이다.
 *
 * <p>GitHub 의 403 은 세 가지가 한 코드에 겹쳐 있다.
 *
 * <ol>
 *   <li>진짜 권한 부족 — 토큰 스코프가 모자라거나 접근할 수 없는 저장소
 *   <li>1차 레이트리밋 소진 — 시간당 할당량을 다 썼다
 *   <li>2차 레이트리밋(abuse detection) — <b>429 가 아니라 403 으로 온다</b>
 * </ol>
 *
 * <p>2 · 3 을 1 로 처리하면 정상 운영 상황이 영구 실패가 되고, 1 을 2 · 3 으로 처리하면
 * 고쳐지지 않을 요청을 계속 다시 건다 — {@code .claude/rules/context/external-deps.md}.
 *
 * <p><b>애매하면 권한 오류로 떨어뜨린다.</b> 레이트리밋 신호가 하나도 없는데 리밋으로 추정하면
 * 오판의 결과가 무한 대기·무한 재시도가 되지만, 권한 오류로 보면 빠른 실패로 끝나고 로그에 남는다.
 */
public class GitHubErrorTranslator {

    /** 2차 레이트리밋일 때 GitHub 본문에 실리는 문구. 헤더가 없을 때의 마지막 신호다. */
    private static final String[] SECONDARY_LIMIT_MARKERS = {
            "secondary rate limit",
            "abuse detection",
            "exceeded a secondary"
    };

    /** 예외 메시지에 싣는 응답 본문의 최대 길이. 전문을 실으면 로그가 payload 저장소가 된다. */
    private static final int BODY_EXCERPT_LIMIT = 200;

    private final Clock clock;

    public GitHubErrorTranslator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param status  HTTP 상태코드
     * @param headers 응답 헤더 (레이트리밋·{@code Retry-After} 판정에 쓴다)
     * @param body    응답 본문. 없으면 {@code null}
     * @param path    요청 경로. 진단용이며 <b>토큰을 포함하지 않는다</b>
     */
    public GitHubApiException translate(int status, HttpHeaders headers, String body, String path) {
        HttpHeaders safeHeaders = headers == null ? HttpHeaders.EMPTY : headers;
        String where = "path=" + path + " status=" + status;

        if (status == 401) {
            return new GitHubAuthenticationException(
                    "GitHub 인증 실패 — 토큰이 없거나 유효하지 않습니다. " + where);
        }
        if (status == 403 || status == 429) {
            return translateForbidden(status, safeHeaders, body, where);
        }
        if (status == 404) {
            return new GitHubResourceNotFoundException("GitHub 리소스를 찾을 수 없습니다. " + where);
        }
        if (status >= 500) {
            return new GitHubTransientException(status,
                    "GitHub 서버 오류 — 재시도 대상입니다. " + where + excerpt(body));
        }
        return new GitHubApiException(status, "GitHub 호출 실패. " + where + excerpt(body));
    }

    /** 연결 자체가 실패한 경우(타임아웃·연결 거부). 응답이 없으므로 항상 재시도 대상이다. */
    public GitHubTransientException translateIoFailure(String path, Throwable cause) {
        return new GitHubTransientException(GitHubApiException.NO_STATUS,
                "GitHub 연결 실패 — 재시도 대상입니다. path=" + path, cause);
    }

    private GitHubApiException translateForbidden(int status, HttpHeaders headers, String body,
            String where) {
        GitHubRateLimit rateLimit = GitHubHeaders.rateLimit(headers);
        Duration retryAfter = GitHubHeaders.retryAfter(headers, clock);

        // 1차 — 할당량 소진. 가장 확실한 신호이고 리셋 시각이 함께 온다
        if (rateLimit.isExhausted()) {
            return new GitHubRateLimitException(status, GitHubRateLimitException.Scope.PRIMARY,
                    rateLimit.resetAt(), retryAfter,
                    "GitHub 1차 레이트리밋 소진 — 실패가 아니라 지연 대상입니다. " + where
                            + " resetAt=" + rateLimit.resetAt());
        }
        // 2차 — abuse detection. Retry-After 가 있거나, 429 이거나, 본문이 그렇게 말한다
        if (retryAfter != null || status == 429 || mentionsSecondaryLimit(body)) {
            return new GitHubRateLimitException(status, GitHubRateLimitException.Scope.SECONDARY,
                    rateLimit.resetAt(), retryAfter,
                    "GitHub 2차 레이트리밋(abuse detection) — 실패가 아니라 지연 대상입니다. " + where
                            + " retryAfter=" + retryAfter);
        }
        // 신호가 없다 — 권한 오류로 본다. 추정으로 지연시키지 않는다
        return new GitHubPermissionException(status,
                "GitHub 권한 오류 — 레이트리밋 신호가 없습니다. " + where + excerpt(body));
    }

    private static boolean mentionsSecondaryLimit(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : SECONDARY_LIMIT_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 진단용 본문 발췌. 길이를 자른다.
     *
     * <p>본문은 대상 저장소·GitHub 이 준 <b>남의 텍스트</b>다. 전문을 남기면 저장소가 커밋해 둔
     * 시크릿까지 따라 들어올 수 있으므로 양을 줄인다.
     *
     * <p>🔴 <b>스크럽을 절단보다 먼저</b> 한다. {@link GitHubApiException} 생성자가 한 번 더
     * 통과시키지만, 순서를 뒤집으면 토큰이 절단 경계에 걸렸을 때 앞 조각만 남고
     * 그 조각이 패턴의 최소 길이에 못 미쳐 그대로 나간다 — S-4.
     */
    private static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String scrubbed = TokenRedactor.redact(body).strip().replaceAll("\\s+", " ");
        if (scrubbed.length() > BODY_EXCERPT_LIMIT) {
            scrubbed = scrubbed.substring(0, BODY_EXCERPT_LIMIT) + "…(잘림)";
        }
        return " body=" + scrubbed;
    }
}
