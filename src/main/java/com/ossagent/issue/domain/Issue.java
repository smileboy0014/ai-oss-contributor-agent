package com.ossagent.issue.domain;

import com.ossagent.support.ExternalText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
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

    /**
     * 코멘트 수 — 규칙 ③ 의 보류 신호이자 #11 의 LLM 판정 입력 (#9).
     *
     * <p>⚠ 이 값으로 <b>배제하지 않는다.</b> 논의가 길면 요구가 불명확할 확률이 높지만
     * 인과가 아니다 — 활발한 논의일 수도 있다.
     */
    private Integer commentCount;

    /**
     * 규칙 필터 판정 — {@link FilterOutcome} 의 이름. 없으면 <b>아직 판정하지 않았다</b>.
     *
     * <p>저장 어휘는 4가지다 — {@code NULL}(미판정) · {@code PASSED} · {@code REJECTED}
     * · {@code UNDECIDED}. {@code UNDECIDED} 는 배제가 아니다.
     */
    private String filterResult;

    /**
     * 판정 사유 — {@link FilterReason} 이름을 콤마로 이은 것. 통과면 {@code null} 이다.
     *
     * <p>🔴 <b>자유 텍스트가 들어가지 않는다</b> (S-4). 이슈 본문 발췌를 넣으면 대상
     * 저장소 사용자가 쓴 임의 텍스트가 우리 DB 를 거쳐 LLM 프롬프트·PR 본문으로 흘러간다 —
     * #7 의 {@code contribution_rules} 가 정확히 그랬다. 사유는 「왜 떨어졌나」의
     * <b>코드</b>이지 증거 인용이 아니므로, 값이 우리 어휘가 되어
     * {@code @ExternalText} 를 뗐고 컬럼도 {@code VARCHAR} 로 내렸다(V7).
     *
     * <p>걸린 사유를 <b>전부</b> 담는다. 대표 하나만 남기면 저장된 사유 분포가 규칙
     * 순서에 편향돼 FR-2 가 노린 「분포를 보고 규칙을 고친다」가 불가능해진다.
     */
    @Column(length = 512)
    private String filterReason;

    /** 라벨 우선순위 점수 (FR-4). #11 이 분석 순서를 <b>SQL 로</b> 정렬할 때 쓴다. */
    private Short filterPriority;

    /**
     * 판정이 언제 섰나. {@link #updatedAt} 과 <b>뜻이 다르다</b> — 저쪽은 재수집 때마다 움직인다.
     *
     * <p>규칙·임계를 바꿔도 기존 판정은 자동으로 무효화되지 않는다(알려진 한계).
     * 그때 이 값으로 대상을 고른다.
     */
    private Instant filterJudgedAt;

    private Instant githubCreatedAt;

    /** <b>증분 수집 커서.</b> 매 스캔 전량 조회는 레이트리밋을 태운다. */
    private Instant githubUpdatedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** 수집되는 것은 전부 open 이다 — 조회가 {@code state=open} 고정이기 때문이다. */
    private static final String STATE_OPEN = "open";

    /** 라벨 저장 구분자. {@code labelList()} 의 한계가 여기서 나온다. */
    private static final String LABEL_DELIMITER = ",";

    /** 라벨 표기 변이 — 하이픈·언더스코어·공백을 같게 본다. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s_-]+");

    /**
     * 🔴 걷어내도 되는 분류 접두 — <b>화이트리스트다.</b>
     *
     * <p>모든 {@code 접두:} 를 걷어내면 배제 규칙의 사정거리가 넓어진다.
     * {@code area: architecture} · {@code component: design} 은 작업 <b>규모</b>가 아니라
     * <b>영역</b>을 가리키는 흔한 관례인데, 접두를 지우면 대규모 변경으로 오인돼
     * 이슈가 배제된다. 오탐의 대가가 되돌릴 수 없는 배제라는 점에서
     * {@code LargeChangeRule} 이 본문 키워드를 거부한 것과 같은 판단이다.
     */
    private static final List<String> CLASSIFYING_PREFIXES = List.of("type", "kind", "category");

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
            clearFilterVerdict();
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
        this.labels = String.join(LABEL_DELIMITER, snapshot.labels());
        this.commentCount = snapshot.commentCount();
        this.githubCreatedAt = snapshot.createdAt();
        this.githubUpdatedAt = snapshot.updatedAt();
        this.updatedAt = now;
    }

    // ─────────────────────────────────────────────────────────
    // 규칙 필터 — #9
    // ─────────────────────────────────────────────────────────

    /**
     * 규칙 필터 판정을 적재한다 — #9.
     *
     * <p>🔴 <b>배제 여부를 여기서 다시 계산하지 않는다.</b> 집계는
     * {@link FilterVerdict} 가 이미 했고, 엔티티가 한 번 더 판단하면 진실이 둘이 된다.
     *
     * <p>{@code now} 를 받는 이유 — {@code Instant.now()} 를 엔티티가 직접 부르면
     * 판정 시각을 테스트로 고정할 수 없다. 이 클래스의 다른 메서드와 같은 형태다
     * ({@code Clock} 주입은 호출자인 application 의 몫이다).
     */
    public void applyFilter(FilterVerdict verdict, Instant now) {
        if (verdict == null) {
            throw new IllegalArgumentException("판정은 필수다");
        }
        if (verdict.priority() > Short.MAX_VALUE) {
            // 🔴 컬럼이 SMALLINT 다. 좁히는 캐스팅은 조용히 음수로 뒤집힌다 —
            //    점수가 커지면 「가장 높은 우선순위」가 맨 뒤로 정렬되는 식으로 틀린다
            throw new IllegalArgumentException(
                    "우선순위 점수가 SMALLINT 상한을 넘었다: " + verdict.priority());
        }
        this.filterResult = verdict.outcome().name();
        this.filterReason = verdict.reasonCodes();
        this.filterPriority = (short) verdict.priority();
        this.filterJudgedAt = now;
        this.updatedAt = now;
    }

    /** 아직 판정되지 않았는가. 재수집으로 내용이 바뀌면 다시 {@code true} 가 된다. */
    public boolean needsFilterJudgment() {
        return filterResult == null;
    }

    /** 저장된 사유를 되읽는다. 모르는 코드는 버려진다 — {@link FilterVerdict#parseReasons}. */
    public List<FilterReason> filterReasons() {
        return FilterVerdict.parseReasons(filterReason);
    }

    /**
     * 판정을 지운다 — 다음 필터 실행이 다시 본다.
     *
     * <p>{@code filterPriority} 까지 지우는 이유 — 점수는 라벨에서 나오고 라벨은 내용과
     * 함께 바뀐다. 남겨 두면 옛 라벨 기준 점수로 정렬된다.
     */
    private void clearFilterVerdict() {
        this.filterResult = null;
        this.filterReason = null;
        this.filterPriority = null;
        this.filterJudgedAt = null;
    }

    // ─────────────────────────────────────────────────────────
    // 라벨 — 규칙 ④ 의 유일한 신호이자 FR-4 의 입력
    // ─────────────────────────────────────────────────────────

    /**
     * 라벨 목록.
     *
     * <p>⚠ <b>알려진 한계.</b> 저장이 콤마 조인이라 라벨 이름 자체에 콤마가 있으면
     * 쪼개진다. GitHub 이 허용하는 문자이긴 하나 실제로는 드물고, 고치려면 저장 형식을
     * 바꿔야 한다(#8 계약 · 마이그레이션). 지금은 사실만 남긴다.
     */
    public List<String> labelList() {
        if (labels == null || labels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(labels.split(LABEL_DELIMITER))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .toList();
    }

    /**
     * 라벨을 갖는가. <b>표기 변이를 흡수한다.</b>
     *
     * <table border="1">
     *   <caption>같게 보는 것</caption>
     *   <tr><td>대소문자</td><td>{@code Bug} = {@code bug}</td></tr>
     *   <tr><td>구분자</td><td>{@code breaking change} = {@code breaking-change} = {@code breaking_change}</td></tr>
     *   <tr><td><b>분류</b> 접두</td><td>{@code type: enhancement} = {@code enhancement}</td></tr>
     *   <tr><td><b>영역</b> 접두</td><td>{@code area: architecture} ≠ {@code architecture} — 아래</td></tr>
     * </table>
     *
     * <p>🔴 접두를 <b>전부</b> 걷어내지 않는다. {@code area:}·{@code component:} 는 작업
     * 규모가 아니라 영역을 가리키므로, 지우면 {@code area: architecture} 가 대규모 변경으로
     * 오인돼 배제된다 — {@link #CLASSIFYING_PREFIXES} 만 걷어낸다.
     *
     * <p>저장소마다 라벨 표기 관례가 다른데 라벨은 규칙 ④ 의 <b>유일한 신호</b>다.
     * 여기가 약하면 배제 규칙과 우선순위가 같이 약해진다.
     *
     * <p>⚠ {@code IssueSnapshot.hasLabel} 은 {@code equalsIgnoreCase} 뿐이라 더 좁다.
     * 그쪽은 수집 단계의 원본 판정이고 이쪽은 필터 판정이라 요구가 다르다.
     */
    public boolean hasLabel(String label) {
        if (label == null || label.isBlank()) {
            return false;
        }
        String target = normalizeLabel(label);
        return labelList().stream().anyMatch(it -> normalizeLabel(it).equals(target));
    }

    /** 대소문자·구분자·<b>분류</b> 접두를 걷어낸 비교용 형태. */
    private static String normalizeLabel(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        if (colon > 0 && colon < value.length() - 1
                && CLASSIFYING_PREFIXES.contains(value.substring(0, colon).trim())) {
            value = value.substring(colon + 1);
        }
        return SEPARATORS.matcher(value).replaceAll(" ").trim();
    }

    private static String issueUrl(String owner, String name, int number) {
        return "https://github.com/%s/%s/issues/%d".formatted(owner, name, number);
    }
}
