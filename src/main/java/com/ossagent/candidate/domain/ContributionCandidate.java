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
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기여 후보. 상태머신의 주체 — {@code .claude/codemaps/domain.md}.
 *
 * <p>{@code UNIQUE(issue_id)} 로 이슈당 1건이다. 둘 생기면 같은 작업을 두 번 하고
 * 대상 저장소에 PR 이 두 개 난다.
 */
@Entity
@Table(name = "contribution_candidate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContributionCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code issue.id}. 값으로만 보관한다 — architecture.md 규율 ④ */
    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    private String category;

    private String difficulty;

    private Integer estimatedFiles;

    private Integer estimatedLoc;

    private Boolean implementationFeasible;

    private Boolean breakingChange;

    /** 0.00 ~ 1.00. {@code double} 이 아니라 {@code BigDecimal} 이다 — 컬럼이 {@code NUMERIC(3,2)}. */
    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @ExternalText(ExternalText.Source.LLM_RESPONSE)
    @Column(columnDefinition = "TEXT")
    private String analysis;

    /**
     * ⚠ {@code @Enumerated(STRING)} 을 반드시 붙인다.
     *
     * <p>JPA 기본값은 ORDINAL 이고, 그러면 Hibernate 가 INTEGER 를 기대해 VARCHAR 컬럼과
     * 어긋나 기동이 실패한다. 통과하더라도 <b>DB 값이 enum 선언 순서에 묶여</b>
     * {@link CandidateStatus} 를 재배치하는 순간 종단 상태의 의미가 뒤바뀐다 — S-6.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CandidateStatus status;

    /**
     * 사람이 고른 시각 — S-6. NULL 이면 구현 단계로 갈 수 없다.
     *
     * <p>「사람이 최종 승인한다」는 제품 정의의 데이터 증거다. 스케줄러가 채우는 경로를
     * 만들지 않는다.
     */
    private Instant selectedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** 사람이 고르지 않았다 — 구현 단계 진입 불가 (S-6). */
    public boolean isNotSelectedByHuman() {
        return selectedAt == null;
    }
}
