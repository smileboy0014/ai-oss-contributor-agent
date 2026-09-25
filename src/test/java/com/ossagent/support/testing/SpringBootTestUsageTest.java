package com.ossagent.support.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * {@code @SpringBootTest} 를 <b>직접 쓰는 테스트가 없음</b>을 단언한다 — #43.
 *
 * <p>{@link ExternalAdapterIsolationTest} 는 <b>자기가 띄운 컨텍스트</b>만 본다. 다른 테스트가
 * 어떤 컨텍스트를 띄우는지는 알지 못한다. 그래서 진입점을 우회하면 가드가 닿지 않는다.
 *
 * <p>배선은 프로필 하나로 갈린다.
 *
 * <table border="1">
 *   <caption>진입점에 따른 배선</caption>
 *   <tr><th></th><th>{@code @AgentIntegrationTest}</th><th>raw {@code @SpringBootTest}</th></tr>
 *   <tr><td>{@code @ExternalAdapter} (실물)</td><td>❌ 빠짐</td><td>✅ <b>올라옴</b></td></tr>
 *   <tr><td>{@code @FakeAdapter} (대역)</td><td>✅ 뜸</td><td>❌ 안 뜸</td></tr>
 * </table>
 *
 * <p>즉 우회의 증상은 「대역이 조용히 빠지는」 것이 아니라 <b>「실물이 들어오는」</b> 것이다.
 * 쓰기 어댑터(#22 · #23)가 생긴 뒤에는 그 컨텍스트에 <b>Fork push 어댑터</b>가 올라온다 —
 * S-1 · S-2 가 지키는 지점이다.
 *
 * <h2>⚠ 메타 애노테이션 함정</h2>
 *
 * <p>{@link AnnotationTypeFilter} 는 <b>기본적으로 메타 애노테이션을 따라간다.</b> 그대로 두면
 * {@code @AgentIntegrationTest}(자신이 {@code @SpringBootTest} 로 메타 애노테이트된다)를
 * <b>제대로 쓴 클래스까지 전부 적발</b>해 이 테스트가 무의미해진다.
 *
 * <p>그래서 {@code considerMetaAnnotations = false} 로 <b>직접 선언만</b> 본다.
 * 그 구분이 이 테스트의 전부이므로 {@link #준수_클래스를_오탐하지_않는다()} 로 양쪽을 고정한다.
 */
class SpringBootTestUsageTest {

    private static final String BASE_PACKAGE = "com.ossagent";

    @Test
    void SpringBootTest_를_직접_쓰는_테스트가_없다_S1_S2() {
        assertThat(directUsers())
                .as("""
                        @SpringBootTest 를 직접 쓰고 있다. 그 컨텍스트는 test 프로필을 받지 못해
                        실제 대외 어댑터가 올라오고 대역이 빠진다 — 배선이 정확히 반대가 된다.

                        고치는 법: @SpringBootTest 대신 @AgentIntegrationTest 를 쓴다.

                        근거: .claude/rules/conventions/testing-philosophy.md · safety-boundaries S-1·S-2""")
                .isEmpty();
    }

    @Test
    void 준수_클래스를_오탐하지_않는다() {
        // 이 테스트가 이 클래스의 존재 이유다 — considerMetaAnnotations 를 기본값으로 두면
        // @AgentIntegrationTest 를 제대로 쓴 클래스가 전부 위반으로 잡힌다
        assertThat(directUsers())
                .as("@AgentIntegrationTest 를 쓴 클래스가 위반으로 잡혔다 — 메타 애노테이션을 따라가고 있다")
                .doesNotContain(ExternalAdapterIsolationTest.class.getName());
    }

    @Test
    void 스캔이_대상을_실제로_찾았다() {
        // 0건을 훑고 초록이 되는 것을 막는다. 메타까지 포함하면 통합 테스트들이 잡혀야 한다
        assertThat(usersIncludingMetaAnnotated())
                .as("""
                        @SpringBootTest 를 (메타 포함) 쓰는 클래스를 하나도 찾지 못했다.
                        스캔 경로가 잘못됐을 수 있다 — 위 단언은 검사하지 않고 통과한 것이다.""")
                .isNotEmpty();
    }

    /** {@code @SpringBootTest} 를 <b>직접 선언</b>한 클래스만. */
    private static List<String> directUsers() {
        return scan(false);
    }

    /** 메타 애노테이션을 통한 것까지 포함. 스캔이 살아 있는지 확인하는 용도다. */
    private static List<String> usersIncludingMetaAnnotated() {
        return scan(true);
    }

    private static List<String> scan(boolean considerMetaAnnotations) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(
                new AnnotationTypeFilter(SpringBootTest.class, considerMetaAnnotations, false));

        List<String> names = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String name = definition.getBeanClassName();
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }
}
