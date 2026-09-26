package com.ossagent.issue.adapter.out.persistence;

import com.ossagent.issue.domain.Issue;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link Issue} 영속 어댑터.
 *
 * <p>이름을 {@code IssueRepository} 로 줄이지 않는다 — {@code repository} 가 이 저장소에서
 * 세 가지 뜻을 갖기 때문이다({@code glossary.md}). {@code Jpa} 를 붙여 「Spring Data 인터페이스」임을 못 박는다.
 */
public interface IssueJpaRepository extends JpaRepository<Issue, Long> {

    /**
     * 멱등 저장의 조회 키 — {@code uk_issue_repository_number} 와 같은 조합이다.
     *
     * <p>이 조합으로 찾아 없으면 insert · 있으면 update 한다. 스캔 재실행이 중복 행을
     * 만들지 않는 것은 <b>이 조회와 DB 의 UNIQUE 제약 둘 다</b>가 지킨다 —
     * 조회만으로는 동시 실행에서 경합이 난다.
     */
    Optional<Issue> findByRepositoryIdAndGithubIssueNumber(Long repositoryId, Integer githubIssueNumber);

    long countByRepositoryId(Long repositoryId);

    /**
     * 아직 규칙 필터를 거치지 않은 이슈 — #9.
     *
     * <p>{@code filter_result IS NULL} 이 곧 「미판정」이다. 내용이 바뀌면
     * {@code Issue.updateFrom} 이 이 값을 비우므로, 갱신된 이슈는 자동으로 다시 대상이 된다.
     *
     * <p>🔴 호출자는 <b>항상 첫 페이지를 읽는다.</b> 판정이 바로 이 조건 컬럼을 채워
     * 판정한 행이 결과 집합에서 빠지므로, {@code page=1} 로 넘기면 배치 크기만큼을
     * 건너뛴다 — {@code IssueFilterBatchWriter}.
     *
     * <p>{@code Page} 가 아니라 {@code List} 를 돌려준다. 전체 건수 count 쿼리가
     * 필요 없는데 {@code Page} 는 매 배치마다 그것을 돈다.
     */
    List<Issue> findByRepositoryIdAndFilterResultIsNull(Long repositoryId, Pageable pageable);

    long countByRepositoryIdAndFilterResult(Long repositoryId, String filterResult);

    /**
     * 분석 대상 이슈를 <b>우선순위 순</b>으로 한 페이지 읽는다 — #11 FR-1.
     *
     * <h2>🔴 대상이 {@code PASSED} 하나가 아니다</h2>
     *
     * <p>{@code UNDECIDED} 는 「규칙으로 가를 수 없다 — LLM 이 본다(#11)」는 신호이고
     * <b>배제가 아니다</b>({@code FilterOutcome.excluded()} 는 {@code REJECTED} 뿐).
     * 빼면 그 이슈들이 어느 단계도 소비하지 않아 테이블에 영구히 고인다.
     *
     * <h2>🔴 {@code COALESCE} 가 장식이 아니다</h2>
     *
     * <p>{@code filter_priority} 는 nullable 인데 <b>{@code ORDER BY ... DESC} 의 NULL 위치가
     * H2 와 PostgreSQL 에서 갈린다.</b> {@code NULLS LAST} 는 벤더 고유 문법이라 쓸 수 없다(Q-2).
     * 값 자체를 결정적으로 만들어 양쪽에서 같은 순서가 나오게 한다.
     * <b>커서 비교식에도 같은 식을 써야</b> 정렬과 어긋나지 않는다.
     *
     * <h2>🔴 OFFSET 이 아니라 키셋이다</h2>
     *
     * <p>분석이 진행되면 후보가 생기지만 <b>이 쿼리는 그것을 모른다</b>(애그리거트가 다르다).
     * 호출자가 이미 후보가 있는 이슈를 걸러내므로, OFFSET 으로 넘기면 그만큼 행을 건너뛴다.
     *
     * @param afterPriority 직전 페이지 마지막 행의 {@code COALESCE(filter_priority, 0)}.
     *                      첫 페이지는 {@code afterId} 와 함께 {@code null}
     */
    @Query("""
            SELECT i FROM Issue i
            WHERE i.repositoryId = :repositoryId
              AND i.filterResult IN :outcomes
              AND (:afterId IS NULL
                   OR COALESCE(i.filterPriority, 0) < :afterPriority
                   OR (COALESCE(i.filterPriority, 0) = :afterPriority AND i.id > :afterId))
            ORDER BY COALESCE(i.filterPriority, 0) DESC, i.id ASC
            """)
    List<Issue> findAnalyzable(@Param("repositoryId") Long repositoryId,
            @Param("outcomes") Collection<String> outcomes,
            @Param("afterPriority") Short afterPriority,
            @Param("afterId") Long afterId,
            Pageable pageable);
}
