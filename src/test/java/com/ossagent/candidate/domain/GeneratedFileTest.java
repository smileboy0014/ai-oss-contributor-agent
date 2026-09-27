package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 🔴 코딩 산출물의 <b>두 방어</b>를 확인한다 — S-4 · #18.
 *
 * <p>{@code glossary.md} 가 못 박은 구분이다 —
 * <b>「경로를 배제했다」와 「내용을 스크럽했다」는 순서가 다른 두 방어이고 서로를 대신하지 않는다.</b>
 *
 * <p>초안은 <b>내용 스크럽만</b> 세웠다. 경로 배제({@code SecretFilePolicy})는
 * #15 가 <b>유입 방향</b>에만 걸어 뒀고, <b>산출 방향에는 없었다</b> — 리뷰가 찾은 네 번째 입구다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GeneratedFileTest {

    @Test
    @DisplayName("시크릿 경로에는 쓰지 않는다 — S-4 경로 배제")
    void 시크릿_경로에는_쓰지_않는다_S4() {
        // 🔴 CodingOutOfPlanException 이 이것을 대신하지 못한다 — 그것은 **계획에 없을 때만**
        //    막는다. 계획(#16)은 대상 저장소 트리에서 뽑히므로 .env 가 계획에 들어갈
        //    가능성이 0 이라고 말할 근거가 없다
        assertThatThrownBy(() -> new GeneratedFile(".env", "SECRET=x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시크릿 경로");

        assertThatThrownBy(() -> new GeneratedFile("id_rsa", "key"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new GeneratedFile("config/app.pem", "key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("평범한 소스 경로는 통과한다 — 과차단하면 아무것도 못 고친다")
    void 평범한_소스_경로는_통과한다() {
        GeneratedFile file = new GeneratedFile(
                "src/main/java/org/springframework/kafka/core/KafkaTemplate.java", "class X {}");

        assertThat(file.path()).endsWith("KafkaTemplate.java");
    }

    @Test
    @DisplayName("내용의 토큰이 가려진다 — S-4 내용 스크럽")
    void 내용의_토큰이_가려진다_S4() {
        // 🔴 조립한다 — 스크럽이 **실제로 무는 모양**이라야 의미가 있다.
        //    모델은 대상 저장소 코드를 컨텍스트로 받았고, 그 저장소가 시크릿을
        //    커밋해 뒀다면 되뱉을 수 있다
        String tokenShaped = "ghp_" + "NOTAREALTOKENFORTESTSONLY" + "A".repeat(11);

        GeneratedFile file = new GeneratedFile("src/main/java/A.java",
                "// 예시 토큰: " + tokenShaped + "\nclass A {}");

        assertThat(file.content()).doesNotContain(tokenShaped);
        assertThat(file.content()).contains("class A {}");
    }

    @Test
    @DisplayName("빈 내용과 null 을 가른다 — 「빈 파일」과 「모델이 안 줬다」는 다르다")
    void 빈_내용과_null_을_가른다() {
        assertThat(new GeneratedFile("src/A.java", "").content()).isEmpty();

        assertThatThrownBy(() -> new GeneratedFile("src/A.java", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
