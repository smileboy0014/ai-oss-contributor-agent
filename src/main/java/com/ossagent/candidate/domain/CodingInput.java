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
 * @param plan        구현 계획 — 경로 목록이 <b>허용 집합</b>이다
 * @param context     저장소 컨텍스트. 🔴 <b>재구성하지 않는다</b>
 * @param constraints 대상 저장소 규약 — {@code testsRequired} 가 테스트 생성 여부를 정한다
 */
public record CodingInput(
        ImplementationPlan plan,
        RepositoryContext context,
        ContributionConstraints constraints) {

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
}
