package com.ossagent.repository.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.repository.domain.ContextKeywords.AnalyzableIssueText;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 이슈 텍스트 → 키워드. 순수 함수라 여기서 규칙을 못 박는다 — 이슈 #15 FR-1. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContextKeywordsTest {

    private static List<ContextKeyword> from(String title, String body) {
        return ContextKeywords.from(new AnalyzableIssueText(title, body), 40);
    }

    private static List<String> valuesOf(List<ContextKeyword> keywords, ContextKeyword.Kind kind) {
        return keywords.stream()
                .filter(it -> it.kind() == kind)
                .map(ContextKeyword::normalized)
                .toList();
    }

    @Test
    void 스택트레이스_프레임에서_패키지와_타입을_뽑는다() {
        var keywords = from("NPE 발생", """
                at org.springframework.kafka.listener.KafkaMessageListenerContainer.doStart(KafkaMessageListenerContainer.java:412)
                """);

        assertThat(valuesOf(keywords, ContextKeyword.Kind.PACKAGE))
                .contains("org.springframework.kafka.listener");
        assertThat(valuesOf(keywords, ContextKeyword.Kind.TYPE_NAME))
                .contains("kafkamessagelistenercontainer");
        assertThat(valuesOf(keywords, ContextKeyword.Kind.PATH_LITERAL))
                .as("프레임 끝의 파일 이름도 신호다")
                .contains("kafkamessagelistenercontainer.java");
    }

    @Test
    void 경로_리터럴을_뽑는다() {
        var keywords = from("수정 요청", "`src/main/java/org/x/Poller.java` 를 봐 주세요");

        assertThat(valuesOf(keywords, ContextKeyword.Kind.PATH_LITERAL))
                .contains("src/main/java/org/x/poller.java");
    }

    @Test
    void 버전_번호와_도메인은_경로가_아니다() {
        var keywords = from("3.2.1 에서 발생", "spring.io 문서 참고, e.g. 아래");

        assertThat(valuesOf(keywords, ContextKeyword.Kind.PATH_LITERAL))
                .as("확장자를 보지 않으면 버전 번호가 가장 높은 가중치를 가져간다")
                .isEmpty();
    }

    @Test
    void 문장_첫_낱말은_타입_이름이_아니다() {
        var keywords = from("When the consumer stops", "The Kafka broker restarts");

        assertThat(valuesOf(keywords, ContextKeyword.Kind.TYPE_NAME))
                .as("대문자 하나로 타입을 인정하면 영어 문장과 제품 이름이 전부 타입이 된다")
                .isEmpty();
    }

    @Test
    void 백틱_안의_단어_하나짜리_타입은_건진다() {
        var keywords = from("문제", "`Poller` 가 멈춥니다");

        assertThat(valuesOf(keywords, ContextKeyword.Kind.TYPE_NAME))
                .as("사람이 일부러 표시한 것이라 CamelCase 규칙에서 빠져도 건진다")
                .contains("poller");
    }

    @Test
    void 불용어는_버린다() {
        var keywords = from("Expected behavior", "This should reproduce the problem");

        assertThat(valuesOf(keywords, ContextKeyword.Kind.TERM))
                .as("모든 이슈에 나타나는 낱말은 신호가 아니라 배경이다")
                .doesNotContain("should", "expected", "behavior", "problem", "reproduce");
    }

    @Test
    void 같은_값이_여러_종류로_잡히면_가장_강한_것만_남는다() {
        var keywords = from("KafkaTemplate 문제", "`KafkaTemplate` 이 KafkaTemplate 에서 실패");

        assertThat(keywords.stream().map(ContextKeyword::normalized).filter("kafkatemplate"::equals))
                .as("중복을 두면 본문에 여러 번 나온 낱말이 종류를 이긴다")
                .hasSize(1);
    }

    @Test
    void 상한을_넘으면_강한_것부터_남긴다() {
        var keywords = ContextKeywords.from(new AnalyzableIssueText(
                "`src/main/java/A.java` KafkaTemplate", "alpha bravo charlie delta echo foxtrot"), 2);

        assertThat(keywords).hasSize(2);
        assertThat(keywords).extracting(ContextKeyword::kind)
                .as("약한 낱말이 잘리고 경로·타입이 남아야 한다")
                .doesNotContain(ContextKeyword.Kind.TERM);
    }

    @Test
    void 입력이_길어도_상한까지만_훑는다() {
        String tail = "KafkaTemplate";
        String body = "x".repeat(ContextKeywords.MAX_SCAN_CHARS) + " " + tail;

        var keywords = from("제목", body);

        assertThat(valuesOf(keywords, ContextKeyword.Kind.TYPE_NAME))
                .as("이슈 본문 길이는 우리 통제 밖이다 — 측정 대신 경계를 둔다")
                .doesNotContain("kafkatemplate");
    }

    @Test
    void 제목은_잘려도_남는다() {
        var keywords = ContextKeywords.from(
                new AnalyzableIssueText("KafkaTemplate", "x".repeat(ContextKeywords.MAX_SCAN_CHARS)),
                40);

        assertThat(valuesOf(keywords, ContextKeyword.Kind.TYPE_NAME)).contains("kafkatemplate");
    }

    @Test
    void 신호가_없으면_빈_목록이다() {
        assertThat(from("동작이 이상합니다", "가끔 느려집니다")).isEmpty();
    }
}
