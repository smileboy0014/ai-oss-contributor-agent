package com.ossagent.pullrequest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Fork 에 올릴 파일은 <b>가려서 올리지 않는다 — 걸리면 거부한다</b> (#111 · S-4).
 *
 * <p>이 값은 파일 전체이고 그대로 커밋된다. 우리가 안 건드린 줄의 정당한 리터럴이
 * {@code ***REDACTED***} 로 바뀌어 나가면 사람이 쓰지 않은 변조가 Draft PR 에 실린다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FileChangeScrubTest {

    @Test
    @DisplayName("시크릿 패턴이 든 내용은 거부한다 — 가려서 올리지 않는다 S-4")
    void 시크릿_패턴이_든_내용은_거부한다_S4() {
        // 🔴 조립한다 — 스크럽이 실제로 무는 모양이라야 이 검사가 의미를 갖는다 (요구 3)
        String tokenShaped = "ghp_" + "NOTAREALTOKENFORTESTSONLY" + "A".repeat(11);

        assertThatThrownBy(() -> FileChange.modified("src/Config.java",
                "class Config { String t = \"" + tokenShaped + "\"; }"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .as("메시지에 걸린 조각을 싣지 않는다")
                        .doesNotContain(tokenShaped)
                        .contains("src/Config.java"));
    }

    @Test
    @DisplayName("시크릿이 없는 내용은 원문 그대로다 — 변조 없이 커밋된다")
    void 시크릿_없는_내용은_원문_그대로다() {
        String content = "class A {\n  // see https://example.com/docs\n}\n";

        assertThat(FileChange.modified("src/A.java", content).content()).isEqualTo(content);
    }

    /**
     * 🔴 URL 자격증명 패턴이 2차식이었다 (#111). {@code ://} 없는 긴 소문자 런에서 시작 위치마다 끝까지
     * 되돌아와 1MB 한 줄이 50분을 돌렸고, 그 경로가 이제 Fork 에 올리는 파일 전체를 지난다.
     * 상한 테스트가 그때 지워졌다 — 여기서 되살린다.
     */
    @Test
    @DisplayName("1MB 한 줄 소문자 런을 선형 시간에 통과한다 — URL 자격증명 패턴이 2차식이 아니다")
    void 긴_소문자_런에서_선형이다() {
        String content = "a".repeat(1024 * 1024);
        long started = System.nanoTime();

        FileChange change = FileChange.modified("src/Long.java", content);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(change.byteLength()).isEqualTo(1024 * 1024);
        assertThat(elapsed)
                .as("2차식이면 이 입력에 분 단위가 든다 — 선형이면 초 안이다")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("총 바이트 상한을 넘는 요청은 거부한다 — #111 이 되살린 검사")
    void 총_바이트_상한() {
        String big = "x\n".repeat(PublishRequest.MAX_TOTAL_BYTES / 2 + 1);

        assertThatThrownBy(() -> new PublishRequest(
                new SyncedFork(ForkRef.of(new com.ossagent.repository.domain.RepositoryCoordinates(
                        "smileboy0014", "spring-kafka"), "smileboy0014"), SyncOutcome.MERGED),
                BaseBranch.main(), BranchName.of(1, "x"),
                CommitMessage.from("Fix it", null,
                        com.ossagent.repository.domain.ContributionConstraints.unknown(), 1, null),
                List.of(FileChange.modified("src/Big.java", big)), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("총 바이트 상한");
    }
}
