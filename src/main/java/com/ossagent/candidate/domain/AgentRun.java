package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    public enum Stage {
        ANALYZE,
        PLAN,
        CODE,
        VERIFY,
        REVIEW
    }

    public enum RunStatus {
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
