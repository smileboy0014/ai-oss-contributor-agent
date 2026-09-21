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
@Table(name = "issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Issue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code oss_repositories.id}. 값으로만 보관한다 — architecture.md 규율 ④ */
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
}
