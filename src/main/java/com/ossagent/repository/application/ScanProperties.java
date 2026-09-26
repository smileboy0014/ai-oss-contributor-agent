package com.ossagent.repository.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스캔 실행 풀 설정 — {@code scan.*}.
 *
 * <p>⚠️ {@code adapter/in/scheduler} 가 아니라 {@code application} 에 둔다 —
 * {@code issue} 도메인의 {@code IssueScanProperties}·{@code IssueFilterProperties} 와 같은 자리다.
 *
 * <h2>🔴 {@code scan.schedule.*} 는 여기 없다 — 의도다</h2>
 *
 * <p>{@code scan.schedule.enabled} 는 {@code ScanScheduler}·{@code SchedulingConfig} 의
 * {@code @ConditionalOnProperty} 가 <b>직접</b> 읽고, {@code fixed-delay}·{@code initial-delay} 는
 * {@code @Scheduled} 가 플레이스홀더로 읽는다. 이 레코드에 {@code scheduleEnabled} 필드를
 * 두면 <b>바인딩되지도 않으면서</b>(중첩 키 {@code scan.schedule.enabled} 는
 * {@code scan.schedule-enabled} 와 다르다) 「여기서 읽는다」는 인상만 준다 —
 * 영원히 {@code false} 인 필드는 알리바이가 된다.
 *
 * @param queueCapacity        차면 409. 무한 큐는 「쌓이는 줄 모르는」 상태를 만든다
 * @param shutdownGraceSeconds 종료 시 진행 중 스캔을 기다리는 시간
 */
@ConfigurationProperties("scan")
public record ScanProperties(int queueCapacity, int shutdownGraceSeconds) {

    private static final int DEFAULT_QUEUE_CAPACITY = 10;
    private static final int DEFAULT_SHUTDOWN_GRACE_SECONDS = 60;

    public ScanProperties {
        queueCapacity = queueCapacity <= 0 ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
        shutdownGraceSeconds =
                shutdownGraceSeconds <= 0 ? DEFAULT_SHUTDOWN_GRACE_SECONDS : shutdownGraceSeconds;
    }

    public static ScanProperties defaults() {
        return new ScanProperties(DEFAULT_QUEUE_CAPACITY, DEFAULT_SHUTDOWN_GRACE_SECONDS);
    }
}
