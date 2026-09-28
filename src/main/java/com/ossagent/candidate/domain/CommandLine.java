package com.ossagent.candidate.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 규약에서 온 명령 <b>문자열</b>을 argv 로 바꾼다 — #19 · 🔴 S-3.
 *
 * <h2>이 클래스가 존재하는 이유 하나</h2>
 *
 * <p>{@code ContributionConstraints.buildCommand} 는 <b>한 줄짜리 문자열</b>이다
 * ({@code "./gradlew compileJava"}). 그런데 {@code SandboxCommand.argv()} 는
 * <b>리스트</b>이고, 그 사이를 잇는 가장 쉬운 방법이
 * {@code List.of("sh", "-c", buildCommand)} 다. <b>그것이 정확히 S-3 가 금지하는 것이다</b> —
 * {@code external-deps.md}: 「명령은 argv 다. 쉘을 경유하지 않는다.」
 *
 * <p>그 값은 <b>LLM 이 대상 저장소 문서에서 뽑은 자유 문자열</b>이다(#7). 쉘에 넘기면
 * {@code ;}·{@code &&}·{@code $(…)} 가 살아 있고 {@code FOO=bar cmd} 로 환경변수까지
 * 주입된다 — 우리가 {@code SandboxCommand} 에 환경변수 자리를 <b>안 만든</b> 이유(S-4)가
 * 그 한 줄로 무너진다.
 *
 * <p>그래서 변환을 <b>타입이 있는 한 자리</b>에 모으고, 여기서 쉘 메타문자를 <b>거부</b>한다.
 * 문자열이 argv 로 바뀌는 경로가 하나뿐이면 「누가 {@code sh -c} 를 썼나」를 찾을 필요가 없다.
 *
 * <h2>🔴 이스케이프하지 않고 거부한다</h2>
 *
 * <p>인용 규칙을 흉내 내 「안전하게 넘기는」 길도 있다. 택하지 않았다 — 쉘 인용은
 * 구현이 미묘하고, <b>틀리면 조용히 통과</b>한다. 그리고 애초에 <b>쉘이 필요한 빌드 명령이
 * 정상이 아니다.</b> Gradle 호출에 파이프가 필요할 이유가 없다.
 *
 * <p>⚠️ <b>거부는 「보류」로 이어진다.</b> 명령을 해석하지 못한 것을 「명령이 없다」로
 * 번역하지 않는다 — S-5 의 「못 읽음은 허용이 아니다」와 같은 축이다.
 *
 * <h2>🕳 한계 — 조용히 통과하는 것부터</h2>
 *
 * <ol>
 *   <li>🔴 <b>거부 목록은 열거다.</b> 아래 {@link #SHELL_METACHARACTERS} 에 없는 형태는
 *       통과한다. 다만 여집합(「쉘을 안 거치니 메타문자에 <b>의미가 없다</b>」)이 뒤를
 *       받친다 — 이 검사는 <b>의도를 드러내는 경고</b>이지 유일한 방어가 아니다.
 *       진짜 방어는 {@code ExecuteCommand.argv} 가 <b>exec 로 직접</b> 넘어가는 구조다</li>
 *   <li>공백 분할이라 <b>인용된 인자</b>({@code -Dx="a b"})를 두 조각으로 가른다.
 *       Gradle 태스크 이름에는 공백이 없어 지금 문제가 되지 않지만,
 *       {@code -D} 속성을 쓰는 저장소를 만나면 여기를 다시 본다</li>
 * </ol>
 */
public final class CommandLine {

    /**
     * 쉘에서 의미를 갖는 문자들.
     *
     * <p>이것이 들어 있다는 것은 <b>작성자가 쉘을 전제했다</b>는 신호다. argv 로 넘기면
     * 리터럴이 되어 「조용히 다르게 동작」하므로, 통과시키지 않고 드러낸다.
     */
    private static final String SHELL_METACHARACTERS = ";&|<>$`(){}[]*?!#\n\r\\\"'";

    private static final int MAX_TOKENS = 64;
    private static final int MAX_TOKEN_LENGTH = 400;

    private CommandLine() {
    }

    /**
     * @param command 규약에서 온 명령 한 줄
     * @return argv. 🔴 <b>쉘을 경유하지 않는다</b>
     * @throws VerificationSetupException 비었거나 쉘 메타문자가 있거나 상한을 넘으면
     */
    public static List<String> parse(String command) {
        if (command == null || command.isBlank()) {
            throw new VerificationSetupException("실행할 명령이 비어 있다 — 규약에서 읽지 못했다 (S-5)");
        }
        List<String> argv = new ArrayList<>();
        // 🔴 가로 공백으로만 자른다 (#115). `\s+` 는 개행을 구분자로 삼켜
        //    `./gradlew test\nrm -rf /` 가 토큰 5개짜리 합법 명령이 됐다 — 개행이 토큰에
        //    남아야 아래 requireShellFree 가 문다. TargetCommandLine 이 같은 판단을 먼저 했다
        for (String raw : command.trim().split("[ \t]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            requireShellFree(raw);
            if (raw.length() > MAX_TOKEN_LENGTH) {
                throw new VerificationSetupException(
                        "명령 인자가 너무 길다 length=" + raw.length());
            }
            argv.add(raw);
        }
        if (argv.isEmpty()) {
            throw new VerificationSetupException("실행할 명령이 비어 있다");
        }
        if (argv.size() > MAX_TOKENS) {
            throw new VerificationSetupException("명령 인자가 너무 많다 count=" + argv.size());
        }
        return List.copyOf(argv);
    }

    private static void requireShellFree(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x20 || c == 0x7F || SHELL_METACHARACTERS.indexOf(c) >= 0) {
                // 🔴 문자 자체를 메시지에 싣지 않는다 — 명령 문자열은 대상 저장소에서 온
                //    자유 텍스트이고, 이 메시지는 AgentRun.errorMessage 로 들어간다
                throw new VerificationSetupException(
                        "명령에 쉘 메타문자가 있다 — argv 로 넘기면 리터럴이 되어 의도와 다르게 돈다 (S-3)"
                                + " position=" + i);
            }
        }
    }
}
