package com.ossagent.issue.application;

import com.ossagent.issue.adapter.out.persistence.IssueJpaRepository;
import com.ossagent.issue.domain.Issue;
import com.ossagent.issue.domain.IssueSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집한 페이지를 <b>멱등하게</b> 저장한다 — #8 FR-2.
 *
 * <h2>🔴 왜 별도 빈인가</h2>
 *
 * <p>{@code ScanIssuesUseCase} 안의 메서드로 두면 <b>자기 호출이라 프록시를 타지 않아
 * 트랜잭션이 걸리지 않는다.</b> 조용히 틀리는 종류라 클래스를 가른다.
 *
 * <p>그리고 이 분리가 {@code architecture.md} 의 「대외 호출은 트랜잭션 밖」을 실현한다 —
 * 스캔 전체를 트랜잭션으로 감싸면 GitHub 응답을 기다리는 동안 커넥션이 잡힌다.
 * 페이지마다 <b>짧게</b> 열고 닫는다.
 */
@Component
public class IssuePageWriter {

    private final IssueJpaRepository issues;
    private final Clock clock;

    public IssuePageWriter(IssueJpaRepository issues, Clock clock) {
        this.issues = issues;
        this.clock = clock;
    }

    /**
     * 한 페이지를 저장하고 <b>본 것 중 가장 늦은 {@code updatedAt}</b> 을 돌려준다.
     *
     * <p>돌려준 값이 커서의 다음 워터마크가 된다. 저장이 끝난 뒤에 커서를 전진시키기 위해
     * 이 메서드가 값을 <b>반환</b>한다 — 여기서 직접 커서를 건드리면 다른 애그리거트를
     * 같은 트랜잭션에 끌어들이게 된다.
     *
     * @return 이 페이지의 최대 {@code updatedAt}. 저장할 이슈가 없었으면 {@code null}
     */
    @Transactional
    public Instant save(Long repositoryId, String owner, String name, List<IssueSnapshot> snapshots) {
        Instant now = clock.instant();
        Instant watermark = null;

        for (IssueSnapshot snapshot : snapshots) {
            upsert(repositoryId, owner, name, snapshot, now);
            watermark = later(watermark, snapshot.updatedAt());
        }
        return watermark;
    }

    private void upsert(Long repositoryId, String owner, String name, IssueSnapshot snapshot,
            Instant now) {
        issues.findByRepositoryIdAndGithubIssueNumber(repositoryId, snapshot.number())
                .ifPresentOrElse(
                        existing -> existing.updateFrom(snapshot, now),
                        () -> issues.save(Issue.fromSnapshot(repositoryId, owner, name, snapshot, now)));
    }

    private static Instant later(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
}
