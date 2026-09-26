package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.DocumentFingerprint;
import com.ossagent.repository.domain.PolicyDocumentFingerprints;
import com.ossagent.repository.domain.FakeContributionRuleInterpreter;
import com.ossagent.repository.domain.FakePolicyDocumentSource;
import com.ossagent.repository.domain.FakeRepositorySource;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.PolicyClearance;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryMetadata;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RuleReading;
import com.ossagent.repository.domain.ScrubbedRules;
import com.ossagent.repository.domain.UnreadableReason;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    /** 기존 행을 직접 심을 때만 쓴다 — UseCase 는 주입된 Clock 을 쓴다. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"),
            ZoneOffset.UTC);

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
    void 지문_기록이_없는_기존_정책은_강등하지_않는다_S5() {
        // 🔴 지문 기록 이전에 만들어진 행 — 비교 기준이 없다.
        //    이것을 「바뀌었다」로 읽으면 #68 을 배포하는 순간 모든 저장소가 일괄 보류로 떨어진다
        policies.save(RepositoryPolicy.analyzed(
                repositories.findById(repositoryId).orElseThrow(), allowed(), CLOCK));

        documents.givenUnreadable("CONTRIBUTING.md", UnreadableReason.UNKNOWN);

        RepositoryPolicy policy = useCase.analyze(repositoryId).orElseThrow();

        assertThat(policy.allowsContribution())
                .as("비교 기준이 없으면 「바뀌었다」를 주장할 수 없다 — 기존 판정을 유지한다")
                .isTrue();
        assertThat(policies.findAll())
                .as("UNIQUE(repository_id) 가 있다 — 새 행을 만들면 제약 위반으로 터진다")
                .hasSize(1);
    }

    @Test
    void 규약이_바뀌었는데_못_읽으면_보류로_강등한다_S5() {
        // ① 읽었다 → 금지 문구 없음 → 허용
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        // ②③ 저장소가 문서를 고쳤고, 그 결과 우리 상한을 넘어 판정에 쓸 수 없게 됐다.
        //     🔴 내용을 받긴 했으므로 **바뀌었다는 사실은 안다** — 그것이 이 이슈의 요점이다
        documents.reset().givenUnreadableWithFingerprint(
                "CONTRIBUTING.md", UnreadableReason.TRUNCATED, "기여 방법 + AI 생성 기여 금지");
        interpreter.reset();

        RepositoryPolicy policy = useCase.analyze(repositoryId).orElseThrow();

        // ④⑤ 판정이 TRUE 로 남으면 우리는 계속 Draft PR 을 만든다 — 아무도 모르는 채로
        assertThat(policy.isAiContributionUndetermined())
                .as("🔴 바뀐 것을 관측했는데 판정이 서지 않았다. 통과시키면 S-5 위반이 진행된다")
                .isTrue();
        assertThat(policy.getPendingReason())
                .as("「아무도 모르는 채로 남지 않는다」 — 어느 경로가 왜 그런지가 남아야 사람이 푼다")
                .contains("CONTRIBUTING.md")
                .contains("TRUNCATED");
        assertThat(policies.findAll()).hasSize(1);
    }

    @Test
    void 읽던_문서를_이제_못_읽으면_보류로_강등한다_S5() {
        // 🔴 1MB 초과 형태 — GitHub 이 본문을 주지 않아 **지문조차 없다.**
        //    「양쪽에 지문이 있을 때만 바뀌었다고 한다」는 규칙만으로는 이 형태를 놓친다
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        documents.reset().givenUnreadable("CONTRIBUTING.md", UnreadableReason.UNKNOWN);
        interpreter.reset();

        RepositoryPolicy policy = useCase.analyze(repositoryId).orElseThrow();

        assertThat(policy.isAiContributionUndetermined())
                .as("판정 근거로 삼았던 문서를 이제 확인할 수 없다 — 낡은 판정을 유지하지 않는다")
                .isTrue();
        assertThat(policy.getPendingReason()).contains("CHANGED_UNREADABLE");
    }

    @Test
    void 일시적으로_못_읽으면_강등하지_않는다_S5() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        // 🔴 5xx 는 한 시간 뒤면 풀린다. 이것으로 강등하면 정상 운영 상황이
        //    사람이 풀어야 하는 보류가 된다 — #7 이 세운 규칙을 뒤집지 않는다
        documents.reset().givenUnreadable("CONTRIBUTING.md", UnreadableReason.SERVER_ERROR);
        interpreter.reset();

        assertThat(useCase.analyze(repositoryId))
                .as("아무것도 쓰지 않고 중단한다 — 다음 스캔이 다시 시도한다")
                .isEmpty();
        assertThat(policies.findByRepositoryId(repositoryId).orElseThrow().allowsContribution())
                .isTrue();
    }

    @Test
    void 지문이_같으면_LLM_을_부르지_않는다() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        interpreter.reset().given(allowed());
        useCase.analyze(repositoryId);

        assertThat(interpreter.calls())
                .as("🔴 비싼 것은 LLM 판정이다. 건너뛰지 못하면 매 스캔 재확인이 성립하지 않고, "
                        + "그러면 「한 번 허용이면 영원히 허용」으로 되돌아간다")
                .isEmpty();
    }

    @Test
    void 못_읽던_문서가_읽히면_다시_판정한다_S5() {
        // 기준에 지문이 없던 필수 경로가 이번에 읽혔다. 「바뀌었다」도 「그대로다」도 아니다 —
        // 🔴 그대로로 취급해 LLM 을 건너뛰면 그 문서의 금지 문구를 한 바퀴 놓친다
        documents.givenRead("CONTRIBUTING.md", "기여 방법")
                .givenUnreadable("AGENTS.md", UnreadableReason.UNKNOWN);
        interpreter.given(allowed());
        policies.save(RepositoryPolicy.analyzed(
                repositories.findById(repositoryId).orElseThrow(), allowed(),
                PolicyDocumentFingerprints.parse(
                        "CONTRIBUTING.md=" + DocumentFingerprint.of("기여 방법").value()),
                CLOCK));

        documents.reset()
                .givenRead("CONTRIBUTING.md", "기여 방법")
                .givenRead("AGENTS.md", "AI 생성 기여 금지");
        interpreter.reset().given(forbidden());

        useCase.analyze(repositoryId);

        assertThat(policies.findByRepositoryId(repositoryId).orElseThrow()
                .isAiContributionForbidden())
                .as("새로 읽힌 경로의 금지 문구가 반영되어야 한다")
                .isTrue();
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

    // ── 통행증 (#24) — 게이트와 같은 기준이어야 한다 ──────────────────

    @Test
    void 허용이면_통행증을_발급한다_S5() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        PolicyClearance clearance = useCase.clearanceFor(repositoryId);

        assertThat(clearance.repositoryId())
                .as("발급 경로가 이것 하나뿐이라 통행증의 id 는 항상 조회한 그 id 다 — "
                        + "「남의 통행증을 들고 왔다」를 호출자가 따로 검증할 필요가 없다")
                .isEqualTo(repositoryId);
    }

    @Test
    void 보류는_통행증을_받지_못한다_S5() {
        documents.givenUnreadable("CONTRIBUTING.md", UnreadableReason.TRUNCATED);
        useCase.analyze(repositoryId);

        assertThatThrownBy(() -> useCase.clearanceFor(repositoryId))
                .as("🔴 보류에 통행증이 나가면 Q-8 의 「보류는 자동으로 풀리지 않는다」가 무의미해진다")
                .isInstanceOfSatisfying(ContributionNotAllowedException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ContributionNotAllowedException.Reason.UNDETERMINED));
    }

    @Test
    void 금지는_통행증을_받지_못한다_S5() {
        documents.givenRead("CONTRIBUTING.md", "AI 기여 금지");
        interpreter.given(forbidden());
        useCase.analyze(repositoryId);

        assertThatThrownBy(() -> useCase.clearanceFor(repositoryId))
                .isInstanceOfSatisfying(ContributionNotAllowedException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ContributionNotAllowedException.Reason.FORBIDDEN));
    }

    @Test
    void 정책이_없으면_통행증을_받지_못한다_S5() {
        assertThatThrownBy(() -> useCase.clearanceFor(repositoryId))
                .as("엔티티 메서드로는 판정할 수 없는 경우다 — 부를 대상이 없다")
                .isInstanceOfSatisfying(ContributionNotAllowedException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ContributionNotAllowedException.Reason.NOT_ANALYZED));
    }

    @Test
    void 게이트와_통행증은_같은_기준으로_판정한다_S5() {
        // 🔴 판정 로직이 두 벌이면 한쪽에만 새 규칙이 들어가고, 같은 클래스의 두 문이
        //    다르게 판정하게 된다. 네 상황 전부에서 두 문의 결과가 같은지를 본다
        assertBothReject();

        documents.givenUnreadable("CONTRIBUTING.md", UnreadableReason.TRUNCATED);
        useCase.analyze(repositoryId);
        assertBothReject();

        policies.deleteAll();
        documents.reset();
        documents.givenRead("CONTRIBUTING.md", "AI 기여 금지");
        interpreter.given(forbidden());
        useCase.analyze(repositoryId);
        assertBothReject();

        policies.deleteAll();
        documents.reset();
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);
        useCase.assertContributionAllowed(repositoryId);
        assertThat(useCase.clearanceFor(repositoryId)).isNotNull();
    }

    private void assertBothReject() {
        assertThatThrownBy(() -> useCase.assertContributionAllowed(repositoryId))
                .isInstanceOf(ContributionNotAllowedException.class);
        assertThatThrownBy(() -> useCase.clearanceFor(repositoryId))
                .isInstanceOf(ContributionNotAllowedException.class);
    }

    @Test
    void 사람이_해소한_판정은_재분석이_허용으로_되돌리지_않는다_그리고_스캔을_깨뜨리지도_않는다() {
        // 🔴 #24 가 「사람이 해소한 판정을 재분석이 허용으로 되돌리지 않는다」를 엔티티 예외로
        //    고정해 뒀다. 그 예외가 스캔 파이프라인까지 올라가면 POLICY 단계가 FAILED 가 되어
        //    「거부」가 「스캔 전체 실패」로 나타난다. 지금까지는 analyze() 가 불리지 않아
        //    도달 불가였고, #68 이 그 경로를 연다
        RepositoryPolicy resolved = RepositoryPolicy.pending(
                repositories.findById(repositoryId).orElseThrow(), "AGENTS.md=UNKNOWN",
                PolicyDocumentFingerprints.parse(
                        "CONTRIBUTING.md=" + DocumentFingerprint.of("옛 내용").value()),
                CLOCK);
        resolved.resolvePending(true, "문서를 직접 읽었고 금지 문구가 없다", CLOCK);
        policies.save(resolved);

        documents.givenRead("CONTRIBUTING.md", "새 내용");
        interpreter.given(allowed());

        RepositoryPolicy after = useCase.analyze(repositoryId).orElseThrow();

        assertThat(after.allowsContribution()).isTrue();
        assertThat(after.isHumanResolved())
                .as("자동이 사람 판단을 다시 쓰지 않는다 — 판정 출처가 그대로 사람이어야 한다")
                .isTrue();
        assertThat(after.fingerprints().changedRequiredPaths(
                PolicyDocumentFingerprints.parse(
                        "CONTRIBUTING.md=" + DocumentFingerprint.of("새 내용").value())))
                .as("지문을 갱신하지 않으면 매 스캔 같은 변경을 다시 발견해 LLM 을 영원히 태운다")
                .isEmpty();
    }

    @Test
    void 사람이_해소하면_같은_문서로_다시_강등되지_않는다_Q8() {
        documents.givenRead("CONTRIBUTING.md", "기여 방법");
        interpreter.given(allowed());
        useCase.analyze(repositoryId);

        documents.reset().givenUnreadableWithFingerprint(
                "CONTRIBUTING.md", UnreadableReason.TRUNCATED, "커진 문서");
        interpreter.reset();
        useCase.analyze(repositoryId);

        // 사람이 문서를 직접 확인하고 풀었다
        RepositoryPolicy pending = policies.findByRepositoryId(repositoryId).orElseThrow();
        pending.resolvePending(true, "브라우저로 전문을 확인했고 금지 문구가 없다", CLOCK);
        policies.save(pending);

        // 다음 스캔 — 문서는 여전히 상한을 넘지만 **내용은 그대로다**
        RepositoryPolicy after = useCase.analyze(repositoryId).orElseThrow();

        assertThat(after.allowsContribution())
                .as("🔴 같은 문서로 다시 강등하면 사람의 해소가 무력화되고 보류가 영구 루프가 된다")
                .isTrue();
    }
}
