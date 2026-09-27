package com.ossagent.config;

import com.ossagent.agent.adapter.out.sandbox.SandboxProperties;
import com.ossagent.agent.domain.CodeSandbox;
import com.ossagent.agent.domain.DependencyCache;
import com.ossagent.candidate.adapter.out.sandbox.SandboxChangeVerifier;
import com.ossagent.candidate.domain.ChangeVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 변경분 검증 조립 — 이슈 #19.
 *
 * <h2>⚠️ 클래스가 아니라 <b>빈 메서드</b>를 대역 프로필에서 뺀다</h2>
 *
 * <p>{@code IssueAnalysisConfig}(#11) · {@code ImplementationPlanConfig}(#16)와 같은 모양이다.
 * {@code @ExternalAdapter} 는 {@code @Target(TYPE)} 이라 메서드에 못 붙으므로
 * 같은 뜻의 {@code @Profile("!fakes")} 를 쓴다.
 *
 * <h2>빼는 이유는 「대외 차단」이 아니다</h2>
 *
 * <p>{@link SandboxChangeVerifier} 는 네트워크 클라이언트를 직접 갖지 않는다 —
 * {@link CodeSandbox} <b>능력</b>에만 기댄다. 실제 차단은 그 능력의 실물
 * ({@code DockerCodeSandbox})에 걸린 {@code @ExternalAdapter} 가 한다.
 *
 * <p>여기서 빼는 이유는 <b>업무 흐름 테스트에서 {@code FakeChangeVerifier} 로 보고서를
 * 직접 주는 편이 맞기</b> 때문이다(Q-9 의 「능력 대역」 층). 단계 순서·판정·스크럽은
 * 페이크 {@link CodeSandbox} 를 끼운 유닛 테스트가 본다.
 *
 * <p>⚠️ 이 {@code @Profile} 이 빠지면 대역과 실물이 <b>둘 다</b> 올라와 컨텍스트가
 * 기동하지 못한다.
 */
@Configuration
public class VerificationConfig {

    /**
     * 🔴 노출되는 {@link CodeSandbox} 빈은 실물 하나다 — 대상 저장소 코드가
     * 컨테이너 밖에서 도는 경로가 조립에 없다 (S-3).
     */
    @Bean
    @Profile("!fakes")
    public ChangeVerifier changeVerifier(CodeSandbox sandbox, DependencyCache dependencyCache,
            SandboxProperties properties) {
        return new SandboxChangeVerifier(sandbox, dependencyCache, properties);
    }
}
