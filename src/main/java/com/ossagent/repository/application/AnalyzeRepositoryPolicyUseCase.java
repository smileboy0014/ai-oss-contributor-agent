package com.ossagent.repository.application;

import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.ContributionRuleInterpreter;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.PolicyDocumentPath;
import com.ossagent.repository.domain.PolicyDocumentSource;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryDocuments;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RepositorySource;
import com.ossagent.repository.domain.RuleReading;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대상 저장소의 기여 규약을 수집·판정해 {@link RepositoryPolicy} 로 고정한다 — 이슈 #7.
 *
 * <p><b>이 UseCase 가 S-5 의 실행체다.</b> 「못 읽음」을 「규약 없음」으로 번역하면
 * 규약 위반 PR 이 외부 OSS 로 나간다.
 *
 * <h2>🔴 트랜잭션 경계</h2>
 *
 * <p>GitHub 수집과 LLM 판정은 <b>트랜잭션 밖</b>이다. 경로 13개 × 대외 호출 + LLM 호출이라
 * 한 트랜잭션에 넣으면 DB 커넥션을 그 시간 내내 잡는다. 조회와 저장만 짧은 트랜잭션이다.
 */
@Service
public class AnalyzeRepositoryPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeRepositoryPolicyUseCase.class);

    private final OssRepositoryRepository repositories;
    private final RepositoryPolicyRepository policies;
    private final RepositorySource repositorySource;
    private final PolicyDocumentSource documentSource;
    private final ContributionRuleInterpreter interpreter;
    private final RepositoryPolicyWriter writer;

    public AnalyzeRepositoryPolicyUseCase(OssRepositoryRepository repositories,
            RepositoryPolicyRepository policies, RepositorySource repositorySource,
            PolicyDocumentSource documentSource, ContributionRuleInterpreter interpreter,
            RepositoryPolicyWriter writer) {
        this.repositories = repositories;
        this.policies = policies;
        this.repositorySource = repositorySource;
        this.documentSource = documentSource;
        this.interpreter = interpreter;
        this.writer = writer;
    }

    /**
     * 규약을 분석한다.
     *
     * @return 저장된 정책. <b>일시적 실패로 중단하면 {@link Optional#empty()}</b> —
     *         아무것도 쓰지 않았다는 뜻이고, 다음 스캔이 다시 시도한다
     */
    public Optional<RepositoryPolicy> analyze(Long repositoryId) {
        Snapshot snapshot = load(repositoryId);

        // ① 이미 보류·금지면 더 볼 것이 없다. 반영할 수 없는 판정에 토큰을 쓰지 않는다 — S-5 ⑤
        if (snapshot.blocksReanalysis()) {
            log.info("재분석 대상이 아니다 repo={} — 보류·금지는 사람이 해소한다 (#24)",
                    snapshot.coordinates().fullName());
            return Optional.of(snapshot.policy());
        }

        try {
            // ② 보관된 저장소는 PR 을 받지 않는다. 여기서 안 거르면 파이프라인을 끝까지
            //    돌린 뒤 PR 생성에서야 실패한다
            if (!repositorySource.fetchMetadata(snapshot.coordinates()).acceptsContributions()) {
                log.info("보관된 저장소다 repo={} — 규약을 분석하지 않는다",
                        snapshot.coordinates().fullName());
                return Optional.empty();
            }

            RepositoryDocuments documents =
                    documentSource.collect(snapshot.coordinates(), PolicyDocumentPath.all());

            // ③ 🔴 일시적 실패 — 아무것도 쓰지 않고 중단한다.
            //    보류로 만들면 한 시간 뒤면 저절로 풀렸을 일이 되돌릴 수단 없는 영구 보류가
            //    된다(#24 미구현). 정책이 없으므로 구현 단계는 어차피 막히고(FR-4),
            //    안전 성질은 보류와 같으면서 복구만 자동이다
            if (documents.hasTransientlyUnreadableRequired()) {
                log.warn("규약 문서를 일시적으로 읽지 못했다 repo={} — 기록 없이 중단, 다음 스캔에서 재시도",
                        snapshot.coordinates().fullName());
                return Optional.empty();
            }

            // ④ 영구적으로 못 읽었다 → 보류. 사람이 봐야 풀린다
            if (documents.hasPermanentlyUnreadableRequired()) {
                return Optional.of(writer.savePending(
                        snapshot.repository(), snapshot.policy(), documents.requiredPendingReason()));
            }

            // ⑤ 필수 경로가 전부 404 → 허용. 금지 표기가 존재할 수 없다 (Q-8 확정 ①).
            //    LLM 을 부르지 않는다 — 판정할 텍스트가 없는데 토큰을 쓸 이유가 없다
            RuleReading reading = documents.readDocuments().isEmpty()
                    ? RuleReading.allowedByAbsence()
                    : interpreter.interpret(snapshot.coordinates(), documents);

            // ⑥ 판정이 서지 않았다 → 보류
            if (reading.isUndetermined()) {
                return Optional.of(writer.savePending(
                        snapshot.repository(), snapshot.policy(), "LLM_UNDETERMINED"));
            }
            return Optional.of(writer.saveAnalyzed(snapshot.repository(),
                    snapshot.policy() == null ? null : snapshot.policy().getId(), reading));

        } catch (LlmTransientException e) {
            // LLM 쪽 일시적 실패도 ③과 같다 — 기록 없이 중단
            log.warn("규약 판정이 일시적으로 실패했다 repo={} reason={} — 기록 없이 중단",
                    snapshot.coordinates().fullName(), e.reason());
            return Optional.empty();
        }
    }

    /**
     * 🔴 <b>기여 가능한 저장소인지 단언한다</b> — FR-4 · S-5.
     *
     * <p>{@code boolean} 이 아니라 예외인 것이 설계다. 「RepositoryPolicy 없이 구현 단계로
     * 넘어가지 못하게 <b>막기</b>」가 요구이고, 반환값은 무시할 수 있지만 예외는 무시하기 어렵다.
     *
     * <p>트랜잭션 안에서 조회한다 — {@code OssRepository.policy} 가 LAZY 라 트랜잭션 밖에서
     * 건드리면 {@code LazyInitializationException} 이 난다. 게이트가 그런 이유로 죽으면
     * 호출자가 그것을 {@code catch} 해 넘길 위험이 생긴다.
     *
     * <p>호출자는 #11 · #24 다. 이 PR 은 단언을 제공하고 강제는 그쪽에서 일어난다.
     */
    @Transactional(readOnly = true)
    public void assertContributionAllowed(Long repositoryId) {
        RepositoryPolicy policy = policies.findByRepositoryId(repositoryId)
                .orElseThrow(() -> new ContributionNotAllowedException(
                        repositoryId, ContributionNotAllowedException.Reason.NOT_ANALYZED));

        if (policy.isAiContributionUndetermined()) {
            throw new ContributionNotAllowedException(
                    repositoryId, ContributionNotAllowedException.Reason.UNDETERMINED);
        }
        if (policy.isAiContributionForbidden()) {
            throw new ContributionNotAllowedException(
                    repositoryId, ContributionNotAllowedException.Reason.FORBIDDEN);
        }
    }

    @Transactional(readOnly = true)
    protected Snapshot load(Long repositoryId) {
        OssRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
        return new Snapshot(
                repository,
                new RepositoryCoordinates(repository.getOwner(), repository.getName()),
                policies.findByRepositoryId(repositoryId).orElse(null));
    }



    /** 트랜잭션 밖으로 들고 나가는 값. 엔티티를 LAZY 인 채로 끌고 다니지 않는다. */
    protected record Snapshot(OssRepository repository, RepositoryCoordinates coordinates,
            RepositoryPolicy policy) {

        boolean blocksReanalysis() {
            return policy != null
                    && (policy.isAiContributionUndetermined() || policy.isAiContributionForbidden());
        }
    }
}
