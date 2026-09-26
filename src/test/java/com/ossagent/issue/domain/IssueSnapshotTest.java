package com.ossagent.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.support.secret.TokenRedactor;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이슈 본문·제목에 섞여 들어온 시크릿이 <b>스냅샷을 통과하지 못하는지</b> — S-4 · 이슈 #28.
 *
 * <p>이슈 본문에 로그를 붙여넣으며 토큰이 섞이는 일은 흔하고, 그 값은 DB
 * ({@code issue.body})로도 가고 LLM 프롬프트로도 간다. 값이 들어오는 <b>유일한 문</b>인
 * 이 생성자에서 막아야 두 경로가 한 번에 닫힌다.
 */
class IssueSnapshotTest {

    // ⚠ 런타임 조립이다. testing-philosophy.md 는 조립을 기본값에서 금지하되 「토큰의
    //   길이·문자셋이 실제로 유의미한 테스트」를 예외로 둔다. 스크럽 검증이 그것이다 —
    //   정규식에 물리지 않는 상수를 쓰면 이 테스트가 통째로 공허해진다.
    private static final String LEAKED_TOKEN = "ghp_" + "a".repeat(30);

    private static IssueSnapshot snapshotWith(String title, String body) {
        return new IssueSnapshot(42, title, body, List.of("bug"), "octocat", 3,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"),
                false);
    }

    @Test
    @DisplayName("본문에 섞인 토큰은 스냅샷에 남지 않는다")
    void 본문의_토큰을_스냅샷이_가린다_S4() {
        var snapshot = snapshotWith("제목", "재현 로그:\nAuthorization 실패 token=" + LEAKED_TOKEN);

        assertThat(snapshot.body())
                .as("""
                        이슈 본문은 대상 저장소 사용자가 쓴 임의 텍스트다. 여기서 막지 않으면
                        DB 와 LLM 프롬프트 양쪽으로 원문이 나간다 — 회수는 폐기·재발급뿐이다.""")
                .doesNotContain(LEAKED_TOKEN)
                .contains(TokenRedactor.MASK);
    }

    @Test
    @DisplayName("제목에 섞인 토큰도 가린다 — 컬럼 길이는 보안 경계가 아니다")
    void 제목의_토큰도_가린다_S4() {
        var snapshot = snapshotWith(LEAKED_TOKEN + " 로 호출하면 500", "본문");

        assertThat(snapshot.title())
                .as("title 에 @ExternalText 가 없는 것은 TEXT 컬럼이 아니기 때문이지"
                        + " 외부 텍스트가 아니어서가 아니다")
                .doesNotContain(LEAKED_TOKEN)
                .contains(TokenRedactor.MASK);
    }

    @Test
    @DisplayName("엔티티까지 스크럽된 값이 간다 — DB 로 가는 경로가 닫힌다")
    void 엔티티에도_스크럽된_값이_들어간다_S4() {
        var snapshot = snapshotWith("제목", "token=" + LEAKED_TOKEN);

        Issue issue = Issue.fromSnapshot(1L, "spring-projects", "spring-kafka", snapshot,
                Instant.parse("2026-09-03T00:00:00Z"));

        assertThat(issue.getBody())
                .as("Issue.applySnapshot 이 원문을 그대로 대입하던 자리다 — 값 쪽에서 막았다")
                .doesNotContain(LEAKED_TOKEN);
    }

    @Test
    @DisplayName("재수집해도 이중 마스킹이 생기지 않는다")
    void 재수집이_이중_마스킹을_만들지_않는다_S4() {
        String body = "token=" + LEAKED_TOKEN;

        String once = snapshotWith("제목", body).body();
        String twice = snapshotWith("제목", once).body();

        assertThat(twice)
                .as("""
                        커서가 포함 경계라 같은 이슈가 매 스캔 재수집된다. 스크럽이 멱등이 아니면
                        저장된 값과 새로 읽은 값이 어긋나 내용 변경으로 오판한다.""")
                .isEqualTo(once);
    }

    @Test
    @DisplayName("시크릿이 없는 본문은 훼손하지 않는다")
    void 시크릿이_없으면_원문을_유지한다() {
        String body = "NPE 가 납니다. KafkaTemplate#send 호출 시 재현됩니다.";

        assertThat(snapshotWith("버그 제목", body).body())
                .as("정상 본문을 망가뜨리면 분석 품질이 떨어지고, 결국 스크럽을 끄고 싶어진다")
                .isEqualTo(body);
    }

    @Test
    @DisplayName("라벨은 가리지 않는다 — 후보 판정이 값을 그대로 비교한다")
    void 라벨은_가리지_않는다() {
        var snapshot = new IssueSnapshot(42, "제목", "본문", List.of("good first issue"),
                "octocat", 0, Instant.EPOCH, Instant.EPOCH, false);

        assertThat(snapshot.hasLabel("good first issue"))
                .as("라벨은 메인테이너가 정한 짧은 식별자이지 자유 텍스트가 아니다")
                .isTrue();
    }
}
