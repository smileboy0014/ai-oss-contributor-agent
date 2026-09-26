package com.ossagent.issue.adapter.out.persistence;

import com.ossagent.issue.domain.Issue;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
