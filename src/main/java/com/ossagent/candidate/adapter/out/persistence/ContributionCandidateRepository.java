package com.ossagent.candidate.adapter.out.persistence;

import com.ossagent.candidate.application.CandidateSummaryView;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.ContributionCandidate;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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

    /**
     * 후보가 <b>이미 있는</b> 이슈 식별자를 골라낸다 — #11 의 낙관적 선별.
     *
     * <p>이슈 한 건씩 {@code existsByIssueId} 를 치면 배치 크기만큼 쿼리가 나간다.
     * 한 번에 묻는다.
     *
     * <p>⚠️ <b>이것은 멱등 방어가 아니라 최적화다.</b> 두 워커가 동시에 통과할 수 있다 —
     * 멱등의 정본은 {@code UNIQUE(issue_id)} 제약이고, 이 쿼리는 <b>LLM 호출을 아끼는</b> 용도다.
     * 이 구분이 흐려지면 「확인했으니 안전하다」로 읽혀 제약 위반 처리를 빠뜨리게 된다.
     */
    @Query("SELECT c.issueId FROM ContributionCandidate c WHERE c.issueId IN :issueIds")
    Set<Long> findExistingIssueIds(@Param("issueIds") Collection<Long> issueIds);

    /**
     * 상태별 후보 수 — {@code candidate_count} 게이지(#25)가 스크레이프마다 부른다.
     *
     * <p>🔴 <b>누적이 아니라 현재 값이다.</b> 카운터로 두면 후보가 상태를 옮길 때마다
     * 올라가기만 해서 「지금 몇 건이 {@code ANALYZED} 인가」에 답하지 못한다.
     *
     * <p>⚠️ 스크레이프마다 쿼리가 나간다. {@code idx_contribution_candidate_status} 가
     * 있고 지금은 후보가 0건이라 감당되지만, 수만 건이 되면 캐시를 붙인다.
     */
    @Query("SELECT c.status, count(c) FROM ContributionCandidate c GROUP BY c.status")
    List<Object[]> countGroupedByStatus();
}
