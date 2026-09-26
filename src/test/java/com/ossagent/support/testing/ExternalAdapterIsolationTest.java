package com.ossagent.support.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.agent.domain.CodeSandbox;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.issue.domain.IssueSource;
import com.ossagent.repository.domain.RepositorySource;
import com.ossagent.support.testing.probe.adapter.out.github.ProbePackageAdapter;
import java.util.ArrayList;
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
 * <p>판정 규칙 자체가 살아 있는지는 {@link ExternalAdaptersTest} 가 따로 증명한다.
 * <b>둘이 한 쌍</b>이다 — 이 클래스는 「컨텍스트에 무엇이 올라왔나」를, 저쪽은
 * 「판정기가 무는가」를 본다.
 */
@AgentIntegrationTest
class ExternalAdapterIsolationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void 스프링_컨텍스트에_대외_어댑터_빈이_없다_S1_S2_S3() {
        List<String> external = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) {
                // 타입을 못 읽은 빈을 조용히 건너뛰면 「0건 검사」와 구분되지 않는다.
                // 세어서 아래에서 드러낸다
                unresolved.add(name);
            } else if (ExternalAdapters.isExternalAdapter(type)) {
                external.add(name + " (" + type.getName() + ")");
            }
        }

        assertThat(external)
                .as("""
                        대외 어댑터가 테스트 컨텍스트에 올라왔다 — 이 테스트는 네트워크·컨테이너를 탄다.
                        금지 기술: %s (persistence 는 제외 — DB 는 Testcontainers 로 실제로 띄운다)

                        고치는 법 — 둘 다 해야 한다.
                          1. 어댑터(또는 그 @Configuration)에 @ExternalAdapter 를 단다
                          2. 같은 능력의 페이크에 @FakeAdapter 를 단다 (등록은 자동이다)

                        근거: .claude/rules/conventions/testing-philosophy.md · safety-boundaries S-1·S-2·S-3""",
                        ExternalAdapters.externalTechnologies())
                .isEmpty();

        assertThat(unresolved)
                .as("""
                        타입을 해석하지 못한 빈이 있다. 이 빈들은 검사에서 빠졌으므로
                        「대외 어댑터가 없다」가 그만큼 덜 검사된 것이다 — 조용히 넘기지 않는다.""")
                .isEmpty();
    }

    /**
     * 능력 인터페이스로 주입되는 것이 <b>페이크인지</b> 본다.
     *
     * <p>빈이 없는 것과 「대역이 제자리에 있는 것」은 다른 문제다. 실어댑터를
     * {@code @ExternalAdapter} 로 빼기만 하고 대역에 {@code @FakeAdapter} 를 달지 않으면, 컨텍스트는 초록인데
     * <b>UseCase 가 주입받을 것이 없는</b> 상태가 된다.
     */
    @Test
    void 능력_인터페이스는_페이크로_주입된다() {
        assertThat(context.getBean(IssueSource.class).getClass().getName())
                .as("IssueSource 가 페이크가 아니다 — 대역에 @FakeAdapter 가 붙었는지 확인하라")
                .startsWith("com.ossagent.issue.domain.Fake");

        assertThat(context.getBean(RepositorySource.class).getClass().getName())
                .as("RepositorySource 가 페이크가 아니다 — 대역에 @FakeAdapter 가 붙었는지 확인하라")
                .startsWith("com.ossagent.repository.domain.Fake");

        assertThat(context.getBean(LanguageModel.class).getClass().getName())
                .as("LanguageModel 이 페이크가 아니다 — 실물이 뜨면 테스트가 LLM 을 실제로 호출하고 돈을 태운다")
                .startsWith("com.ossagent.agent.domain.Fake");

        assertThat(context.getBean(CodeSandbox.class).getClass().getName())
                .as("CodeSandbox 가 페이크가 아니다 — 실물이 뜨면 테스트가 실제 Docker 데몬을 잡고, "
                        + "샌드박스는 신뢰할 수 없는 대상 저장소 코드를 실행하는 물건이다 (S-3)")
                .startsWith("com.ossagent.agent.domain.Fake");
    }

    /**
     * {@code @FakeAdapter} 의 <b>자동 등록이 실제로 먹는지</b> 확인한다.
     *
     * <p>대역은 중앙 등록 없이 컴포넌트 스캔으로 올라온다 — test 클래스 디렉토리가
     * 런타임 클래스패스에 있고 스캔 베이스가 {@code com.ossagent} 루트이기 때문이다.
     * <b>이 전제가 깨지면 조용히 아무 페이크도 등록되지 않는다.</b> 위 테스트가 결과를
     * 잡지만, 원인을 「페이크를 안 만들었나」가 아니라 「자동 등록이 안 먹나」로 바로
     * 읽히게 하려고 따로 둔다.
     */
    @Test
    void 페이크가_자동_등록된다() {
        assertThat(context.getBeanNamesForAnnotation(FakeAdapter.class))
                .as("""
                        @FakeAdapter 가 붙은 빈이 하나도 없다 — 컴포넌트 스캔이 test 클래스를
                        집지 못한 것이다. 대역이 전부 빠진 상태이므로 UseCase 테스트가 전부 깨진다.""")
                .isNotEmpty();
    }

    /**
     * <b>검사 모수가 0 이 아님</b>을 증명한다.
     *
     * <p>위 「대외 어댑터 빈이 없다」는 판정기가 빈 목록을 훑어도, 항상 {@code false} 를
     * 돌려줘도 똑같이 초록이다. 그래서 두 가지를 함께 단언한다.
     *
     * <ol>
     *   <li>판정기가 <b>어댑터 모양의 실제 빈</b>({@code adapter/out/persistence})을 훑었다 —
     *       모수가 0 이 아니다</li>
     *   <li>같은 판정기가 미끼를 <b>문다</b> — 항상 {@code false} 인 고장이 아니다</li>
     * </ol>
     *
     * <p>가짜 어댑터를 {@code @Component} 로 심는 방법은 쓰지 않는다 — 스캔 베이스가
     * {@code com.ossagent} 루트라 그런 미끼는 모든 컨텍스트에 올라와 가드를 영구 RED 로 만든다.
     */
    @Test
    void 판정기가_실제_빈을_훑었고_미끼를_문다() {
        List<Class<?>> persistenceAdapters = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null && type.getName().contains(".adapter.out.persistence.")) {
                persistenceAdapters.add(type);
            }
        }

        assertThat(persistenceAdapters)
                .as("""
                        영속 어댑터 빈을 찾지 못했다. 판정기가 「어댑터 모양의 빈」을 실제로 훑었다는
                        증거가 없으므로, 위 「대외 어댑터가 없다」는 검사하지 않고 통과한 것일 수 있다.""")
                .isNotEmpty();

        assertThat(persistenceAdapters)
                .as("영속 어댑터를 대외로 잘못 판정했다 — DB 는 Testcontainers 로 실제로 띄운다(Q-2b)")
                .noneMatch(ExternalAdapters::isExternalAdapter);

        assertThat(ExternalAdapters.isExternalAdapter(ProbePackageAdapter.class))
                .as("""
                        판정기가 미끼(%s)를 놓쳤다 — 항상 false 를 돌려주는 고장이면
                        위 단언들이 전부 의미 없이 초록이 된다.""", ProbePackageAdapter.class.getName())
                .isTrue();
    }
}
