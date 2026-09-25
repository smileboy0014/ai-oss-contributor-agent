package com.ossagent.support.testing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 통합 테스트의 <b>유일한 진입점</b>이다. {@code @SpringBootTest} 를 직접 쓰지 않는다.
 *
 * <p>이유 — 대외 의존이 셋(GitHub · LLM · 샌드박스)인데 전부 느리거나 비결정적이다.
 * 실제 어댑터가 컨텍스트에 올라오면 <b>테스트가 네트워크를 탄다</b>. 쓰기 어댑터(#22 · #23)가
 * 생긴 뒤에는 그 호출이 대상 저장소로 나가는 경로가 된다 — S-1 · S-2 의 보조 방어다.
 *
 * <p>세 가지를 한 곳에 모은다.
 * <ul>
 *   <li>{@code @SpringBootTest} — 컨텍스트 기동</li>
 *   <li>{@code @ActiveProfiles("test")} — 실제 대외 어댑터가 {@code @Profile("!test")} 로
 *       스스로 빠질 수 있는 <b>이음매</b>. 오늘 {@code main} 에는 대외 어댑터가 없어 효과가 없지만,
 *       어댑터가 생길 때 배선할 자리를 미리 정해 둔다</li>
 *   <li>{@link FakeExternalDependencies} — 페이크 조립 지점</li>
 * </ul>
 *
 * <p>⚠ 이 애노테이션을 쓰는 것만으로는 보장이 되지 않는다. 우회할 수 있기 때문에
 * {@code ExternalAdapterIsolationTest}(올라온 것을 잡는다)와
 * {@code SpringBootTestUsageTest}(우회를 잡는다)가 함께 지킨다.
 *
 * <p>DB 는 차단 대상이 아니다 — PostgreSQL 은 Testcontainers 로 <b>실제로</b> 띄운다(Q-2b).
 * 트랜잭션 경계·동시성은 실 DB 가 아니면 검증되지 않는다.
 *
 * @see FakeExternalDependencies
 * @see ExternalAdapterPackages
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeExternalDependencies.class)
public @interface AgentIntegrationTest {
}
