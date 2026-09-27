package com.ossagent.support.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>머지된 커밋이 검증된다</b>는 것을 고정한다 — #61 · Q-10.
 *
 * <h2>무엇이 새고 있었나 — 실측</h2>
 *
 * <p>워크플로우가 {@code cancel-in-progress: true} 를 <b>무조건</b> 걸고 있었다.
 * PR 에서는 옳다(직전 head 의 결과는 쓸모가 없다). 그런데 {@code main} 은
 * concurrency group 이 {@code refs/heads/main} <b>하나</b>라, 연달아 머지하면
 * <b>앞 머지의 검증이 뒤 머지에 취소된다.</b>
 *
 * <p>🔴 그 커밋은 <b>영영 검증되지 않는다.</b> squash 머지는 새 커밋을 만들므로
 * PR 의 green 은 브랜치 head 의 것이고, 그 {@code main} 커밋을 보는 실행은
 * push 하나뿐이다.
 *
 * <p>실측 (2026-09-27): {@code main} push 최근 25건 중
 * <b>success 16 · cancelled 8 · failure 1</b> — <b>머지 셋 중 하나가 검증 없이</b>
 * 지나갔다. 그리고 유일한 failure 가 난 그 분에 취소된 실행이 2건 더 있었다.
 *
 * <h2>왜 테스트로 고정하나</h2>
 *
 * <p>YAML 한 줄이라 되돌리기가 쉽고, 되돌아간 증상이 <b>「아무 일도 안 일어남」</b>이다 —
 * 빨개지지 않으므로 아무도 모른다. 이 저장소가 반복해 아픈 그 모양이다.
 *
 * <p>⚠ 이 테스트가 읽는 파일은 {@code build.gradle.kts} 의 {@code ciWorkflows} 입력으로
 * 선언돼 있다. 선언하지 않으면 워크플로우만 고친 커밋에서 {@code UP-TO-DATE} 로
 * 건너뛴다 — 요구 0(「돌기는 하는가」).
 *
 * <h2>🕳 이 테스트가 보지 <b>못하는</b> 것 — 새는 쪽 먼저</h2>
 *
 * <ul>
 *   <li>🔴 <b>GitHub 이 이 표현식을 우리가 읽는 대로 평가하는지 보지 못한다.</b>
 *       텍스트 검사다. 표현식이 틀려 항상 거짓이 되어도 여기서는 초록이고,
 *       그때 증상은 <b>PR 실행이 취소되지 않는 것</b>(비용만 는다)이라 역시 조용하다</li>
 *   <li>브랜치 보호·필수 체크 설정은 저장소 설정이라 파일에 없다 — 보지 못한다</li>
 *   <li>워크플로우를 <b>새로 추가</b>하면서 같은 실수를 하면 이 테스트는 {@code build.yml}
 *       만 본다</li>
 * </ul>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CiWorkflowTest {

    /**
     * 🔴 <b>작업 디렉토리 기준 상대경로 하나만 쓴다.</b>
     *
     * <p>처음엔 {@code ../../../.github/…} 를 먼저 보는 폴백을 뒀는데, 이 저장소는
     * worktree 에서 작업하므로 그 경로가 <b>메인 체크아웃</b>으로 빠져나갔다 —
     * 즉 테스트가 <b>지금 고친 파일이 아니라 다른 체크아웃의 옛 파일</b>을 읽었다.
     * 실제로 이 테스트가 그렇게 한 번 빨개졌고, 그것이 없었으면 반대로
     * <b>옛 파일이 초록이라 통과</b>했을 것이다.
     *
     * <p>요구 0b — 「내가 잰 것이 게이트가 도는 그것인가」. 폴백은 그 물음을 흐린다.
     */
    private static final Path WORKFLOW = Path.of(".github", "workflows", "build.yml");

    @Test
    @DisplayName("🔴 main 푸시 실행은 취소되지 않는다 — 취소되면 그 커밋은 영영 검증되지 않는다")
    void main_실행은_취소되지_않는다() throws IOException {
        String yaml = effectiveLines();

        assertThat(yaml)
                .as("🔴 무조건 취소면 연달아 머지할 때 앞 커밋의 검증이 사라진다 (#61)")
                .doesNotContain("cancel-in-progress: true");
        assertThat(yaml)
                .as("PR 에서는 취소가 옳다 — 조건부여야 한다")
                .contains("cancel-in-progress: ${{ github.event_name == 'pull_request' }}");
    }

    @Test
    @DisplayName("게이트가 main 푸시와 PR 양쪽에서 돈다 — 한쪽만이면 우회로가 생긴다")
    void 게이트가_양쪽에서_돈다() throws IOException {
        String yaml = read();

        assertThat(yaml).contains("push:");
        assertThat(yaml).contains("branches: [main]");
        assertThat(yaml).contains("pull_request:");
    }

    @Test
    @DisplayName("🔴 스캔 2종이 CI 에서 tree 모드로 다시 돈다 — --no-verify 우회를 잡는 자리다")
    void 스캔_2종이_tree_모드로_돈다() throws IOException {
        String yaml = read();

        assertThat(yaml).contains("secret-scan.sh");
        assertThat(yaml).contains("safety-boundary-check.sh");
        // staged 모드로 돌면 CI 에서는 스테이징이 없어 **0건을 검사하고 초록**이 된다
        assertThat(yaml).contains("SCAN_MODE: tree");
    }

    /**
     * ⚠ 모수 단언 — 파일을 못 찾으면 위 검사들이 빈 문자열을 훑고 조용히 통과한다.
     * 경로는 테스트 작업 디렉토리(Gradle 은 프로젝트 루트) 기준이지만,
     * worktree·IDE 실행에서 달라질 수 있어 <b>찾지 못하면 실패</b>시킨다.
     */
    /**
     * 🔴 <b>주석을 뺀</b> 실효 설정만 본다.
     *
     * <p>처음엔 원문을 그대로 훑었는데, <b>이 변경을 설명하는 주석</b>이 예전 값을 인용하는
     * 순간 빨개졌다 — 검사 대상이 「설정」인데 「그 설정을 말하는 문장」을 함께 세고 있었다.
     * 원문 검사는 <b>설명을 쓸수록 더 자주 틀리는</b> 검사다.
     */
    private static String effectiveLines() throws IOException {
        return read().lines()
                .filter(line -> !line.strip().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String read() throws IOException {
        Path path = WORKFLOW;
        assertThat(Files.exists(path))
                .as("워크플로우 파일을 찾지 못했다 — 검사가 0건을 훑고 초록이 된다")
                .isTrue();
        String content = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(content).as("워크플로우가 비었다").isNotBlank();
        return content;
    }
}
