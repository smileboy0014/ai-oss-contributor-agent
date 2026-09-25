package com.ossagent.support.github;

import java.util.Optional;

/**
 * 요청에 붙일 자격증명을 <b>호출 시점에</b> 공급하는 능력.
 *
 * <p>지금 구현은 {@link StaticTokenCredentials} 하나이고 값은 고정된 classic PAT 이다.
 * 그런데도 이 인터페이스를 두는 이유는 Q-1 이 {@code (#6)} 으로 명시한 요구 때문이다 —
 * 「다중 사용자로 확장할 때의 경로는 GitHub App + user-to-server OAuth 다.
 * 능력 인터페이스를 그쪽으로 갈아끼울 수 있게 설계한다」
 * ({@code .claude/rules/context/open-questions.md} Q-1 「남은 것」).
 *
 * <p>그 경로의 토큰은 <b>단수명이라 주기적으로 갱신</b>된다. 토큰을 빈 생성 시점에
 * 기본 헤더로 구워 넣으면 갱신할 자리가 없어 조립을 통째로 다시 써야 한다.
 * 그래서 값이 아니라 <b>공급자</b>를 주입한다.
 */
public interface GitHubCredentials {

    /**
     * {@code Authorization} 헤더 값. 자격증명이 없으면 {@link Optional#empty()} 다.
     *
     * <p>비어 있으면 미인증 호출(60 req/h)이 된다 — 실패시키지 않는 것은 의도다.
     * 로컬에서 토큰 없이 기동·조회할 수 있어야 하고, 리밋은 정상 경로
     * ({@link GitHubRateLimitException})로 드러난다.
     *
     * <p>🔴 이 값은 <b>로그·예외 메시지에 싣지 않는다</b> — S-4.
     */
    Optional<String> authorizationHeader();
}
