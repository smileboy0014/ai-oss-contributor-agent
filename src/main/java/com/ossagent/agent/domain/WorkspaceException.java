package com.ossagent.agent.domain;

/**
 * 워크스페이스를 준비하거나 읽지 못했다 — #18.
 *
 * <h2>⚠️ 이것은 「대상 저장소 코드가 잘못됐다」가 아니다</h2>
 *
 * <p>{@code CodeSandbox} 가 「빌드 실패는 <b>결과</b>(종료코드)이지 예외가 아니다」로 계약한 것과
 * 같은 축이다. 여기서 예외가 나는 것은 <b>우리가 작업을 수행하지 못한 경우</b>다 —
 * 네트워크·권한·경로. 대상 저장소의 코드 품질과 무관하다.
 *
 * <p>🔴 그래서 이 예외를 <b>재시도 루프의 「코드가 깨졌다」로 세지 않는다.</b> 섞으면
 * 고칠 수 없는 것에 재시도 예산(Q-6 의 3바퀴)을 태우고, 후보가 <b>코드 문제 없이</b>
 * {@code FAILED} 로 떨어진다.
 */
public class WorkspaceException extends RuntimeException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
