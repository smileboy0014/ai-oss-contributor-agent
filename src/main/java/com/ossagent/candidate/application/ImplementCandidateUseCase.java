package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.UnwiredChangeVerifier;
import com.ossagent.candidate.domain.ChangeVerifier;
import com.ossagent.candidate.domain.StatusTransition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사람의 승인 지점 — <b>착수</b> (#18 · S-6 두 번째 게이트).
 *
 * <p>PRD §20 이 정한 승인 지점 셋 중 두 번째다. 첫 번째(선정)는
 * {@code SelectCandidateUseCase}, 세 번째(PR 생성)는 #23 이다.
 *
 * <h2>🔴 여기서 PR 까지 흘려보내지 않는다</h2>
 *
 * <p>PRD §24 시퀀스는 {@code implement} 호출 <b>하나가</b> Fork → 계획 → 코딩 → 테스트 →
 * 리뷰 → <b>Draft PR 생성까지</b> 수행하는 것으로 그려져 있다. <b>그 다이어그램이 틀렸다</b>(#30).
 * 그대로 구현하면 <b>세 번째 게이트가 사라지고 S-2 까지 뚫린다.</b>
 *
 * <p>이 UseCase 는 <b>{@code READY_FOR_PR} 이전에서 끝난다.</b> push·PR 생성 경로를 갖지 않는다 (S-1·S-2).
 *
 * <h2>🔴 트랜잭션 밖이다</h2>
 *
 * <p>샌드박스 실행은 <b>최대 30분</b>({@code agent.execution.timeout-seconds})이다.
 * 트랜잭션 안에 들어가면 커넥션이 30분 잡힌다 — {@code architecture.md} 가 이 규율을
 * 「특히 중요한 이유」로 이 숫자를 든다.
 *
 * <p>그래서 이 클래스에 {@code @Transactional} 이 <b>없고</b>, 트랜잭션이 필요한 조각은
 * {@link CandidateImplementationWriter} 를 통해 부른다. <b>같은 빈 안에서 부르면
 * 프록시가 적용되지 않는다</b>는 함정(self-invocation)을 구조로 피한 것이다.
 *
 * <h2>⚠️ 지금은 1바퀴만 돈다</h2>
 *
 * <p>{@code CODE → VERIFY → REVIEW} 루프와 {@code attempt} 상한 소진 판정은 <b>#21</b> 이다.
 * 여기서는 검증을 한 번 부르고, 실패하면 {@code FAILED} 로 떨어뜨린다.
 */
@Service
public class ImplementCandidateUseCase {

    private static final Logger log = LoggerFactory.getLogger(ImplementCandidateUseCase.class);

    private static final String MDC_CANDIDATE_ID = "candidateId";
    private static final String MDC_STAGE = "stage";
    private static final String MDC_ATTEMPT = "attempt";

    private final CandidateImplementationWriter writer;
    private final ChangeVerifier verifier;

    /**
     * 🔴 {@link ChangeVerifier} 를 {@link ObjectProvider} 로 받는다.
     *
     * <p>#19 가 실물을 넣기 전까지는 {@link UnwiredChangeVerifier} 가 쓰인다 —
     * <b>항상 실패</b>를 돌려주므로 후보가 {@code IMPLEMENTING} 에 갇히지 않고
     * {@code FAILED} 라는 <b>사람에게 넘기는 신호</b>가 된다.
     *
     * <p>⚠️ {@code @ConditionalOnMissingBean} 을 쓰지 않는다 — 자동설정 전용이라
     * 컴포넌트 스캔 빈에서는 평가 시점이 순서에 좌우돼 <b>조용히 어긋난다.</b>
     * 여기서 명시적으로 고르면 순서에 기대는 자리가 없다.
     */
    public ImplementCandidateUseCase(CandidateImplementationWriter writer,
            ObjectProvider<ChangeVerifier> verifiers) {
        this.writer = writer;
        this.verifier = verifiers.getIfAvailable(UnwiredChangeVerifier::new);
        if (this.verifier instanceof UnwiredChangeVerifier) {
            log.warn("ChangeVerifier 실물이 없다 — 닫히는 기본값으로 동작한다 (#19 대기)");
        }
    }

    /**
     * {@code SELECTED → IMPLEMENTING} 이후를 진행한다 — 🔴 <b>사람이 착수를 지시한다.</b>
     *
     * <p>호출자는 <b>web 어댑터뿐</b>이어야 한다. 스케줄러·워커가 이것을 부르면
     * 「사람이 착수를 지시한다」가 무너진다 — 아키텍처 테스트가 그 사실을 고정한다.
     *
     * @throws com.ossagent.repository.domain.ContributionNotAllowedException
     *         보류·금지 저장소 — <b>403</b> (S-5)
     */
    public StatusTransition implement(Long candidateId) {
        if (candidateId == null) {
            throw new IllegalArgumentException("후보 식별자는 필수입니다");
        }
        assertNoTransaction();

        // 🔴 판정은 여기서 하고 **막는 것은 writer 가** 한다 — 통행증 확인 뒤여야 하기 때문이다.
        //    여기서 바로 던지면 정책이 막았어야 할 요청이 503 으로 가려져 S-5 게이트가
        //    한 번도 돌지 않는다. 그러면 「막는다」를 검증할 수 없다
        CandidateImplementationWriter.ImplementationStart start =
                writer.start(candidateId, executorReady());

        MDC.put(MDC_CANDIDATE_ID, String.valueOf(candidateId));
        MDC.put(MDC_ATTEMPT, String.valueOf(start.attempt()));
        try {
            runOutsideTransaction(start);
        } finally {
            MDC.remove(MDC_CANDIDATE_ID);
            MDC.remove(MDC_STAGE);
            MDC.remove(MDC_ATTEMPT);
        }
        return start.transition();
    }

    /**
     * 🔴 트랜잭션 밖 구간 — 워크스페이스 · LLM · 샌드박스.
     *
     * <p>⚠️ <b>아직 미완이다.</b> B(워크스페이스) · C(코딩) · D(산출)가 들어오기 전까지
     * 검증기를 바로 부를 대상이 없으므로, <b>닫히는 쪽</b>으로 끝낸다 —
     * 후보를 {@code FAILED} 로 떨어뜨리고 사유를 남긴다.
     *
     * <p>이것이 「게이트만 먼저 열어 두는 것」과 다른 점 — 후보가 <b>갇히지 않는다.</b>
     */
    private void runOutsideTransaction(CandidateImplementationWriter.ImplementationStart start) {
        MDC.put(MDC_STAGE, "CODE");
        // TODO(#18-B/C/D): 워크스페이스 clone → 코딩 → 포맷 → diff → GeneratedChange
        //   그때까지는 아래 검증 호출에 넘길 산출물이 없다.
        writer.fail(start.candidateId(), "구현 실행기가 아직 없다 — #18 의 B·C·D 미완");
    }

    /**
     * 🔴 실행기가 없으면 <b>전이하지 않는다</b> — 후보를 태우지 않는다.
     *
     * <p>{@code IMPLEMENTING} 에서 나갈 길은 {@code TESTING}·{@code FAILED} 뿐이고
     * <b>{@code FAILED} 는 종단</b>이다. 실행기 없이 전이하면 사람이 버튼 한 번으로
     * <b>후보를 영구히 죽인다.</b>
     *
     * <p>⚠️ {@code UnwiredChangeVerifier} 와 층이 다르다. 그쪽은 <b>코드를 만든 뒤</b>
     * 검증기가 없을 때이고, 그때는 작업이 실제로 있었으므로 {@code FAILED} 가 맞다.
     * 여기는 <b>아무것도 하기 전</b>이라 아무것도 태우지 않는 것이 맞다.
     *
     * <p>🕳 <b>지금은 항상 걸린다</b> — C(코딩 에이전트 구현)·D(산출)가 아직 없다.
     * 그래서 이 PR 만으로는 착수가 <b>시작되지 않고</b>, 후보는 {@code SELECTED} 로 남는다.
     * 게이트는 열렸지만 <b>되돌릴 수 없는 일은 일어나지 않는다.</b>
     */
    private boolean executorReady() {
        // TODO(#18-C/D): CodingAgent·TargetWorkspaceSource 배선이 들어오면
        //   「그 빈들이 있는가」로 바꾼다. 지금은 실행 경로 자체가 없다.
        return false;
    }

    /**
     * 🔴 트랜잭션 안에서 불리는 것을 막는다.
     *
     * <p>이 클래스는 {@code @Transactional} 을 붙이지 않았지만 <b>호출자가 감싸면
     * 규율이 조용히 깨진다.</b> 증상이 「느리다」뿐이라 리뷰에서도 놓치기 쉽다 —
     * 샌드박스 하나가 <b>커넥션을 30분</b> 잡는다.
     */
    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "착수를 트랜잭션 안에서 부를 수 없다 — 샌드박스 실행이 커넥션을 점유한다. "
                            + "호출자의 @Transactional 을 제거하고, 영속화가 필요하면 호출이 끝난 뒤 "
                            + "짧은 트랜잭션으로 분리한다 (architecture.md 규율)");
        }
    }
}
