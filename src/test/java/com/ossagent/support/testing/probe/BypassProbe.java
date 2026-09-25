package com.ossagent.support.testing.probe;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 우회 미끼용 애노테이션 — <b>{@code fakes} 프로필 없이</b> 컨텍스트를 띄운다.
 *
 * <p>{@code @SpringBootTest} 를 메타 애노테이트하고 {@code @ActiveProfiles("fakes")} 는 붙이지
 * 않았다. 즉 이것을 쓰면 <b>실물이 올라오고 대역이 빠지는</b> 컨텍스트가 만들어진다.
 *
 * <p>이 저장소에서 <b>실제로 쓰라고 만든 것이 아니다.</b>
 * {@code IntegrationTestProfileTest} 가 「이런 우회를 잡아내는가」를 검사하는 대상이다.
 *
 * @see ProbeUnprofiledBootstrap
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
public @interface BypassProbe {
}
