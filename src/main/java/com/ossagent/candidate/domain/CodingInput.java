package com.ossagent.candidate.domain;

import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryContext;
import java.util.Set;

/**
 * 코딩 에이전트에 넘기는 <b>값</b> — #18.
 *
 * <h2>🔴 {@code RepositoryContext} 를 그대로 받는 이유</h2>
 *
 * <p>그 안의 {@code SelectedFile} 은 compact 생성자가 <b>스크럽을 강제</b>하고
 * {@code SecretFilePolicy} 가 <b>경로를 이미 걸렀다</b>(#15). 여기서 내용을 다시 뽑아
 * {@code String} 으로 재조립하면 <b>두 보증이 함께 사라진다.</b>
 *
 * <p>⚠️ 「값 타입이라 규율 ④ 예외」와 「스크럽된 값이라 건너가도 안전」은 <b>다른 보증</b>이다.
 * 전자가 유지되면서 후자만 깨질 수 있고, <b>그때 증상은 아무것도 빨개지지 않는 것</b>이다.
 *
 * <h2>재시도 바퀴는 <b>피드백만</b> 다르다 (#21)</h2>
 *
 * <p>계획·컨텍스트·규약은 바퀴 내내 같고, 바뀌는 것은 <b>직전 바퀴가 왜 실패했는가</b>뿐이다.
 * {@code PlanningInput.withFeedback(verdict)}(#16)이 이미 같은 모양이다.
 *
 * <p>🔴 <b>계획을 다시 세우지 않는다.</b> 계획 재생성은 별개 축
 * ({@code agent.plan.max-attempts})이고, 루프 안에서 계획이 바뀌면 「계획 밖 경로」 게이트의
 * 기준이 바퀴마다 달라져 <b>무엇을 허용했는지가 사라진다.</b>
 *
 * @param plan        구현 계획 — 경로 목록이 <b>허용 집합</b>이다
 * @param context     저장소 컨텍스트. 🔴 <b>재구성하지 않는다</b>
 * @param constraints 대상 저장소 규약 — {@code testsRequired} 가 테스트 생성 여부를 정한다
 * @param feedback    직전 바퀴의 실패. <b>첫 바퀴는 {@code null}</b> 이다 —
 *                    🔴 「실패한 적 없음」과 「실패했는데 지적이 없음」은 다른 상태이고,
 *                    후자는 {@link CodingFeedback} 생성자가 이미 거부한다
 */
public record CodingInput(
        ImplementationPlan plan,
        RepositoryContext context,
        ContributionConstraints constraints,
        CodingFeedback feedback) {

    /** 첫 바퀴 — 되먹일 것이 아직 없다. */
    public CodingInput(ImplementationPlan plan, RepositoryContext context,
            ContributionConstraints constraints) {
        this(plan, context, constraints, null);
    }

    public CodingInput {
        if (plan == null) {
            throw new IllegalArgumentException("구현 계획은 필수다 — 허용 경로 집합이 거기서 나온다");
        }
        if (context == null) {
            throw new IllegalArgumentException("저장소 컨텍스트는 필수다");
        }
        if (constraints == null) {
            throw new IllegalArgumentException("대상 저장소 규약은 필수다");
        }
    }

    /**
     * 🔴 모델이 손댈 수 있는 경로의 <b>전부</b>.
     *
     * <p>이 집합 밖의 경로가 응답에 나타나면 그 자리에서 중단한다.
     */
    public Set<String> allowedPaths() {
        return Set.copyOf(plan.paths());
    }

    /** 대상 저장소 규약이 테스트를 요구하는가 — 요구하면 테스트 파일도 계획에 있어야 한다. */
    public boolean testsRequired() {
        return constraints.testsRequired();
    }

    /**
     * 다음 바퀴의 입력 — 🔴 <b>피드백만</b> 갈아끼운다 (#21).
     *
     * <p>계획·컨텍스트·규약을 함께 바꿀 수 있게 열면 루프가 바퀴마다 다른 것을 고치게 되고,
     * 「계획 밖 경로」 게이트의 기준이 흔들린다.
     */
    public CodingInput withFeedback(CodingFeedback next) {
        if (next == null) {
            throw new IllegalArgumentException(
                    "되먹일 피드백이 없다 — 첫 바퀴가 아니면 실패 사유가 있어야 한다");
        }
        return new CodingInput(plan, context, constraints, next);
    }

    /** 재시도 바퀴인가. 프롬프트가 이것으로 갈린다 — FR-3. */
    public boolean hasFeedback() {
        return feedback != null;
    }
}
