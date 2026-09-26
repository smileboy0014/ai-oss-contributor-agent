package com.ossagent.agent.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 대상 저장소의 빌드·테스트를 돌린다 — <b>네트워크 없이</b> (#17 · Q-4 ③).
 *
 * <h2>🔴 네트워크를 인자로 받지 않는다</h2>
 *
 * <p>여기서 도는 것은 대상 저장소가 정한 명령, 즉 <b>신뢰할 수 없는 코드</b>다.
 * 네트워크를 고를 자리가 있으면 언젠가 열린다. 타입에 그 자리가 없다.
 *
 * <h2>🔴 명령은 argv 다 — 쉘을 경유하지 않는다 (S-4)</h2>
 *
 * <p>{@code buildCommand} 는 LLM 이 대상 저장소 문서에서 뽑은 <b>문자열</b>이다(#7).
 * 그것을 {@code sh -c "<문자열>"} 로 돌리면 {@code ;} · {@code &&} · {@code $(…)} 가 전부
 * 살아난다. argv 로 넘기면 쉘이 없어 해석되지 않고, 덤으로
 * {@code FOO=bar cmd} 식의 환경변수 주입도 함께 막힌다.
 */
public record ExecuteCommand(
        SandboxWorkspace workspace,
        SandboxCacheVolume cacheVolume,
        BuildTool buildTool,
        String image,
        List<String> argv,
        SandboxLimits limits) implements SandboxCommand {

    /** Gradle 이 네트워크를 시도하지 않게 하는 플래그. 아래 {@link #withOfflineFlag} 참조. */
    private static final String OFFLINE = "--offline";

    private static final List<String> GRADLE_LAUNCHERS = List.of("./gradlew", "gradlew", "gradle");

    public ExecuteCommand {
        if (workspace == null || cacheVolume == null || buildTool == null || limits == null) {
            throw new SandboxPermanentException("실행 요청의 필수 값이 비었다");
        }
        if (image == null || image.isBlank()) {
            throw new SandboxPermanentException("이미지는 필수다");
        }
        if (argv == null || argv.isEmpty()) {
            throw new SandboxPermanentException("실행할 명령이 비었다");
        }
        if (argv.stream().anyMatch(it -> it == null || it.isBlank())) {
            throw new SandboxPermanentException("명령 인자에 빈 값이 있다");
        }
        buildTool.requireSupported();
        argv = List.copyOf(argv);
    }

    public static ExecuteCommand of(SandboxWorkspace workspace, SandboxCacheVolume cacheVolume,
            BuildTool buildTool, String javaVersion, String defaultImage,
            List<String> argv, SandboxLimits limits) {
        return new ExecuteCommand(workspace, cacheVolume, buildTool,
                SandboxImages.of(javaVersion, defaultImage), withOfflineFlag(argv), limits);
    }

    /**
     * 🔴 <b>「명령은 대상 것」의 유일한 예외</b> — Gradle 실행에만 {@code --offline} 을 덧붙인다.
     *
     * <p>붙이지 않으면 network=none 에서 의존성 해석이 <b>즉시 실패가 아니라
     * DNS/connect 타임아웃</b>으로 나타난다. 30분 예산을 조용히 태우고, 결과가
     * 「테스트 실패」로 오분류된다. 실패의 <b>종류를 보존</b>하려고 붙인다.
     *
     * <p>⚠ {@code argv[0]} 이 Gradle 런처일 때만 붙인다. {@code bash ci/build.sh} 같은
     * 명령에 붙이면 뜻이 깨진다 — 우리가 이해하지 못하는 명령은 건드리지 않는다.
     *
     * <p>⚠ 이미 있으면 다시 붙이지 않는다.
     */
    static List<String> withOfflineFlag(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return argv;
        }
        if (!isGradleLauncher(argv.get(0)) || argv.contains(OFFLINE)) {
            return argv;
        }
        List<String> withFlag = new ArrayList<>(argv.size() + 1);
        withFlag.add(argv.get(0));
        withFlag.add(OFFLINE);
        withFlag.addAll(argv.subList(1, argv.size()));
        return List.copyOf(withFlag);
    }

    private static boolean isGradleLauncher(String first) {
        String value = first.trim().toLowerCase(Locale.ROOT);
        return GRADLE_LAUNCHERS.contains(value);
    }
}
