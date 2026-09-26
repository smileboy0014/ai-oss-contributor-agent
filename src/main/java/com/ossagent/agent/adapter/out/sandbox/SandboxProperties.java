package com.ossagent.agent.adapter.out.sandbox;

import com.ossagent.agent.domain.SandboxLimits;
import com.ossagent.agent.domain.SandboxPermanentException;
import com.ossagent.agent.domain.SandboxWorkspace;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * 샌드박스 설정 — #17 · S-3.
 *
 * <h2>🔴 {@code Duration} 단위 함정이 두 방향이다</h2>
 *
 * <p>{@code application.yml} 에 기록이 있다 — {@code agent.llm} 에서 {@code timeout-seconds}
 * 로 써 두었더니 필드명({@code timeout})과 어긋나 <b>바인딩되지 않았고, 값을 낮춰도 아무 일도
 * 일어나지 않았다</b>(#10).
 *
 * <p>여기는 반대 방향 함정이 하나 더 있다 — {@code SANDBOX_TIMEOUT_SECONDS=1800} 을 단위 없이
 * 넣으면 Spring 이 <b>밀리초</b>로 읽어 <b>1.8초</b>가 된다. 모든 실행이 즉시 타임아웃한다.
 * {@link DurationUnit} 으로 단위를 못 박고, {@code SandboxPropertiesTest} 가
 * <b>실제 yml 을 바인딩해 30분인지 단언</b>한다.
 *
 * @param workspaceRoot   🔴 바인드 허용 루트. <b>없으면 경로 제한이 무력해진다</b> —
 *                        「모든 경로가 루트 하위」가 되기 때문이다 (S-3)
 * @param defaultImage    알 수 없는 Java 버전의 폴백 이미지
 * @param warmNetwork     워밍 전용 네트워크 이름. 🔴 <b>자유 문자열이 아니다</b> —
 *                        {@code host}·{@code container:…} 를 거부한다
 * @param cpuQuota        {@code cpuPeriod} 대비 CPU 할당량
 * @param cpuPeriod       CPU 스케줄링 주기(마이크로초)
 * @param memoryBytes     메모리 상한
 * @param pidsLimit       프로세스 수 상한 — fork 폭탄 방어
 * @param timeout         실행 1회의 상한. {@code agent.execution.*} 와 <b>다른 축</b>이다
 * @param warmTimeout     워밍의 상한. 실행보다 짧게
 * @param logTimeout      🔴 로그 수집의 상한. 여기서 매달리면 {@code finally} 의 제거에
 *                        도달하지 못해 <b>FR-5·FR-6 이 동시에 깨진다</b>
 * @param maxOutputChars  출력 상한. 받은 뒤 자르는 것이 아니라 <b>스트리밍 중 끊는다</b>
 * @param dockerApiVersion Q-9b. docker-java 는 API 버전을 협상하지 않는다.
 *                        ⚠ <b>핀이지 고정이 아니다</b> — 데몬이 더 낮으면 내려야 한다
 */
@ConfigurationProperties("sandbox")
public record SandboxProperties(
        Path workspaceRoot,
        String defaultImage,
        String warmNetwork,
        Long cpuQuota,
        Long cpuPeriod,
        Long memoryBytes,
        Long pidsLimit,
        @DurationUnit(ChronoUnit.SECONDS) Duration timeout,
        @DurationUnit(ChronoUnit.SECONDS) Duration warmTimeout,
        @DurationUnit(ChronoUnit.SECONDS) Duration logTimeout,
        Integer maxOutputChars,
        String dockerApiVersion) {

    private static final String DEFAULT_IMAGE = "eclipse-temurin:21-jdk";
    private static final String DEFAULT_WARM_NETWORK = "oss-agent-warm";
    private static final long DEFAULT_CPU_QUOTA = 200_000L;    // cpuPeriod 대비 2코어
    private static final long DEFAULT_CPU_PERIOD = 100_000L;   // Docker 기본
    private static final long DEFAULT_MEMORY_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long DEFAULT_PIDS_LIMIT = 512L;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DEFAULT_WARM_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration DEFAULT_LOG_TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 200_000;
    private static final String DEFAULT_API_VERSION = "1.44";

    /** Docker 네트워크 이름 규칙. 경로·특수 모드로 해석될 여지가 없는 문자만. */
    private static final Pattern NETWORK_NAME = Pattern.compile("[a-z0-9][a-z0-9_.-]{1,62}");

    /**
     * 🔴 네트워크 모드로 쓰면 격리가 무너지는 이름들.
     *
     * <p>{@code host} 는 컨테이너가 <b>호스트 네트워크 네임스페이스를 공유</b>하게 만든다 —
     * 기본 bridge 보다 훨씬 심각하고, 전용 네트워크를 도입한 목적(호스트 서비스 노출 축소)이
     * 정반대로 뒤집힌다. {@code container:<id>} 는 다른 컨테이너에 붙는다.
     */
    private static final List<String> FORBIDDEN_NETWORKS = List.of("host", "none", "bridge", "container");

    public SandboxProperties {
        defaultImage = blankTo(defaultImage, DEFAULT_IMAGE);
        warmNetwork = requireSafeNetwork(blankTo(warmNetwork, DEFAULT_WARM_NETWORK));
        cpuQuota = nullTo(cpuQuota, DEFAULT_CPU_QUOTA);
        cpuPeriod = nullTo(cpuPeriod, DEFAULT_CPU_PERIOD);
        memoryBytes = nullTo(memoryBytes, DEFAULT_MEMORY_BYTES);
        pidsLimit = nullTo(pidsLimit, DEFAULT_PIDS_LIMIT);
        timeout = nullTo(timeout, DEFAULT_TIMEOUT);
        warmTimeout = nullTo(warmTimeout, DEFAULT_WARM_TIMEOUT);
        logTimeout = nullTo(logTimeout, DEFAULT_LOG_TIMEOUT);
        maxOutputChars = nullTo(maxOutputChars, DEFAULT_MAX_OUTPUT_CHARS);
        dockerApiVersion = blankTo(dockerApiVersion, DEFAULT_API_VERSION);

        if (maxOutputChars < 1) {
            throw new SandboxPermanentException("출력 상한은 1 이상이어야 한다: " + maxOutputChars);
        }
        // 🔴 기동 시점에 루트를 검증한다. 런타임까지 끌고 가면 첫 실행에서야 드러난다
        workspaceRoot = SandboxWorkspace.requireValidRoot(workspaceRoot);
    }

    /** 실행 단계 상한. 값이 비면 {@link SandboxLimits} 가 거부한다. */
    public SandboxLimits executeLimits() {
        return new SandboxLimits(cpuQuota, cpuPeriod, memoryBytes, pidsLimit, timeout);
    }

    /** 워밍·씨딩 상한. 실행보다 짧다. */
    public SandboxLimits warmLimits() {
        return new SandboxLimits(cpuQuota, cpuPeriod, memoryBytes, pidsLimit, warmTimeout);
    }

    /**
     * 🔴 네트워크 이름을 그대로 {@code networkMode} 에 넣으면 설정 한 줄로 격리가 뒤집힌다.
     *
     * <p>{@code SANDBOX_NETWORK} 를 설정 키에서 없앤 것과 같은 이유다 — 없앤 문이 다른
     * 이름으로 다시 열리면 없앤 것이 아니다.
     */
    private static String requireSafeNetwork(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_NETWORKS) {
            if (lower.equals(forbidden) || lower.startsWith(forbidden + ":")) {
                throw new SandboxPermanentException(
                        "워밍 네트워크로 쓸 수 없는 이름이다 — 격리가 무너진다 (S-3): " + forbidden);
            }
        }
        if (!NETWORK_NAME.matcher(lower).matches()) {
            throw new SandboxPermanentException("워밍 네트워크 이름이 규칙에 맞지 않는다 (S-3)");
        }
        return lower;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <T> T nullTo(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
