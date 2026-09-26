package com.ossagent.agent.adapter.out.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 운영 코드에 <b>호스트 실행 경로가 없다</b>를 고정한다 — #17 FR-2 · S-3.
 *
 * <h2>⚠ 이 테스트가 증명하는 것과 증명하지 못하는 것</h2>
 *
 * <p>「호스트 실행 경로를 만들지 않는다」의 원래 증거는 {@code safety-boundary-check.sh} 의
 * 문자열 grep 뿐이었다. 그런데 {@code safety-boundaries.md} 스스로
 * 「<b>훅이 문자열만 본다 → 훅 통과가 합격이 아니다</b>」라고 적어 두었다. 같은 검사를
 * 훅 밖에도 하나 둔다 — 훅은 {@code --no-verify} 로 건너뛸 수 있고, 이 자리는 그럴 수 없다.
 *
 * <p>🔴 <b>그래도 「구조적으로 불가능하다」고 적지 않는다.</b> 리플렉션·{@code ServiceLoader}·
 * JNI 로 우회하면 문자열 검사는 둘 다 못 본다. 증거의 <b>등급</b>이 문자열 검사라는 것을
 * 숨기지 않는다. 진짜 판단은 리뷰가 한다.
 *
 * <p>⚠ Testcontainers 는 걸리지 않는다 — Java API 를 쓰지 프로세스를 띄우지 않는다.
 * 그리고 인프라 컨테이너는 S-3 의 대상이 아니다(Q-2b).
 */
class HostExecutionAbsenceTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /** 호스트에서 프로세스를 띄우는 유일한 수단들. */
    private static final List<String> HOST_EXECUTION = List.of(
            // safety-ok: 검사할 패턴 목록이다 — 이 문자열로 무엇을 실행하지 않는다
            "new ProcessBuilder", "Runtime.getRuntime().exec");

    @Test
    void 운영_코드에_호스트_실행_경로가_없다_S3() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            for (Path file : sources.filter(it -> it.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                for (String pattern : HOST_EXECUTION) {
                    if (content.contains(pattern)) {
                        offenders.add(file + " → " + pattern);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("""
                        대상 저장소 코드는 신뢰할 수 없다. 빌드 스크립트는 임의 코드 실행이고,
                        호스트에서 돌리면 머신이 장악된다 — S-3.
                        실행은 CodeSandbox 를 통해 컨테이너 안에서만 한다.

                        ⚠ 이 검사는 문자열만 본다. 리플렉션으로 우회하면 잡지 못한다.""")
                .isEmpty();
    }

    @Test
    void 검사_대상_소스를_실제로_찾았다() throws IOException {
        // 🔴 경로가 틀리면 0건을 훑고 초록이 된다. 「검증하지 않은 것을 통과라고 하지 않는다」
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            assertThat(sources.filter(it -> it.toString().endsWith(".java")).count())
                    .as("src/main/java 를 훑지 못했다 — 위 테스트가 조용히 통과한 것이다")
                    .isGreaterThan(50);
        }
    }

    @Test
    void 검사기가_실제로_문다() {
        // 미끼 — 판정기가 항상 false 를 돌려주는 고장이 아님을 증명한다.
        // 파일로 심지 않는다: src/main 에 두면 진짜 위반이 되고, 스캔 베이스가 오염된다
        // safety-ok: 검사기가 무는지 보는 미끼 문자열이다 — 컴파일되는 코드가 아니다
        String bait = "var p = new ProcessBuilder(\"sh\");";

        assertThat(HOST_EXECUTION.stream().anyMatch(bait::contains))
                .as("판정기가 물지 않으면 위 테스트는 0건 검사와 구분되지 않는다")
                .isTrue();
    }
}
