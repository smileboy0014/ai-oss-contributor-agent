package com.ossagent.candidate.adapter.out.persistence;

import com.ossagent.candidate.application.CandidateSummaryView;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.ContributionCandidate;
import java.math.BigDecimal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 후보 영속 어댑터.
 *
 * <p>자기 도메인의 Spring Data 인터페이스라 {@code candidate} 의 UseCase 가 직접 주입받는다 —
 * {@code architecture.md} 의도적 완화 ②.
 */
public interface ContributionCandidateRepository extends JpaRepository<ContributionCandidate, Long> {

    /**
     * 목록 — <b>프로젝션으로 읽는다.</b>
     *
     * <p>🔴 엔티티를 로드하면 {@code analysis}(TEXT, 수십 KB)가 함께 딸려 온다. 목록 20건이면 20배다.
     * constructor expression 이 선택한 컬럼만 SELECT 하므로 TEXT 를 읽지 않는다 —
     * {@code CandidateQueryStatementTest} 가 생성 SQL 로 확인한다.
     *
     * <p>🔴 <b>{@code ORDER BY} 가 계약의 일부다.</b> 정렬 없는 {@code LIMIT/OFFSET} 은 행 순서를
     * 보장하지 않아, page 0 과 1 에 같은 행이 나오거나 어떤 행이 어느 페이지에도 안 나올 수 있다.
     * H2 와 PostgreSQL 이 다르게 동작할 수 있어 마이그레이션 테스트로도 안 잡힌다.
     * PK 내림차순이라 인덱스가 이미 받치고 새 마이그레이션이 필요 없다.
     *
     * <p>⚠️ {@code countQuery} 를 <b>명시</b>한다. constructor expression 이 있는 쿼리의 파생 count 는
     * Hibernate 버전에 따라 접히는 방식이 달라진다.
     *
     * <p>⚠️ {@code Pageable} 의 {@code sort} 는 쓰지 않는다 — 위 {@code ORDER BY} 가 정본이다.
     * 호출자는 {@code PageRequest.of(page, size)} 만 넘긴다.
     */
    @Query(value = """
            SELECT new com.ossagent.candidate.application.CandidateSummaryView(
                       c.id, c.issueId, c.status, c.difficulty, c.confidence, c.attempt, c.selectedAt)
            FROM ContributionCandidate c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:difficulty IS NULL OR c.difficulty = :difficulty)
              AND (:minConfidence IS NULL OR c.confidence >= :minConfidence)
            ORDER BY c.id DESC
            """,
            countQuery = """
            SELECT count(c)
            FROM ContributionCandidate c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:difficulty IS NULL OR c.difficulty = :difficulty)
              AND (:minConfidence IS NULL OR c.confidence >= :minConfidence)
            """)
    Page<CandidateSummaryView> findSummaries(@Param("status") CandidateStatus status,
            @Param("difficulty") String difficulty,
            @Param("minConfidence") BigDecimal minConfidence,
            Pageable pageable);
}
