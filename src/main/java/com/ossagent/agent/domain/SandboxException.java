package com.ossagent.agent.domain;

/**
 * 샌드박스 실행 실패 — #17.
 *
 * <p>🔴 <b>대상 저장소 텍스트를 메시지에 담지 않는다</b> (S-4 · 로그 인젝션).
 * 빌드 출력·예외 본문에는 대상 저장소가 커밋해 둔 시크릿이 섞여 있을 수 있고,
 * 그것이 {@code AgentRun.errorMessage} 로 흘러가는 것이 현실적인 유출 경로다 —
 * {@code LlmException} 이 같은 이유로 원인 예외를 달지 않는다.
 *
 * <p>진단에 필요한 컨테이너 id·종료코드는 <b>어댑터가 로그로</b> 남긴다. 그쪽은 마스킹을
 * 통제할 수 있는 자리다.
 *
 * <p>재시도 가능 여부는 {@link SandboxTransientException} / {@link SandboxPermanentException}
 * 두 갈래로 <b>타입에서</b> 갈린다. 호출자가 메시지를 해석해야 한다면 언젠가 틀린다.
 *
 * <p>⚠ 여기서 말하는 재시도는 <b>전송 축</b>(데몬에 다시 말을 건다)이다.
 * 파이프라인 재시도({@code agent.execution.max-retries} · Q-6)는 #21 의 몫이고,
 * <b>빌드가 실패한 것은 예외가 아니라 {@link SandboxResult} 의 종료코드</b>다 —
 * 그것이 이 제품의 게이트이지 오류가 아니기 때문이다.
 */
public abstract class SandboxException extends RuntimeException {

    protected SandboxException(String message) {
        super(message);
    }

    /** 같은 요청을 그대로 다시 보낼 가치가 있는가. */
    public abstract boolean retryable();
}
