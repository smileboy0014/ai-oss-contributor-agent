package com.ossagent.issue.domain;

import com.ossagent.support.ExternalText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대상 저장소에서 수집한 GitHub 이슈.
 *
 * <p>{@code UNIQUE(repository_id, github_issue_number)} 가 재수집 멱등성의 근거다.
 * 없으면 재스캔마다 같은 이슈가 중복 적재되고 후보도 중복 생성된다.
 */
@Entity
@Table(name = "issue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Issue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code oss_repository.id}. 값으로만 보관한다 — architecture.md 규율 ④ */
    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(nullable = false)
    private Integer githubIssueNumber;

    @Column(length = 1024)
    private String title;

    @ExternalText(ExternalText.Source.TARGET_REPOSITORY)
    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private String state;

    @Column(length = 512)
    private String url;

    /** 필터 근거. {@code good first issue} 등. */
    @Column(length = 1024)
    private String labels;

    /** 규칙 필터 판정. 없으면 같은 이슈를 매 스캔마다 다시 판정한다. */
    private String filterResult;

    /** 판정 사유. 이슈 본문·라벨을 인용하므로 외부 텍스트다 — S-4. */
    @ExternalText(ExternalText.Source.TARGET_REPOSITORY)
    @Column(columnDefinition = "TEXT")
    private String filterReason;

    private Instant githubCreatedAt;

    /** <b>증분 수집 커서.</b> 매 스캔 전량 조회는 레이트리밋을 태운다. */
    private Instant githubUpdatedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** 수집되는 것은 전부 open 이다 — 조회가 {@code state=open} 고정이기 때문이다. */
    private static final String STATE_OPEN = "open";

    /**
     * 수집한 스냅샷으로 새 이슈 행을 만든다 — #8.
     *
     * <p>⚠ {@code state} 는 {@code "open"} 으로 고정한다. 조회가 {@code state=open} 이라
     * 수집되는 것은 전부 open 이다. <b>알려진 공백</b> — 닫힌 이슈를 우리가 조회하지 않으므로
     * 한 번 저장된 이슈는 <b>영원히 open 으로 남는다.</b> #9 의 「종료됨」 필터가 이 값을
     * 믿으면 안 된다. 닫힌 이슈 반영은 #9·#14 의 몫이다.
     *
     * <p>⚠ {@code url} 은 스냅샷에 없어 좌표와 번호로 <b>파생</b>한다. 그 하나 때문에
     * {@code IssueSource} 계약을 넓히지 않는다.
     */
    public static Issue fromSnapshot(Long repositoryId, String owner, String name,
            IssueSnapshot snapshot, Instant now) {
        Issue issue = new Issue();
        issue.repositoryId = repositoryId;
        issue.githubIssueNumber = snapshot.number();
        issue.state = STATE_OPEN;
        issue.url = issueUrl(owner, name, snapshot.number());
        issue.createdAt = now;
        issue.applySnapshot(snapshot, now);
        return issue;
    }

    /**
     * 재수집한 스냅샷을 반영한다 — 멱등 upsert 의 update 쪽 (#8).
     *
     * <p>🔴 <b>내용이 바뀌었으면 필터 결과를 무효화한다.</b> 판정 근거가 달라졌는데 낡은
     * 판정을 남기면 #9 가 바뀐 이슈를 다시 보지 않는다. 반대로 매번 지우면
     * 「없으면 같은 이슈를 매 스캔마다 다시 판정한다」는 스키마 주석의 의도가 깨진다.
     *
     * <p>기준은 <b>GitHub 이 준 {@code updated_at}</b> 이다 — 우리가 내용을 비교해 추측하지
     * 않는다. 커서가 포함(inclusive) 경계라 경계 이슈는 매 스캔 재수집되는데,
     * 그때 {@code updated_at} 이 그대로면 판정도 그대로 남는다.
     */
    public void updateFrom(IssueSnapshot snapshot, Instant now) {
        if (hasNewerContent(snapshot)) {
            this.filterResult = null;
            this.filterReason = null;
        }
        applySnapshot(snapshot, now);
    }

    private boolean hasNewerContent(IssueSnapshot snapshot) {
        return githubUpdatedAt == null
                || (snapshot.updatedAt() != null && snapshot.updatedAt().isAfter(githubUpdatedAt));
    }

    private void applySnapshot(IssueSnapshot snapshot, Instant now) {
        this.title = snapshot.title();
        this.body = snapshot.body();
        this.labels = String.join(",", snapshot.labels());
        this.githubCreatedAt = snapshot.createdAt();
        this.githubUpdatedAt = snapshot.updatedAt();
        this.updatedAt = now;
    }

    private static String issueUrl(String owner, String name, int number) {
        return "https://github.com/%s/%s/issues/%d".formatted(owner, name, number);
    }
}
