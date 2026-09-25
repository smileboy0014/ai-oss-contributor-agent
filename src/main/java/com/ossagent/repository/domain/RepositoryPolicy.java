package com.ossagent.repository.domain;

import com.ossagent.support.ExternalText;
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
     * <p>⚠️ <b>우리 어휘만</b> 들어간다 — 경로 + 사유 코드. 예외 원문을 넣지 않는다 (S-4).
     * {@code TEXT} 가 아니라 {@code VARCHAR} 인 것도 같은 이유다 — 외부 텍스트가 아니다.
     */
    @Column(length = 1024)
    private String pendingReason;

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
