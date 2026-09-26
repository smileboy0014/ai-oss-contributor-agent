package com.ossagent.repository.application;

import com.ossagent.repository.domain.RepositoryCoordinates;

/**
 * 스캔 파이프라인이 한 저장소에 대해 알아야 할 <b>전부</b> — 좌표 + 기여 가능 여부.
 *
 * <p>둘을 함께 나르는 이유는 DB 왕복이다. {@code ScanIssuesUseCase.scan} 이
 * {@link RepositoryCoordinates} 를 요구하고, 파이프라인은 그 전에 정책을 봐야 한다.
 * 따로 조회하면 같은 행을 두 번 읽는다.
 *
 * <p>🔴 <b>이것은 S-5 게이트가 아니다.</b> 게이트는
 * {@code AnalyzeRepositoryPolicyUseCase.assertContributionAllowed} 하나뿐이고,
 * 파이프라인 마지막 단계({@code AnalyzeIssuesUseCase})가 그것을 부른다.
 * 여기 {@link #contributionAllowed()} 는 <b>수집을 시작하기 전에 끊기 위한 조기 판정</b>이다 —
 * 이것이 빠지거나 틀려도 게이트가 여전히 막는다.
 *
 * @param skipReason 기여할 수 없을 때의 사유. 가능할 때는 {@code null}
 */
public record ScanTarget(
        Long repositoryId,
        RepositoryCoordinates coordinates,
        boolean contributionAllowed,
        SkipReason skipReason) {

    /**
     * 수집을 시작하지 않는 사유.
     *
     * <h2>⚠️ 알려진 한계 — {@code POLICY_UNAVAILABLE} 이 두 가지를 합치고 있다</h2>
     *
     * <p>{@code AnalyzeRepositoryPolicyUseCase.analyze()} 는 <b>일시적 실패</b>와
     * <b>보관된 저장소</b>를 둘 다 {@code Optional.empty()} 로 돌려준다. 하나는 일시이고
     * 하나는 <b>영구</b>인데 바깥에서 가를 수단이 없다 — {@code archived} 는 GitHub 응답에만
     * 있고 {@code oss_repository} 에 저장되지 않는다.
     *
     * <p>그래서 보관된 저장소를 <b>매 주기 메타데이터 호출로 두드리게 된다.</b>
     * 대상이 한 곳인 Phase 1 에서는 주기당 호출 1회라 감당되지만, 저장소가 늘면 낭비가 는다.
     *
     * <p>🔴 <b>{@code ARCHIVED} 상수를 만들어 두지 않는다.</b> 만들어도 이 코드가 그것을
     * 내보낼 방법이 없어 <b>도달 불가능한 어휘</b>가 되고, 다음 사람이 「구분되고 있다」고
     * 오해한다. 해소하려면 {@code analyze()} 가 사유를 실은 타입을 돌려줘야 한다 —
     * #7 의 계약 변경이라 이 PR 의 범위 밖이다.
     */
    public enum SkipReason {
        /**
         * 규약을 읽지 못했다 — 다음 주기가 재시도한다.
         * ⚠️ <b>보관된 저장소도 여기로 들어온다</b>(위 한계).
         */
        POLICY_UNAVAILABLE,
        /** 규약 판정이 서지 않았다(보류) — 🔴 사람이 푼다. 자동으로 풀리지 않는다 (Q-8 확정 ②) */
        POLICY_UNDETERMINED,
        /** AI 기여를 금지한다 */
        CONTRIBUTION_FORBIDDEN,
        /** 레이트리밋 — 🔴 실패가 아니라 <b>지연</b>이다 */
        RATE_LIMITED
    }

    public ScanTarget {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다");
        }
        if (contributionAllowed == (skipReason != null)) {
            throw new IllegalArgumentException(
                    "기여 가능 여부와 사유가 어긋난다: allowed=%s reason=%s"
                            .formatted(contributionAllowed, skipReason));
        }
    }

    public static ScanTarget allowed(Long repositoryId, RepositoryCoordinates coordinates) {
        return new ScanTarget(repositoryId, coordinates, true, null);
    }

    public static ScanTarget skip(Long repositoryId, RepositoryCoordinates coordinates,
            SkipReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("건너뛰는 데에는 사유가 반드시 있어야 한다");
        }
        return new ScanTarget(repositoryId, coordinates, false, reason);
    }

    /** 다음 주기에 다시 시도할 가치가 있는가 — 로그 레벨과 진행 조회 표시가 갈린다. */
    public boolean isTransientSkip() {
        return skipReason == SkipReason.POLICY_UNAVAILABLE || skipReason == SkipReason.RATE_LIMITED;
    }
}
