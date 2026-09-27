package com.ossagent.candidate.domain;

/**
 * 검증을 <b>시작조차 하지 못한</b> 경우 — #19.
 *
 * <h2>🔴 이것은 「검증 실패」가 아니다</h2>
 *
 * <p>둘을 같은 것으로 다루면 <b>재시도 루프가 고칠 수 없는 것을 세 번 고치려 든다</b>
 * (Q-6 의 예산 3 × 3 을 태운다).
 *
 * <table border="1">
 *   <caption>가르는 선</caption>
 *   <tr><th></th><th>무엇</th><th>고칠 주체</th></tr>
 *   <tr><td>{@link StageOutcome#FAILED}</td><td>컴파일·테스트가 <b>돌았고</b> 깨졌다</td>
 *       <td>코딩 단계 — 재시도가 의미 있다</td></tr>
 *   <tr><td><b>이 예외</b></td><td>명령이 없다 · 쉘 메타문자가 있다 · 빌드 도구 미지원</td>
 *       <td>🔴 <b>사람</b> — 규약을 다시 읽거나 Q-4 를 열어야 한다</td></tr>
 * </table>
 *
 * <p>⚠️ 「빌드 실패는 예외가 아니라 종료코드다」({@code external-deps.md})와 어긋나지
 * 않는다. 그 규율이 막는 것은 <b>게이트가 작동한 모습을 예외로 내보내는 것</b>이고,
 * 이 예외는 <b>게이트를 돌리지도 못한 것</b>이다.
 */
public class VerificationSetupException extends RuntimeException {

    public VerificationSetupException(String message) {
        super(message);
    }

    public VerificationSetupException(String message, Throwable cause) {
        super(message, cause);
    }
}
