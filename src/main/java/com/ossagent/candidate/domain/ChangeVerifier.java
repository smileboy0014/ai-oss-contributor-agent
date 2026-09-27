package com.ossagent.candidate.domain;

/**
 * 생성된 변경분을 <b>검증</b>하는 능력 — #19 가 구현한다.
 *
 * <h2>계층</h2>
 *
 * <pre>
 * agent/domain/CodeSandbox (1층 — #17)
 *   └ candidate/domain/ChangeVerifier (2층)
 *       └ candidate/adapter/out/sandbox/SandboxChangeVerifier (#19)
 * </pre>
 *
 * <p>{@code ImplementationPlanner}({@code LanguageModel} 위의 2층)와 같은 자리다 —
 * 1층 능력을 여러 단계가 공유하고, 그 위에 단계별 2층이 얹힌다.
 *
 * <h2>🔴 이 능력이 <b>여기</b> 선언된 이유</h2>
 *
 * <p>검증은 샌드박스를 타므로 {@code agent} 가 자연스러워 보인다. 그러나 이 능력의
 * 소비자는 <b>후보의 상태 전이</b>({@code startTesting} · {@code retryImplementation})를
 * 지시하는 쪽이고, 그것은 {@code candidate} 다. 능력은 <b>부르는 쪽의 도메인</b>에 선다 — 규율 ③.
 *
 * <h2>⚠️ 이 이슈(#18)는 1바퀴만 돌린다</h2>
 *
 * <p>{@code CODE → VERIFY → REVIEW} 루프와 {@code attempt} 상한 판정은 <b>#21</b> 이다.
 * 여기서는 한 번 부르고 결과를 그대로 쓴다.
 */
public interface ChangeVerifier {

    /**
     * 워크스페이스의 변경분을 검증한다.
     *
     * <p>🔴 <b>검증 실패는 예외가 아니라 {@link VerificationReport} 다.</b> 예외는
     * <b>검증 자체를 수행하지 못한 경우</b>에만 던진다 — 샌드박스 부재·이미지 없음 등.
     *
     * @throws com.ossagent.agent.domain.SandboxException 검증을 수행하지 못했다
     */
    VerificationReport verify(VerificationRequest request);
}
