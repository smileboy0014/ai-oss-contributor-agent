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
     * 한 페이지를 저장한다.
     *
     * <p>⚠ <b>커서를 여기서 건드리지 않는다.</b> 다른 애그리거트를 같은 트랜잭션에
     * 끌어들이게 되고, 무엇보다 커서 전진은 「읽은 것 전체」 기준이라
     * 「저장한 것」만 아는 이 클래스가 정할 수 없다 — PR 은 저장하지 않지만
     * 같은 {@code since} 순서를 차지한다.
     *
     * <p>⚠ <b>알려진 공백 — 동시 스캔.</b> 조회 후 insert 라 같은 저장소를 두 스캔이
     * 동시에 돌면 경합이 난다. 중복 행은 {@code uk_issue_repository_number} 가 막지만
     * {@code DataIntegrityViolationException} 으로 <b>페이지 전체가 롤백</b>되고 스캔이
     * 예외로 끝난다. 커서가 전진하지 않아 <b>유실은 없고</b> 다음 스캔이 다시 읽는다.
     * 지금은 스케줄러가 없어 동시 실행 경로 자체가 없다 — 스케줄러를 붙이는 <b>#14 가
     * 저장소별 동시 실행을 막거나</b> 행 단위 재시도를 넣어야 한다.
     *
     * @return 저장(신규+갱신)한 이슈 수
     */
    @Transactional
    public int save(Long repositoryId, String owner, String name, List<IssueSnapshot> snapshots) {
        Instant now = clock.instant();
        for (IssueSnapshot snapshot : snapshots) {
            upsert(repositoryId, owner, name, snapshot, now);
        }
        return snapshots.size();
    }

    private void upsert(Long repositoryId, String owner, String name, IssueSnapshot snapshot,
            Instant now) {
        issues.findByRepositoryIdAndGithubIssueNumber(repositoryId, snapshot.number())
                .ifPresentOrElse(
                        existing -> existing.updateFrom(snapshot, now),
                        () -> issues.save(Issue.fromSnapshot(repositoryId, owner, name, snapshot, now)));
    }
}
