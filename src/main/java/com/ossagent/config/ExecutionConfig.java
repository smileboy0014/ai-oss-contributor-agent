package com.ossagent.config;

import com.ossagent.candidate.application.ExecutionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 파이프라인 실행 설정 등록 — {@code agent.execution.*} (#18).
 *
 * <h2>🔴 {@code @Profile} 을 붙이지 않는다</h2>
 *
 * <p>{@code ImplementationPlanConfig} 는 <b>빈</b> 쪽에 {@code @Profile("!fakes")} 를 걸어
 * 대역과 실물이 함께 올라오는 것을 막는다. 여기에는 그런 빈이 없다 —
 * {@link ExecutionProperties} 는 <b>설정값</b>이고, 대역 프로필에서도 필요하다.
 *
 * <p>⚠️ 프로필을 붙이면 {@code fakes} 컨텍스트에서 {@code CandidateImplementationWriter} 가
 * 주입받을 값이 사라져 <b>통합 테스트가 기동하지 못한다.</b> 증상이 「빈이 없다」라
 * 원인이 설정 등록에 있다는 것이 바로 보이지 않는다.
 */
@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfig {
}
