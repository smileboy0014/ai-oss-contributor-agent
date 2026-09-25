package com.ossagent.support.testing;

import java.util.Set;

/**
 * 「이 클래스는 <b>대외 기술 어댑터</b>인가」를 판정한다 — 자동 스위트가 네트워크·컨테이너를
 * 타지 않게 하는 가드의 판정기다.
 *
 * <p><b>고정 목록이 아니라 규칙으로 판정한다.</b> 「지금 있는 어댑터 N개가 컨텍스트에 없는가」를
 * 보면 나중에 추가되는 어댑터를 영영 잡지 못한다. 같은 이유로 {@code ExternalTextMarkerTest} 도
 * 규칙 검사를 택했다.
 *
 * <p><b>규칙</b> — {@code com.ossagent} 아래에서 패키지 세그먼트 중 하나가
 * {@code github} · {@code llm} · {@code sandbox} 이면 대외 어댑터로 본다.
 *
 * <pre>
 * com.ossagent.issue.adapter.out.github.GitHubIssueSource        → 대외 ✅
 * com.ossagent.repository.adapter.out.github.GitHubRepositorySource → 대외 ✅
 * com.ossagent.support.github.GitHubApiClient                    → 대외 ✅ (전송 클라이언트)
 * com.ossagent.agent.adapter.out.sandbox.DockerCodeSandbox       → 대외 ✅
 *
 * com.ossagent.repository.adapter.out.persistence.*              → 허용 ❌ 아래 참조
 * com.ossagent.issue.domain.FakeIssueSource                      → 허용 (페이크)
 * com.ossagent.repository.application.RegisterRepositoryUseCase  → 허용
 * </pre>
 *
 * <p><b>{@code persistence} 는 일부러 넣지 않았다.</b> DB 는 대역 대상이 아니다 —
 * PostgreSQL 은 Testcontainers 로 실제로 띄운다(Q-2b). 트랜잭션 경계·동시성은 실 DB 가
 * 아니면 검증되지 않으므로, 영속 어댑터가 컨텍스트에 올라오는 것은 <b>의도된 것</b>이다.
 *
 * <p>세그먼트 이름으로 판정하는 이유 — 기술 어댑터는 규율 ③ 에 따라 <b>기술 이름</b>의
 * 패키지에 놓이게 되어 있다({@code adapter/out/{persistence·github·llm·sandbox}}).
 * 전송 클라이언트가 {@code support} 에 있어도({@code support.github}) 같은 이름을 쓰므로
 * 경로 깊이에 의존하지 않고 잡힌다.
 *
 * @see <a href="file:../../../../../../../.claude/rules/conventions/architecture.md">architecture.md 규율 ③</a>
 */
public final class ExternalAdapterPackages {

    /** 느리거나 비결정적이라 자동 스위트에서 대역으로 갈음하는 대외 기술 — Q-9. */
    private static final Set<String> EXTERNAL_TECHNOLOGIES = Set.of("github", "llm", "sandbox");

    private static final String BASE_PACKAGE = "com.ossagent";

    private ExternalAdapterPackages() {
    }

    /**
     * {@code type} 이 대외 기술 어댑터인가.
     *
     * <p>{@code com.ossagent} 밖의 클래스는 판정 대상이 아니다 — 스프링·하이버네이트 등
     * 프레임워크 빈이 우연히 {@code github} 같은 세그먼트를 갖는 일을 배제한다.
     */
    public static boolean isExternalAdapter(Class<?> type) {
        return isExternalAdapter(type.getName());
    }

    /** 클래스를 로드하지 않고 이름만으로 판정한다. 정적 스캔에서 쓴다. */
    public static boolean isExternalAdapter(String className) {
        if (className == null || !className.startsWith(BASE_PACKAGE + ".")) {
            return false;
        }
        int lastDot = className.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);

        for (String segment : packageName.split("\\.")) {
            if (EXTERNAL_TECHNOLOGIES.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /** 실패 메시지에 「무엇이 금지인지」를 실어 보내기 위해 노출한다. */
    public static Set<String> externalTechnologies() {
        return EXTERNAL_TECHNOLOGIES;
    }
}
