package com.ossagent.pullrequest.domain;

/**
 * Fork 의 기준 브랜치를 upstream 과 맞춘 결과.
 *
 * <h2>🔴 상태코드가 아니라 응답 본문의 {@code merge_type} 으로 판정한다</h2>
 *
 * <p>{@code POST /repos/{owner}/{repo}/merge-upstream} 은 성공 시 <b>200 + 본문</b>을 주고,
 * 「이미 최신」은 별도 상태코드가 아니라 <b>{@code merge_type: "none"}</b> 이다.
 * 상태코드로 판정하게 두면 테스트 스텁이 실제와 다른 응답을 흉내내도 초록이 되고,
 * 어댑터 매핑 층이 잡아야 할 오류를 <b>테스트가 같은 오류를 갖고 있어서</b> 못 잡는다.
 */
public enum SyncOutcome {

    /** {@code merge_type: fast-forward} 또는 {@code merge} — 맞춰졌다. */
    MERGED,

    /** {@code merge_type: none} — 이미 같다. */
    ALREADY_UP_TO_DATE,

    /**
     * 409 — Fork 가 <b>갈라졌다</b>. 과거 force push 의 잔재일 수 있다.
     *
     * <p>🔴 이 위에 커밋을 쌓으면 <b>머지 불가능한 PR</b> 이 된다. 다만 여기서 막지 않는다 —
     * 진행 여부는 <b>PR 을 만드는 주체</b>(#23)가 정할 일이고 이 도메인은 PR 을 만들지 않는다.
     */
    CONFLICT,

    /** 422 — 머지를 수행할 수 없다. */
    UNMERGEABLE;

    /** 기준 브랜치가 upstream 과 같은 상태인가. */
    public boolean isAligned() {
        return this == MERGED || this == ALREADY_UP_TO_DATE;
    }
}
