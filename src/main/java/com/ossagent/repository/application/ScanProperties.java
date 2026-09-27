package com.ossagent.repository.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스캔 실행 설정 — {@code scan.*}.
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
 * <p>⚠️ {@code defaultInterval} 은 예외다. 그것은 <b>스케줄 주기가 아니라 저장소 주기의
 * 기본값</b>이고, {@code ScanScheduler} 가 코드로 읽어 {@code OssRepository.isDueForScan}
 * 에 넘긴다 — 실제로 읽는 자리가 있다.
 *
 * @param queueCapacity        차면 409. 무한 큐는 「쌓이는 줄 모르는」 상태를 만든다
 * @param shutdownGraceSeconds 종료 시 진행 중 스캔을 기다리는 시간
 * @param leaseDuration        🔴 자리를 붙들 수 있는 시간 — 아래
 * @param defaultInterval      저장소별 주기가 없을 때 쓰는 기본 주기 (FR-1)
 */
@ConfigurationProperties("scan")
public record ScanProperties(
        int queueCapacity,
        int shutdownGraceSeconds,
        Duration leaseDuration,
        Duration defaultInterval) {

    private static final int DEFAULT_QUEUE_CAPACITY = 10;
    private static final int DEFAULT_SHUTDOWN_GRACE_SECONDS = 60;

    /**
     * 🔴 <b>스캔 상한보다 넉넉해야 한다.</b> 짧으면 <b>도는 스캔을 남이 뺏는다</b> —
     * 최악이 중복 스캔 1회라 되돌릴 수 있지만 LLM 토큰을 두 번 태운다.
     *
     * <p>기본 2시간은 「스캔 1회가 이 안에 끝난다」는 가정이고, 그 가정이 틀리면
     * <b>이 값 하나만 올린다</b> — 리스는 유일한 조정 지점이다.
     */
    private static final Duration DEFAULT_LEASE = Duration.ofHours(2);

    /** {@code scan.schedule.fixed-delay}(1시간)와 맞춘다 — 주기를 안 적은 저장소가 매 순회 돌지 않게 */
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    public ScanProperties {
        queueCapacity = queueCapacity <= 0 ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
        shutdownGraceSeconds =
                shutdownGraceSeconds <= 0 ? DEFAULT_SHUTDOWN_GRACE_SECONDS : shutdownGraceSeconds;
        // ⚠ 0·음수를 「무제한」으로 읽지 않는다. 리스가 0 이면 모든 행이 즉시 만료로
        //   판정돼 중복 방어가 통째로 사라진다 — 설정 한 줄로 FR-3 이 꺼지는 경로다
        leaseDuration = isPositive(leaseDuration) ? leaseDuration : DEFAULT_LEASE;
        defaultInterval = isPositive(defaultInterval) ? defaultInterval : DEFAULT_INTERVAL;
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    public static ScanProperties defaults() {
        return new ScanProperties(DEFAULT_QUEUE_CAPACITY, DEFAULT_SHUTDOWN_GRACE_SECONDS,
                DEFAULT_LEASE, DEFAULT_INTERVAL);
    }
}
