package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 파이프라인 한 단계의 1회 실행 기록. <b>비용과 재시도의 유일한 근거</b>다.
 *
 * <p><b>독립 애그리거트 루트</b>다 — {@code ContributionCandidate} 의 멤버가 아니다.
 * 재시도마다 무한정 쌓이는 <b>append-only 기록</b>이라, 후보 애그리거트에 넣으면
 * 루트를 읽을 때마다 전체를 끌고 오게 된다. 애그리거트는 작게 유지한다.
 *
 * <p>그래서 {@code candidateId} 는 <b>다른 애그리거트로의 ID 참조</b>이고, 이것이
 * 정상이다 — 연관관계를 걸지 않은 것은 예외가 아니라 설계다.
 *
 * <p>⚠️ 불변식 ⑧(재시도 상한)을 이 컬렉션을 세어 판정하지 않는다. 후보 루트가
 * 자기 상태로 들고 있어야 한다. {@code attempt} 의 의미는 확정됐고(Q-6),
 * <b>남은 것은 후보 루트가 그것을 어떤 필드로 들 것인가</b>다 — #21 · #12.
 *
 * <p>토큰을 기록하지 않으면 재시도 루프가 조용히 돈을 태운다.
 */
@Entity
@Table(name = "agent_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code contribution_candidate.id}. <b>다른 애그리거트</b>로의 ID 참조 — architecture.md 규율 ④ */
    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Stage stage;

    /**
     * <b>{@code CODE} → {@code VERIFY} → {@code REVIEW} 한 바퀴가 1</b>이다 — Q-6 확정(2026-09-25).
     *
     * <p>단계별 독립 카운터가 아니다. 카운터의 주체는 stage 가 아니라 <b>사이클</b>이고,
     * 그래서 <b>같은 사이클에서 만들어진 세 행이 같은 값을 갖는다</b>. 상한은
     * {@code agent.execution.max-retries}(3)이고 소진하면 후보가 {@code FAILED} 다 — S-6.
     *
     * <p>{@code ANALYZE}·{@code PLAN} 은 이 카운터 밖이다. 루프가 아니라 선형 단계이므로
     * 파이프라인 재시도가 없고, 값은 항상 1 이다. 5xx·타임아웃은 전송 계층 축
     * ({@code github.max-retries})이 흡수하므로 이 카운터를 태우지 않는다.
     *
     * <p>⚠ 이 정의가 흔들리면 비용 집계도 함께 흔들린다. 바꾸려면 Q-6 부터 다시 연다.
     */
    @Column(nullable = false)
    private Integer attempt;

    private Integer inputTokens;

    private Integer outputTokens;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    /** 스택트레이스에 토큰이 섞이는 것이 가장 흔한 유출 경로다 — S-4. */
    @ExternalText(ExternalText.Source.EXCEPTION)
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    /**
     * 실행 시작을 기록한다. <b>대외 호출 전에</b> 부른다.
     *
     * <p>왜 호출 전인가 — <b>실패한 호출도 토큰을 먹는다.</b> 타임아웃으로 끊긴 호출은 사용량을
     * 돌려받지 못하지만 모델은 이미 생성했고 과금된다. 시작 행을 먼저 남기지 않으면 그 비용이
     * 장부에서 통째로 사라진다.
     *
     * @param attempt Q-6 확정(2026-09-25) — {@code CODE → VERIFY → REVIEW} 한 바퀴가 1 이다.
     *                같은 사이클의 여러 행이 같은 값을 갖는다. <b>전송 재시도 횟수를 더하지 않는다</b>
     */
    public static AgentRun start(Long candidateId, Stage stage, int attempt, Clock clock) {
        if (stage == null) {
            throw new IllegalArgumentException("stage 는 필수다");
        }
        // 🔴 POLICY 만 저장소 단위라 후보가 없다. 나머지는 여전히 필수다 —
        //    전면 허용으로 풀면 「기록을 붙일 대상이 없는」 행이 조용히 늘어난다
        if (stage.requiresCandidate() && candidateId == null) {
            throw new IllegalArgumentException("candidateId 는 필수다: stage=" + stage);
        }
        if (!stage.requiresCandidate() && candidateId != null) {
            throw new IllegalArgumentException("저장소 단위 기록에 candidateId 를 붙일 수 없다: stage=" + stage);
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 는 1 부터다: " + attempt);
        }
        AgentRun run = new AgentRun();
        run.candidateId = candidateId;
        run.stage = stage;
        run.attempt = attempt;
        run.status = RunStatus.RUNNING;
        run.startedAt = clock.instant();
        return run;
    }

    /** 성공과 토큰을 기록한다. */
    public void succeed(int inputTokens, int outputTokens, Clock clock) {
        requireRunning();
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("토큰 수는 음수일 수 없다");
        }
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.status = RunStatus.SUCCEEDED;
        this.finishedAt = clock.instant();
    }

    /**
     * 실패를 기록한다.
     *
     * <p>⚠️ {@code reason} 은 <b>우리 어휘</b>여야 한다. SDK·HTTP 예외 메시지를 그대로 넘기지
     * 않는다 — 요청 URL 과 헤더가 담겨 있고, 거기 토큰이 붙어 있으면 그대로 적재된다 (S-4).
     */
    public void fail(String reason, Clock clock) {
        fail(reason, null, null, clock);
    }

    /**
     * 실패하면서 <b>토큰은 나간</b> 경우.
     *
     * <p>출력 상한 절단이 대표적이다 — 응답을 받았으니 사용량을 알지만 결과는 실패다.
     * 성공으로 기록하면 장부가 거짓말을 하고, 사용량을 버리면 비용이 사라진다.
     * 토큰을 모르면 {@code null} 을 넘긴다.
     */
    public void fail(String reason, Integer inputTokens, Integer outputTokens, Clock clock) {
        requireRunning();
        if ((inputTokens != null && inputTokens < 0) || (outputTokens != null && outputTokens < 0)) {
            throw new IllegalArgumentException("토큰 수는 음수일 수 없다");
        }
        // 🔴 마지막 그물이다. 호출자가 「우리 어휘만 넣는다」는 규약을 지키는 것이 1차 방어이나,
        // 규약은 언젠가 깨진다 — #17 샌드박스 단계가 예외 메시지를 그대로 넘기면 요청 URL 과
        // 토큰이 이 컬럼에 적재된다. 구조로 막는 비용이 한 줄이다 (S-4)
        this.errorMessage = TokenRedactor.redact(reason);
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.status = RunStatus.FAILED;
        this.finishedAt = clock.instant();
    }

    /**
     * 종단 상태에서 나가는 전이를 만들지 않는다.
     *
     * <p>{@code SUCCEEDED}·{@code FAILED} 는 append-only 기록의 끝이다. 이미 끝난 기록을
     * 다시 쓰면 <b>비용 장부가 조용히 바뀐다</b> — 그 자체가 재시도 상한 판정의 근거이므로
     * S-6 의 종단 상태 원칙과 같은 무게다.
     */
    private void requireRunning() {
        if (status != RunStatus.RUNNING) {
            throw new IllegalStateException(
                    "종단 상태의 실행 기록은 바꿀 수 없다: status=" + status + " id=" + id);
        }
    }

    public enum Stage {
        ANALYZE,
        PLAN,
        CODE,
        VERIFY,
        REVIEW,

        /**
         * 대상 저장소 기여 규약 판정 — 이슈 #7.
         *
         * <p>⚠️ <b>후보가 없는 유일한 단계</b>다. 저장소 단위로 일어나고 후보보다 먼저다.
         * 그래서 이 행만 {@code candidate_id} 가 {@code NULL} 이다.
         *
         * <p>{@code CODE → VERIFY → REVIEW} 루프 밖이라 {@code attempt} 는 항상 1 이다 —
         * {@code ANALYZE}·{@code PLAN} 과 같은 취급 (Q-6).
         */
        POLICY;

        /** 이 단계가 특정 후보에 속하는가. */
        public boolean requiresCandidate() {
            return this != POLICY;
        }
    }

    public enum RunStatus {
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
