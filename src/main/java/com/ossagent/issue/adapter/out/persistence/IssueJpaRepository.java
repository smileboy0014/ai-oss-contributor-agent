package com.ossagent.issue.adapter.out.persistence;

import com.ossagent.issue.domain.Issue;
import java.util.Optional;
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
}
