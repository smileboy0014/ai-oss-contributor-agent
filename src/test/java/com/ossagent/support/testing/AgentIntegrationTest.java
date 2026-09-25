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
 * <p>하는 일은 하나 — {@code test} 프로필을 켠다. 그러면 배선이 <b>양쪽에서 저절로</b> 맞는다.
 *
 * <table border="1">
 *   <caption>프로필로 갈리는 것</caption>
 *   <tr><th></th><th>표시</th><th>{@code test} 프로필에서</th></tr>
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
 * <p>🕳 <b>열려 있는 구멍 — 숨기지 않는다.</b> {@code @SpringBootTest} 를 직접 쓰는 것을
 * 막는 장치는 <b>없다.</b> 그런 테스트는 {@code test} 프로필을 받지 않으므로
 * <b>실물이 올라오고 대역이 빠진다.</b> 즉 우회는 「대역이 빠지는」 조용한 형태가 아니라
 * 「실물이 들어오는」 요란한 형태로 나타나고, 그것은 가드가 잡는다.
 * 정적 스캔으로 사용 자체를 막는 방안은 {@code ClassPathScanningCandidateComponentProvider}
 * 가 메타 애노테이션을 따라가 <b>준수 클래스까지 전부 적발</b>하는 함정이 있어 넣지 않았다.
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
@ActiveProfiles("test")
public @interface AgentIntegrationTest {
}
