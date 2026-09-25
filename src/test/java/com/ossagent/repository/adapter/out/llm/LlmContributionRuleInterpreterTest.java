package com.ossagent.repository.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.FakeLanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmPermanentException;
import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.agent.domain.LlmUsage;
import com.ossagent.repository.domain.FetchedDocument;
import com.ossagent.repository.domain.PolicyDocumentPath;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryDocuments;
import com.ossagent.repository.domain.RuleReading;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 모델 응답 → 판정 번역 — S-5 · S-4.
 *
 * <p>가장 중요한 것은 <b>못 믿을 응답이 「허용」으로 읽히지 않는다</b>는 것이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LlmContributionRuleInterpreterTest {

    private static final RepositoryCoordinates REPO =
            new RepositoryCoordinates("spring-projects", "spring-kafka");

    private static RepositoryDocuments docsWith(String content) {
        return new RepositoryDocuments(List.of(FetchedDocument.read(
                new PolicyDocumentPath("CONTRIBUTING.md", PolicyDocumentPath.Role.REQUIRED),
                content)));
    }

    private static LlmContributionRuleInterpreter interpreterFor(FakeLanguageModel model) {
        return new LlmContributionRuleInterpreter(
                model, PolicyAnalysisProperties.defaults(), new ObjectMapper());
    }

    private static String json(String aiContribution) {
        return """
                {"aiContribution":"%s","evidence":"","javaVersion":"17",
                 "buildCommand":"./gradlew build","testCommand":"./gradlew test",
                 "issueReferenceRequired":true,"signoffRequired":true,"testsRequired":true,
                 "prTemplateSummary":null}
                """.formatted(aiContribution);
    }

    @Test
    void 금지_문구를_찾으면_금지로_판정한다_S5() {
        var model = new FakeLanguageModel().respondWith(json("FORBIDDEN"), 10, 10);

        RuleReading reading = interpreterFor(model).interpret(REPO, docsWith("AI 기여 금지"));

        assertThat(reading.isForbidden()).isTrue();
    }

    @Test
    void 언급이_없으면_허용이다_S5() {
        var model = new FakeLanguageModel().respondWith(json("NOT_MENTIONED"), 10, 10);

        RuleReading reading = interpreterFor(model).interpret(REPO, docsWith("기여 방법"));

        assertThat(reading.aiContributionAllowed())
                .as("Q-8 확정 ① — 침묵은 허용이다. 「명시적 허용이 있어야 허용」으로 두면 "
                        + "Phase 1 대상이 즉시 보류돼 파이프라인이 한 번도 안 돈다")
                .isTrue();
        assertThat(reading.javaVersion()).isEqualTo("17");
        assertThat(reading.signoffRequired()).isTrue();
    }

    @Test
    void 불명확은_보류다_S5() {
        var model = new FakeLanguageModel().respondWith(json("UNCLEAR"), 10, 10);

        assertThat(interpreterFor(model).interpret(REPO, docsWith("애매한 문장")).isUndetermined())
                .as("「언급 없음」과 「불명확」이 갈리는 것이 Q-8 의 선이다")
                .isTrue();
    }

    @Test
    void 알_수_없는_값은_보류다_S5() {
        var model = new FakeLanguageModel().respondWith(json("MAYBE_OK"), 10, 10);

        assertThat(interpreterFor(model).interpret(REPO, docsWith("문서")).isUndetermined())
                .as("모델이 스키마를 벗어난 값을 내면 못 믿는다 — 허용으로 읽지 않는다")
                .isTrue();
    }

    @Test
    void 파싱_실패는_보류다_S5() {
        var model = new FakeLanguageModel().respondWith("이건 JSON 이 아닙니다", 10, 10);

        assertThat(interpreterFor(model).interpret(REPO, docsWith("문서")).isUndetermined())
                .as("「모델이 대충 말한 것」을 허용으로 읽지 않는다")
                .isTrue();
    }

    @Test
    void 코드펜스로_감싼_응답도_읽는다() {
        var model = new FakeLanguageModel()
                .respondWith("```json\n" + json("NOT_MENTIONED") + "\n```", 10, 10);

        assertThat(interpreterFor(model).interpret(REPO, docsWith("문서")).aiContributionAllowed())
                .as("모델이 코드펜스를 붙이는 일이 흔하다 — 그것 때문에 보류로 떨어뜨릴 이유는 없다")
                .isTrue();
    }

    @Test
    void 모델이_거부하면_보류다_S5() {
        var model = new FakeLanguageModel().failWith(
                new LlmPermanentException(LlmFailureReason.REJECTED, LlmCallSite.POLICY));

        assertThat(interpreterFor(model).interpret(REPO, docsWith("문서")).isUndetermined())
                .as("재전송해도 같다 — 보류로 굳히고 사람이 본다")
                .isTrue();
    }

    @Test
    void 일시적_실패는_전파한다_보류로_굳히지_않는다_S5() {
        var model = new FakeLanguageModel().failWith(
                new LlmTransientException(LlmFailureReason.TIMEOUT, LlmCallSite.POLICY));

        assertThatThrownBy(() -> interpreterFor(model).interpret(REPO, docsWith("문서")))
                .as("호출자가 아무것도 쓰지 않고 중단해야 한다 — "
                        + "보류로 만들면 저절로 풀렸을 일이 영구 보류가 된다 (#24 미구현)")
                .isInstanceOf(LlmTransientException.class);
    }

    @Test
    void 저장소_단위로_호출하고_후보를_붙이지_않는다() {
        var model = new FakeLanguageModel().respondWith(json("NOT_MENTIONED"), 10, 10);

        interpreterFor(model).interpret(REPO, docsWith("문서"));

        var ctx = model.calls().get(0).ctx();
        assertThat(ctx.callSite()).isEqualTo(LlmCallSite.POLICY);
        assertThat(ctx.candidateId())
                .as("규약 분석은 후보가 만들어지기 전이다 — 가짜 ID 로 장부를 오염시키지 않는다")
                .isNull();
        assertThat(ctx.attempt())
                .as("CODE→VERIFY→REVIEW 루프 밖이라 항상 1 이다 (Q-6)")
                .isEqualTo(1);
    }

    @Test
    void 읽은_문서가_없으면_모델을_부르지_않는다() {
        var model = new FakeLanguageModel().respondWith(json("NOT_MENTIONED"), 10, 10);

        RuleReading reading =
                interpreterFor(model).interpret(REPO, new RepositoryDocuments(List.of()));

        assertThat(reading.isUndetermined()).isTrue();
        assertThat(model.calls())
                .as("판정할 텍스트가 없는데 토큰을 쓸 이유가 없다")
                .isEmpty();
    }

    @Test
    void 영속화할_요약에_토큰_패턴이_남지_않는다_S4() {
        String leaked = "ghp_" + "B".repeat(36);
        var model = new FakeLanguageModel().respondWith(
                """
                {"aiContribution":"NOT_MENTIONED","evidence":"%s","javaVersion":null,
                 "buildCommand":null,"testCommand":null,"issueReferenceRequired":false,
                 "signoffRequired":false,"testsRequired":false,"prTemplateSummary":null}
                """.formatted(leaked), 10, 10);

        RuleReading reading = interpreterFor(model).interpret(REPO, docsWith("문서"));

        assertThat(reading.rules().value())
                .as("evidence 는 모델이 문서에서 인용한 문장이라 시크릿이 실려 올 수 있다. "
                        + "이 값은 DB 를 거쳐 PR 본문(#23)까지 갈 수 있다")
                .doesNotContain(leaked);
    }

    @Test
    void 프롬프트에_문서_내용이_실리고_사용량이_기록된다() {
        var model = new FakeLanguageModel()
                .withFallback(new com.ossagent.agent.domain.LlmResponse(
                        json("NOT_MENTIONED"), new LlmUsage(3, 4)));

        interpreterFor(model).interpret(REPO, docsWith("CONTRIBUTING 본문"));

        assertThat(model.calls().get(0).request().userPrompt())
                .contains("CONTRIBUTING.md")
                .contains("CONTRIBUTING 본문");
    }
}
