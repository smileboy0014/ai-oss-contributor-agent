package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 검증기가 쓰는 명령 파서 — #115 · S-3.
 *
 * <p>{@code TargetCommandLine}(agent) 이 먼저 잡은 함정이다: {@code \s+} 로 자르면 개행이
 * 구분자로 삼켜져 {@code ./gradlew test\nrm -rf /} 가 토큰 5개짜리 합법 명령이 된다.
 * 쉘을 거치지 않으므로 RCE 는 아니지만, 형제 클래스가 막은 것을 여기서 열어 둘 이유가 없다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CommandLineTest {

    @Test
    @DisplayName("가로 공백으로 자른다")
    void 가로_공백으로_자른다() {
        assertThat(CommandLine.parse("./gradlew   test\t--tests Foo"))
                .containsExactly("./gradlew", "test", "--tests", "Foo");
    }

    @Test
    @DisplayName("🔴 개행이 든 명령은 거부한다 — 구분자로 삼키지 않는다_S3")
    void 개행은_거부한다_S3() {
        assertThatThrownBy(() -> CommandLine.parse("./gradlew test\nrm -rf /"))
                .isInstanceOf(VerificationSetupException.class);
    }

    @Test
    @DisplayName("쉘 메타문자는 거부한다")
    void 메타문자는_거부한다_S3() {
        assertThatThrownBy(() -> CommandLine.parse("./gradlew test; rm -rf /"))
                .isInstanceOf(VerificationSetupException.class);
    }
}
