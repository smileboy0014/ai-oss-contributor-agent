package com.ossagent.candidate.domain;

/**
 * 생성된 변경분을 <b>샌드박스 안에서</b> 검증하는 능력 — #19 · 🔴 S-3.
 *
 * <h2>2층 능력이다</h2>
 *
 * <p>{@code agent/domain/CodeSandbox} 가 1층(여러 도메인이 공유하는 실행 능력)이고,
 * 그 위에 검증이라는 <b>업무 어휘</b>를 얹은 것이 이것이다 —
 * {@code IssueAnalyst}(#11) · {@code ImplementationPlanner}(#16) 와 같은 모양이다.
 *
 * <pre>
 * candidate/domain/ChangeVerifier                          ← 여기 (2층)
 *   └ candidate/adapter/out/sandbox/SandboxChangeVerifier  ← 구현
 *       └ agent/domain/CodeSandbox (#17)                   ← 1층
 * </pre>
 *
 * <p>🔴 <b>{@code candidate} 에 두는 이유</b>: 소비자가 {@code GeneratedChange}·
 * {@code ContributionCandidate} 다. {@code agent} 에 두면 그쪽이 남의 애그리거트를
 * import 하게 된다(규율 ④).
 *
 * <h2>⚠️ 이 파일은 #18 과 겹친다 — 먼저 머지되는 쪽이 남는다</h2>
 *
 * <p>#18(착수 게이트)이 이 인터페이스의 <b>호출자</b>이고 원래 선언 주인이다.
 * 이 PR 은 구현({@code SandboxChangeVerifier})이 컴파일되려면 선언이 있어야 해서
 * <b>합의된 시그니처 그대로</b> 함께 들고 있다. 내용이 같으므로 충돌은 사소하고,
 * 나중에 머지되는 쪽이 자기 사본을 버린다.
 *
 * <p>🔴 <b>이것은 완화책이 아니라 선후 의존이다.</b> #18 이 머지되기 전에는
 * 「검증 결과를 {@code GeneratedChange} 에 저장한다」가 성립하지 않는다 —
 * 그 엔티티에 쓰기 경로를 만드는 것이 #18 의 몫이기 때문이다. PR 본문에 적어 둔다.
 */
public interface ChangeVerifier {

    /**
     * 검증 <b>전</b> 준비 — 의존성 워밍·씨딩을 <b>원본 clone 에서</b> 끝낸다 (#99 · Q-4).
     *
     * <p>🔴 <b>코딩 전에 부른다.</b> 워밍은 대상 저장소의 {@code testClasses} 를 컴파일한다.
     * {@link #verify} 안에서 처음 워밍하면 <b>생성 코드가 그 컴파일에 섞이고</b>, 컴파일 실패가
     * 「빌드 실패」(종료코드 · 재시도 대상)가 아니라 「워밍 실패」(예외 · 종단)로 나와
     * 3바퀴 루프가 첫 바퀴에서 끝났다 — 루프가 존재하는 이유인 바로 그 실패에서.
     *
     * <p>착수 흐름은 이것을 {@code SELECTED → IMPLEMENTING} 전이 <b>앞</b>에서 부른다.
     * 여기서 나는 예외는 후보를 건드리지 않는다 — 인프라가 준비되면 사람이 다시 누른다.
     *
     * @throws VerificationSetupException 명령이 없거나 빌드 도구를 모른다 — 시작조차 못 한다
     * @throws com.ossagent.agent.domain.SandboxException 워밍·씨딩이 실행되지 못했거나 실패했다
     */
    void prepare(Long candidateId, com.ossagent.repository.domain.RepositoryCoordinates coordinates,
            java.nio.file.Path workspacePath,
            com.ossagent.repository.domain.ContributionConstraints constraints);

    /**
     * 컴파일 → 테스트 → diff 를 순서대로 돌리고 <b>첫 실패에서 멈춘다.</b>
     *
     * <p>🔴 <b>빌드 실패로 예외를 던지지 않는다.</b> 그것은 게이트가 작동한 모습이고,
     * 예외로 내보내면 호출자가 재시도 루프에서 삼킨다 —
     * {@code external-deps.md}: 「빌드 실패는 예외가 아니라 종료코드다.」
     * 보고서의 {@link StageOutcome} 으로 돌려준다.
     *
     * @throws VerificationSetupException 검증을 <b>시작조차 못 한</b> 경우
     *                                    (명령 없음 · 쉘 메타문자 · 빌드 도구 미지원).
     *                                    🔴 재시도 대상이 아니다 — 그 javadoc 참조
     * @throws com.ossagent.agent.domain.SandboxException 샌드박스 자체가 실행되지 못한 경우
     */
    VerificationReport verify(VerificationRequest request);
}
