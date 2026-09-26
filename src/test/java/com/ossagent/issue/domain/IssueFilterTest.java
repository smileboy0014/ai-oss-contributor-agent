package com.ossagent.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 규칙 필터의 <b>판정</b>을 고정한다 — #9.
 *
 * <p>여기서 지키는 것은 「어떤 구현이냐」가 아니라 다음 네 가지다.
 * <ul>
 *   <li>{@code PASSED} 가 <b>도달 가능한가</b> — 도달 불가능하면 하류가 보류를 통과로 뭉갠다</li>
 *   <li>확정 배제와 <b>LLM 인계</b>가 갈리는가 — 완료조건 3</li>
 *   <li>사유가 <b>전부</b> 남는가 — FR-2 의 분포</li>
 *   <li>사유에 <b>이슈 본문이 섞이지 않는가</b> — S-4</li>
 * </ul>
 */
class IssueFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final int MIN_BODY = 200;
    private static final int MAX_COMMENTS = 30;

    private final IssueFilter filter = IssueFilter.of(MIN_BODY, MAX_COMMENTS);

    // ─────────────────────────────────────────────────────────
    // 해피패스 — 이것이 없으면 나머지가 무의미하다
    // ─────────────────────────────────────────────────────────

    @Test
    void 모든_규칙을_통과하면_PASSED_다() {
        Issue issue = issue(longBody(), List.of("good first issue"), 3);

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.outcome())
                .as("PASSED 가 나오지 않으면 도달 불가능한 판정이 있다는 뜻이고, "
                        + "하류(#11)는 UNDECIDED 를 통과로 취급할 수밖에 없다")
                .isEqualTo(FilterOutcome.PASSED);
        assertThat(verdict.reasons()).isEmpty();
        assertThat(verdict.excluded()).isFalse();
    }

    // ─────────────────────────────────────────────────────────
    // 규칙 ① 종료됨 — 구현돼 있으나 현재는 발화하지 않는다
    // ─────────────────────────────────────────────────────────

    @Test
    void 종료된_이슈는_배제한다() {
        Issue issue = issue(longBody(), List.of(), 0);
        // 🔴 생산 경로로는 closed 이슈를 만들 수 없다 — 수집이 state=open 고정이다.
        //    그 공백 자체가 아래 테스트의 대상이고, 여기서는 규칙만 따로 고정한다
        ReflectionTestUtils.setField(issue, "state", "closed");

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.outcome()).isEqualTo(FilterOutcome.REJECTED);
        assertThat(verdict.reasons()).contains(FilterReason.CLOSED);
    }

    @Test
    void 수집_데이터가_항상_open_이라_종료됨_규칙은_현재_발화하지_않는다() {
        Issue issue = issue(longBody(), List.of(), 0);

        assertThat(issue.getState())
                .as("""
                        수집 쿼리가 state=open 고정이라 한 번 저장된 이슈는 영원히 open 이다 (#8).
                        「종료됨」 규칙은 구현돼 있으나 실제로는 아무것도 배제하지 않는다.
                        이 테스트가 깨지면 #14 가 데이터를 고쳤다는 뜻이고, 그때 규칙이 발화한다.""")
                .isEqualTo("open");
        assertThat(filter.evaluate(issue).reasons()).doesNotContain(FilterReason.CLOSED);
    }

    // ─────────────────────────────────────────────────────────
    // 규칙 ③ 불명확 — 확정 배제와 LLM 인계를 가른다
    // ─────────────────────────────────────────────────────────

    @Test
    void 본문이_없으면_배제한다() {
        Issue issue = issue(null, List.of(), 0);

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.outcome()).isEqualTo(FilterOutcome.REJECTED);
        assertThat(verdict.reasons()).containsExactly(FilterReason.EMPTY_BODY);
    }

    @Test
    void 본문이_공백뿐이면_배제한다() {
        Issue issue = issue("  \n\t  \n ", List.of(), 0);

        assertThat(filter.evaluate(issue).reasons())
                .as("마크다운 이슈 템플릿은 빈 줄이 많다. 개행 하나로 빈 본문 배제가 우회되면 안 된다")
                .containsExactly(FilterReason.EMPTY_BODY);
    }

    @Test
    void 본문이_짧으면_배제가_아니라_보류다() {
        Issue issue = issue("NPE at Foo.java:12 — https://github.com/x/y/issues/1", List.of(), 0);

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.outcome())
                .as("짧다고 불명확한 것이 아니다 — 스택트레이스 링크 한 줄짜리 명확한 버그 리포트가 있다. "
                        + "확정 배제로 두면 그런 이슈를 전부 잃는다")
                .isEqualTo(FilterOutcome.UNDECIDED);
        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.reasons()).containsExactly(FilterReason.SHORT_BODY);
    }

    @Test
    void 코멘트가_많으면_배제가_아니라_보류다() {
        Issue issue = issue(longBody(), List.of(), MAX_COMMENTS + 1);

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.outcome())
                .as("논의가 길면 요구가 불명확할 확률이 높지만 인과가 아니다 — 활발한 논의일 수도 있다")
                .isEqualTo(FilterOutcome.UNDECIDED);
        assertThat(verdict.reasons()).containsExactly(FilterReason.HEAVY_DISCUSSION);
    }

    @Test
    void 코멘트_수가_경계값이면_걸리지_않는다() {
        Issue issue = issue(longBody(), List.of(), MAX_COMMENTS);

        assertThat(filter.evaluate(issue).outcome()).isEqualTo(FilterOutcome.PASSED);
    }

    // ─────────────────────────────────────────────────────────
    // 규칙 ④ 대규모 — 라벨만 본다
    // ─────────────────────────────────────────────────────────

    @Test
    void breaking_라벨은_배제한다() {
        Issue issue = issue(longBody(), List.of("breaking-change"), 0);

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.outcome()).isEqualTo(FilterOutcome.REJECTED);
        assertThat(verdict.reasons()).containsExactly(FilterReason.BREAKING_CHANGE);
    }

    @Test
    void 본문에_refactor_가_있다고_배제하지_않는다() {
        Issue issue = issue("This is not a refactor, it is a redesign of the epic architecture. "
                + longBody(), List.of(), 0);

        assertThat(filter.evaluate(issue).outcome())
                .as("본문 키워드는 오탐이 크다 — 「이건 리팩토링이 아니라 버그입니다」에도 걸린다. "
                        + "라벨은 메인테이너가 붙인 명시적 신호라 신뢰도가 다르다")
                .isEqualTo(FilterOutcome.PASSED);
    }

    // ─────────────────────────────────────────────────────────
    // 라벨 매칭 — 규칙 ④ 의 유일한 신호이자 FR-4 의 입력
    // ─────────────────────────────────────────────────────────

    @Test
    void 라벨의_대소문자와_구분자_변이를_같게_본다() {
        assertThat(issue(longBody(), List.of("Breaking Change"), 0).hasLabel("breaking-change")).isTrue();
        assertThat(issue(longBody(), List.of("BREAKING_CHANGE"), 0).hasLabel("breaking-change")).isTrue();
        assertThat(issue(longBody(), List.of("type: enhancement"), 0).hasLabel("enhancement")).isTrue();
        assertThat(issue(longBody(), List.of("enhancement-request"), 0).hasLabel("enhancement")).isFalse();
    }

    @Test
    void 영역_접두는_걷어내지_않는다() {
        Issue areaLabeled = issue(longBody(), List.of("area: design"), 0);

        assertThat(areaLabeled.hasLabel("design"))
                .as("area:·component: 는 작업 규모가 아니라 영역을 가리킨다. 접두를 전부 걷어내면 "
                        + "「아키텍처 영역의 버그」가 대규모 변경으로 오인돼 배제되는데, "
                        + "배제의 대가는 되돌릴 수 없다")
                .isFalse();
        assertThat(filter.evaluate(areaLabeled).outcome()).isEqualTo(FilterOutcome.PASSED);
    }

    // ─────────────────────────────────────────────────────────
    // 우선순위 — 배제 규칙이 아니다 (FR-4)
    // ─────────────────────────────────────────────────────────

    @Test
    void 우선순위_라벨이_없어도_배제하지_않는다() {
        Issue issue = issue(longBody(), List.of(), 0);

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.outcome())
                .as("대부분의 이슈에 우선순위 라벨이 없다. 배제로 쓰면 Phase 1 대상이 거의 다 떨어진다")
                .isEqualTo(FilterOutcome.PASSED);
        assertThat(verdict.priority()).isEqualTo(LabelPriority.NONE);
    }

    @Test
    void 우선순위는_가장_높은_라벨_하나로_정해진다() {
        Issue many = issue(longBody(), List.of("documentation", "bug", "good first issue"), 0);
        Issue one = issue(longBody(), List.of("good first issue"), 0);

        assertThat(filter.evaluate(many).priority())
                .as("합산하면 라벨을 많이 붙이는 저장소가 구조적으로 앞서고, "
                        + "점수의 뜻이 「얼마나 쉬운가」에서 「라벨이 몇 개냐」로 바뀐다")
                .isEqualTo(filter.evaluate(one).priority());
    }

    @Test
    void 우선순위가_SMALLINT_상한을_넘으면_거부한다() {
        Issue issue = issue(longBody(), List.of(), 0);
        FilterVerdict tooHigh =
                new FilterVerdict(FilterOutcome.PASSED, List.of(), Short.MAX_VALUE + 1);

        assertThatThrownBy(() -> issue.applyFilter(tooHigh, NOW))
                .as("컬럼이 SMALLINT 다. 좁히는 캐스팅은 조용히 음수로 뒤집혀 "
                        + "「가장 높은 우선순위」가 맨 뒤로 정렬된다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 배제된_이슈도_우선순위를_계산한다() {
        Issue issue = issue(null, List.of("good first issue"), 0);

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.excluded()).isTrue();
        assertThat(verdict.priority()).isGreaterThan(LabelPriority.NONE);
    }

    // ─────────────────────────────────────────────────────────
    // 집계 — 정의하지 않으면 위가 전부 무의미하다
    // ─────────────────────────────────────────────────────────

    @Test
    void 배제가_보류보다_우선한다() {
        Issue issue = issue("짧다", List.of("breaking-change"), MAX_COMMENTS + 1);

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.outcome()).isEqualTo(FilterOutcome.REJECTED);
    }

    @Test
    void 모든_규칙을_평가해_사유를_전부_수집한다() {
        Issue issue = issue("짧다", List.of("breaking-change", "epic"), MAX_COMMENTS + 1);

        FilterVerdict verdict = filter.evaluate(issue);

        assertThat(verdict.reasons())
                .as("""
                        첫 매치에서 끊으면 사유 분포가 규칙 평가 순서의 함수가 된다.
                        그러면 FR-2 가 노린 「분포를 보고 규칙을 고친다」가 불가능해진다.""")
                .containsExactly(
                        FilterReason.BREAKING_CHANGE,
                        FilterReason.LARGE_SCOPE,
                        FilterReason.SHORT_BODY,
                        FilterReason.HEAVY_DISCUSSION);
    }

    @Test
    void 사유_순서는_입력_순서가_아니라_선언_순서다() {
        Issue a = issue("짧다", List.of("epic", "breaking-change"), 0);
        Issue b = issue("짧다", List.of("breaking-change", "epic"), 0);

        assertThat(filter.evaluate(a).reasonCodes())
                .as("같은 입력이 같은 문자열로 저장돼야 사유 분포를 집계할 수 있다")
                .isEqualTo(filter.evaluate(b).reasonCodes());
    }

    @Test
    void 배제와_보류에는_사유가_반드시_붙는다() {
        assertThatThrownBy(() -> new FilterVerdict(FilterOutcome.REJECTED, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FilterVerdict(FilterOutcome.UNDECIDED, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> new FilterVerdict(FilterOutcome.PASSED, List.of(FilterReason.CLOSED), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 규칙이_하나도_없는_필터는_만들_수_없다() {
        assertThatThrownBy(() -> new IssueFilter(List.of()))
                .as("규칙이 없으면 전건 PASSED 다 — 「필터를 돌렸다」는 사실만 남고 아무것도 걸러지지 않는다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────────────────────────────────────────
    // S-4 · 결정성
    // ─────────────────────────────────────────────────────────

    @Test
    void 판정_사유에_이슈_본문이_들어가지_않는다_S4() {
        // 🔴 리터럴로 쓰지 않는다 — secret-scan.sh 가 소스에서 그 패턴을 찾는다
        String secretish = "token is " + "ghp_" + "A".repeat(36) + " " + longBody();
        Issue issue = issue(secretish, List.of("breaking-change"), 0);

        FilterVerdict verdict = filter.evaluate(issue);
        issue.applyFilter(verdict, NOW);

        assertThat(issue.getFilterReason())
                .as("""
                        사유는 「왜 떨어졌나」의 코드이지 증거 인용이 아니다.
                        본문 발췌가 들어가면 대상 저장소 사용자가 쓴 임의 텍스트가 우리 DB 를 거쳐
                        LLM 프롬프트·PR 본문으로 흘러간다 — S-4.""")
                .isEqualTo("BREAKING_CHANGE")
                .doesNotContain("ghp_", "token");
        assertThat(issue.filterReasons()).containsExactly(FilterReason.BREAKING_CHANGE);
    }

    @Test
    void 같은_입력에_같은_판정이_나온다() {
        Issue issue = issue("짧다", List.of("bug"), MAX_COMMENTS + 1);

        assertThat(filter.evaluate(issue)).isEqualTo(filter.evaluate(issue));
    }

    @Test
    void 판정을_적재하면_미판정_상태가_풀린다() {
        Issue issue = issue(longBody(), List.of("bug"), 0);
        assertThat(issue.needsFilterJudgment()).isTrue();

        issue.applyFilter(filter.evaluate(issue), NOW);

        assertThat(issue.needsFilterJudgment()).isFalse();
        assertThat(issue.getFilterResult()).isEqualTo("PASSED");
        assertThat(issue.getFilterReason()).isNull();
        assertThat(issue.getFilterJudgedAt()).isEqualTo(NOW);
        assertThat(issue.getFilterPriority()).isEqualTo((short) 60);
    }

    @Test
    void 내용이_갱신되면_다시_판정_대상이_된다() {
        Issue issue = issue(longBody(), List.of("bug"), 0);
        issue.applyFilter(filter.evaluate(issue), NOW);

        issue.updateFrom(snapshot("새 본문 " + longBody(), List.of("bug"), 0,
                NOW.plusSeconds(60)), NOW.plusSeconds(60));

        assertThat(issue.needsFilterJudgment())
                .as("판정 근거가 달라졌는데 낡은 판정을 남기면 바뀐 이슈를 다시 보지 않는다")
                .isTrue();
        assertThat(issue.getFilterPriority())
                .as("점수는 라벨에서 나오고 라벨은 내용과 함께 바뀐다 — 남겨 두면 옛 기준으로 정렬된다")
                .isNull();
    }

    // ─────────────────────────────────────────────────────────

    private static Issue issue(String body, List<String> labels, int comments) {
        return Issue.fromSnapshot(1L, "spring-projects", "spring-kafka",
                snapshot(body, labels, comments, NOW), NOW);
    }

    private static IssueSnapshot snapshot(String body, List<String> labels, int comments,
            Instant updatedAt) {
        return new IssueSnapshot(1, "제목", body, labels, "someone", comments, NOW, updatedAt, false);
    }

    /** {@code min-body-length} 를 확실히 넘는 본문. 경계 검증은 별도 테스트가 한다. */
    private static String longBody() {
        return "본문".repeat(MIN_BODY);
    }
}
