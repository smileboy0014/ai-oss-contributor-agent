package com.ossagent.config;

import com.ossagent.repository.application.RepositoryContextProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 저장소 분석 설정 바인딩 — {@code agent.context.*} (이슈 #15).
 *
 * <p>⚠️ {@code @ExternalAdapter} 를 붙이지 <b>않는다.</b> 여기서 올라오는 것은 설정 레코드뿐이고
 * 대외 호출을 하는 빈이 없다. 테스트에서도 이 값들은 그대로 필요하다 —
 * 대역 컨텍스트에서 빠지면 {@code BuildRepositoryContextUseCase} 가 상한 없이 뜬다.
 */
@Configuration
@EnableConfigurationProperties(RepositoryContextProperties.class)
public class RepositoryContextConfig {
}
