package com.ossagent.repository.domain;

/**
 * 스캔 실행 1회의 <b>국면</b> — 「지금 어느 지점에 있나」.
 *
 * <p>🔴 <b>후보 상태머신({@code CandidateStatus})과 다른 축이다.</b> 이쪽은 저장소 하나에
 * 대한 파이프라인 <b>실행</b>의 국면이고, 저쪽은 이슈 하나가 기여 후보로서 거치는 단계다.
 * 섞어 쓰면 「스캔이 끝났다」와 「후보가 준비됐다」가 한 단어가 된다.
 *
 * <p>🔴 <b>{@code QUEUED} 를 {@code RUNNING} 과 가르는 것이 의도다.</b> 풀이
 * {@code corePoolSize=1} 이라 두 번째 저장소는 실제로는 <b>큐에서 대기</b> 중인데,
 * 어휘가 없으면 「돌고 있다」로 보인다. 동시 1건이 실제로 지켜지는지 사람이 확인할 수
 * 있는 유일한 창이다.
 *
 * <h2>왜 {@code domain} 에 있나 — 원래 {@code application} 에 있었다 (#26)</h2>
 *
 * <p>원래 {@code ScanExecutionState.Phase} 로 {@code repository.application} 안에 중첩돼 있었다.
 * #26 이 실행 상태를 <b>DB 로 옮기면서</b> 엔티티({@code ScanExecution})가 이 어휘를
 * 써야 했고, <b>domain 이 application 을 import 하면 의존 방향이 뒤집힌다</b>
 * ({@code architecture.md} 규율 ①). 어휘 자체는 기술을 모르는 도메인 개념이라
 * 안쪽으로 내린 것이지, 계약을 바꾼 것이 아니다.
 */
public enum ScanPhase {

    /** 한 번도 돌지 않았거나 마지막 실행이 끝났다 */
    IDLE,

    /** 제출됐고 스레드를 기다린다 */
    QUEUED,

    /** 돌고 있다 */
    RUNNING,

    /** 끝났다 */
    SUCCEEDED,

    /**
     * 🔴 <b>실패가 아니다</b> — 규약이 막았거나 읽지 못했거나 지연이다.
     * {@code ScanPipelineResult.skipReason} 참조
     */
    SKIPPED,

    /** 실패했다 */
    FAILED;

    /**
     * 진행 중인가 — <b>중복 스캔 차단이 보는 값</b>이다.
     *
     * <p>⚠️ 이것만으로 「자리가 잡혀 있다」를 판정하지 않는다. DB 구현에서는 <b>리스 만료</b>를
     * 함께 본다 — 인스턴스가 비정상 종료하면 {@code RUNNING} 행이 남고, 국면만 보면
     * 그 저장소가 <b>영원히 잠긴다</b>. 방어가 스스로를 잠그는 구조다 (#26 · FR-4).
     */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
