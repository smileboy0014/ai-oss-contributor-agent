package com.ossagent.candidate.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.FakeLanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmPermanentException;
import com.ossagent.candidate.application.IssueAnalysisProperties;
import com.ossagent.candidate.domain.AnalysisRejectedException;
import com.ossagent.candidate.domain.IssueAnalysis;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 어댑터 매핑 층 — Q-9 의 3계층 중 가운데. <b>요청 조립과 응답 파싱</b>을 본다.
 *
 * <p>🔴 이 클래스의 절반은 「나쁜 응답을 성공으로 읽지 않는가」다 (FR-2).
 * 제품의 품질 축이 「좋은 코드를 쓰는가」가 아니라 <b>「나쁜 결과를 걸러내는가」</b>이기 때문이다.
 */
class LlmIssueAnalystTest {

    private static final long CANDIDATE_ID = 7L;

    private final FakeLanguageModel languageModel = new FakeLanguageModel();
    private final LlmIssueAnalyst analyst = new LlmIssueAnalyst(
            languageModel, IssueAnalysisProperties.defaults(), new ObjectMapper());

    @BeforeEach
    void resetFake() {
        languageModel.reset();
    }

    private static String validJson() {
        return """
                {"category":"bug","difficulty":"MEDIUM","implementationFeasible":true,
                 "estimatedFiles":4,"estimatedLoc":120,"testRequired":true,
                 "breakingChange":false,"confidence":0.87,"summary":"재현 조건이 명확하다"}
                """;
    }

    private static AnalyzableIssue issue() {
        return issue(FilterOutcome.PASSED, "NPE 가 발생한다");
    }

    private static AnalyzableIssue issue(FilterOutcome outcome, String body) {
        return new AnalyzableIssue(1L, 100L, 42, "NPE in consumer", body,
                List.of("bug", "good first issue"), "https://example.invalid/1", outcome,
                (short) 10);
    }

    @Test
    @DisplayName("정상 응답을 값으로 옮긴다")
    void 정상_응답을_파싱한다() {
        languageModel.respondWith(validJson(), 1200, 300);

        IssueAnalysis analysis = analyst.analyze(CANDIDATE_ID, issue());

        assertThat(analysis.category()).isEqualTo("bug");
        assertThat(analysis.difficulty()).isEqualTo(IssueAnalysis.Difficulty.MEDIUM);
        assertThat(analysis.implementationFeasible()).isTrue();
        assertThat(analysis.estimatedFiles()).isEqualTo(4);
        assertThat(analysis.confidence()).isEqualByComparingTo("0.87");
    }

    @Test
    @DisplayName("🔴 ANALYZE 로 · attempt 는 항상 1 로 호출한다 — Q-6")
    void 호출_컨텍스트가_ANALYZE_attempt1_이다() {
        languageModel.respondWith(validJson(), 1, 1);

        analyst.analyze(CANDIDATE_ID, issue());

        var ctx = languageModel.calls().getFirst().ctx();
        assertThat(ctx.callSite()).isEqualTo(LlmCallSite.ANALYZE);
        assertThat(ctx.candidateId()).isEqualTo(CANDIDATE_ID);
        assertThat(ctx.attempt())
                .as("ANALYZE 는 파이프라인 재시도 카운터 밖이다 — 올라가면 비용 집계가 흔들린다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("코드펜스로 감싼 응답도 읽는다 — 그것 때문에 FAILED 로 떨어뜨리지 않는다")
    void 코드펜스를_벗긴다() {
        languageModel.respondWith("```json\n" + validJson() + "\n```", 1, 1);

        assertThat(analyst.analyze(CANDIDATE_ID, issue()).category()).isEqualTo("bug");
    }

    @Test
    @DisplayName("JSON 이 아니면 거부한다 — 파싱 실패를 성공으로 처리하지 않는다")
    void JSON_이_아니면_거부한다() {
        languageModel.respondWith("분석 결과를 알려드리겠습니다. 이 이슈는...", 1, 1);

        assertThatThrownBy(() -> analyst.analyze(CANDIDATE_ID, issue()))
                .isInstanceOf(AnalysisRejectedException.class);
    }

    @Test
    @DisplayName("JSON 배열은 거부한다")
    void 객체가_아니면_거부한다() {
        languageModel.respondWith("[1,2,3]", 1, 1);

        assertThatThrownBy(() -> analyst.analyze(CANDIDATE_ID, issue()))
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("객체");
    }

    @Test
    @DisplayName("🔴 모르는 difficulty 를 기본값으로 떨어뜨리지 않는다")
    void 모르는_difficulty_는_거부한다() {
        languageModel.respondWith(validJson().replace("\"MEDIUM\"", "\"보통\""), 1, 1);

        assertThatThrownBy(() -> analyst.analyze(CANDIDATE_ID, issue()))
                .as("MEDIUM 으로 떨어뜨리면 모델의 실패가 「보통 난이도 후보」로 둔갑한다")
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("difficulty");
    }

    @Test
    @DisplayName("🔴 implementationFeasible 누락이 false 로 둔갑하지 않는다")
    void boolean_누락은_false_가_아니라_거부다() {
        languageModel.respondWith(
                validJson().replace("\"implementationFeasible\":true,", ""), 1, 1);

        assertThatThrownBy(() -> analyst.analyze(CANDIDATE_ID, issue()))
                .as("asBoolean(false) 로 읽으면 누락이 「구현 불가」가 되어 조용히 REJECTED 된다")
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("implementationFeasible");
    }

    @Test
    @DisplayName("confidence 가 문자열이면 거부한다")
    void confidence_가_숫자가_아니면_거부한다() {
        languageModel.respondWith(validJson().replace("0.87", "\"높음\""), 1, 1);

        assertThatThrownBy(() -> analyst.analyze(CANDIDATE_ID, issue()))
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    @DisplayName("범위 밖 confidence 는 값 타입이 거부한다 — 검증이 한 곳에 있다")
    void 범위_밖_confidence_는_거부한다() {
        languageModel.respondWith(validJson().replace("0.87", "1.4"), 1, 1);

        assertThatThrownBy(() -> analyst.analyze(CANDIDATE_ID, issue()))
                .isInstanceOf(AnalysisRejectedException.class);
    }

    @Test
    @DisplayName("estimatedFiles 가 소수면 거부한다")
    void 정수가_아니면_거부한다() {
        languageModel.respondWith(validJson().replace("\"estimatedFiles\":4", "\"estimatedFiles\":4.5"), 1, 1);

        assertThatThrownBy(() -> analyst.analyze(CANDIDATE_ID, issue()))
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("estimatedFiles");
    }

    @Test
    @DisplayName("⚠ LlmException 은 잡지 않고 전파한다 — 잘린 JSON 을 파서에 넣지 않는다")
    void 호출_실패는_전파한다() {
        languageModel.failWith(
                new LlmPermanentException(LlmFailureReason.TRUNCATED, LlmCallSite.ANALYZE));

        assertThatThrownBy(() -> analyst.analyze(CANDIDATE_ID, issue()))
                .as("절단은 전송 실패가 아니라 출력이 예산을 넘은 것이다 — 재전송해도 같은 곳에서 잘린다")
                .isInstanceOf(LlmPermanentException.class);
    }

    @Test
    @DisplayName("candidateId 없이 부를 수 없다 — 비용 기록을 붙일 대상이 없다")
    void candidateId_는_필수다() {
        assertThatThrownBy(() -> analyst.analyze(null, issue()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⚠ 본문을 자르면 잘랐다고 프롬프트에 적는다")
    void 절단_사실을_모델에게_알린다() {
        IssueAnalysisProperties tiny = new IssueAnalysisProperties(50, 20, 1500, 10, null);
        LlmIssueAnalyst shortAnalyst =
                new LlmIssueAnalyst(languageModel, tiny, new ObjectMapper());
        languageModel.respondWith(validJson(), 1, 1);

        shortAnalyst.analyze(CANDIDATE_ID, issue(FilterOutcome.PASSED, "가".repeat(200)));

        String prompt = languageModel.calls().getFirst().request().userPrompt();
        assertThat(prompt)
                .as("말하지 않으면 모델이 전문을 봤다고 전제하고 confidence 를 높게 준다")
                .contains("잘렸다");
        assertThat(prompt.length())
                .as("절단이 실제로 일어나야 한다")
                .isLessThan(200);
    }

    @Test
    @DisplayName("UNDECIDED 라는 사실을 프롬프트가 모델에게 전달한다")
    void UNDECIDED_사실을_전달한다() {
        languageModel.respondWith(validJson(), 1, 1);

        analyst.analyze(CANDIDATE_ID, issue(FilterOutcome.UNDECIDED, "짧다"));

        assertThat(languageModel.calls().getFirst().request().userPrompt())
                .as("규칙이 가르지 못했다는 것 자체가 하류에 전달돼야 할 정보다 — FilterOutcome javadoc")
                .contains("규칙 필터가 이 이슈를 판정하지 못했다");
    }

    @Test
    @DisplayName("PASSED 이슈에는 보류 문구를 붙이지 않는다")
    void PASSED_에는_보류_문구가_없다() {
        languageModel.respondWith(validJson(), 1, 1);

        analyst.analyze(CANDIDATE_ID, issue());

        assertThat(languageModel.calls().getFirst().request().userPrompt())
                .doesNotContain("판정하지 못했다");
    }

    @Test
    @DisplayName("maxOutputTokens 는 분석 전용 설정을 쓴다 — agent.llm 의 16000 이 아니다")
    void 출력_상한은_분석_설정을_따른다() {
        languageModel.respondWith(validJson(), 1, 1);

        analyst.analyze(CANDIDATE_ID, issue());

        assertThat(languageModel.calls().getFirst().request().maxOutputTokens())
                .isEqualTo(IssueAnalysisProperties.defaults().maxOutputTokens());
    }
}
