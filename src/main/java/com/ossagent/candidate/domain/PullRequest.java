package com.ossagent.candidate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Clock;
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

    /**
     * 소유 후보. <b>같은 애그리거트 안이라 연관관계를 쓴다</b>.
     *
     * <p>{@code UNIQUE(candidate_id)} 와 「PR 은 항상 draft」가 후보 상태와 <b>함께 서야 하는</b>
     * 불변식이다(①③⑨). 값으로 들고 있으면 그 불변식을 코드로 표현할 수 없다.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private ContributionCandidate candidate;

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

    // ─────────────────────────────── 생성 ───────────────────────────────

    /**
     * 🔴 <b>유일한 생성 경로</b> — 만들어지는 PR 은 전부 {@code DRAFT} 다 (S-2).
     *
     * <p>{@link Status} 를 인자로 받지 않는다. 받으면 「draft 가 아닌 PR 을 만드는 호출」을
     * <b>쓸 수 있게</b> 되고, 그러면 enum 이 단일값인 것이 우연이 된다.
     * 상태를 채우는 자리는 여기 한 줄뿐이다.
     *
     * <p>🔴 <b>{@code candidate} 를 인자로 요구한다.</b> 후보 없이 떠 있는 PR 행을 만들 수
     * 없다는 뜻이고, 그것이 「PR 은 후보 애그리거트의 멤버」를 코드로 표현한 것이다.
     * 역방향({@code candidate.pullRequest})은
     * {@link ContributionCandidate#markPrCreated(PullRequest, Clock)} 이 함께 채운다 —
     * 한쪽만 채우면 같은 트랜잭션 안에서 애그리거트가 자기 PR 을 못 본다.
     *
     * @param candidate  소유 후보
     * @param forkUrl    push 대상 Fork 의 주소 — S-1 의 <b>증거</b>다. 사고 후 「어디에
     *                   썼는가」를 여기서 읽는다
     * @param branchName Fork 안의 브랜치
     * @param prNumber   upstream PR 번호
     * @param prUrl      사람이 열어 볼 주소
     */
    public static PullRequest draftFor(ContributionCandidate candidate, String forkUrl,
            String branchName, Integer prNumber, String prUrl, Clock clock) {

        if (candidate == null) {
            throw new IllegalArgumentException("후보 없이 PR 행을 만들 수 없습니다");
        }
        if (forkUrl == null || forkUrl.isBlank()) {
            // 🔴 「어디에 push 했는가」가 비어 있으면 S-1 의 사후 증명이 불가능해진다
            throw new IllegalArgumentException("Fork 주소는 필수입니다 — S-1 의 증거다");
        }
        if (branchName == null || branchName.isBlank()) {
            throw new IllegalArgumentException("브랜치 이름은 필수입니다");
        }
        if (prNumber == null || prNumber < 1) {
            throw new IllegalArgumentException("PR 번호가 유효하지 않습니다: " + prNumber);
        }
        if (prUrl == null || prUrl.isBlank()) {
            throw new IllegalArgumentException("PR 주소는 필수입니다 — 없으면 사람이 이어받을 수 없다");
        }
        if (clock == null) {
            throw new IllegalArgumentException("Clock 은 필수입니다");
        }
        PullRequest pullRequest = new PullRequest();
        pullRequest.candidate = candidate;
        pullRequest.forkUrl = forkUrl.trim();
        pullRequest.branchName = branchName.trim();
        pullRequest.githubPrNumber = prNumber;
        pullRequest.prUrl = prUrl.trim();
        // 🔴 여기가 status 를 채우는 유일한 줄이다 — S-2
        pullRequest.status = Status.DRAFT;
        pullRequest.createdAt = Instant.now(clock);
        pullRequest.updatedAt = pullRequest.createdAt;
        return pullRequest;
    }
}
