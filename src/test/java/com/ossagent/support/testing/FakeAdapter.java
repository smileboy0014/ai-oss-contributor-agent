package com.ossagent.support.testing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 대외 능력의 <b>대역</b>이라는 표시다. 붙이면 <b>테스트 컨텍스트에만 뜬다.</b>
 *
 * <p>{@link com.ossagent.support.ExternalAdapter} 의 짝이다. 실물에 {@code @ExternalAdapter},
 * 대역에 {@code @FakeAdapter} — 둘을 붙이면 배선이 끝난다. <b>등록할 곳이 따로 없다.</b>
 *
 * <pre>
 * &#64;FakeAdapter
 * public class FakeIssueSource implements IssueSource { … }
 * </pre>
 *
 * <p>스캔 베이스가 {@code com.ossagent} 루트이고 Gradle 의 test 런타임 클래스패스에
 * test 클래스 디렉토리가 포함되므로, {@code src/test} 의 이 클래스가 <b>그대로 컴포넌트
 * 스캔에 잡힌다.</b> 중앙 등록 지점(`@TestConfiguration`)이 필요 없는 이유다 — 등록을
 * 잊어 「빈이 없다」로 헤매는 경로 자체를 없앴다.
 *
 * <h2>⚠ 상태가 테스트 사이에 샌다</h2>
 *
 * <p>페이크는 <b>싱글턴</b>이고 스프링 컨텍스트는 테스트 클래스 사이에 <b>캐시된다.</b>
 * 한 테스트에서 {@code given…()} 으로 채운 상태나 누적된 호출 기록이
 * <b>다음 테스트 클래스로 넘어간다.</b>
 *
 * <p>덮어쓰는 종류({@code given(page)})는 대체로 문제가 없지만, <b>누적되는 것</b>
 * ({@code queries()} · {@code fetchedPaths()})은 앞 테스트의 흔적을 본다.
 * 호출 기록을 단언하는 테스트는 {@code @BeforeEach} 에서 페이크를 초기화하거나,
 * 정 어려우면 {@code @DirtiesContext} 로 컨텍스트를 버린다(느리므로 최후 수단).
 *
 * <p>이것은 이 방식 고유의 문제가 아니다 — 중앙 등록(`@Bean`)이어도 싱글턴이라 같다.
 * 다만 자동 등록은 그 사실이 눈에 덜 띄므로 여기 적어 둔다.
 *
 * <p>⚠ 이름은 {@code Fake{능력이름}} — {@code Mock}·{@code Stub} 을 쓰지 않는다.
 * Mockito 의 mock 과 섞여 「무엇이 검증 대상인지」가 흐려진다.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있어야 한다</b> — 예외 · 빈 결과 · 깨진 LLM 출력 · 타임아웃.
 * 「항상 성공만 반환하는 페이크」는 게이트를 검증하지 못한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Profile("test")
public @interface FakeAdapter {
}
