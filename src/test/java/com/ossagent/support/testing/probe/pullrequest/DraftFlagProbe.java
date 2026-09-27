package com.ossagent.support.testing.probe.pullrequest;

/**
 * 🔴 <b>미끼</b> — 「{@code draft} 를 담을 자리가 있는」 payload 의 모양.
 *
 * <p>{@code ForkPublishArchitectureTest.draft_는_담을_자리가_없다_S2} 는 <b>위반 0건</b>인
 * 상태에서 도는 규칙이라, 판정기가 항상 {@code false} 를 돌려줘도 초록이다. 이 표본이
 * <b>물리는지</b>를 확인하는 것이 그 규칙이 살아 있다는 유일한 증거다
 * ({@code testing-philosophy.md} 가드 요구 2).
 *
 * <p>⚠️ <b>대표성</b>(요구 3) — 실제 S-2 위반의 모양은 「{@code mergePullRequest()} 라는
 * 메서드가 생긴다」가 아니라 <b>「payload 가 {@code draft} 를 값으로 든다」</b>이다.
 * 그 순간 {@code false} 인 인스턴스가 표현 가능해지고, 「설정으로도 끌 수 없게 한다」가
 * 무너진다.
 *
 * <p>🔴 <b>운영 패키지에 심지 않는다.</b> 컴포넌트 스캔 베이스가 {@code com.ossagent} 루트라
 * 미끼를 거기 두면 모든 컨텍스트가 오염된다. 이름이 {@code Test} 로 끝나지 않고
 * {@code @Test} 메서드도 없어 <b>실행되지 않는다.</b>
 *
 * <p>⚠️ 이 클래스를 「고쳐서 통과시키지」 않는다. 빨개지면 고칠 곳은 <b>운영 payload</b> 다.
 */
public final class DraftFlagProbe {

    private final String title;

    /** 🔴 바로 이것이 막으려는 모양이다 — 호출부가 {@code false} 를 넘길 수 있다. */
    private final boolean draft;

    public DraftFlagProbe(String title, boolean draft) {
        this.title = title;
        this.draft = draft;
    }

    public String getTitle() {
        return title;
    }

    public boolean isDraft() {
        return draft;
    }
}
