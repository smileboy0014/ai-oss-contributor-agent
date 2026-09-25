package com.ossagent.support.testing;

import com.ossagent.support.ExternalAdapter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 통합 테스트의 <b>표준 진입점</b>이다. {@code @SpringBootTest} 를 직접 쓰지 않는다.
 *
 * <p>하는 일은 하나 — {@code fakes} 프로필을 켠다. 그러면 배선이 <b>양쪽에서 저절로</b> 맞는다.
 *
 * <table border="1">
 *   <caption>프로필로 갈리는 것</caption>
 *   <tr><th></th><th>표시</th><th>{@code fakes} 프로필에서</th></tr>
 *   <tr><td>실물</td><td>{@link ExternalAdapter}</td><td>❌ 빠진다</td></tr>
 *   <tr><td>대역</td><td>{@link FakeAdapter}</td><td>✅ 뜬다</td></tr>
 * </table>
 *
 * <p>대역은 <b>컴포넌트 스캔으로 자동 등록</b>된다. 중앙 등록 지점이 없으므로
 * 「등록을 깜빡해서 빈이 없다」는 경로 자체가 없다.
 *
 * <p>이유 — 대외 의존이 셋(GitHub · LLM · 샌드박스)인데 전부 느리거나 비결정적이다.
 * 실물이 컨텍스트에 올라오면 <b>테스트가 네트워크를 탄다</b>. 쓰기 어댑터(#22 · #23)가
 * 생긴 뒤에는 그 호출이 대상 저장소로 나가는 경로가 된다 — S-1 · S-2 의 보조 방어다.
 *
 * <p>⚠ <b>이 애노테이션이 보장의 전부가 아니다.</b> 실제 보장은
 * {@code ExternalAdapterIsolationTest} 가 한다 — 어떤 경로로 올라왔든 컨텍스트에 실어댑터
 * 빈이 있으면 잡는다. 이 애노테이션은 <b>편의</b>이지 게이트가 아니다.
 *
 * <p>🔒 <b>지키는 불변식은 「프로필」이다</b>(#43). 컨텍스트를 어떤 경로로 띄우든
 * {@code fakes} 프로필이 없으면 <b>실물이 올라오고 대역이 빠진다</b> — 배선이 정확히 반대가 된다.
 * {@code IntegrationTestProfileTest} 가 <b>컨텍스트를 띄우는 모든 클래스에 {@code fakes} 프로필이
 * 있는지</b>를 단언하며, 예외 목록은 없다.
 *
 * <p>그래서 <b>자기만의 합성 애노테이션을 만드는 것 자체는 막지 않는다.</b> 다만 만들 때
 * {@code @ActiveProfiles("fakes")} 를 반드시 포함시켜야 한다 — 빠뜨리면 잡힌다.
 *
 * <p>🕳 <b>남는 구멍은 숨기지 않는다.</b> 검사기는 <b>구체·독립 클래스</b>만 훑으므로
 * 비정적 내부 클래스({@code @Nested})에 <b>직접</b> 컨텍스트 애노테이션을 달면 빠진다
 * (바깥 클래스를 통하는 일반적인 경우는 커버된다).
 * {@code new AnnotationConfigApplicationContext(...)} 처럼 <b>손으로</b> 컨텍스트를 만드는 코드도
 * 애노테이션이 없어 잡지 못한다.
 *
 * <p>DB 는 차단 대상이 아니다 — PostgreSQL 은 Testcontainers 로 <b>실제로</b> 띄운다(Q-2b).
 *
 * @see ExternalAdapter
 * @see FakeAdapter
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@ActiveProfiles("fakes")
public @interface AgentIntegrationTest {
}
