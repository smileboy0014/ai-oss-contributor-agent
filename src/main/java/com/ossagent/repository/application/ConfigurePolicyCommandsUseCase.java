package com.ossagent.repository.application;

import com.ossagent.agent.domain.TargetCommandLine;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RepositoryPolicyNotFoundException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사람이 빌드·테스트 명령을 <b>직접</b> 넣는다 — #102 · S-5.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>{@code build_command}·{@code test_command} 의 유일한 출처가 LLM 의 규약 문서 추출이었다.
 * spring-kafka 의 {@code CONTRIBUTING.md} 에는 그런 내용이 없어(Q-8 실측) 값이 {@code null} 이었고,
 * 검증기는 「명령을 못 읽었다」를 「검증할 것이 없다」로 접지 않으므로(S-5) <b>모든 후보가
 * 검증을 시작조차 못 하고 {@code FAILED}</b> 였다. 규약이 침묵하는 것을 사람이 채우는 경로가 이것이다.
 *
 * <h2>🔴 검증기와 같은 화이트리스트로 거른다</h2>
 *
 * <p>{@link TargetCommandLine#parse} 가 쉘 메타문자·모르는 런처·Maven 을 여기서 거부한다 — 저장 시점에
 * 막아야 첫 착수에서야 {@code VerificationSetupException} 으로 드러나는 것을 피한다.
 * 규약 해소({@code ResolvePolicyPendingUseCase})와 같은 이유로 정책 행이 없으면 404 다 —
 * 만들어 주면 「읽지 않고 허용」이 된다.
 */
@Service
public class ConfigurePolicyCommandsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfigurePolicyCommandsUseCase.class);

    private final RepositoryPolicyRepository policies;
    private final Clock clock;

    public ConfigurePolicyCommandsUseCase(RepositoryPolicyRepository policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * @param javaVersion  비면 기존 값을 유지한다
     * @param buildCommand 필수 — 검증의 COMPILE 단계
     * @param testCommand  비면 「규약이 침묵」으로 남긴다(TEST 단계 {@code UNDETERMINED})
     * @return 덮어쓴 시각
     * @throws RepositoryPolicyNotFoundException 정책 행이 없다 — 분석을 먼저 돌린다 (S-5)
     * @throws com.ossagent.agent.domain.SandboxPermanentException 명령이 화이트리스트를 통과하지 못했다 → 422
     */
    @Transactional
    public Instant configure(Long repositoryId, String javaVersion, String buildCommand,
            String testCommand) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수입니다");
        }
        // 🔴 저장 전에 검증기와 같은 파서로 거른다 — 여기서 통과한 것만 검증기가 돌린다
        TargetCommandLine.parse(buildCommand);
        if (testCommand != null && !testCommand.isBlank()) {
            TargetCommandLine.parse(testCommand);
        }
        RepositoryPolicy policy = policies.findByRepositoryId(repositoryId)
                .orElseThrow(() -> new RepositoryPolicyNotFoundException(repositoryId));
        policy.overrideCommands(javaVersion, buildCommand, testCommand, clock);
        policies.save(policy);
        // 명령 문자열은 사람이 넣은 값이지만 로그에는 싣지 않는다 — 길이만
        log.info("규약 명령 수동 설정 repositoryId={} buildLen={} testLen={}", repositoryId,
                buildCommand.length(), testCommand == null ? 0 : testCommand.length());
        return policy.getCommandsOverriddenAt();
    }
}
