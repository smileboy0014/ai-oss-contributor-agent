package com.ossagent.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;

/**
 * 이 빈은 <b>대외 시스템(GitHub · LLM · 샌드박스)을 실제로 타는 것</b>이라는 표시다.
 * 붙이면 <b>테스트 컨텍스트에서 빠진다.</b>
 *
 * <p>대외 어댑터를 만들 때 기억할 것은 <b>이 애노테이션 하나</b>다. 짝이 되는
 * {@code @FakeAdapter} 를 대역에 붙이면 배선이 끝난다.
 *
 * <pre>
 * // 실물 — 테스트에서 빠진다
 * &#64;Component
 * &#64;ExternalAdapter
 * public class GitHubIssueSource implements IssueSource { … }
 *
 * // 대역 — 테스트에서만 뜬다 (src/test)
 * &#64;FakeAdapter
 * public class FakeIssueSource implements IssueSource { … }
 * </pre>
 *
 * <p><b>왜 필요한가</b> — 대외 의존은 셋 다 느리거나 비결정적이다. 실물이 테스트
 * 컨텍스트에 올라오면 테스트가 레이트리밋을 태우고, 쓰기 어댑터(#22 · #23)라면
 * <b>대상 저장소로 나가는 호출</b>이 된다(S-1 · S-2). 샌드박스라면 신뢰할 수 없는
 * 코드를 실제로 실행한다(S-3).
 *
 * <p>깜빡해도 조용히 새지 않는다 — {@code ExternalAdapterIsolationTest} 가 RED 로 잡는다.
 *
 * <p>⚠ <b>스테레오타입을 대신하지 않는다.</b> {@code @Component}·{@code @Configuration} 은
 * 그대로 두고 여기에 <b>더한다.</b> 이 애노테이션이 스테레오타입을 품지 않는 이유는
 * {@code @Configuration} 클래스에도 똑같이 붙일 수 있게 하기 위해서다 — 전송 클라이언트를
 * 조립하는 {@code @Configuration} 도 테스트에서 빠져야 한다.
 *
 * <p>⚠ 이 애노테이션은 {@code adapter/out} 과 전송 클라이언트({@code support}) 에만 쓴다.
 * {@code domain} 에는 기술이 들어오지 않는다 — architecture 규율 ①.
 *
 * <h2>🔴 {@code fakes} 프로필을 운영에서 켜지 않는다</h2>
 *
 * <p>이름이 뜻하는 것은 「테스트」가 아니라 <b>「대역만 켠다 = 실물을 뺀다」</b>이다.
 * {@code SPRING_PROFILES_ACTIVE=fakes} 로 애플리케이션을 띄우면 GitHub·LLM 어댑터와
 * 전송 설정이 <b>통째로 사라진다.</b>
 *
 * <p>원래 이름은 {@code test} 였는데 배포 환경 이름으로 너무 흔해 바꿨다(#42).
 * ⚠ 다만 <b>위험은 축소된 것이지 제거된 것이 아니다</b> — {@code fakes} 도 평범한
 * 프로파일 문자열이라 누가 켜면 똑같이 사라진다. 「운영인지」를 코드가 판정할 방법이 없어
 * (배포 프로파일이 Q-3 에서 아직 정해지지 않았다) 런타임 가드는 두지 않았고,
 * <b>충돌 불가능한 이름</b>과 이 문단이 그 자리를 대신한다.
 * Q-3 이 배포 프로파일을 확정하면 런타임 가드를 다시 검토한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Profile("!fakes")
public @interface ExternalAdapter {
}
