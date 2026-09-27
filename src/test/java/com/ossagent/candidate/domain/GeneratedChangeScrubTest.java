package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 🔴 {@code GeneratedChange.diff} 의 스크럽이 <b>실제로 걸리는지</b> 본다 — S-4 · #18.
 *
 * <p>{@code ExternalTextScrubRegistryTest} 는 「누군가 결정을 등록했다」까지만 강제한다 —
 * 데이터 흐름 판정은 리플렉션으로 불가능하다고 그 테스트 자신이 적어 뒀다.
 * <b>「정말 가려지는가」는 여기서만 증명된다.</b>
 *
 * <h2>왜 이 자리가 중요한가</h2>
 *
 * <p>diff 는 <b>대상 저장소 코드 조각</b>이다. 그 저장소가 시크릿을 커밋해 뒀다면
 * diff 에 실려 오고, 이 엔티티는 <b>DB 에 앉는다.</b> 등록표가 이 필드를 「쓰는 코드가
 * 생기는 이슈」로 #18 을 지목해 둔 이유다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GeneratedChangeScrubTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("diff 에 섞인 토큰이 가려진다 — S-4")
    void diff_에_섞인_토큰이_가려진다_S4() {
        // 🔴 조립한다 — 스크럽이 **실제로 무는 모양**이라야 이 검사가 의미를 갖는다.
        //    물리지 않는 문자열로 테스트하면 「가려졌다」가 스크럽 덕인지 애초에 없어서인지
        //    구분되지 않는다 (testing-philosophy.md 요구 3 · 샘플의 대표성).
        //    소스에 리터럴로 두면 이 파일이 커밋되지 않는 것도 같은 이유다.
        String tokenShaped = "ghp_" + "NOTAREALTOKENFORTESTSONLY" + "A".repeat(11);
        String diff = """
                --- a/src/main/resources/application.yml
                +++ b/src/main/resources/application.yml
                @@ -1,3 +1,3 @@
                -github.token: old
                +github.token: %s
                """.formatted(tokenShaped);

        GeneratedChange change = GeneratedChange.record(7L, "oss-agent/issue-13-typo", diff, CLOCK);

        assertThat(change.getDiff())
                .as("대상 저장소가 커밋해 둔 토큰이 DB 로 그대로 간다 — 이 엔티티는 영속된다")
                .doesNotContain(tokenShaped);
        assertThat(change.getDiff())
                .as("diff 의 나머지는 남아야 한다 — 과차단하면 사람이 변경을 읽을 수 없다")
                .contains("application.yml");
    }

    @Test
    @DisplayName("diff 가 null 이면 거부한다 — 「바뀐 것이 없다」와 「못 만들었다」는 다르다")
    void diff_가_null_이면_거부한다() {
        assertThatThrownBy(() -> GeneratedChange.record(7L, "b", null, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 diff 는 유효하다 — 변경이 없었다는 사실도 기록이다")
    void 빈_diff_는_유효하다() {
        GeneratedChange change = GeneratedChange.record(7L, "b", "", CLOCK);

        assertThat(change.getDiff()).isEmpty();
    }

    @Test
    @DisplayName("검증·리뷰 결과는 생성 시점에 비어 있다 — 아직 검증 전이다")
    void 검증_리뷰_결과는_생성_시점에_비어_있다() {
        // 🔴 빈 문자열로 채우면 「검증했는데 출력이 없다」와 구분되지 않는다.
        //    그 구분이 #21 의 에러 분석에 필요하다
        GeneratedChange change = GeneratedChange.record(7L, "b", "diff", CLOCK);

        assertThat(change.getTestResult()).isNull();
        assertThat(change.getReviewResult()).isNull();
    }
}
