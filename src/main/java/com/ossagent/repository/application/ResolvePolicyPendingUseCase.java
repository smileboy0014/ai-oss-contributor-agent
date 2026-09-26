package com.ossagent.repository.application;

import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RepositoryPolicyNotFoundException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사람이 기여 규약 <b>보류</b>를 해소한다 — Q-8 · #24 · S-5.
 *
 * <p>Q-8 은 「보류는 자동으로 풀리지 않는다」를 확정하며 재시도·시간경과·횟수소진을 전부
 * 배제했다. 그러면 <b>푸는 경로가 하나도 없다</b> — 이것이 그 경로다.
 *
 * <h2>🔴 「해소」는 「허용」이 아니다</h2>
 *
 * <p>사람이 문서를 읽고 「이 저장소는 AI 기여 금지다」라고 판단하는 것도 <b>정상적인 해소
 * 결과</b>다. 허용 전용으로 두면 금지 판정을 내리려고 DB 를 손으로 고치게 되고, 그쪽이 더
 * 위험하다.
 *
 * <p>🔴 <b>정책 행이 「없는」 것과 「보류」를 가른다.</b> 분석이 5xx·레이트리밋으로 중단되면
 * 행이 아예 만들어지지 않는데, 거기서 허용을 만들어 주면 <b>「읽지 않고 허용」</b> 이 되어
 * S-5 가 정면으로 뚫린다.
 */
@Service
public class ResolvePolicyPendingUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResolvePolicyPendingUseCase.class);

    private static final String MDC_REPOSITORY_ID = "repositoryId";

    /**
     * 해소 근거의 입력 상한.
     *
     * <p>⚠️ 컬럼은 1024 다. 차이는 {@code TokenRedactor} 가 <b>마스킹하며 길이를 늘릴 수
     * 있기</b> 때문이고, 그래서 도메인도 {@code truncate} 를 한 번 더 건다.
     */
    public static final int MAX_NOTE_LENGTH = 1000;

    private final RepositoryPolicyRepository policies;
    private final Clock clock;

    public ResolvePolicyPendingUseCase(RepositoryPolicyRepository policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * @param allowed 사람의 판정. {@code true} 허용 · {@code false} 금지
     * @param note    판단 근거. <b>도메인이 스크럽한다</b> (S-4)
     * @return 해소 시각. 응답이 <b>스크럽된 사유를 되돌려주지 않는</b> 이유는
     *         {@code PolicyResolutionResponse} javadoc
     * @throws RepositoryPolicyNotFoundException 정책 행이 없다 — 분석을 먼저 돌려야 한다
     */
    @Transactional
    public Instant resolve(Long repositoryId, boolean allowed, String note) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수입니다");
        }
        RepositoryPolicy policy = policies.findByRepositoryId(repositoryId)
                .orElseThrow(() -> new RepositoryPolicyNotFoundException(repositoryId));

        try {
            policy.resolvePending(allowed, note, clock);
        } catch (RuntimeException e) {
            // 🔴 거부도 남긴다. 금지를 뒤집으려는 시도일 수 있고, 그것이 막혔다는 사실은
            //    사고 후에 확인할 수 있어야 한다. afterCommit 은 롤백 경로에서 돌지 않는다
            log.warn("보류 해소 거부 repositoryId={} allowed={} reason={}",
                    repositoryId, allowed, e.getMessage());
            throw e;
        }
        // 🔴 save 를 명시한다. 이 UseCase 는 트랜잭션 경계이고 policy 는 관리 상태라
        //    dirty checking 으로도 반영되지만, 의존하면 「@Transactional 이 빠졌을 때」
        //    조용히 사라진다 — #7 에서 실제로 겪은 종류다
        policies.save(policy);

        logAfterCommit(repositoryId, allowed);
        return policy.getResolvedAt();
    }

    /**
     * 🔴 커밋 확정 후에 남긴다 — 롤백된 해소가 로그에 남으면 「막았는가」를 증명하지 못한다.
     *
     * <p>⚠️ <b>사유를 로그에 싣지 않는다.</b> 이미 스크럽됐지만 사람이 쓴 자유 텍스트이고,
     * 개행이 섞이면 로그 인젝션이 된다. 사유는 DB(`resolution_note`)가 들고 있으므로
     * 로그는 <b>무슨 일이 언제 일어났나</b>만 남긴다 — {@code logging.md} 의 「저장 vs 로그」.
     */
    private void logAfterCommit(Long repositoryId, boolean allowed) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("트랜잭션 동기화가 없다 — 커밋 확정 여부를 알 수 없는 채로 남긴다 repositoryId={}",
                    repositoryId);
            logResolved(repositoryId, allowed);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    logResolved(repositoryId, allowed);
                } catch (RuntimeException e) {
                    log.warn("해소 기록에 실패했다 — 해소는 이미 커밋됐다 repositoryId={}", repositoryId, e);
                }
            }
        });
    }

    private void logResolved(Long repositoryId, boolean allowed) {
        MDC.put(MDC_REPOSITORY_ID, String.valueOf(repositoryId));
        try {
            log.info("보류 해소 repositoryId={} 보류 → {} (사람 판단)",
                    repositoryId, allowed ? "허용" : "금지");
        } finally {
            MDC.remove(MDC_REPOSITORY_ID);
        }
    }
}
