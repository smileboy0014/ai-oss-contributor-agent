package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.support.testing.AgentIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 🔴 착수가 <b>트랜잭션 밖</b>에서만 돌게 고정한다 — #18 · 체크박스 12.
 *
 * <h2>왜 실행 경로가 없어도 쓸 수 있나</h2>
 *
 * <p>초안은 이 검증을 <b>「실행 경로가 없어 증명 불가」</b>로 분류했다. <b>그 분류가 틀렸다</b> —
 * 가드는 UseCase <b>진입부</b>에 있으므로 실행기가 없어도 걸린다. 리뷰가
 * {@code ScanPipelineUseCaseTest} 의 같은 성격 선례를 짚어 줬다.
 *
 * <p>「지금은 못 쓴다」로 미뤄 두면 <b>D 가 들어올 때 이 가드가 실제로 도는지 아무도 모른 채</b>
 * 30분짜리 샌드박스 실행이 트랜잭션 안으로 들어간다. 증상은 예외가 아니라 <b>「느리다」</b>뿐이라
 * 리뷰에서도 놓친다.
 *
 * <h2>⚠️ 테스트 메서드에 {@code @Transactional} 을 붙이지 않는다</h2>
 *
 * <p>붙이고 자기호출로 부르면 프록시를 타지 않아 <b>트랜잭션이 아예 열리지 않는다</b> —
 * 그러면 이 테스트가 <b>실패할 수 없는 테스트</b>가 된다. {@link TransactionTemplate} 으로
 * 실제 트랜잭션을 연다.
 */
@AgentIntegrationTest
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ImplementCandidateTransactionTest {

    @Autowired
    private ImplementCandidateUseCase implementCandidate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("🔴 트랜잭션 안에서 부르면 거부한다 — 샌드박스가 커넥션을 30분 점유한다")
    void 트랜잭션_안에서는_부를_수_없다() {
        // 🔴 후보가 없어도 된다 — 가드가 **조회보다 먼저**다. 그래서 이 테스트는
        //    픽스처에 기대지 않고, 픽스처가 바뀌어도 흔들리지 않는다
        assertThatThrownBy(() ->
                transactionTemplate.execute(status -> implementCandidate.implement(1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("트랜잭션");
    }
}
