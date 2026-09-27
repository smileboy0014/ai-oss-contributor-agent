package com.ossagent.candidate.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 파이프라인 실행 설정 — {@code agent.execution.*} (이슈 #18).
 *
 * <p>위치가 {@code application} 인 이유는 {@code ImplementationPlanProperties} 와 같다 —
 * {@code adapter/out/*} 에 두면 {@code ExternalAdapterIsolationTest} 가 잡는다.
 *
 * <h2>🔴 이름이 의미와 어긋난다 — 고치지 않는다</h2>
 *
 * <p>프로퍼티 키는 {@code max-retries} 인데 값은 <b>총 시도 수(attempts)</b>로 쓰인다 —
 * 1 만큼 다른 개념이다. Q-6 가 이 어긋남을 기록하며 경고해 뒀다.
 *
 * <p>⚠️ <b>이름만 보고 「off-by-one 버그」로 판단해 비교를 고치면 곱셈 예산이 함께 무효가 된다.</b>
 * 후보 1건당 대외 호출 최대 <b>3 × (1 + 2) = 9회</b>가 이 값 위에 서 있다.
 * 개명({@code max-attempts})은 Q-6 가 <b>#21</b> 로 지정했다 — 지금 바꾸면
 * {@code open-questions.md} · {@code architecture.md} · {@code external-deps.md} 를 동시에
 * 건드려 교차 충돌이 된다.
 *
 * <h2>이 축이 세는 것</h2>
 *
 * <p>{@code CODE → VERIFY → REVIEW} <b>한 바퀴가 1</b>이다 (Q-6 확정).
 * 전송 계층 재시도({@code github.max-retries} · {@code agent.llm.max-retries})는
 * <b>다른 축</b>이고 이 카운터를 태우지 않는다.
 *
 * <p>⚠️ 이 이슈(#18)는 <b>1바퀴만</b> 돌린다. 루프와 상한 소진 판정은 #21 이다.
 * 여기서 이 값을 쓰는 곳은 {@code startImplementing(clearance, maxAttempts, clock)} 하나뿐이고,
 * 도메인이 {@code MAX_ALLOWED_ATTEMPTS} 로 위쪽 경계를 다시 본다.
 *
 * @param maxRetries      총 시도 수. 도메인 상수보다 크면 {@code startImplementing} 이 거부한다
 * @param timeoutSeconds  샌드박스 실행 상한. 🔴 이 값이 「트랜잭션 밖에서 실행한다」의 근거다
 */
@ConfigurationProperties("agent.execution")
public record ExecutionProperties(int maxRetries, int timeoutSeconds) {

    public ExecutionProperties {
        // 🔴 아래쪽만 막는 것으로는 부족하다. 0 은 무한이 아니라 최강 제약이고
        //    (attempt >= 0 이 항상 참이라 즉시 FAILED), 정작 위험한 10000 은 무저항 통과한다.
        //    위쪽 경계는 도메인 상수가 본다 — 여기서는 「값이 말이 되는가」만 본다
        if (maxRetries < 1) {
            throw new IllegalArgumentException(
                    "agent.execution.max-retries 는 1 이상이어야 한다: " + maxRetries);
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException(
                    "agent.execution.timeout-seconds 는 1 이상이어야 한다: " + timeoutSeconds);
        }
    }
}
