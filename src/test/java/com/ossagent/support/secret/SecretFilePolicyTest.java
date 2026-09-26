package com.ossagent.support.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 프롬프트에 실으면 안 되는 파일 경로 판정 — S-4 · 이슈 #28.
 *
 * <p>소비자(#15)가 아직 없어 <b>목록의 정확성만</b> 검증한다. 「이 판정이 실제 호출부에서
 * 불리는가」는 여기서 증명할 수 없고, #15 이 배선할 때 그쪽 테스트가 본다.
 */
class SecretFilePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            ".env",
            ".env.local",
            ".env.production",
            ".envrc",
            "config/.env.example",
            "deploy/server.pem",
            "certs/client.key",
            "keystore/app.p12",
            "credentials.json",
            "credentials-prod.yml",
            "secrets/db.yml",
            "config/secrets/nested/token.txt",
    })
    @DisplayName("자격증명 파일은 배제한다")
    void 자격증명_파일을_배제한다_S4(String path) {
        assertThat(SecretFilePolicy.isSecretPath(path))
                .as("""
                        키 파일에는 우리가 모르는 형식의 자격증명이 들어 있다.
                        TokenRedactor 의 패턴 매칭으로는 「가렸다」고 말할 수 없으므로
                        애초에 열지 않는다 — %s""", path)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".ENV",
            "Deploy/Server.PEM",
            "SECRETS/db.yml",
            "CREDENTIALS.json",
    })
    @DisplayName("대소문자를 가리지 않는다")
    void 대소문자를_가리지_않는다_S4(String path) {
        assertThat(SecretFilePolicy.isSecretPath(path))
                .as("대상 저장소의 파일명 관습은 우리가 정하지 않는다 — %s", path)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/java/org/springframework/kafka/core/KafkaTemplate.java",
            "README.md",
            "CONTRIBUTING.adoc",
            "build.gradle",
            "src/test/resources/application.yml",
            "docs/keynote.md",
            "src/main/java/Monkey.java",
            "src/main/java/org/apache/http/client/CredentialsProvider.java",
    })
    @DisplayName("정상 소스는 통과시킨다")
    void 정상_소스는_통과한다(String path) {
        assertThat(SecretFilePolicy.isSecretPath(path))
                .as("""
                        과배제는 안전하지만 공짜가 아니다. 정상 파일을 막으면 분석 품질이
                        떨어지고, 결국 배제를 끄고 싶어진다. keynote.md 는 key 로 시작하지
                        않고 Monkey.java 는 확장자가 key 가 아니다 — %s""", path)
                .isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "src/"})
    @DisplayName("경로를 알 수 없으면 배제한다 — fail-closed")
    void 경로를_알_수_없으면_배제한다_S4(String path) {
        assertThat(SecretFilePolicy.isSecretPath(path))
                .as("""
                        파일 하나를 덜 보내면 분석 품질이 조금 떨어진다(되돌릴 수 있다).
                        키를 한 번 보내면 회수가 폐기·재발급뿐이다(되돌릴 수 없다).
                        모를 때는 되돌릴 수 없는 쪽을 피한다 — external-deps.md""")
                .isTrue();
    }

    @Test
    @DisplayName("secrets 라는 이름의 파일은 배제 대상이 아니다 — 디렉토리 규칙이다")
    void secrets_파일명은_디렉토리_규칙에_걸리지_않는다() {
        // 규칙이 무엇을 보는지 고정한다. 나중에 파일명까지 넓히더라도 그것은 의도된 변경이어야 한다
        assertThat(SecretFilePolicy.isSecretPath("docs/secrets.md"))
                .as("secrets/ 는 디렉토리 규칙이다. 파일명까지 막으면 문서가 함께 사라진다")
                .isFalse();
    }
}
