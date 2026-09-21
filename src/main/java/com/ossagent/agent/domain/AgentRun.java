package com.ossagent.agent.domain;

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
 * <p>토큰을 기록하지 않으면 재시도 루프가 조용히 돈을 태운다.
 */
@Entity
@Table(name = "agent_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code contribution_candidates.id}. 값으로만 보관한다 — architecture.md 규율 ④ */
    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Stage stage;

    /**
     * ⚠ <b>의미가 아직 확정되지 않았다</b> — Q-6.
     *
     * <p>단계별 독립 카운터인지 후보 전체 통합인지 정해지지 않았다. 컬럼만 만들어 두고
     * 해석은 #21 에서 확정한다. 정의가 흔들리면 비용 집계도 함께 흔들린다.
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
