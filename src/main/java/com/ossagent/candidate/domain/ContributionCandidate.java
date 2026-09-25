package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기여 후보. 상태머신의 주체 — {@code .claude/codemaps/domain.md}.
 *
 * <p>{@code UNIQUE(issue_id)} 로 이슈당 1건이다. 둘 생기면 같은 작업을 두 번 하고
 * 대상 저장소에 PR 이 두 개 난다.
 *
 * <h2>이 클래스가 제품 정의를 들고 있다 — S-6</h2>
 *
 * <p>「사람이 최종 승인한다」는 {@link #selectByHuman(Clock)} 만이 {@code selectedAt} 을
 * 채운다는 것으로 표현되고, 「재시도 상한 소진은 사람에게 넘기는 신호」는
 * {@code → FAILED} 로 표현된다. <b>범용 setter 를 만들지 않는 이유가 이것이다</b> —
 * {@code setStatus} 하나가 생기면 아래 불변식이 전부 우회 가능해진다.
 *
 * <p>모든 전이 메서드는 {@link Clock} 을 받아 {@code updatedAt} 을 갱신하고
 * {@link StatusTransition} 을 반환한다. 로깅은 <b>호출자가 커밋 확정 후</b> 한다 —
 * 이유는 {@link StatusTransition} javadoc.
 */
@Entity
@Table(name = "contribution_candidate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContributionCandidate {

    /**
     * 🔴 <b>설정으로 이 값을 넘을 수 없다</b> — 불변식 ⑧ · S-6.
     *
     * <p>PRD §17 의 「Retry Count &lt; 3」이고 Q-6 이 확정한 「최대 3바퀴」다.
     * 상한을 올리려면 <b>이 상수를 고쳐야 하고, 그것은 리뷰에 보인다.</b>
     * 프로퍼티만 바꿔 LLM 비용이 조용히 폭주하는 경로를 막는다.
     */
    static final int MAX_ALLOWED_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 동시 전이 방어 — <b>상태머신이 이 제품의 유일한 중복 실행 방어</b>다.
     *
     * <p>이것이 없으면 트랜잭션 둘이 동시에 {@code SELECTED} 를 읽고 <b>둘 다 전이에 성공</b>해
     * last-write-wins 로 {@code IMPLEMENTING} 이 된다. 결과는 같은 후보에 구현 사이클 2개 —
     * 30분 샌드박스 ×2, LLM 과금 ×2, 브랜치 2개.
     *
     * <p>「같은 전이를 두 번 호출하면 두 번째는 예외」는 <b>한 인스턴스 안에서만</b> 참이다.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    /** {@code issue.id}. 값으로만 보관한다 — architecture.md 규율 ④ */
    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    private String category;

    private String difficulty;

    private Integer estimatedFiles;

    private Integer estimatedLoc;

    private Boolean implementationFeasible;

    private Boolean breakingChange;

    /** 0.00 ~ 1.00. {@code double} 이 아니라 {@code BigDecimal} 이다 — 컬럼이 {@code NUMERIC(3,2)}. */
    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @ExternalText(ExternalText.Source.LLM_RESPONSE)
    @Column(columnDefinition = "TEXT")
    private String analysis;

    /**
     * ⚠ {@code @Enumerated(STRING)} 을 반드시 붙인다.
     *
     * <p>JPA 기본값은 ORDINAL 이고, 그러면 Hibernate 가 INTEGER 를 기대해 VARCHAR 컬럼과
     * 어긋나 기동이 실패한다. 통과하더라도 <b>DB 값이 enum 선언 순서에 묶여</b>
     * {@link CandidateStatus} 를 재배치하는 순간 종단 상태의 의미가 뒤바뀐다 — S-6.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CandidateStatus status;

    /**
     * <b>{@code CODE} → {@code VERIFY} → {@code REVIEW} 한 바퀴를 센다</b> — Q-6 확정.
     *
     * <p>{@code 0} 은 「아직 구현에 착수하지 않음」이고 첫 {@code IMPLEMENTING} 진입에서
     * {@code 1} 이 된다. {@code TESTING}·{@code REVIEWING} 은 같은 바퀴라 올리지 않는다.
     *
     * <p>⚠ {@link AgentRun#getAttempt()} 와 <b>{@code CODE}·{@code VERIFY}·{@code REVIEW} 행에
     * 한해</b> 같은 값이다. {@code ANALYZE}·{@code PLAN} 행의 {@code attempt} 는 항상 1 인데
     * 그 시점 이 필드는 0 이다 — 둘을 무조건 같다고 보면 비용 집계가 어긋난다.
     */
    @Column(nullable = false)
    private Integer attempt;

    /**
     * 사람이 고른 시각 — S-6. NULL 이면 구현 단계로 갈 수 없다.
     *
     * <p>「사람이 최종 승인한다」는 제품 정의의 데이터 증거다. 스케줄러가 채우는 경로를
     * 만들지 않는다.
     */
    private Instant selectedAt;

    /**
     * 생성된 Draft PR. 아직 없으면 {@code null} 이다.
     *
     * <p>애그리거트 안의 읽기 전용 역방향({@code mappedBy}). {@code cascade} 는 걸지 않는다 —
     * 종단 기록을 지우지 않는 것이 이 프로젝트의 원칙이다(불변식 ⑩).
     *
     * <p>{@link AgentRun}·{@link GeneratedChange} 는 <b>이 애그리거트의 멤버가 아니다.</b>
     * 무한정 쌓이는 append-only 기록이라 별도 애그리거트로 두고 {@code candidateId} 로
     * 참조한다. 컬렉션이 없는 것은 성능 회피가 아니라 <b>경계가 다르기 때문</b>이다.
     */
    @OneToOne(mappedBy = "candidate", fetch = FetchType.LAZY)
    private PullRequest pullRequest;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // ─────────────────────────────── 생성 ───────────────────────────────

    /**
     * 후보를 만드는 <b>유일한 문</b>. 필터를 통과한 이슈로 스캐너가 부른다.
     *
     * <p>생성 경로를 하나로 두는 이유는 {@code status}·{@code attempt} 가 {@code NOT NULL}
     * 이기 때문만이 아니다. 경로가 늘면 <b>「{@code SELECTED} 인데 {@code selectedAt} 이 없는」
     * 같은 불법 상태</b>를 만들 수 있다 — Q-7 이 {@code @Builder} 를 금지한 이유와 같다.
     */
    public static ContributionCandidate discover(Long issueId, Clock clock) {
        if (issueId == null) {
            throw new IllegalArgumentException("issueId 는 필수입니다");
        }
        ContributionCandidate candidate = new ContributionCandidate();
        candidate.issueId = issueId;
        candidate.status = CandidateStatus.DISCOVERED;
        candidate.attempt = 0;
        candidate.createdAt = Instant.now(clock);
        candidate.updatedAt = candidate.createdAt;
        return candidate;
    }

    // ─────────────────────────────── 전이 ───────────────────────────────

    /** {@code DISCOVERED → ANALYZING} */
    public StatusTransition startAnalysis(Clock clock) {
        return transitionTo(CandidateStatus.ANALYZING, clock);
    }

    /** {@code ANALYZING → ANALYZED} */
    public StatusTransition completeAnalysis(Clock clock) {
        return transitionTo(CandidateStatus.ANALYZED, clock);
    }

    /**
     * {@code ANALYZING → FAILED} — 분석 실패는 <b>즉시 종단</b>이다 (Q-6).
     *
     * <p>{@code ANALYZE}·{@code PLAN} 은 파이프라인 재시도 카운터 밖이다. 5xx·타임아웃은
     * 전송 계층 축이 이미 흡수하므로, 여기까지 온 실패는 재시도로 풀리지 않는다.
     * 이 전이가 없으면 후보가 {@code ANALYZING} 에 영구히 박혀 <b>사람이 볼 신호가 발생하지
     * 않는다.</b>
     */
    public StatusTransition failAnalysis(Clock clock) {
        return transitionTo(CandidateStatus.FAILED, clock);
    }

    /** {@code ANALYZED → REJECTED} — {@code implementation_feasible=false} 등 시스템 판정 */
    public StatusTransition rejectAsInfeasible(Clock clock) {
        return transitionTo(CandidateStatus.REJECTED, clock);
    }

    /**
     * {@code ANALYZED → SELECTED} — 🔴 <b>사람만 부른다.</b>
     *
     * <p>이 메서드 <b>하나만</b> {@code selectedAt} 을 채운다. 다른 어떤 경로로도
     * {@code SELECTED} 에 도달할 수 없다 — 불변식 ② · S-6.
     * 이름에 {@code ByHuman} 을 박은 것은 <b>호출부 리뷰에서 눈에 띄게</b> 하기 위해서다.
     * 스케줄러·워커가 이것을 부르면 제품 정의가 무너진다.
     */
    public StatusTransition selectByHuman(Clock clock) {
        StatusTransition transition = transitionTo(CandidateStatus.SELECTED, clock);
        this.selectedAt = Instant.now(clock);
        return transition;
    }

    /**
     * {@code SELECTED → REJECTED} — 🔴 <b>사람만 부른다.</b> 선택 취소 (Q-5 확정 ②).
     *
     * <p>{@code SELECTED} 는 종단이 아니므로 「종단에서 나가는 전이 금지」에 걸리지 않는다.
     * {@code REJECTED} 는 종단이라 다시 고르려면 재분석이 필요하다 — <b>의도다.</b>
     * 번복이 가벼우면 승인이 가벼워진다.
     */
    public StatusTransition cancelSelection(Clock clock) {
        return transitionTo(CandidateStatus.REJECTED, clock);
    }

    /**
     * {@code SELECTED → IMPLEMENTING} — 첫 바퀴. {@code attempt} 가 1 이 된다.
     *
     * <p>🔴 <b>호출자는 S-5 를 확인할 의무를 진다.</b> 이 메서드는 대상 저장소의
     * {@code RepositoryPolicy} 를 보지 않는다 — 그 데이터는 {@code repository} 애그리거트에
     * 있고 여기서 읽으면 규율 ④ 위반이다. 그러나 <b>「{@code RepositoryPolicy} 없이 구현
     * 단계로 넘어가지 않는다」(S-5)를 지키는 문이 바로 이 메서드다.</b>
     * 호출자(UseCase)가 정책 확인과 AI 기여 허용 판정({@code aiContributionAllowed != null}
     * 이고 {@code TRUE})을 <b>먼저</b> 해야 한다 — 배선은 #24.
     */
    public StatusTransition startImplementing(int maxAttempts, Clock clock) {
        guardAttemptBudget(maxAttempts);
        guardHumanSelection();
        StatusTransition transition = transitionTo(CandidateStatus.IMPLEMENTING, clock);
        this.attempt = 1;
        return transition;
    }

    /** {@code IMPLEMENTING → TESTING} — 같은 바퀴 안이라 {@code attempt} 를 올리지 않는다 */
    public StatusTransition startTesting(Clock clock) {
        return transitionTo(CandidateStatus.TESTING, clock);
    }

    /** {@code TESTING → REVIEWING} — 같은 바퀴 안 */
    public StatusTransition startReview(Clock clock) {
        return transitionTo(CandidateStatus.REVIEWING, clock);
    }

    /**
     * {@code TESTING·REVIEWING → IMPLEMENTING} — 다음 바퀴. {@code attempt} 가 1 오른다.
     *
     * <p><b>상한을 넘으면 전이하지 않고 {@code FAILED} 로 간다.</b> 상한 소진은 실패가 아니라
     * <b>사람에게 넘기는 신호</b>다 — S-6.
     *
     * <p>리뷰 실패도 테스트 실패와 <b>같은 카운터</b>를 쓴다 — PRD §17 의 게이트가 하나뿐이고
     * Q-6 이 「합산」으로 확정했다.
     */
    public StatusTransition retryImplementation(int maxAttempts, Clock clock) {
        guardAttemptBudget(maxAttempts);
        if (attempt >= maxAttempts) {
            return fail(clock);
        }
        StatusTransition transition = transitionTo(CandidateStatus.IMPLEMENTING, clock);
        this.attempt = attempt + 1;
        return transition;
    }

    /** {@code REVIEWING → READY_FOR_PR} */
    public StatusTransition markReadyForPr(Clock clock) {
        return transitionTo(CandidateStatus.READY_FOR_PR, clock);
    }

    /**
     * {@code READY_FOR_PR → PR_CREATED} (종단).
     *
     * <p>⚠️ <b>「{@code PR_CREATED} 인데 PR 행이 없다」를 여기서 막지 않는다.</b>
     * (불변식 ③ 의 본체인 「PR 은 항상 draft」와 다른 이야기다 — 그쪽은 아래에서 다룬다.)
     * 막으려 했으나 <b>지금은 작동할 수 없다</b> — {@link #pullRequest} 는 {@code mappedBy}
     * 역방향이라 PR 을 만든 <b>같은 트랜잭션 안에서는 항상 {@code null}</b> 이다(소유 측이
     * {@code PullRequest} 다). 가드를 넣으면 정상 경로가 100% 막힌다.
     *
     * <p>제대로 막으려면 애그리거트가 <b>양방향을 함께 채우는 attach 연산</b>을 가져야 하고,
     * 그것은 {@code PullRequest} 생성 경로와 같은 곳에 있어야 한다 — <b>#23 이 만든다.</b>
     * 그때 이 메서드가 {@code PullRequest} 를 인자로 받는 형태로 바뀐다.
     *
     * <p>draft 고정 자체는 {@code PullRequestStatus} 단일값 + DB {@code CHECK} 가 이미
     * 보장하므로 이 PR 에서 비는 것은 「PR 행 존재 여부」 하나다.
     */
    public StatusTransition markPrCreated(Clock clock) {
        return transitionTo(CandidateStatus.PR_CREATED, clock);
    }

    /** 구현 루프에서 {@code FAILED} (종단). 재시도 상한 소진 또는 복구 불가 오류 */
    public StatusTransition fail(Clock clock) {
        return transitionTo(CandidateStatus.FAILED, clock);
    }

    // ─────────────────────────────── 판정 ───────────────────────────────

    /** 사람이 고르지 않았다 — 구현 단계 진입 불가 (S-6). */
    public boolean isNotSelectedByHuman() {
        return selectedAt == null;
    }

    /** 여기서 나가는 전이가 없다 — {@code PR_CREATED}·{@code REJECTED}·{@code FAILED}. */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    // ─────────────────────────────── 내부 ───────────────────────────────

    private StatusTransition transitionTo(CandidateStatus next, Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock 은 필수입니다 — updatedAt 을 채울 수 없습니다");
        }
        CandidateStatus previous = this.status;
        if (!previous.canTransitionTo(next)) {
            throw CandidateTransitionException.illegal(id, previous, next);
        }
        this.status = next;
        this.updatedAt = Instant.now(clock);
        return new StatusTransition(previous, next);
    }

    /**
     * 🔴 <b>넘겨받은 상한을 검증한다</b> — 불변식 ⑧ · S-6.
     *
     * <p>도메인이 설정을 <b>읽는</b> 것이 아니라 호출자가 넘긴 값을 <b>검증</b>하는 것이므로
     * 규율 ①(domain 에 기술 없음)과 충돌하지 않는다.
     *
     * <p>⚠️ 위쪽 경계가 본체다. {@code maxAttempts = 0} 은 무한이 아니라 최강 제약이고
     * (즉시 {@code FAILED}), 정작 위험한 것은 {@code 10000} 처럼 <b>상한을 사실상 없애는 값</b>이다.
     */
    /**
     * 🔴 <b>사람이 고르지 않았으면 구현 단계로 못 간다</b> — 불변식 ② · S-6.
     *
     * <p>지금은 전이표가 이미 막는다({@code SELECTED} 에 닿는 유일한 길이
     * {@link #selectByHuman(Clock)} 이고 그것이 {@code selectedAt} 을 채운다).
     * <b>그래도 게이트에서 한 번 더 본다.</b>
     *
     * <p>이유는 이 클래스가 {@code isTerminal()} 을 하드코딩 목록으로 두지 않은 것과 같다 —
     * 「지금 도달 불가하니 안전하다」에 기대면, 나중에 어떤 전이의 목적지가 바뀌는 순간
     * {@code SELECTED} + {@code selectedAt == null} 이라는 불법 상태가 조용히 생긴다.
     * S-1 이 「없는 권한에 기대지 않는다」로 어설션을 필수로 둔 것과 같은 형태다.
     *
     * <p>{@code selectedAt} 필드 javadoc 과 {@code codemaps/data.md} 가 「NULL 이면 구현
     * 단계로 갈 수 없다」고 <b>단언</b>한다. 그 문장을 코드가 뒷받침해야 한다.
     */
    private void guardHumanSelection() {
        if (isNotSelectedByHuman()) {
            throw new CandidateTransitionException(
                    "사람이 고르지 않은 후보는 구현 단계로 갈 수 없습니다 candidateId=" + id
                            + " — selectedAt 이 비어 있다 (S-6)");
        }
    }

    private void guardAttemptBudget(int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > MAX_ALLOWED_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "재시도 상한은 1 이상 " + MAX_ALLOWED_ATTEMPTS + " 이하여야 합니다: " + maxAttempts
                            + " — 상한을 올리려면 도메인 상수를 고쳐야 한다 (S-6)");
        }
    }
}
