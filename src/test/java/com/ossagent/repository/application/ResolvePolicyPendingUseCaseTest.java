package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.PolicyResolutionRejectedException;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RepositoryPolicyNotFoundException;
import com.ossagent.repository.domain.RuleReading;
import com.ossagent.repository.domain.ScrubbedRules;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 승인 게이트 ② — 보류 해소 (S-5 · Q-8).
 *
 * <p>Q-8 이 재분석·시간경과·횟수소진을 전부 배제한 뒤 <b>유일하게 남긴 경로</b>다.
 * 여기가 느슨하면 「판정 불가를 통과로 처리」가 API 한 번으로 일어난다.
 *
 * <p>🔴 <b>DB 를 다시 읽어 단언한다.</b> 엔티티 반환값만 보면 트랜잭션이 없어도 통과한다.
 */
@AgentIntegrationTest
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ResolvePolicyPendingUseCaseTest {

    @Autowired
    private ResolvePolicyPendingUseCase useCase;
    @Autowired
    private OssRepositoryRepository repositories;
    @Autowired
    private RepositoryPolicyRepository policies;
    @Autowired
    private Clock clock;

    private Long repositoryId;

    @BeforeEach
    void setUp() {
        policies.deleteAll();
        String name = "spring-kafka-" + System.nanoTime();
        repositoryId = repositories.save(new OssRepository(
                "spring-projects", name,
                "https://github.com/spring-projects/" + name)).getId();
    }

    private void givenPending() {
        policies.save(RepositoryPolicy.pending(
                repositories.findById(repositoryId).orElseThrow(), "AGENTS.md=UNKNOWN", clock));
    }

    private void givenForbidden() {
        policies.save(RepositoryPolicy.analyzed(
                repositories.findById(repositoryId).orElseThrow(),
                new RuleReading(false, null, null, null, false, false, false, ScrubbedRules.none()),
                clock));
    }

    private RepositoryPolicy reread() {
        return policies.findByRepositoryId(repositoryId).orElseThrow();
    }

    @Test
    void 사람이_보류를_풀면_커밋된다_S5() {
        givenPending();

        Instant resolvedAt = useCase.resolve(repositoryId, true, "adoc 을 직접 열었다 — 금지 문구 없음");

        assertThat(resolvedAt).isNotNull();
        assertThat(reread().allowsContribution()).isTrue();
        assertThat(reread().isHumanResolved())
                .as("resolvedAt 이 「기계가 아니라 사람이 판단했다」를 남긴다")
                .isTrue();
    }

    @Test
    void 금지로_닫는_것도_정상적인_해소다_Q8() {
        givenPending();

        useCase.resolve(repositoryId, false, "AGENTS.md 에 AI 생성 기여 금지가 적혀 있다");

        assertThat(reread().isAiContributionForbidden()).isTrue();
    }

    @Test
    void 보류_사유는_해소_뒤에도_남는다() {
        givenPending();

        useCase.resolve(repositoryId, true, "직접 확인했다");

        assertThat(reread().getPendingReason())
                .as("왜 보류였는지가 사라지면 그 판단을 나중에 재검토할 수 없다")
                .isEqualTo("AGENTS.md=UNKNOWN");
    }

    @Test
    void 정책_행이_없으면_만들어_주지_않는다_S5() {
        assertThatThrownBy(() -> useCase.resolve(repositoryId, true, "그냥 허용하자"))
                .as("🔴 행이 없는데 해소해 주면 「읽지 않고 허용」이 되어 S-5 가 정면으로 뚫린다. "
                        + "분석을 먼저 돌려야 한다")
                .isInstanceOf(RepositoryPolicyNotFoundException.class);

        assertThat(policies.findByRepositoryId(repositoryId))
                .as("거부가 행을 만들지 않는다")
                .isEmpty();
    }

    @Test
    void 금지_판정은_해소로_뒤집히지_않는다_S5() {
        givenForbidden();

        assertThatThrownBy(() -> useCase.resolve(repositoryId, true, "메인테이너가 괜찮다고 했다"))
                .isInstanceOf(PolicyResolutionRejectedException.class);

        assertThat(reread().isAiContributionForbidden()).isTrue();
        assertThat(reread().isHumanResolved())
                .as("🔴 거부가 절반만 적용되지 않는다 — 롤백 이전에 엔티티부터 온전해야 한다")
                .isFalse();
    }

    @Test
    void 근거_없는_해소는_거부되고_보류가_유지된다() {
        givenPending();

        assertThatThrownBy(() -> useCase.resolve(repositoryId, true, "   "))
                .isInstanceOf(PolicyResolutionRejectedException.class);

        assertThat(reread().isAiContributionUndetermined())
                .as("🔴 검증 전에 한 줄이라도 대입하면 「사람이 풀지 않았는데 허용」이 남는다")
                .isTrue();
    }

    @Test
    void 해소_근거는_스크럽되어_저장된다_S4() {
        givenPending();
        // 토큰 「형태」가 유의미하다 — 스크럽이 실제로 무는지를 봐야 하므로 조립한다
        String leaked = "ghp_" + "C".repeat(36);

        useCase.resolve(repositoryId, true, "토큰 " + leaked + " 로 직접 열어 봤다");

        assertThat(reread().getResolutionNote())
                .as("사람이 자기 토큰을 붙여넣는 자리다 — DB 에 넣을 때도 스크럽은 동일하다")
                .doesNotContain(leaked);
    }

    @Test
    void 식별자가_없으면_거부한다() {
        assertThatThrownBy(() -> useCase.resolve(null, true, "근거"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 재분석이_사람의_판단을_조용히_덮어쓰지_못한다_S5() {
        // 정책 = 허용. 여기서 쓰는 주체가 둘이 된다 —
        //   사람(허용 → 금지 로 조인다) · 재분석(허용 → 허용, 아직 바뀐 문서를 못 봤다)
        policies.save(RepositoryPolicy.analyzed(
                repositories.findById(repositoryId).orElseThrow(),
                new RuleReading(true, "17", "./gradlew build", "./gradlew test",
                        true, true, true, ScrubbedRules.of("{}")),
                clock));

        // 재분석이 자기 트랜잭션에서 읽은 사본. 이 시점에는 아직 허용이다
        RepositoryPolicy asSeenByReanalysis = reread();

        // 그 사이 사람이 금지로 닫는다 — 규약이 바뀐 것을 확인했다
        useCase.resolve(repositoryId, false, "AGENTS.md 에 AI 기여 금지가 추가됐다");

        // 재분석이 자기 사본에 허용 판정을 적용하고 저장한다.
        // 🔴 @Version 이 없으면 UPDATE ... WHERE id = ? 라 이것이 그대로 이긴다
        asSeenByReanalysis.reanalyze(
                new RuleReading(true, "17", "./gradlew build", "./gradlew test",
                        true, true, true, ScrubbedRules.of("{}")),
                clock);

        assertThatThrownBy(() -> policies.save(asSeenByReanalysis))
                .as("사람의 금지 판단이 사라지면 우리는 계속 Draft PR 을 만든다 — "
                        + "되돌릴 수 없는 방향이 그쪽이다. 충돌은 409 로 나간다")
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(reread().isAiContributionForbidden())
                .as("사람의 판단이 남는다")
                .isTrue();
    }
}
