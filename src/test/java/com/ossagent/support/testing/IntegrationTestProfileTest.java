package com.ossagent.support.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.support.testing.probe.ProbeUnprofiledBootstrap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.util.ClassUtils;

/**
 * 스프링 컨텍스트를 띄우는 테스트가 <b>전부 {@code test} 프로필을 받는지</b> 검사한다 — #43.
 *
 * <p>{@link ExternalAdapterIsolationTest} 는 <b>자기가 띄운 컨텍스트</b>만 본다. 다른 테스트가
 * 어떤 컨텍스트를 띄우는지는 알지 못한다. 그래서 진입점을 우회하면 가드가 닿지 않는다.
 *
 * <table border="1">
 *   <caption>프로필에 따른 배선</caption>
 *   <tr><th></th><th>{@code test} 프로필</th><th>기본 프로필</th></tr>
 *   <tr><td>{@code @ExternalAdapter} (실물)</td><td>❌ 빠짐</td><td>✅ <b>올라옴</b></td></tr>
 *   <tr><td>{@code @FakeAdapter} (대역)</td><td>✅ 뜸</td><td>❌ 안 뜸</td></tr>
 * </table>
 *
 * <p>즉 우회의 증상은 「대역이 조용히 빠지는」 것이 아니라 <b>「실물이 들어오는」</b> 것이다.
 * 쓰기 어댑터(#22 · #23)가 생긴 뒤에는 그 컨텍스트에 <b>Fork push 어댑터</b>가 올라온다 —
 * S-1 · S-2 가 지키는 지점이다.
 *
 * <h2>「{@code @SpringBootTest} 직접 사용 금지」로는 부족하다 — 실제로 확인했다</h2>
 *
 * <p>처음에는 그렇게 짰다가 <b>우회가 실제로 되는 것을 확인하고</b> 바꿨다.
 * {@code AnnotationTypeFilter} 로 「직접 선언」만 보면 아래가 전부 빠져나간다.
 *
 * <ol>
 *   <li>{@code @SpringBootTest} 를 메타 애노테이트한 <b>자기만의 애노테이션</b>을 새로 만드는 경우</li>
 *   <li>추상 상위 클래스를 <b>2단계 이상</b> 거쳐 상속하는 경우 —
 *       {@code AnnotationTypeFilter} 가 조부모까지 따라가지 않는다</li>
 *   <li>{@code @ContextConfiguration} · {@code @DataJpaTest} 등 <b>다른 진입점</b></li>
 * </ol>
 *
 * <p>그래서 검사 축을 <b>「어떻게 띄웠는가」가 아니라 「프로필이 맞는가」</b>로 옮겼다.
 *
 * <ul>
 *   <li>후보를 {@code @SpringBootTest} 로 <b>거르지 않는다.</b> {@code com.ossagent} 의 구체
 *       클래스를 전부 훑고, 컨텍스트를 띄우는지는 {@link BootstrapWith}(모든 테스트 컨텍스트
 *       애노테이션이 이것으로 메타 애노테이트된다) 또는 {@link ContextConfiguration} 로 판정한다.
 *       → 1·3 이 닫힌다</li>
 *   <li>애노테이션을 {@link SearchStrategy#TYPE_HIERARCHY} 로 읽는다. → 2 가 닫힌다</li>
 * </ul>
 *
 * <h2>⚠ 남는 한계 — 숨기지 않는다</h2>
 *
 * <p>스캐너는 <b>구체(concrete)이고 독립(independent)인</b> 클래스만 후보로 본다.
 * 비정적 내부 클래스({@code @Nested})는 후보가 아니다 — 다만 바깥 클래스의 컨텍스트 설정을
 * 물려받고 그 바깥은 검사 대상이므로 실질 커버된다. <b>비정적 내부 클래스에 직접</b>
 * 컨텍스트 애노테이션을 다는 경우는 검사에서 빠진다.
 *
 * <p>{@code new AnnotationConfigApplicationContext(...)} 처럼 <b>손으로 컨텍스트를 만드는</b>
 * 코드는 애노테이션이 없으므로 잡지 못한다.
 */
class IntegrationTestProfileTest {

    private static final String BASE_PACKAGE = "com.ossagent";
    private static final String REQUIRED_PROFILE = "test";

    /** 미끼가 사는 곳. 본 단언에서 제외하지 않으면 가드가 영구 RED 가 된다. */
    private static final String PROBE_PACKAGE = "com.ossagent.support.testing.probe";

    @Test
    void 컨텍스트를_띄우는_테스트는_전부_test_프로필을_받는다_S1_S2() {
        List<String> violations = new ArrayList<>();

        for (Class<?> type : contextBootstrappingClasses()) {
            if (type.getName().startsWith(PROBE_PACKAGE)) {
                continue;   // 미끼는 일부러 위반이다 — 아래 전용 테스트가 검사한다
            }
            if (!hasTestProfile(type)) {
                violations.add(type.getName());
            }
        }

        assertThat(violations)
                .as("""
                        스프링 컨텍스트를 띄우면서 test 프로필을 받지 않는 테스트가 있다.
                        그 컨텍스트에는 실제 대외 어댑터가 올라오고 대역이 빠진다 — 배선이 정확히 반대다.

                        고치는 법: @AgentIntegrationTest 를 쓴다.
                        (자기만의 합성 애노테이션을 만든다면 @ActiveProfiles("test") 를 반드시 포함시킨다)

                        근거: .claude/rules/conventions/testing-philosophy.md · safety-boundaries S-1·S-2""")
                .isEmpty();
    }

    /**
     * 검사기가 <b>실제로 무는지</b> 증명한다.
     *
     * <p>위 단언은 위반이 0건이라 <b>음성만 관찰</b>된다. 검사기가 아무것도 못 잡아도 똑같이
     * 초록이므로, 상시 양성 표본으로 물림을 회귀에 고정한다.
     */
    @Test
    void 프로필_없이_컨텍스트를_띄우는_클래스를_잡아낸다() {
        assertThat(contextBootstrappingClasses())
                .as("미끼(%s)를 「컨텍스트를 띄운다」로 판정하지 못했다 — 검사기가 무력하다",
                        ProbeUnprofiledBootstrap.class.getSimpleName())
                .contains(ProbeUnprofiledBootstrap.class);

        assertThat(hasTestProfile(ProbeUnprofiledBootstrap.class))
                .as("미끼는 test 프로필이 없다 — 이것이 false 가 아니면 프로필 판정이 고장 난 것이다")
                .isFalse();
    }

    @Test
    void 준수_클래스를_오탐하지_않는다() {
        assertThat(hasTestProfile(ExternalAdapterIsolationTest.class))
                .as("@AgentIntegrationTest 를 쓴 클래스의 test 프로필을 읽지 못했다 — 메타 애노테이션 탐색이 고장")
                .isTrue();

        assertThat(contextBootstrappingClasses())
                .as("메타 애노테이션을 통한 사용도 검사 대상에 포함되어야 한다")
                .contains(ExternalAdapterIsolationTest.class);
    }

    @Test
    void 스캔이_대상을_실제로_찾았다() {
        // 0건을 훑고 초록이 되는 것을 막는다
        assertThat(contextBootstrappingClasses())
                .as("컨텍스트를 띄우는 클래스를 하나도 찾지 못했다 — 스캔 경로가 잘못됐을 수 있다")
                .hasSizeGreaterThan(1);
    }

    /** 병합된 {@code @ActiveProfiles} 에 {@code test} 가 있는가 — 상위 클래스까지 본다. */
    private static boolean hasTestProfile(Class<?> type) {
        return MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY)
                .stream(ActiveProfiles.class)
                .map(annotation -> annotation.getStringArray("value"))
                .flatMap(Arrays::stream)
                .anyMatch(REQUIRED_PROFILE::equals);
    }

    /** 스프링 테스트 컨텍스트를 띄우는 클래스 — 진입점 종류를 가리지 않는다. */
    private static List<Class<?>> contextBootstrappingClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));   // 전부 훑는다

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String name = definition.getBeanClassName();
            if (name == null) {
                continue;
            }
            Class<?> type = ClassUtils.resolveClassName(name, null);
            if (bootstrapsContext(type)) {
                classes.add(type);
            }
        }
        return classes;
    }

    private static boolean bootstrapsContext(Class<?> type) {
        MergedAnnotations annotations = MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY);
        return annotations.isPresent(BootstrapWith.class)
                || annotations.isPresent(ContextConfiguration.class);
    }
}
