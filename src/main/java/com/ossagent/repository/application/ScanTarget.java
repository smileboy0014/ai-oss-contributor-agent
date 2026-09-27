package com.ossagent.repository.application;

import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.ScanSkipReason;

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
 * <p>⚠️ 사유 어휘({@link ScanSkipReason})는 <b>domain 에 있다</b> — 원래 이 record 안에
 * 중첩돼 있었으나 #26 이 실행 상태를 DB 로 옮기면서 엔티티가 그것을 저장해야 했다.
 *
 * @param skipReason 기여할 수 없을 때의 사유. 가능할 때는 {@code null}
 */
public record ScanTarget(
        Long repositoryId,
        RepositoryCoordinates coordinates,
        boolean contributionAllowed,
        ScanSkipReason skipReason) {

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
            ScanSkipReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("건너뛰는 데에는 사유가 반드시 있어야 한다");
        }
        return new ScanTarget(repositoryId, coordinates, false, reason);
    }

    /** 다음 주기에 다시 시도할 가치가 있는가 — 로그 레벨과 진행 조회 표시가 갈린다. */
    public boolean isTransientSkip() {
        return skipReason == ScanSkipReason.POLICY_UNAVAILABLE
                || skipReason == ScanSkipReason.RATE_LIMITED;
    }
}
