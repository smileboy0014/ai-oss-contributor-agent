package com.ossagent.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각은 주입받는다. {@code Instant.now()} 를 도메인·유즈케이스에 직접 두면
 * 만료·재시도·타임아웃 경계를 테스트로 고정할 수 없다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
