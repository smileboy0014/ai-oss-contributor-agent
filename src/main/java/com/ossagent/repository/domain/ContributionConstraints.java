package com.ossagent.repository.domain;

/**
 * 대상 저장소의 기여 규약 중 <b>구현 단계가 지켜야 할 것</b>만 추린 값 — 이슈 #16.
 *
 * <h2>🔴 왜 엔티티를 그대로 넘기지 않는가</h2>
 * {@link RepositoryPolicy} 는 <b>{@code repository} 애그리거트의 멤버 엔티티</b>다.
 * {@code candidate} 가 그것을 직접 import 하면 규율 ④ 위반이고, 애그리거트를 떼어내는
 * 순간 컴파일이 깨진다. 넘어가는 것은 값 하나여야 한다 —
 * {@code AnalyzableIssue}(issue → candidate)와 같은 패턴이다.
 *
 * <p>판정 필드({@code aiContributionAllowed} · {@code pendingReason})를 <b>일부러 뺐다.</b>
 * 그 판정은 {@code assertContributionAllowed} 가 <b>게이트로</b> 쓰는 것이고, 여기까지
 * 내려왔다는 것은 이미 통과했다는 뜻이다. 값으로 또 실어 보내면 소비자가 그것을 다시
 * 판정하려 들고, <b>게이트가 두 곳</b>이 된다 — 그러면 한쪽이 느슨해질 때 드러나지 않는다.
 *
 * <p>🔴 <b>이 값은 LLM 프롬프트로 나간다</b>(#16 FR-6). 대상 저장소 문서에서 뽑은
 * 문자열이므로({@code javaVersion}·{@code buildCommand} 는 #7 의 LLM 판정 산출물이다)
 * 로그에 포맷 문자열로 쓰지 않는다.
 *
 * @param javaVersion            빌드에 쓸 Java 버전. 모르면 {@code null}
 * @param buildCommand           빌드 명령. 모르면 {@code null}
 * @param testCommand            테스트 명령. 모르면 {@code null}
 * @param testsRequired          🔴 테스트를 <b>반드시</b> 함께 내야 하는가 — 계획 검증이 이것을 본다
 * @param issueReferenceRequired 커밋·PR 에 이슈 번호를 참조해야 하는가
 * @param signoffRequired        DCO sign-off 가 필요한가
 */
public record ContributionConstraints(
        String javaVersion,
        String buildCommand,
        String testCommand,
        boolean testsRequired,
        boolean issueReferenceRequired,
        boolean signoffRequired) {

    /**
     * 규약을 아직 모를 때의 값.
     *
     * <p>⚠️ 「제약이 없다」가 아니라 <b>「우리가 아는 제약이 없다」</b>는 뜻이다.
     * 이 값으로 S-5 를 판정하지 않는다 — 그 판정은 {@code assertContributionAllowed} 의
     * 몫이고, 거기서 막히면 여기까지 오지 않는다.
     */
    public static ContributionConstraints unknown() {
        return new ContributionConstraints(null, null, null, false, false, false);
    }

    public boolean hasTestCommand() {
        return testCommand != null && !testCommand.isBlank();
    }

    public boolean hasBuildCommand() {
        return buildCommand != null && !buildCommand.isBlank();
    }

    /**
     * 🔴 <b>값을 그대로 찍지 않는다.</b> {@code buildCommand}·{@code javaVersion} 은 대상 저장소
     * 문서에서 LLM 이 뽑은 자유 문자열이라, 포맷 문자열에 넣으면 로그 인젝션 경로가 된다 —
     * {@code logging.md}.
     */
    @Override
    public String toString() {
        return "ContributionConstraints[testsRequired=%s, issueRef=%s, signoff=%s, hasBuild=%s, hasTest=%s]"
                .formatted(testsRequired, issueReferenceRequired, signoffRequired,
                        hasBuildCommand(), hasTestCommand());
    }
}
