package com.ossagent.support.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 자동 테스트가 <b>실제 GitHub · LLM · 샌드박스를 타지 않는다</b>를 지키는 가드 — Q-9.
 *
 * <p>{@code testing-philosophy.md} 는 「{@code @SpringBootTest} 가 붙은 테스트는 대외 의존을
 * 실제로 타지 않는지 먼저 확인한다」고 적어 두었지만 <b>확인의 주체가 사람</b>이었다.
 * 사람이 확인하는 규칙은 어댑터가 늘어나면 지켜지지 않는다. 이 테스트가 그 확인을 대신한다.
 *
 * <p>무엇을 막는지는 안전 경계로 읽는 편이 정확하다 — 읽기 어댑터가 컨텍스트에 올라오면
 * 테스트가 레이트리밋을 태우고, 쓰기 어댑터(#22 · #23)가 올라오면 <b>대상 저장소로 나가는
 * 호출</b>이 된다(S-1 · S-2). 샌드박스라면 신뢰할 수 없는 코드를 실제로 실행한다(S-3).
 *
 * <p>⚠ 오늘 아래 단언은 <b>0건을 검사한다</b> — {@code main} 에 대외 어댑터가 아직 없다(#6 미머지).
 * 그 사실을 숨기지 않고, 판정기가 실제로 물어뜯는지는 {@link ExternalAdapterPackagesTest} 가
 * 따로 증명한다. <b>둘이 한 쌍</b>이다.
 */
@AgentIntegrationTest
class ExternalAdapterIsolationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void 스프링_컨텍스트에_대외_어댑터_빈이_없다_S1_S2_S3() {
        List<String> external = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && ExternalAdapterPackages.isExternalAdapter(type);
                })
                .toList();

        assertThat(external)
                .as("""
                        대외 어댑터가 테스트 컨텍스트에 올라왔다 — 이 테스트는 네트워크·컨테이너를 탄다.
                        금지 기술: %s (persistence 는 제외 — DB 는 Testcontainers 로 실제로 띄운다)

                        고치는 법 — 둘 중 하나다.
                          · 어댑터에 @Profile("!test") 를 달아 테스트 프로필에서 빠지게 한다
                          · 능력 인터페이스의 페이크를 FakeExternalDependencies 에 @Bean 으로 등록한다

                        근거: .claude/rules/conventions/testing-philosophy.md · safety-boundaries S-1·S-2·S-3""",
                        ExternalAdapterPackages.externalTechnologies())
                .isEmpty();
    }

    /**
     * <b>양성 대조</b> — 검사 모수가 0 이 아님을 증명한다.
     *
     * <p>위 단언은 오늘 <b>0건을 검사하고</b> 초록이다({@code main} 에 대외 어댑터가 없다).
     * 판정기가 항상 {@code false} 를 돌려주거나 빈 목록을 훑어도 똑같이 초록이므로,
     * 「검사했다」를 따로 증명해야 한다.
     *
     * <p>가짜 어댑터를 {@code @Component} 로 심는 방법은 <b>쓰지 않는다</b> — 스캔 베이스가
     * {@code com.ossagent} 루트라 그런 미끼는 모든 컨텍스트에 올라와 가드를 영구 RED 로 만든다.
     * 대신 <b>이미 있는 어댑터 빈</b>({@code adapter/out/persistence})을 실제로 찾아내고
     * 「허용」으로 판정했음을 단언한다. 컨텍스트를 오염시키지 않으면서 모수를 증명한다.
     */
    @Test
    void 판정기가_실제_어댑터_빈을_검사했다_양성대조() {
        List<Class<?>> ourBeans = Arrays.stream(context.getBeanDefinitionNames())
                .<Class<?>>map(context::getType)
                .filter(type -> type != null && type.getName().startsWith("com.ossagent."))
                .toList();

        assertThat(ourBeans)
                .as("컨텍스트에서 com.ossagent 빈을 하나도 찾지 못했다 — 위 단언이 0건을 검사한 것이다")
                .isNotEmpty();

        List<Class<?>> persistenceAdapters = ourBeans.stream()
                .filter(type -> type.getName().contains(".adapter.out.persistence."))
                .toList();

        assertThat(persistenceAdapters)
                .as("""
                        영속 어댑터 빈을 찾지 못했다. 판정기가 「어댑터 모양의 빈」을 실제로 훑었다는
                        증거가 없으므로, 위 「대외 어댑터가 없다」는 검사하지 않고 통과한 것일 수 있다.""")
                .isNotEmpty();

        assertThat(persistenceAdapters)
                .as("영속 어댑터를 대외로 잘못 판정했다 — DB 는 Testcontainers 로 실제로 띄운다(Q-2b)")
                .noneMatch(ExternalAdapterPackages::isExternalAdapter);
    }
}
