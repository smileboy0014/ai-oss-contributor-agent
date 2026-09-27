package com.ossagent.candidate.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.candidate.application.CodingProperties;
import com.ossagent.agent.domain.FakeLanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.candidate.domain.CodingInput;
import com.ossagent.candidate.domain.CodingOutOfPlanException;
import com.ossagent.candidate.domain.CodingRejectedException;
import com.ossagent.candidate.domain.GeneratedFile;
import com.ossagent.candidate.domain.ImplementationPlan;
import com.ossagent.candidate.domain.PlannedFile;
import com.ossagent.repository.domain.ContextBudget;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryContext;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.SelectionReason;
import com.ossagent.repository.domain.SelectedFile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 코딩 어댑터의 <b>판정</b>을 본다 — #18.
 *
 * <p>모델 응답 자체는 비결정적이므로 {@code FakeLanguageModel} 로 고정한다 —
 * 여기서 보는 것은 <b>「모델이 이렇게 답하면 우리가 무엇을 하는가」</b>다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LlmCodingAgentTest {

    private static final String ALLOWED = "src/main/java/A.java";
    private static final String OUTSIDE = "src/main/java/Z.java";

    private FakeLanguageModel languageModel;
    private LlmCodingAgent agent;

    @BeforeEach
    void setUp() {
        languageModel = new FakeLanguageModel();
        agent = new LlmCodingAgent(languageModel, new CodingProperties(16000), new ObjectMapper());
    }

    @Test
    @DisplayName("🔴 계획 밖 경로를 돌려주면 중단한다 — 조기 차단")
    void 계획_밖_경로를_돌려주면_중단한다() {
        languageModel.respondWith("""
                {"files":[{"path":"%s","content":"class A {}"},
                          {"path":"%s","content":"class Z {}"}]}
                """.formatted(ALLOWED, OUTSIDE), 10, 10);

        assertThatThrownBy(() -> agent.write(7L, 1, input()))
                .isInstanceOf(CodingOutOfPlanException.class)
                .hasMessageContaining(OUTSIDE);
    }

    @Test
    @DisplayName("🔴 계획 밖 경로를 **모아서** 던진다 — 하나씩 고치며 바퀴를 태우지 않는다")
    void 계획_밖_경로를_모아서_던진다() {
        languageModel.respondWith("""
                {"files":[{"path":"a/x.java","content":"x"},
                          {"path":"b/y.java","content":"y"}]}
                """, 10, 10);

        assertThatThrownBy(() -> agent.write(7L, 1, input()))
                .isInstanceOf(CodingOutOfPlanException.class)
                .satisfies(e -> assertThat(((CodingOutOfPlanException) e).outsidePaths())
                        .as("재시도 예산이 3바퀴뿐이라 한 번에 다 보여야 한다")
                        .containsExactlyInAnyOrder("a/x.java", "b/y.java"));
    }

    @Test
    @DisplayName("계획 안 경로만 돌려주면 통과한다")
    void 계획_안_경로만_돌려주면_통과한다() {
        languageModel.respondWith(
                """
                {"files":[{"path":"%s","content":"class A {}"}]}
                """.formatted(ALLOWED), 10, 10);

        List<GeneratedFile> files = agent.write(7L, 1, input());

        assertThat(files).hasSize(1);
        assertThat(files.getFirst().path()).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("🔴 attempt 를 그대로 넘긴다 — AgentRun 의 사이클이 갈리면 안 된다")
    void attempt_를_그대로_넘긴다() {
        languageModel.respondWith(
                """
                {"files":[{"path":"%s","content":"x"}]}
                """.formatted(ALLOWED), 10, 10);

        agent.write(7L, 3, input());

        // 🔴 여기서 1 로 고정하면 CODE 행과 VERIFY 행의 attempt 가 갈려
        //    같은 사이클을 이어 붙일 수 없다 (Q-6 불변식)
        assertThat(languageModel.calls().getFirst().ctx().attempt()).isEqualTo(3);
        assertThat(languageModel.calls().getFirst().ctx().callSite()).isEqualTo(LlmCallSite.CODE);
    }

    @Test
    @DisplayName("스키마 위반과 계획 이탈은 다른 예외다 — 재시도 판정이 다르다")
    void 스키마_위반과_계획_이탈은_다른_예외다() {
        languageModel.respondWith("이건 JSON 이 아니다", 10, 10);

        assertThatThrownBy(() -> agent.write(7L, 1, input()))
                .isInstanceOf(CodingRejectedException.class)
                // ⚠ 응답 본문이 메시지에 실리면 대상 저장소 텍스트가 로그로 나간다 (S-4)
                .hasMessageNotContaining("이건 JSON 이 아니다");
    }

    @Test
    @DisplayName("files 가 비면 거부한다 — 빈 산출을 「성공」으로 읽지 않는다")
    void files_가_비면_거부한다() {
        languageModel.respondWith("{\"files\":[]}", 10, 10);

        assertThatThrownBy(() -> agent.write(7L, 1, input()))
                .isInstanceOf(CodingRejectedException.class);
    }

    private static CodingInput input() {
        ImplementationPlan plan = new ImplementationPlan(
                List.of(new PlannedFile(ALLOWED, PlannedFile.ChangeKind.MODIFY, "고친다")),
                "요약", "테스트 전략", 10);
        RepositoryContext context = new RepositoryContext(
                new RepositoryCoordinates("spring-projects", "spring-kafka"),
                "main", "sha",
                List.of(new SelectedFile(ALLOWED, SelectionReason.PATH_LITERAL, 10, "class A {}")),
                ContextBudget.of(12, 100_000, 20_000),
                false, 1, Map.of());
        return new CodingInput(plan, context, ContributionConstraints.unknown());
    }
}
