package com.ossagent.agent.domain;

import java.time.Duration;

/**
 * 컨테이너에 거는 자원 상한 — #17 · S-3.
 *
 * <h2>🔴 전부 필수다. 기본값에 맡기는 경로를 만들지 않는다</h2>
 *
 * <p>「설정하지 않으면 Docker 기본값」은 <b>제한이 없는 것과 같다.</b> S-3 이 네 가지를
 * 나열한 이유가 그것이고, 하나라도 비면 거부한다.
 *
 * @param cpuQuota  {@code cpuPeriod} 대비 할당량. 코어 수가 아니라 비율이다
 * @param cpuPeriod 스케줄링 주기(마이크로초). Docker 기본은 100,000 (= 100ms)
 * @param memoryBytes 메모리 상한
 * @param pidsLimit 프로세스 수 상한. ⚠ <b>이슈 본문에 없지만 넣는다</b> —
 *                  CPU·메모리만으로는 fork 폭탄을 막지 못한다. 컨테이너가 살아 있는 동안
 *                  호스트 PID 공간을 고갈시킬 수 있다
 * @param timeout   실행 1회의 상한. 🔴 {@code agent.execution.max-retries} 가 세는
 *                  <b>파이프라인 예산과 다른 축</b>이다 — Q-6
 */
public record SandboxLimits(
        long cpuQuota,
        long cpuPeriod,
        long memoryBytes,
        long pidsLimit,
        Duration timeout) {

    public SandboxLimits {
        requirePositive(cpuQuota, "CPU 할당량");
        requirePositive(cpuPeriod, "CPU 주기");
        requirePositive(memoryBytes, "메모리 상한");
        requirePositive(pidsLimit, "PID 상한");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            // 🔴 타임아웃이 없으면 신뢰할 수 없는 코드가 무한히 돈다.
            //    상한 넷 중 이것만 빠뜨리기 쉬워 따로 메시지를 둔다
            throw new SandboxPermanentException(
                    "실행 시간 상한이 없다 — 신뢰할 수 없는 코드가 무한히 돈다 (S-3)");
        }
    }

    /**
     * 메모리와 <b>같은</b> 스왑 상한.
     *
     * <p>🔴 Docker 는 {@code memory} 만 주면 {@code memory-swap} 을 <b>그 2배</b>로 잡는다.
     * 즉 선언한 상한이 상한이 아니게 된다. 같은 값을 주면 스왑이 꺼진다.
     *
     * <p>⚠ 커널이 스왑 계정을 지원하지 않으면 Docker 가 <b>경고만 하고 무시</b>한다.
     * 우리가 보장할 수 있는 것은 「설정에 그렇게 실렸다」까지다.
     */
    public long memorySwapBytes() {
        return memoryBytes;
    }

    private static void requirePositive(long value, String what) {
        if (value <= 0) {
            throw new SandboxPermanentException(
                    what + " 이(가) 없다 — 기본값에 맡기면 제한이 없는 것과 같다 (S-3)");
        }
    }
}
