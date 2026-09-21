package com.ossagent.pullrequest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Draft PR 메타데이터. <b>자동화는 여기서 끝난다</b> — 제출은 사람이 한다.
 *
 * <p>{@code UNIQUE(candidate_id)} 가 없으면 재시도 시 대상 저장소에 PR 이 두 개 열린다.
 * 남의 저장소에 중복 PR 을 여는 것은 스팸으로 취급된다 — S-2.
 */
@Entity
@Table(name = "pull_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code contribution_candidate.id}. 값으로만 보관한다 — architecture.md 규율 ④ */
    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    /**
     * push 대상 Fork 의 좌표 — S-1.
     *
     * <p>스키마에 upstream 좌표가 없다는 뜻이 아니다({@link #prUrl} 과
     * {@code oss_repository.url} 은 upstream 을 가리킨다). S-1 의 방어는 컬럼 구성이 아니라
     * <b>push 직전 owner 어설션</b>이며 그것은 #22 소관이다.
     */
    @Column(nullable = false, length = 512)
    private String forkUrl;

    @Column(nullable = false, length = 512)
    private String branchName;

    private Integer githubPrNumber;

    /** 생성된 upstream PR 주소. */
    @Column(length = 512)
    private String prUrl;

    /**
     * ⚠ {@link Status} 에 값이 {@code DRAFT} 하나뿐인 것은 의도다 — S-2.
     *
     * <p>enum 에 다른 값을 추가하는 것은 「draft 가 아닌 PR 을 만들 수 있게 한다」는 뜻이고,
     * 그것은 조항 위반이다. DB 쪽에도 {@code CHECK (status = 'DRAFT')} 가 걸려 있어
     * 코드가 실수해도 거부된다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** draft 외의 상태를 두지 않는다 — S-2. */
    public enum Status {
        DRAFT
    }
}
