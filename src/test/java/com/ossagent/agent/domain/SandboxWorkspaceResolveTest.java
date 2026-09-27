package com.ossagent.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 🔴 <b>LLM 이 준 경로</b>로 워크스페이스 밖에 쓰지 못하게 한다 — #18 · S-3.
 *
 * <h2>{@code under} 와 다른 축이다</h2>
 *
 * <p>{@code under} 가 막는 것은 <b>마운트 소스</b>이고 그 입력은 <b>우리 설정</b>이다.
 * 이쪽이 막는 것은 <b>쓰기 대상</b>이고 그 입력은 <b>모델 출력</b>이다 —
 * 신뢰 등급이 전혀 다르다.
 *
 * <p>⚠️ 계획 경로 검사({@code CodingOutOfPlanException})가 1차로 막지만,
 * <b>계획 자체가 대상 저장소 트리에서 나오므로</b> 그 검사만으로 이 축이 닫히지 않는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SandboxWorkspaceResolveTest {

    @Test
    @DisplayName("🔴 상위 참조로 워크스페이스를 벗어나면 거부한다 — S-3")
    void 상위_참조로_벗어나면_거부한다_S3(@TempDir Path root) throws Exception {
        SandboxWorkspace workspace = workspaceUnder(root);

        assertThatThrownBy(() -> workspace.resolveInside("../escaped.txt"))
                .isInstanceOf(SandboxPermanentException.class);

        // 🔴 문자열로는 하위처럼 보이는 모양 — normalize 전에 보면 통과한다
        assertThatThrownBy(() -> workspace.resolveInside("src/../../escaped.txt"))
                .isInstanceOf(SandboxPermanentException.class);
    }

    @Test
    @DisplayName("절대경로도 거부한다 — 워크스페이스를 통째로 무시한다")
    void 절대경로도_거부한다_S3(@TempDir Path root) throws Exception {
        SandboxWorkspace workspace = workspaceUnder(root);

        assertThatThrownBy(() -> workspace.resolveInside("/etc/passwd"))
                .isInstanceOf(SandboxPermanentException.class);
    }

    @Test
    @DisplayName("평범한 상대경로는 워크스페이스 안으로 풀린다")
    void 평범한_상대경로는_안으로_풀린다(@TempDir Path root) throws Exception {
        SandboxWorkspace workspace = workspaceUnder(root);

        Path resolved = workspace.resolveInside("src/main/java/A.java");

        // ⚠ AssertJ 의 Path.startsWith 는 **실경로를 해석**하므로 없는 파일에서 터진다.
        //   여기서 보는 것은 「경로가 어디를 가리키는가」이지 「그 파일이 있는가」가 아니다
        assertThat(resolved.toString()).startsWith(workspace.path().toString());
        assertThat(resolved.toString()).endsWith("src/main/java/A.java");
    }

    @Test
    @DisplayName("빈 경로는 거부한다")
    void 빈_경로는_거부한다(@TempDir Path root) throws Exception {
        SandboxWorkspace workspace = workspaceUnder(root);

        assertThatThrownBy(() -> workspace.resolveInside("  "))
                .isInstanceOf(SandboxPermanentException.class);
    }

    private static SandboxWorkspace workspaceUnder(Path root) throws Exception {
        Path real = root.toRealPath();
        Path dir = real.resolve("ws");
        Files.createDirectories(dir);
        return SandboxWorkspace.under(dir, real);
    }
}
