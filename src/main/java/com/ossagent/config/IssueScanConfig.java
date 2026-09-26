package com.ossagent.config;

import com.ossagent.issue.application.IssueFilterProperties;
import com.ossagent.issue.application.IssueScanProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 이슈 스캔·필터 설정 바인딩. 조립만 하고 비즈니스 코드를 두지 않는다.
 *
 * <p>⚠ <b>{@code @ExternalAdapter} 를 붙이지 않는다.</b> {@code GitHubClientConfig} 와 달리
 * 이것은 <b>대외 호출을 만들지 않는</b> 순수 설정이라 테스트 컨텍스트에도 있어야 한다.
 * 붙이면 {@code fakes} 프로필에서 빠져 {@code ScanIssuesUseCase} 가 조립되지 못한다.
 */
@Configuration
@EnableConfigurationProperties({IssueScanProperties.class, IssueFilterProperties.class})
public class IssueScanConfig {
}
