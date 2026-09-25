package com.ossagent.candidate.domain;

/**
 * 일어난 전이 하나. <b>전이 메서드가 이것을 반환하고, 로깅은 호출자가 한다.</b>
 *
 * <p>🔴 <b>엔티티가 직접 로그를 찍지 않는 이유</b> — 전이는 UseCase 트랜잭션 안에서 일어난다.
 * 커밋 실패·후속 예외로 <b>롤백되면 DB 는 되돌아가지만 로그 라인은 이미 나갔다.</b>
 * {@code logging.md} 는 안전 게이트 로그의 목적을 「사고 후 <b>막았는가</b>를 증명할 수 있어야
 * 한다」로 규정하는데, 커밋 여부와 어긋날 수 있는 로그는 그 증명을 못 한다.
 * {@code selectByHuman} 이 S-6 승인의 증거라 이 괴리가 가장 아픈 자리다.
 *
 * <p>호출자는 <b>커밋이 확정된 뒤</b> 이 값으로 로그를 남긴다 — MDC(`candidateId`·`stage`·
 * `attempt`)를 채우는 것도 호출자 몫이다. MDC 는 요청 스코프라 {@code finally} 에서 clear 할
 * 주체가 필요한데 엔티티는 그 수명을 모른다. 배선은 #13 · #24.
 *
 * <p>테스트에서 전이 결과를 직접 단언할 수 있어 로그 캡처 테스트가 필요 없다.
 *
 * @param from 전이 전 상태
 * @param to   전이 후 상태
 */
public record StatusTransition(CandidateStatus from, CandidateStatus to) {

    public StatusTransition {
        if (from == null || to == null) {
            throw new IllegalArgumentException("전이의 양끝은 null 일 수 없습니다");
        }
    }
}
