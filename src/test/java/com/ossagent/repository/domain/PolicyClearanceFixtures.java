package com.ossagent.repository.domain;

/**
 * {@link PolicyClearance} 값 픽스처 — #24.
 *
 * <p>🔴 <b>이 패키지에 있어야만 한다.</b> 통행증의 생성자는 패키지 가시성이라
 * {@code com.ossagent.repository.domain} 밖에서는 만들 수 없다. 그것이 설계이고,
 * 픽스처를 여기 두는 것이 그 설계를 우회하는 것이 아니라 <b>그 설계가 허용하는 유일한 방법</b>이다
 * ({@code PolicyClearance} javadoc 의 「테스트 픽스처에는 오히려 유용하다」).
 *
 * <h2>⚠️ 애그리거트를 넘어 공유하는 예외다</h2>
 *
 * <p>{@code testing-philosophy.md} 는 「값 픽스처를 애그리거트 너머로 공유하지 않는다」고
 * 정했다. 그 조항이 막는 것은 <b>규율 ④ 가 막는 의존이 테스트를 통해 되살아나는 것</b>인데,
 * {@code PolicyClearance} 는 규율 ④의 <b>값 타입 예외</b>라 {@code candidate} 운영 코드가
 * 이미 정당하게 import 한다({@code ContributionCandidate.startImplementing}).
 * 막을 의존이 애초에 없으므로 테스트에서 가리는 것은 의미가 없고,
 * 가리려면 {@code candidate} 테스트가 <b>패키지 사기</b>(같은 패키지명을 자기 쪽에 또 만드는 것)를
 * 쳐야 한다 — 그쪽이 훨씬 나쁘다.
 *
 * <p>⚠️ 넓히지 않는다. {@code RepositoryPolicy}·{@code OssRepository} 엔티티 픽스처는
 * <b>여기 두지 않는다</b> — 그것들은 값 타입이 아니라 남의 애그리거트다.
 */
public final class PolicyClearanceFixtures {

    private PolicyClearanceFixtures() {
    }

    /** 임의 저장소의 통행증. id 가 의미를 갖지 않는 테스트에서 쓴다. */
    public static PolicyClearance any() {
        return of(7L);
    }

    public static PolicyClearance of(Long repositoryId) {
        return new PolicyClearance(repositoryId);
    }
}
