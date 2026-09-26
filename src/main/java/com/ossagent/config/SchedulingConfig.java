package com.ossagent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 활성화 — #14.
 *
 * <p>🔴 <b>이것도 {@code scan.schedule.enabled} 에 걸려 있다.</b> 스케줄러 빈만 막고
 * {@code @EnableScheduling} 을 무조건 켜 두면 스케줄러 스레드풀이 <b>이유 없이</b> 뜬다.
 * 켜는 것은 배포 결정이므로 둘 다 같은 스위치에 건다.
 *
 * <p>⚠️ Q-3 을 닫을 때 <b>여기에 {@code @Profile("worker")}</b> 가 붙는다 —
 * 진입점({@code ScanScheduler})과 짝이다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "scan.schedule.enabled", havingValue = "true")
public class SchedulingConfig {
}
