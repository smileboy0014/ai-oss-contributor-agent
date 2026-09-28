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
import jakarta.persistence.Version;
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

    /** {@code V9} 의 {@code VARCHAR(4000)} 과 같아야 한다 — 어긋나면 저장 시점에 잘린다. */
    static final int MAX_FINGERPRINTS_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 동시 갱신 방어 — 🔴 <b>사람의 판단이 재분석에 조용히 지워지지 않게 한다</b> (S-5 · #24).
     *
     * <p>이 행에 쓰는 주체가 둘이다 — 재분석({@code RepositoryPolicyWriter.saveAnalyzed},
     * 스케줄러가 기동한다)과 사람의 보류 해소({@link #resolvePending}).
     * 둘 다 자기 트랜잭션 안에서 행을 <b>다시 읽으므로</b> 오래된 엔티티로 덮어쓰지는 않는다.
     * 그런데 그것만으로는 부족하다.
     *
     * <pre>
     * 정책 = 허용
     *   사람:   허용 → 금지   (규약이 바뀐 것을 사람이 확인했다)
     *   재분석: 허용 → 허용   (아직 바뀐 문서를 못 봤다)
     * 둘 다 가드를 통과한다 → 나중 커밋이 이긴다
     * </pre>
     *
     * <p>재분석이 이기면 <b>사람의 금지 판단이 사라지고</b> 우리는 계속 Draft PR 을 만든다.
     * 되돌릴 수 없는 방향이 그쪽이다. 충돌은 409 로 나간다.
     */
    @Version
    @Column(nullable = false)
    private Long version;

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

    /**
     * 사람이 명령을 직접 넣은 시각 (#102). {@code null} 이 아니면 재분석이 {@code javaVersion} ·
     * {@code buildCommand} · {@code testCommand} 를 덮어쓰지 않는다 — 자동이 사람 판단을 다시 쓰지 않는다.
     */
    @Column(name = "commands_overridden_at")
    private Instant commandsOverriddenAt;

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

    /**
     * 판정을 세울 때 본 <b>규약 문서들의 지문</b> — 이슈 #68. 다음 관측의 <b>비교 기준</b>이다.
     *
     * <p>🔴 <b>{@code null} 이 「바뀌지 않았다」가 아니다.</b> 지문 기록 이전에 만들어진 행은
     * 비교 기준이 없고, 그것을 「같다」로 읽으면 변경이 영영 탐지되지 않는다. 반대로
     * 「다르다」로 읽으면 이 기능을 배포하는 순간 모든 저장소가 일괄 보류로 떨어진다.
     * {@link PolicyDocumentFingerprints#changedRequiredPaths} 가 한쪽만 있는 경로를
     * <b>세지 않는</b> 이유가 이것이다.
     *
     * <p>⚠️ {@code @ExternalText} 가 아니고 {@code TEXT} 도 아니다 — SHA-256 은 단방향이라
     * 대상 저장소 원문이 복원되지 않고, 경로는 {@link PolicyDocumentPath} 의 우리 상수
     * 목록에서만 온다 (S-4). {@code pendingReason}(V5)·{@code resolutionNote}(V8)와 같은 취급이다.
     */
    @Column(length = MAX_FINGERPRINTS_LENGTH)
    private String documentFingerprints;

    /**
     * 규약 문서를 <b>실제로 다시 읽어 본</b> 시각 — 이슈 #68.
     *
     * <p>🔴 {@link #analyzedAt} 과 <b>다른 것</b>이다. 지문이 같으면 LLM 을 부르지 않으므로
     * {@code analyzedAt} 은 전진하지 않는데, 그때 그것을 갱신하면 「분석했다」가 거짓말이 된다.
     *
     * <p>🔴 <b>응답을 못 받는 경우(5xx·레이트리밋)의 유일한 증거다.</b> 그때는 지문을 구할 수
     * 없어 「바뀌었는가」를 원리적으로 알 수 없고, 대신 이 값이 <b>멈춰 있다</b> —
     * 「모르는 상태가 얼마나 오래됐는가」가 여기서만 보인다.
     */
    private Instant documentsCheckedAt;

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
     * 비교 기준이 되는 지문 — 이슈 #68. 기록이 없으면 {@link PolicyDocumentFingerprints#none()}.
     *
     * <p>「기록이 없다」는 <b>「바뀌지 않았다」가 아니다.</b> 비교가 성립하지 않는다는 뜻이고,
     * 그때는 변경을 주장하지 않는다.
     */
    public PolicyDocumentFingerprints fingerprints() {
        return PolicyDocumentFingerprints.parse(documentFingerprints);
    }

    /**
     * 문서를 확인했고 <b>바뀐 것이 없다</b> — 이슈 #68. 판정을 건드리지 않는다.
     *
     * <p>🔴 {@link #analyzedAt} 을 전진시키지 않는 것이 핵심이다. LLM 을 부르지 않았으므로
     * 「분석했다」고 쓰면 거짓말이고, 그러면 「마지막으로 판정한 때」를 영영 알 수 없게 된다.
     *
     * <p>어떤 상태에서도 부를 수 있다 — 「우리가 봤다」는 사실의 기록이라 안전 성질을
     * 바꾸지 않는다.
     */
    public void markVerified(PolicyDocumentFingerprints prints, Clock clock) {
        recordDocuments(prints, clock);
        this.updatedAt = clock.instant();
    }

    /**
     * 🔴 <b>규약이 바뀐 것을 관측했는데 판정이 서지 않는다</b> — {@code TRUE → NULL} (S-5 · #68).
     *
     * <h2>왜 판정을 유지하지 않나</h2>
     *
     * <p>유지하면 <b>#68 이 지적한 상태에 이름만 붙인 것</b>이다. 아무도 조회하지 않는
     * 플래그 옆에서 Draft PR 은 계속 나간다. 이슈가 「「판정이 안 바뀐다」가 아니라
     * <b>「아무도 모르는 채로 남지 않는다」</b>를 단언한다」고 쓴 것이 이 구분이다.
     *
     * <h2>왜 금지({@code FALSE})가 아니라 보류({@code NULL})인가</h2>
     *
     * <p>{@code FALSE} 는 {@link #resolvePending} 이 409 로, {@link #reanalyze} 가 예외로
     * 거부하는 <b>설계상 되돌릴 수 없는 종단</b>이다. 오탐 하나가 저장소를 영구히 죽인다 —
     * {@code external-deps.md} 가 경계한 「방어가 스스로를 잠그는 구조」 그 자체다.
     * 보류는 사람이 푼다({@code POST /api/repositories/&#123;id&#125;/policy/resolution}, #24).
     *
     * <p>⚠️ #7 이 「일시적 실패로 보류를 만들지 않는다」를 택한 근거는 명시적으로
     * <b>「되돌릴 수단이 없다(#24 미구현)」</b>였다. #24 가 머지되어 그 전제가 바뀌었다.
     * 그렇다고 #7 의 규칙을 뒤집는 것은 아니다 — 일시적 실패는 여전히 아무것도 쓰지 않고,
     * 여기로 오는 것은 <b>바뀐 것을 양성으로 관측한</b> 경우뿐이다.
     *
     * <h2>🔴 {@code resolvedAt} 을 비운다</h2>
     *
     * <p>사람이 허용으로 풀었던 행이 강등되면 그 판단은 <b>새 증거로 무효가 된 것</b>이다.
     * 남겨 두면 {@link #isHumanResolved()} 가 「지금 판정이 사람 것」이라고 거짓말한다 —
     * 안전 판정에 쓰이는 술어라 거짓말을 남길 수 없다.
     *
     * <p>⚠️ 이력은 잃지 않는다 — {@link #resolutionNote} 는 <b>보존</b>한다. #24 가
     * 「해소해도 {@code pendingReason} 을 비우지 않는다」고 정한 것과 대칭이다.
     *
     * @param reason 어느 경로가 왜 그런지. <b>우리 어휘만</b> — 경로 + 사유 코드 (S-4)
     * @throws IllegalStateException 허용이 아닌 상태
     */
    public void markUnverifiable(String reason, PolicyDocumentFingerprints prints, Clock clock) {
        if (isAiContributionForbidden()) {
            // 🔴 금지 → 보류는 게이트를 **푸는** 방향이다. 열면 FR-2 가 자동으로 뚫린다
            throw new IllegalStateException(
                    "AI 기여 금지 판정을 보류로 되돌리지 않는다 (S-5 · FR-2)");
        }
        if (isAiContributionUndetermined()) {
            // 이미 보류다. 덮어쓰면 원래 왜 보류였는지가 사라진다 — pendingReason 의 존재 이유
            throw new IllegalStateException("이미 보류다 — 보류 사유를 덮어쓰지 않는다");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("강등 사유는 필수다 — 사람이 판단할 근거가 없다");
        }
        this.aiContributionAllowed = null;
        this.pendingReason = truncate(reason);
        // 🔴 사람의 판단이 새 증거로 무효가 됐다. note 는 이력으로 남긴다
        this.resolvedAt = null;
        recordDocuments(prints, clock);
        this.updatedAt = clock.instant();
    }

    private void recordDocuments(PolicyDocumentFingerprints prints, Clock clock) {
        if (prints == null) {
            throw new IllegalArgumentException("지문은 필수다 — 다음 비교의 기준이 된다");
        }
        // 🔴 비어 있으면 기록하지 않는다. 빈 문자열을 넣으면 「기록은 했는데 기준이 없다」가
        //    되어 「아직 안 봤다」와 구분되지 않는다
        String serialized = prints.isEmpty() ? null : prints.serialize();
        if (serialized != null && serialized.length() > MAX_FINGERPRINTS_LENGTH) {
            // 🔴 자르지 않는다. 잘린 지문은 다음 비교에서 「달라졌다」로 읽혀 오탐 강등을 만든다.
            //    경로를 늘렸다면 컬럼을 함께 늘리는 것이 맞다 — 조용히 넘어갈 일이 아니다
            throw new IllegalStateException(
                    "지문이 컬럼 상한을 넘었다 — 경로를 늘렸다면 V9 컬럼도 함께 늘린다: "
                            + serialized.length() + " > " + MAX_FINGERPRINTS_LENGTH);
        }
        this.documentFingerprints = serialized;
        this.documentsCheckedAt = clock.instant();
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
        // 🔴 검증을 전부 끝낸 뒤에 쓴다. 한 줄이라도 먼저 대입하면 거부된 해소가
        //    「절반만 적용된 엔티티」를 남긴다 — 여기서는 사유 없는 요청이
        //    aiContributionAllowed=true · resolvedAt=null 을 만들었고, 그것은
        //    「사람이 풀지 않았는데 허용」, 즉 기계 판정으로 위장한 허용이다.
        //    호출자가 예외를 잡고 같은 트랜잭션을 계속하면 dirty checking 이 그대로 flush 한다
        String resolved = truncate(TokenRedactor.redact(requireNote(note)));

        this.aiContributionAllowed = allowed;
        this.resolutionNote = resolved;
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
        return analyzed(repository, reading, PolicyDocumentFingerprints.none(), clock);
    }

    /**
     * 판정과 <b>그 판정이 무엇을 보고 선 것인지</b>를 함께 기록한다 — 이슈 #68.
     *
     * <p>⚠️ 지문 없는 3인자 형태는 <b>비교 기준이 없는 행</b>을 만든다. 지문 기록 이전에
     * 생긴 행과 같은 상태이고, 그런 행에서는 변경을 주장하지 않는다(NFR-4).
     * 운영 경로({@code RepositoryPolicyWriter})는 항상 이쪽을 쓴다.
     */
    public static RepositoryPolicy analyzed(OssRepository repository, RuleReading reading,
            PolicyDocumentFingerprints prints, Clock clock) {
        if (reading == null || reading.isUndetermined()) {
            throw new IllegalArgumentException("판정이 서지 않았다 — pending() 을 쓴다");
        }
        RepositoryPolicy policy = newFor(repository, clock);
        policy.apply(reading, prints, clock);
        return policy;
    }

    /**
     * 보류 — 읽지 못해 판정할 수 없었다.
     *
     * <p>🔴 {@code aiContributionAllowed} 를 <b>건드리지 않는다</b>(= {@code null}).
     * 팩토리를 가르지 않고 setter 를 열면 「보류인데 allowed=true」라는 불법 상태를 만들 수 있다.
     */
    public static RepositoryPolicy pending(OssRepository repository, String reason, Clock clock) {
        return pending(repository, reason, PolicyDocumentFingerprints.none(), clock);
    }

    /** 보류도 <b>무엇을 봤는지</b>는 남긴다 — 다음에 그것이 바뀌었는지 알아야 한다 (#68). */
    public static RepositoryPolicy pending(OssRepository repository, String reason,
            PolicyDocumentFingerprints prints, Clock clock) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("보류 사유는 필수다 — 사람이 판단할 근거가 없다");
        }
        RepositoryPolicy policy = newFor(repository, clock);
        policy.pendingReason = truncate(reason);
        policy.analyzedAt = clock.instant();
        policy.recordDocuments(prints, clock);
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
        reanalyze(reading, PolicyDocumentFingerprints.none(), clock);
    }

    /** 갱신된 판정과 <b>그 근거가 된 지문</b>을 함께 기록한다 — 이슈 #68. */
    public void reanalyze(RuleReading reading, PolicyDocumentFingerprints prints, Clock clock) {
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
        apply(reading, prints, clock);
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

    private void apply(RuleReading reading, PolicyDocumentFingerprints prints, Clock clock) {
        this.aiContributionAllowed = reading.aiContributionAllowed();
        if (commandsOverriddenAt == null) {
            // 🔴 컬럼(255) 을 넘는 값은 「못 읽었다」로 둔다 (#102). 모델이 명령 대신 문장을 주면 저장이
            //    DataIntegrityViolation 으로 죽어 스캔 전체가 FAILED 였다. null 이면 검증기가
            //    「규약에서 명령을 못 읽었다」로 멈추고 사람이 /policy/commands 로 채운다
            this.javaVersion = withinColumn(reading.javaVersion());
            this.buildCommand = withinColumn(reading.buildCommand());
            this.testCommand = withinColumn(reading.testCommand());
        }
        this.issueReferenceRequired = reading.issueReferenceRequired();
        this.signoffRequired = reading.signoffRequired();
        this.testsRequired = reading.testsRequired();
        // 🔴 ScrubbedRules 를 거쳐야만 값이 들어온다 — 원문이 DB 로 새지 않는다 (S-4)
        this.contributionRules = reading.rules().value();
        this.pendingReason = null;
        this.analyzedAt = clock.instant();
        recordDocuments(prints, clock);
        this.updatedAt = clock.instant();
    }

    /**
     * 🔴 사람이 빌드·테스트 명령을 직접 넣는다 — #102 · S-5.
     *
     * <p>규약 문서가 침묵하는 것을 채우는 경로다. 이후 재분석은 이 세 값을 덮어쓰지 않는다
     * ({@link #apply}) — 사람의 판단을 자동이 다시 쓰지 않는다(Q-8 해소와 같은 방향).
     *
     * @param javaVersion  비면 기존 값을 유지한다
     * @param buildCommand 필수
     * @param testCommand  비면 {@code null} — 「규약이 침묵」으로 남긴다
     */
    public void overrideCommands(String javaVersion, String buildCommand, String testCommand,
            Clock clock) {
        if (buildCommand == null || buildCommand.isBlank()) {
            throw new IllegalArgumentException("빌드 명령은 필수다 — 검증의 COMPILE 단계가 이것으로 돈다");
        }
        if (buildCommand.length() > COMMAND_MAX_LENGTH
                || (testCommand != null && testCommand.length() > COMMAND_MAX_LENGTH)
                || (javaVersion != null && javaVersion.length() > COMMAND_MAX_LENGTH)) {
            throw new IllegalArgumentException("명령은 " + COMMAND_MAX_LENGTH + "자를 넘을 수 없다");
        }
        this.buildCommand = buildCommand.trim();
        this.testCommand = testCommand == null || testCommand.isBlank() ? null : testCommand.trim();
        if (javaVersion != null && !javaVersion.isBlank()) {
            this.javaVersion = javaVersion.trim();
        }
        this.commandsOverriddenAt = clock.instant();
        this.updatedAt = clock.instant();
    }

    /** {@code VARCHAR(255)} — 넘으면 「못 읽었다」({@code null}). 명령은 잘라 쓸 수 없다 */
    private static final int COMMAND_MAX_LENGTH = 255;

    private static String withinColumn(String value) {
        return value == null || value.length() > COMMAND_MAX_LENGTH ? null : value;
    }

    /** 컬럼 상한을 넘지 않게 자른다. 사유 문자열은 진단용이라 잘려도 무해하다. */
    private static String truncate(String reason) {
        return reason.length() <= 1024 ? reason : reason.substring(0, 1024);
    }
}
