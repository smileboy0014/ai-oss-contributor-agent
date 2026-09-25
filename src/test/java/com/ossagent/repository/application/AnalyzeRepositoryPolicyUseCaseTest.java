package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.FakeContributionRuleInterpreter;
import com.ossagent.repository.domain.FakePolicyDocumentSource;
import com.ossagent.repository.domain.FakeRepositorySource;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryMetadata;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RuleReading;
import com.ossagent.repository.domain.ScrubbedRules;
import com.ossagent.repository.domain.UnreadableReason;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 규약 분석 흐름 — <b>S-5 의 실행체</b>.
 *
 * <p>여기서 「못 읽음」이 「규약 없음 → 허용」으로 번역되면 규약 위반 PR 이 외부 OSS 로 나간다.
 */
@AgentIntegrationTest
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AnalyzeRepositoryPolicyUseCaseTest {

    @Autowired
    private AnalyzeRepositoryPolicyUseCase useCase;
    @Autowired
    private OssRepositoryRepository repositories;
    @Autowired
    private RepositoryPolicyRepository policies;
    @Autowired
    private FakePolicyDocumentSource documents;
    @Autowired
    private FakeContributionRuleInterpreter interpreter;
    @Autowired
    private FakeRepositorySource repositorySource;

    private Long repositoryId;
    private RepositoryCoordinates coordinates;

    @BeforeEach
    void setUp() {
        // 페이크는 싱글턴이고 컨텍스트가 테스트 클래스 사이에 캐시된다
        documents.reset();
        interpreter.reset();
        policies.deleteAll();

        String name = "spring-kafka-" + System.nanoTime();
        coordinates = new RepositoryCoordinates("spring-projects", name);
        repositoryId = repositories.save(new OssRepository(
                "spring-projects", name, "https://github.com/spring-projects/" + name)).getId();
        // archived 판정용 — 등록하지 않으면 페이크가 셋업 오류로 막는다
        repositorySource.given(
                new RepositoryMetadata(coordinates, "main", "Java", false, false, 0));
    }

    private void givenArchived() {
        repositorySource.given(
                new RepositoryMetadata(coordinates, "main", "Java", true, false, 0));
    }

    private static RuleReading allowed() {
        return new RuleReading(true, "17", null, null, false, false, false, ScrubbedRules.of("{}"));
    }

    private static RuleReading forbidden() {
        return new RuleReading(false, null, null, null, false, false, false, ScrubbedRules.none());
    }

    // ── 보류 ───────────────────────────────────────────────

    @Test
    void 필수_경로를_영구적으로_못_읽으면_보류한다_S5() {
        documents.givenUnreadable("CONTRIBUTING.md", UnreadableReason.TRUNCATED);
        interpreter.given(allowed());

        RepositoryPolicy policy = useCase.analyze(repositoryId).orElseThrow();

        assertThat(policy.isAiContributionUndetermined())
                .as("못 읽은 것을 허용으로 번역하면 규약 위반 PR 이 나간다")
                .isTrue();
        assertThat(policy.getPendingReason()).contains("CONTRIBUTING.md", "TRUNCATED");
    }

    @Test
    void 못_읽으면_LLM을_부르지_않는다() {
        documents.givenUnreadable("CONTRIBUTING.md", UnreadableReason.TRUNCATED);

        useCase.analyze(repositoryId);

        assertThat(interpreter.calls())
                .as("판정이 설 수 없는 입력에 토큰을 쓰지 않는다")
                .isEmpty();
    }

    @Test
    void LLM_판정_불가는_보류다_S5() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(RuleReading.undetermined());

        assertThat(useCase.analyze(repositoryId).orElseThrow().isAiContributionUndetermined())
                .isTrue();
    }

    // ── 일시적 실패는 기록하지 않는다 ────────────────────────

    @Test
    void 일시적으로_못_읽으면_아무것도_쓰지_않고_중단한다_S5() {
        documents.givenUnreadable("CONTRIBUTING.md", UnreadableReason.RATE_LIMITED);

        Optional<RepositoryPolicy> result = useCase.analyze(repositoryId);

        assertThat(result)
                .as("보류로 만들면 한 시간 뒤면 풀렸을 일이 영구 보류가 된다 (#24 미구현)")
                .isEmpty();
        assertThat(policies.findByRepositoryId(repositoryId))
                .as("정책이 없으므로 구현 단계는 어차피 막힌다 — 안전 성질은 보류와 같고 복구만 자동이다")
                .isEmpty();
    }

    @Test
    void LLM_이_일시적으로_실패해도_아무것도_쓰지_않는다_S5() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.failWith(new LlmTransientException(LlmFailureReason.TIMEOUT, LlmCallSite.POLICY));

        assertThat(useCase.analyze(repositoryId)).isEmpty();
        assertThat(policies.findByRepositoryId(repositoryId)).isEmpty();
    }

    // ── 허용 ───────────────────────────────────────────────

    @Test
    void 필수_경로가_전부_404면_허용하고_LLM을_부르지_않는다_S5() {
        // 아무것도 given 하지 않으면 페이크가 전부 ABSENT 로 돌려준다
        RepositoryPolicy policy = useCase.analyze(repositoryId).orElseThrow();

        assertThat(policy.allowsContribution())
                .as("문서가 없으면 금지 표기가 존재할 수 없다 — Q-8 확정 ①")
                .isTrue();
        assertThat(interpreter.calls()).isEmpty();
    }

    @Test
    void 일부는_읽고_일부는_404면_읽은_것으로_판정한다_S5() {
        // Q-8 실측의 spring-framework 모양 — 실전에서 가장 흔한 형태다
        documents.givenAbsent("AGENTS.md").givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());

        RepositoryPolicy policy = useCase.analyze(repositoryId).orElseThrow();

        assertThat(policy.allowsContribution()).isTrue();
        assertThat(interpreter.calls())
                .as("읽은 문서가 있으면 판정해야 한다")
                .hasSize(1);
    }

    @Test
    void 부가_경로를_못_읽어도_보류하지_않는다_S5() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법")
                .givenUnreadable("README.md", UnreadableReason.TRUNCATED);
        interpreter.given(allowed());

        assertThat(useCase.analyze(repositoryId).orElseThrow().allowsContribution())
                .as("판정과 무관한 문서의 실패가 저장소를 통째로 보류시키면 안 된다")
                .isTrue();
    }

    // ── 금지 ───────────────────────────────────────────────

    @Test
    void 금지_문구를_찾으면_후보에서_제외한다_S5() {
        documents.givenRead("CONTRIBUTING.md", "AI 기여 금지");
        interpreter.given(forbidden());

        assertThat(useCase.analyze(repositoryId).orElseThrow().isAiContributionForbidden()).isTrue();
    }

    // ── 재분석 ─────────────────────────────────────────────

    @Test
    void 보류는_재분석으로_풀리지_않는다_S5() {
        documents.givenUnreadable("CONTRIBUTING.md", UnreadableReason.TRUNCATED);
        useCase.analyze(repositoryId);

        // 이번엔 잘 읽히고 허용 판정이 난다 — 그래도 풀리면 안 된다
        documents.reset().givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        assertThat(policies.findByRepositoryId(repositoryId).orElseThrow()
                .isAiContributionUndetermined())
                .as("Q-8 확정 ② — 해소는 사람의 명시적 행위다 (#24)")
                .isTrue();
    }

    @Test
    void 보류_상태에서는_LLM을_다시_부르지_않는다() {
        documents.givenUnreadable("CONTRIBUTING.md", UnreadableReason.TRUNCATED);
        useCase.analyze(repositoryId);

        documents.reset().givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.reset().given(allowed());
        useCase.analyze(repositoryId);

        assertThat(interpreter.calls())
                .as("반영할 수 없는 판정에 토큰을 쓰지 않는다")
                .isEmpty();
    }

    @Test
    void 이미_판정이_선_저장소를_보류로_되돌리지_않는다_S5() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        // 나중에 문서가 상한을 넘었다 — 영구 실패다
        documents.reset().givenUnreadable("CONTRIBUTING.md", UnreadableReason.TRUNCATED);
        interpreter.reset();

        RepositoryPolicy policy = useCase.analyze(repositoryId).orElseThrow();

        assertThat(policy.allowsContribution())
                .as("이미 확인한 판정을 지울 이유가 없다. 되돌리면 사람이 풀어야 하는 상태가 된다 (#24 미구현)")
                .isTrue();
        assertThat(policies.findAll())
                .as("UNIQUE(repository_id) 가 있다 — 새 행을 만들면 제약 위반으로 터진다")
                .hasSize(1);
    }

    @Test
    void 나중에_금지로_바뀌면_DB_에_반영된다_S5() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        // 저장소가 AGENTS.md 로 AI 기여를 금지했다
        documents.reset().givenRead("AGENTS.md", "AI 기여 금지");
        interpreter.reset().given(forbidden());
        useCase.analyze(repositoryId);

        assertThat(policies.findByRepositoryId(repositoryId).orElseThrow()
                .isAiContributionForbidden())
                .as("반환 객체만 FORBIDDEN 이고 DB 가 TRUE 로 남으면 게이트가 계속 통과시킨다 — FR-2 붕괴")
                .isTrue();

        assertThatThrownBy(() -> useCase.assertContributionAllowed(repositoryId))
                .as("게이트는 DB 를 다시 읽는다 — 영속되지 않으면 여기서 드러난다")
                .isInstanceOf(ContributionNotAllowedException.class);
    }

    // ── 게이트 (FR-4) ──────────────────────────────────────

    @Test
    void 정책이_없으면_구현_단계로_못_간다_S5() {
        assertThatThrownBy(() -> useCase.assertContributionAllowed(repositoryId))
                .isInstanceOfSatisfying(ContributionNotAllowedException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ContributionNotAllowedException.Reason.NOT_ANALYZED));
    }

    @Test
    void 보류면_구현_단계로_못_간다_S5() {
        documents.givenUnreadable("CONTRIBUTING.md", UnreadableReason.TRUNCATED);
        useCase.analyze(repositoryId);

        assertThatThrownBy(() -> useCase.assertContributionAllowed(repositoryId))
                .as("「판정 불가를 통과로 처리」가 정확히 S-5 위반이다")
                .isInstanceOfSatisfying(ContributionNotAllowedException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ContributionNotAllowedException.Reason.UNDETERMINED));
    }

    @Test
    void 금지면_구현_단계로_못_간다_S5() {
        documents.givenRead("CONTRIBUTING.md", "AI 기여 금지");
        interpreter.given(forbidden());
        useCase.analyze(repositoryId);

        assertThatThrownBy(() -> useCase.assertContributionAllowed(repositoryId))
                .isInstanceOfSatisfying(ContributionNotAllowedException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ContributionNotAllowedException.Reason.FORBIDDEN));
    }

    @Test
    void 보관된_저장소는_분석하지_않는다() {
        givenArchived();
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());

        assertThat(useCase.analyze(repositoryId))
                .as("여기서 안 거르면 파이프라인을 끝까지 돌린 뒤 PR 생성에서야 실패한다")
                .isEmpty();
        assertThat(interpreter.calls()).isEmpty();
    }

    @Test
    void 허용이면_게이트를_통과한다() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        useCase.assertContributionAllowed(repositoryId);
    }
}
