package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RuleReading;
import com.ossagent.repository.domain.ScrubbedRules;
import com.ossagent.support.observability.GateOutcome;
import com.ossagent.support.observability.MetricNames;
import com.ossagent.support.observability.SafetyClause;
import com.ossagent.support.testing.AgentIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 🔴 <b>안전 게이트가 몇 번 막고 몇 번 통과시켰는지 집계된다</b> — #25 FR-4 · S-5.
 *
 * <h2>왜 통과까지 세나</h2>
 *
 * <p>{@code logging.md} — 「안전 게이트 … <b>통과한 것도 남긴다.</b> 사고 후 「막았는가」를
 * 증명할 수 있어야 한다」. 그리고 실용적인 이유가 하나 더 있다 —
 * <b>차단만 세면 「0건 차단」과 「계측 고장」이 구분되지 않는다.</b>
 * 통과 카운터가 돌고 있으면 「게이트가 실제로 불렸고 아무도 안 막혔다」를 알 수 있다.
 *
 * <p>⚠️ 레지스트리는 컨텍스트에 공유되고 테스트 클래스 사이에 캐시된다.
 * 절대값이 아니라 <b>델타</b>로 단언한다.
 */
@AgentIntegrationTest
class SafetyGateMetricsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private AnalyzeRepositoryPolicyUseCase policyUseCase;

    @Autowired
    private OssRepositoryRepository repositories;

    @Autowired
    private RepositoryPolicyRepository policies;

    @Autowired
    private MeterRegistry registry;

    private Long repositoryId;

    @BeforeEach
    void setUp() {
        String name = "spring-kafka-" + System.nanoTime();
        repositoryId = repositories.save(new OssRepository(
                "spring-projects", name, "https://github.com/spring-projects/" + name)).getId();
    }

    @Test
    @DisplayName("🔴 게이트 통과가 집계된다 — 분모가 생긴다 S5")
    void 게이트_통과가_집계된다_S5() {
        givenPolicy(Boolean.TRUE);
        double before = gateCount(GateOutcome.PASSED, "NONE");

        policyUseCase.assertContributionAllowed(repositoryId);

        assertThat(gateCount(GateOutcome.PASSED, "NONE") - before)
                .as("통과를 세지 않으면 「0건 차단」과 「계측 고장」이 구분되지 않는다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("🔴 금지 차단이 사유와 함께 집계된다 S5")
    void 금지_차단이_집계된다_S5() {
        givenPolicy(Boolean.FALSE);
        double before = gateCount(GateOutcome.BLOCKED,
                ContributionNotAllowedException.Reason.FORBIDDEN.name());

        assertThatThrownBy(() -> policyUseCase.assertContributionAllowed(repositoryId))
                .isInstanceOf(ContributionNotAllowedException.class);

        assertThat(gateCount(GateOutcome.BLOCKED,
                ContributionNotAllowedException.Reason.FORBIDDEN.name()) - before)
                .as("사고 후 「막았는가」를 증명할 수 있어야 한다 — logging.md")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("보류 차단이 금지와 다른 사유로 집계된다 S5")
    void 보류_차단은_사유가_다르다_S5() {
        policies.save(RepositoryPolicy.pending(
                repositories.findById(repositoryId).orElseThrow(), "문서를 읽지 못했다", CLOCK));
        double before = gateCount(GateOutcome.BLOCKED,
                ContributionNotAllowedException.Reason.UNDETERMINED.name());

        assertThatThrownBy(() -> policyUseCase.assertContributionAllowed(repositoryId))
                .isInstanceOf(ContributionNotAllowedException.class);

        assertThat(gateCount(GateOutcome.BLOCKED,
                ContributionNotAllowedException.Reason.UNDETERMINED.name()) - before)
                .as("보류와 금지를 한 칸에 세면 「사람이 풀어야 하는 것」이 몇 건인지 알 수 없다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("정책 미분석 차단도 집계된다 S5")
    void 미분석_차단이_집계된다_S5() {
        double before = gateCount(GateOutcome.BLOCKED,
                ContributionNotAllowedException.Reason.NOT_ANALYZED.name());

        assertThatThrownBy(() -> policyUseCase.assertContributionAllowed(repositoryId))
                .isInstanceOf(ContributionNotAllowedException.class);

        assertThat(gateCount(GateOutcome.BLOCKED,
                ContributionNotAllowedException.Reason.NOT_ANALYZED.name()) - before)
                .isEqualTo(1.0);
    }

    private double gateCount(GateOutcome outcome, String reason) {
        Counter counter = Search.in(registry)
                .name(MetricNames.SAFETY_GATE)
                .tag(MetricNames.TAG_CLAUSE, SafetyClause.S5.name())
                .tag(MetricNames.TAG_OUTCOME, outcome.name())
                .tag(MetricNames.TAG_REASON, reason)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void givenPolicy(Boolean allowed) {
        policies.save(RepositoryPolicy.analyzed(
                repositories.findById(repositoryId).orElseThrow(),
                new RuleReading(allowed, "17", null, null, false, false, false,
                        ScrubbedRules.of("{}")),
                CLOCK));
    }
}
