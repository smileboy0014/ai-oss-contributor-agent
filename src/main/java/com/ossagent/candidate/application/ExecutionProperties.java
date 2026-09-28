package com.ossagent.candidate.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 파이프라인 실행 설정 — {@code agent.execution.*} (이슈 #18 · 개명 #21).
 *
 * <p>위치가 {@code application} 인 이유는 {@code ImplementationPlanProperties} 와 같다 —
 * {@code adapter/out/*} 에 두면 {@code ExternalAdapterIsolationTest} 가 잡는다.
 *
 * <h2>이름이 {@code max-attempts} 인 것은 의도다 — 값이 <b>총 시도 수</b>다</h2>
 *
 * <p>#18 까지 이 키는 {@code max-retries} 였고 값은 <b>총 시도 수(attempts)</b>로 쓰였다 —
 * 1 만큼 다른 개념이다. Q-6 이 그 어긋남을 기록하며 <b>「개명은 이 값을 실제로 읽는 #21 에서」</b>로
 * 미뤄 두었고, 이 이슈가 그것을 닫았다.
 *
 * <p>🔴 <b>개명은 이름만 바꾼 것이다.</b> 값(3)도 비교({@code attempt >= maxAttempts})도
 * 그대로다. Q-6 의 경고가 그 자리다 —
 * <b>「이름만 보고 off-by-one 으로 판단해 비교를 고치면 곱셈 예산이 함께 무효가 된다.」</b>
 * 후보 1건당 대외 호출 최대 <b>3 × (1 + 2) = 9회</b>가 이 값 위에 서 있다.
 *
 * <h2>이 축이 세는 것</h2>
 *
 * <p>{@code CODE → VERIFY → REVIEW} <b>한 바퀴가 1</b>이다 (Q-6 확정).
 * 전송 계층 재시도({@code github.max-retries} · {@code agent.llm.max-retries})는
 * <b>다른 축</b>이고 이 카운터를 태우지 않는다. 그 둘은 이름이 {@code max-retries} 인 채로
 * 남는다 — <b>그쪽은 이름과 의미가 맞기 때문</b>이다(첫 시도를 세지 않는다).
 *
 * @param maxAttempts     총 시도 수. 도메인 상수({@code MAX_ALLOWED_ATTEMPTS})보다 크면
 *                        {@code assertCanStartImplementing} 이 거부한다
 * @param timeoutSeconds  샌드박스 실행 상한. 🔴 이 값이 「트랜잭션 밖에서 실행한다」의 근거다.
 *                        ⚠️ <b>바퀴 1회의 상한</b>이지 루프 전체의 상한이 아니다 —
 *                        최악은 {@code maxAttempts × timeoutSeconds} 다
 */
@ConfigurationProperties("agent.execution")
public record ExecutionProperties(int maxAttempts, int timeoutSeconds,
        /** 착수 동시 실행 수 (#106). 워크스페이스가 저장소당 하나라 저장소가 겹치면 어차피 409 다 */
        Integer maxConcurrent,
        /** 착수 큐 상한 (#106). 차면 409 이고 후보는 SELECTED 로 되돌아간다 */
        Integer queueCapacity) {

    /** 동시성 설정을 모르는 호출자용 — 테스트가 쓴다. */
    public ExecutionProperties(int maxAttempts, int timeoutSeconds) {
        this(maxAttempts, timeoutSeconds, null, null);
    }


    // 🔴 생성자가 둘이면 Boot 는 어느 것으로 바인딩할지 모른다 — 「No default constructor found」로
    //    컨텍스트 전체가 죽는다. 정본은 canonical 하나다
    @ConstructorBinding
    public ExecutionProperties {
        maxConcurrent = maxConcurrent == null || maxConcurrent < 1 ? 1 : maxConcurrent;
        queueCapacity = queueCapacity == null || queueCapacity < 0 ? 4 : queueCapacity;
        // 🔴 아래쪽만 막는 것으로는 부족하다. 0 은 무한이 아니라 최강 제약이고
        //    (attempt >= 0 이 항상 참이라 즉시 FAILED), 정작 위험한 10000 은 무저항 통과한다.
        //    위쪽 경계는 도메인 상수가 본다 — 여기서는 「값이 말이 되는가」만 본다
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "agent.execution.max-attempts 는 1 이상이어야 한다: " + maxAttempts);
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException(
                    "agent.execution.timeout-seconds 는 1 이상이어야 한다: " + timeoutSeconds);
        }
    }
}
