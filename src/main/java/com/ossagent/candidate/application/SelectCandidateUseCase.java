package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.StatusTransition;
import java.time.Clock;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사람의 승인 지점 — 선정과 취소 (#24 · S-6).
 *
 * <p>PRD §20 이 정한 승인 지점 중 <b>첫 번째 게이트</b>다. #12 가 상태머신으로
 * 「자동으로 {@code SELECTED} 에 도달할 수 없다」를 만들었고, 이것이 <b>사람이 부르는 문</b>이다.
 *
 * <h2>🔴 여기서 다음 단계로 흘려보내지 않는다</h2>
 *
 * <p>선정은 <b>트리아지</b>다 — 비용 0 이고 스캔 1회에 수십 건을 훑는다. 착수는
 * <b>LLM + 샌드박스 최대 30분</b>이다. 「관심 있다」가 곧바로 30분짜리 실행이 되면 고를 수가
 * 없고, 그래서 Q-5 가 둘을 갈랐다. 이 UseCase 는 <b>상태만 바꾸고 끝난다.</b>
 *
 * <h2>🔴 게이트 통과를 커밋 뒤에 남긴다</h2>
 *
 * <p>{@code logging.md} 는 안전 게이트 로그의 목적을 「사고 후 <b>막았는가</b>를 증명할 수
 * 있어야 한다」로 규정한다. 트랜잭션 안에서 찍으면 <b>롤백돼도 로그 라인은 이미 나가</b>
 * 그 증명이 서지 않는다 — #12 가 전이 메서드를 {@link StatusTransition} 반환만 하게
 * 만든 이유다.
 *
 * <p>⚠️ <b>거부는 커밋 뒤에 남길 수 없다.</b> 예외 경로는 롤백이라 {@code afterCommit} 이
 * 돌지 않는다. 그쪽은 예외를 던지기 전에 직접 남긴다.
 */
@Service
public class SelectCandidateUseCase {

    private static final Logger log = LoggerFactory.getLogger(SelectCandidateUseCase.class);

    private static final String MDC_CANDIDATE_ID = "candidateId";

    private final ContributionCandidateRepository candidates;
    private final Clock clock;

    public SelectCandidateUseCase(ContributionCandidateRepository candidates, Clock clock) {
        this.candidates = candidates;
        this.clock = clock;
    }

    /**
     * {@code ANALYZED → SELECTED} — 🔴 <b>사람이 고른다.</b>
     *
     * <p>이 호출만이 {@code selectedAt} 을 채운다. 스케줄러·워커가 이 UseCase 를 부르면
     * 제품 정의가 무너지므로, 호출자는 <b>web 어댑터뿐</b>이어야 한다 —
     * 그 사실을 아키텍처 테스트가 고정한다.
     */
    @Transactional
    public StatusTransition select(Long candidateId) {
        return apply(candidateId, "선정",
                (candidate, c) -> candidate.selectByHuman(c));
    }

    /**
     * {@code SELECTED → REJECTED} — 🔴 <b>사람이 선택을 취소한다.</b>
     *
     * <p>⚠️ <b>되돌릴 수 없다.</b> {@code REJECTED} 는 종단이라 다시 고르려면 재분석이
     * 필요하다 — Q-5 확정 ②의 의도다. <b>번복이 가벼우면 승인이 가벼워진다.</b>
     */
    @Transactional
    public StatusTransition reject(Long candidateId) {
        return apply(candidateId, "선택 취소",
                (candidate, c) -> candidate.cancelSelection(c));
    }

    private StatusTransition apply(Long candidateId, String gate,
            BiFunction<ContributionCandidate, Clock, StatusTransition> action) {

        if (candidateId == null) {
            throw new IllegalArgumentException("후보 식별자는 필수입니다");
        }
        ContributionCandidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));

        StatusTransition transition;
        try {
            transition = action.apply(candidate, clock);
        } catch (RuntimeException e) {
            // 🔴 거부는 여기서 남긴다. 아래 afterCommit 은 롤백 경로에서 돌지 않으므로
            //    거기 두면 「막았다」는 기록이 영영 남지 않는다.
            //    ⚠ 예외 메시지는 우리 어휘다 — 대상 저장소 텍스트가 들어오지 않는다
            log.warn("승인 게이트 거부 gate={} candidateId={} status={} reason={}",
                    gate, candidateId, candidate.getStatus(), e.getMessage());
            throw e;
        }

        // 🔴 커밋이 확정된 뒤에만 「통과했다」를 남긴다
        logAfterCommit(gate, candidateId, transition);
        return transition;
    }

    /**
     * 🔴 커밋 확정 후 게이트 통과를 남긴다 — S-6 · {@code logging.md} 「통과한 것도 남긴다」.
     *
     * <p>⚠️ {@code registerSynchronization} 은 <b>활성 트랜잭션이 없으면 예외</b>다.
     * 이 UseCase 의 {@code @Transactional} 이 전제이고, 그래서 그 전제가 깨졌는지를
     * 조용히 넘기지 않고 확인한다.
     *
     * <p>⚠️ 콜백 안의 예외는 <b>이미 커밋된 승인을 되돌리지 않는다.</b> 로깅 실패가 승인
     * 행위를 무르게 할 수는 없으므로 삼키되, <b>삼켰다는 사실을 남긴다.</b>
     */
    private void logAfterCommit(String gate, Long candidateId, StatusTransition transition) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 없이 불렸다 = 배선이 깨졌다. 로그를 잃지 않되 그 사실을 드러낸다
            log.warn("트랜잭션 동기화가 없다 — 커밋 확정 여부를 알 수 없는 채로 남긴다 gate={}", gate);
            logGatePassed(gate, candidateId, transition);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    logGatePassed(gate, candidateId, transition);
                } catch (RuntimeException e) {
                    log.warn("게이트 통과 기록에 실패했다 — 승인은 이미 커밋됐다 candidateId={}",
                            candidateId, e);
                }
            }
        });
    }

    private void logGatePassed(String gate, Long candidateId, StatusTransition transition) {
        // ⚠ MDC 는 요청 스코프라 finally 에서 지울 주체가 필요하다.
        //   stage·attempt 는 선정·취소에 의미가 없어 싣지 않는다 (logging.md)
        MDC.put(MDC_CANDIDATE_ID, String.valueOf(candidateId));
        try {
            log.info("승인 게이트 통과 gate={} candidateId={} {} → {}{}",
                    gate, candidateId, transition.from(), transition.to(),
                    transition.to().isTerminal() ? " (종단 — 되돌릴 수 없다)" : "");
        } finally {
            MDC.remove(MDC_CANDIDATE_ID);
        }
    }
}
