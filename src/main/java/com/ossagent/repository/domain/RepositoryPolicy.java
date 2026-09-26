package com.ossagent.repository.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
 * 대상 저장소의 기여 규약. 이것 없이 구현 단계로 넘어가지 않는다 — S-5.
 *
 * <p>우리 커밋·PR 컨벤션은 이 저장소 안에서만 유효하다. 대상 저장소에 나가는 산출물은
 * 여기 담긴 규약을 따른다.
 */
@Entity
@Table(name = "repository_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 소유 저장소. <b>같은 {@code repository} 도메인 안이라 연관관계를 쓴다</b> — architecture.md 규율 ④.
     *
     * <p>규율 ④가 막는 것은 <b>도메인을 넘는</b> 참조다. 남의 도메인 엔티티를 import 하면
     * 컴파일 의존이 생겨 떼어낼 때 코드를 고쳐야 한다. 같은 도메인 안에서는 그 문제가 없고,
     * 오히려 값으로 들고 있으면 정책을 읽을 때마다 저장소를 따로 조회해야 한다.
     *
     * <p>물리 FK 는 마이그레이션에 있다. JPA 애노테이션이 아니라 <b>SQL 이 제약을 결정한다</b> —
     * {@code ddl-auto: validate} 라 {@code @ForeignKey(NO_CONSTRAINT)} 같은 지정은 무시된다.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private OssRepository repository;

    private String javaVersion;

    private String buildCommand;

    private String testCommand;

    @Column(nullable = false)
    private boolean issueReferenceRequired;

    @Column(nullable = false)
    private boolean signoffRequired;

    @Column(nullable = false)
    private boolean testsRequired;

    /**
     * AI 기여 허용 여부. <b>{@code Boolean} 이고 NULL 을 허용한다</b> — S-5.
     *
     * <p>NULL = 판정 실패 = <b>보류</b>(허용 아님). {@code boolean} 원시타입으로 바꾸면
     * 판정 실패가 {@code false} 로 뭉개지고, {@code NOT NULL DEFAULT TRUE} 로 두면
     * 「AI 기여 금지」 저장소를 기본 허용해 버린다. 어느 쪽이든 기본값이 곧 위반이 된다.
     */
    private Boolean aiContributionAllowed;

    @ExternalText(ExternalText.Source.TARGET_REPOSITORY)
    @Column(columnDefinition = "TEXT")
    private String contributionRules;

    /** 규약은 바뀐다. 재분석 주기 판단의 근거. */
    private Instant analyzedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * 왜 보류됐는가. {@link #isAiContributionUndetermined()} 일 때만 채워진다 — Q-8 확정 ②.
     *
     * <p>보류는 <b>자동으로 풀리지 않고 사람이 본다.</b> 근거가 없으면 판단할 수가 없다.
     *
     * <p>⚠️ <b>해소된 뒤에도 보존된다</b>(#24). {@code resolvedAt} 이 이미 「보류 아님」을
     * 말해 주므로 비울 이유가 없고, 비우면 <b>왜 보류였는지가 DB 에서 사라진다.</b>
     *
     * <p>⚠️ <b>우리 어휘만</b> 들어간다 — 경로 + 사유 코드. 예외 원문을 넣지 않는다 (S-4).
     * {@code TEXT} 가 아니라 {@code VARCHAR} 인 것도 같은 이유다 — 외부 텍스트가 아니다.
     */
    @Column(length = 1024)
    private String pendingReason;

    /**
     * 사람이 보류를 해소한 시각 — Q-8 · #24. 비어 있으면 <b>기계 판정</b>이다.
     *
     * <p>🔴 이것이 있어야 {@code aiContributionAllowed = TRUE} 가 「LLM 이 문서를 읽고
     * 판정한 것」인지 「사람이 보류를 풀어 준 것」인지 구분된다. 로그로만 남기면
     * <b>S-5 판정의 출처가 사라진다.</b>
     *
     * <p>🔴 {@link #reanalyze} 가 사람 판단을 <b>허용 방향으로</b> 덮어쓰지 못하게 하는
     * 근거다. 금지 방향은 막지 않는다 — {@link #reanalyze} javadoc 참조.
     */
    private Instant resolvedAt;

    /**
     * 사람이 쓴 판단 근거 — Q-8 · #24.
     *
     * <p>⚠️ {@link #pendingReason} 과 <b>다른 정보다.</b> 저쪽은 기계가 기록한 보류 원인
     * (어느 경로를 왜 못 읽었는가)이고 이쪽은 사람이 무엇을 보고 그렇게 판단했는가다.
     * 그래서 해소해도 저쪽을 비우지 않는다.
     *
     * <p>⚠️ {@code @ExternalText} 를 달지 않는다. 대입 지점이 {@link #resolvePending} 하나뿐이고
     * 거기서 스크럽하므로 외부 텍스트가 아니다 — {@code pendingReason}(V5)과 같은 취급이다.
     */
    @Column(length = 1024)
    private String resolutionNote;

    /** 판정이 서지 않았는가. NULL 은 「허용」이 아니라 「보류」다 — S-5. */
    public boolean isAiContributionUndetermined() {
        return aiContributionAllowed == null;
    }

    /** AI 기여가 금지된 저장소인가 — 후보에서 제외된다 (FR-2). */
    public boolean isAiContributionForbidden() {
        return Boolean.FALSE.equals(aiContributionAllowed);
    }

    /**
     * 이 저장소에 기여할 수 있는가. <b>보류도 금지도 아니어야</b> 한다.
     *
     * <p>보류를 통과시키면 S-5 가 무너진다 — 「판정 불가를 통과로 처리」가 바로 그것이다.
     */
    public boolean allowsContribution() {
        return Boolean.TRUE.equals(aiContributionAllowed);
    }

    /** 사람이 판단한 정책인가. 비어 있으면 기계 판정이다 — Q-8 · #24. */
    public boolean isHumanResolved() {
        return resolvedAt != null;
    }

    /**
     * 🔴 사람이 보류를 해소한다 — Q-8 · #24. {@link #reanalyze} 와 <b>다른 문</b>이다.
     *
     * <p>Q-8 이 「보류는 자동으로 풀리지 않고 사람이 명시적으로 푼다」고 정했고,
     * 이것이 그 유일한 경로다.
     *
     * <h2>무엇을 허용하고 무엇을 막는가</h2>
     *
     * <table border="1">
     *   <caption>현재 상태별</caption>
     *   <tr><td>{@code NULL} (보류)</td><td>✅ <b>양방향</b> — 허용·금지 어느 쪽으로도</td></tr>
     *   <tr><td>{@code FALSE} (금지)</td><td>❌ 🔴 API 로 뒤집으면 「AI 기여 금지 저장소 제외」가
     *       호출 한 번으로 풀린다</td></tr>
     *   <tr><td>{@code TRUE} (허용)</td><td>⚠️ <b>금지 방향만</b> — 조이는 것은 언제든 가능하다</td></tr>
     * </table>
     *
     * <p>🔴 <b>「해소」가 「허용」이 아니다.</b> 사람이 문서를 읽고 「이 저장소는 AI 기여
     * 금지다」라고 판단하는 것도 보류 해소의 정상적인 결과다. {@code TRUE} 전용으로 두면
     * 금지 판정을 내리려고 DB 를 손으로 고치게 되고, 그쪽이 더 위험하다.
     *
     * <p>⚠️ {@code pendingReason} 을 <b>비우지 않는다.</b> {@code resolvedAt} 이 이미 「보류
     * 아님」을 말해 주고, 비우면 왜 보류였는지가 DB 에서 사라진다.
     *
     * @param allowed 사람의 판정. {@code true} 면 허용, {@code false} 면 금지
     * @param note    판단 근거. <b>여기서 스크럽된다</b> — 사람이 대상 저장소 원문을
     *                붙여넣을 수 있다 (S-4)
     * @throws PolicyResolutionRejectedException 해소할 수 없는 상태
     */
    public void resolvePending(boolean allowed, String note, Clock clock) {
        if (isAiContributionForbidden()) {
            // ⚠️ 메시지가 우회법을 안내하지 않는다. 「DB 를 고치면 된다」 같은 문장을 넣으면
            //    막힌 사람에게 게이트를 돌아가는 법을 알려주는 꼴이고, 그것이 관행이 되면
            //    S-5 게이트는 있으나 마나다. 막혔다는 사실과 근거만 남긴다
            throw new PolicyResolutionRejectedException(
                    "AI 기여 금지 판정은 해소로 뒤집지 않는다 (S-5 · FR-2)");
        }
        if (allowsContribution() && allowed) {
            // 이미 허용인데 또 허용하는 것은 아무것도 바꾸지 않는다.
            // 조이는 방향(allowed == false)은 아래로 통과한다
            throw new PolicyResolutionRejectedException("이미 허용된 정책이다 — 해소할 것이 없다");
        }
        this.aiContributionAllowed = allowed;
        this.resolutionNote = truncate(TokenRedactor.redact(requireNote(note)));
        this.resolvedAt = clock.instant();
        this.updatedAt = clock.instant();
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            // 🔴 근거 없는 해소를 남기지 않는다. 나중에 왜 그렇게 판단했는지 알 수 없으면
            //    그 판정은 재검토도 못 한다 — pending(reason) 이 같은 이유로 사유를 요구한다
            throw new PolicyResolutionRejectedException("해소 근거는 필수다 — 사람이 판단한 이유가 남아야 한다");
        }
        return note;
    }

    /**
     * 🔴 구현 단계로 넘어가도 좋다는 <b>통행증</b>을 발급한다 — S-5 · #24.
     *
     * <p>발급하는 유일한 곳이다. {@link PolicyClearance} 의 생성자가 패키지 가시성이라
     * 이 패키지 밖에서는 만들 수 없고, 그래서 <b>정책을 읽지 않고 통행증을 손에 넣는
     * 경로가 없다.</b>
     *
     * <p>⚠️ <b>엔티티가 자기 통행증을 발급하는 이유</b> — 팩토리를 {@code PolicyClearance}
     * 쪽에 {@code of(RepositoryPolicy)} 로 두면, 그것을 부르려고 {@code candidate} 가
     * <b>이 엔티티를 import</b> 하게 된다. 규율 ④ 가 막는 바로 그 의존이다.
     * 여기서 발급하면 바깥은 {@code PolicyClearance} 만 보면 된다.
     *
     * <p>⚠️ <b>보류({@code NULL})도 거부한다.</b> {@link #allowsContribution()} 이
     * {@code Boolean.TRUE.equals(...)} 라 보류와 금지를 함께 막는다 — Q-8 · S-5 의
     * 「판정 불가를 통과로 처리하지 않는다」.
     *
     * <p>⚠️ 트랜잭션 안에서 부른다. {@link #repository} 가 LAZY 라 밖에서 부르면
     * {@code LazyInitializationException} 이 난다. <b>게이트가 그런 이유로 죽으면
     * 호출자가 그것을 {@code catch} 해 넘길 위험이 생긴다.</b>
     *
     * @throws ContributionNotAllowedException 보류 또는 금지
     */
    public PolicyClearance clearance() {
        if (isAiContributionUndetermined()) {
            throw new ContributionNotAllowedException(
                    repository.getId(), ContributionNotAllowedException.Reason.UNDETERMINED);
        }
        if (isAiContributionForbidden()) {
            throw new ContributionNotAllowedException(
                    repository.getId(), ContributionNotAllowedException.Reason.FORBIDDEN);
        }
        return new PolicyClearance(repository.getId());
    }

    // ─────────────────────────────────────────────────────────
    // 생성 — 팩토리를 가르는 것이 불변식이다
    // ─────────────────────────────────────────────────────────

    /**
     * 판정이 선 정책.
     *
     * <p>{@link RuleReading} 이 {@code undetermined} 면 거부한다 — 보류는
     * {@link #pending} 으로만 만든다. 한 팩토리가 둘 다 처리하면 「보류인데 사유가 없는」
     * 행이 생긴다.
     */
    public static RepositoryPolicy analyzed(OssRepository repository, RuleReading reading, Clock clock) {
        if (reading == null || reading.isUndetermined()) {
            throw new IllegalArgumentException("판정이 서지 않았다 — pending() 을 쓴다");
        }
        RepositoryPolicy policy = newFor(repository, clock);
        policy.apply(reading, clock);
        return policy;
    }

    /**
     * 보류 — 읽지 못해 판정할 수 없었다.
     *
     * <p>🔴 {@code aiContributionAllowed} 를 <b>건드리지 않는다</b>(= {@code null}).
     * 팩토리를 가르지 않고 setter 를 열면 「보류인데 allowed=true」라는 불법 상태를 만들 수 있다.
     */
    public static RepositoryPolicy pending(OssRepository repository, String reason, Clock clock) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("보류 사유는 필수다 — 사람이 판단할 근거가 없다");
        }
        RepositoryPolicy policy = newFor(repository, clock);
        policy.pendingReason = truncate(reason);
        policy.analyzedAt = clock.instant();
        return policy;
    }

    /**
     * 재분석 — 규약이 바뀌었을 때 갱신한다.
     *
     * <h2>🔴 보류·금지에서는 거부한다</h2>
     *
     * <p>Q-8 확정 ②: <b>「보류는 자동으로 풀리지 않는다」</b> — 재시도·시간경과·횟수소진 전부 배제.
     * 5xx 가 걷힌 뒤 분석을 한 번 더 돌리면 {@code NULL → TRUE} 로 조용히 바뀌는데,
     * 그것이 Q-8 이 막으려던 동작이다.
     *
     * <p>금지({@code FALSE})도 같이 막는다. 막지 않으면 <b>FR-2(AI 기여 금지 저장소 제외)가
     * 재분석 한 번으로 풀린다.</b>
     *
     * <p>이 규칙을 UseCase 의 {@code if} 로 두지 않는 이유 — 조건문은 다음 사람이 지운다.
     * <b>엔티티가 거부하면 지울 수 없다.</b>
     *
     * <p>해소는 사람의 명시적 행위이고 그 API 는 #24 다. 여기에 자동 경로를 만들지 않는다.
     *
     * <h2>🔴 사람이 해소한 뒤의 보호는 <b>비대칭</b>이다 (#24)</h2>
     *
     * <p>Q-8 의 「보류는 자동으로 풀리지 않는다」는 <b>방향성 규칙</b>이다 — 막는 것은
     * <b>느슨해지는 쪽</b>이다. 위 두 가드가 정확히 그렇게 되어 있다(보류·금지에서 거부).
     *
     * <table border="1">
     *   <caption>{@code resolvedAt != null} 일 때</caption>
     *   <tr><td>새 판정이 {@code TRUE} (유지·완화)</td><td><b>거부</b> — 사람 판단을 자동이
     *       재확인할 이유가 없다</td></tr>
     *   <tr><td>새 판정이 {@code FALSE} (조여짐)</td><td>🔴 <b>허용</b></td></tr>
     * </table>
     *
     * <p>🔴 <b>조여지는 쪽까지 막으면 S-5 가 깨진다.</b> 사람이 허용으로 해소한 뒤 대상
     * 저장소가 {@code CONTRIBUTING.md} 에 AI 기여 금지를 명시하면, 재분석이 거부되어
     * <b>금지된 저장소에 계속 Draft PR 을 만들게 된다.</b> 그리고 되돌릴 경로가 없다 —
     * 「승인을 무겁게」가 아니라 <b>되돌릴 수 없는 방향을 고정</b>하는 것이고,
     * {@code external-deps.md} 의 「모르면 되돌릴 수 없는 쪽을 피한다」에 어긋난다.
     */
    public void reanalyze(RuleReading reading, Clock clock) {
        if (isAiContributionUndetermined()) {
            throw new IllegalStateException(
                    "보류는 재분석으로 풀리지 않는다 — 사람이 해소한다 (Q-8 · #24)");
        }
        if (isAiContributionForbidden()) {
            throw new IllegalStateException(
                    "AI 기여 금지 판정은 재분석으로 뒤집히지 않는다 (S-5 · FR-2)");
        }
        if (reading == null || reading.isUndetermined()) {
            throw new IllegalArgumentException("판정이 서지 않은 결과로 갱신할 수 없다");
        }
        // 🔴 사람이 해소한 판정은 자동으로 「유지·완화」되지 않는다 — 방향을 본다.
        //    금지로 조이는 것은 아래로 빠져나가 허용된다
        if (isHumanResolved() && Boolean.TRUE.equals(reading.aiContributionAllowed())) {
            throw new IllegalStateException(
                    "사람이 해소한 판정을 재분석이 허용으로 되돌리지 않는다 (Q-8 · #24)");
        }
        apply(reading, clock);
    }

    private static RepositoryPolicy newFor(OssRepository repository, Clock clock) {
        if (repository == null) {
            throw new IllegalArgumentException("저장소는 필수다");
        }
        RepositoryPolicy policy = new RepositoryPolicy();
        policy.repository = repository;
        policy.createdAt = clock.instant();
        policy.updatedAt = clock.instant();
        return policy;
    }

    private void apply(RuleReading reading, Clock clock) {
        this.aiContributionAllowed = reading.aiContributionAllowed();
        this.javaVersion = reading.javaVersion();
        this.buildCommand = reading.buildCommand();
        this.testCommand = reading.testCommand();
        this.issueReferenceRequired = reading.issueReferenceRequired();
        this.signoffRequired = reading.signoffRequired();
        this.testsRequired = reading.testsRequired();
        // 🔴 ScrubbedRules 를 거쳐야만 값이 들어온다 — 원문이 DB 로 새지 않는다 (S-4)
        this.contributionRules = reading.rules().value();
        this.pendingReason = null;
        this.analyzedAt = clock.instant();
        this.updatedAt = clock.instant();
    }

    /** 컬럼 상한을 넘지 않게 자른다. 사유 문자열은 진단용이라 잘려도 무해하다. */
    private static String truncate(String reason) {
        return reason.length() <= 1024 ? reason : reason.substring(0, 1024);
    }
}
