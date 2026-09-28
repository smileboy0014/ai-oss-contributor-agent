package com.ossagent.agent.adapter.out.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.WorkspaceDiff;
import com.ossagent.agent.domain.WorkspaceException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code apply} 가 <b>실 JGit</b> 으로 무엇을 돌려주는지 고정한다 — #95.
 *
 * <h2>🔴 페이크가 같은 오류를 갖고 있어서 못 잡았다</h2>
 *
 * <p>PR 게이트는 「diff 를 입힌 뒤 {@code diff()} 로 바뀐 경로를 다시 센다」로 짜여 있었고,
 * {@code CreateDraftPrUseCaseTest} 의 페이크는 {@code apply} 와 무관하게 고정 diff 를 돌려줘
 * 초록이었다. 실 JGit 은 {@code apply} 가 <b>인덱스까지 갱신</b>하므로 그 뒤의
 * 「인덱스 대 작업 트리」 diff 가 <b>항상 비어</b>, 게이트가 매번 「입혔는데 바뀐 것이 없다」로 죽었다.
 *
 * <p>여기서는 원격 없이 <b>로컬 저장소</b>로 같은 경로를 돈다 — {@code testing-philosophy.md}
 * 「실제 대외 시스템을 타는 자동 테스트를 만들지 않는다」 안에서 실 구현을 검증하는 길이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JGitWorkspaceSourceApplyTest {

    private static final String MODIFIED = "src/Mod.java";
    private static final String DELETED = "src/Old.java";
    private static final String ADDED = "src/New.java";
    private static final String KEPT = "src/Keep.java";

    @TempDir
    Path root;

    private Path origin;
    private JGitWorkspaceSource source;

    @BeforeEach
    void setUp() throws Exception {
        origin = root.resolve("origin");
        try (Git git = Git.init().setDirectory(origin.toFile()).call()) {
            write(origin, KEPT, "class Keep {}\n");
            write(origin, MODIFIED, "class Mod {\n  int x = 1;\n}\n");
            write(origin, DELETED, "class Old {}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init")
                    .setAuthor("test", "test@example.com")
                    .setCommitter("test", "test@example.com")
                    .call();
        }
        source = new JGitWorkspaceSource(root);
    }

    @Test
    @DisplayName("apply 는 추가·수정·삭제 경로를 전부 돌려주고 작업 트리에 그대로 입힌다")
    void apply_는_바뀐_경로를_돌려준다_S1() throws Exception {
        SandboxWorkspace first = cloneInto("ws1");
        write(first.path(), MODIFIED, "class Mod {\n  int x = 2;\n}\n");
        Files.delete(first.path().resolve(DELETED));
        write(first.path(), ADDED, "class New {}\n");
        WorkspaceDiff diff = source.diff(first);
        assertThat(diff.changedPaths()).containsExactlyInAnyOrder(MODIFIED, DELETED, ADDED);

        SandboxWorkspace second = cloneInto("ws2");
        Set<String> applied = source.apply(second, diff.unifiedDiff());

        assertThat(applied)
                .as("push 에 실릴 경로 — 삭제가 빠지면 Fork 에 옛 파일이 남는다")
                .containsExactlyInAnyOrder(MODIFIED, DELETED, ADDED);
        assertThat(Files.readString(second.path().resolve(MODIFIED))).contains("int x = 2;");
        assertThat(second.path().resolve(ADDED)).exists();
        assertThat(second.path().resolve(DELETED)).doesNotExist();
        assertThat(Files.readString(second.path().resolve(KEPT))).isEqualTo("class Keep {}\n");
    }

    /**
     * 🔴 <b>#95 의 원인을 회귀로 고정한다.</b> 이 단언이 거짓이 되는 날(JGit 이 인덱스를 안 건드리게
     * 바뀌는 날)이 와도 {@code apply} 의 반환값 계약은 그대로다 — 다만 그때는 이 주석을 지운다.
     */
    @Test
    @DisplayName("적용 뒤 인덱스 대 작업 트리 diff 는 비어 있다 — 그래서 apply 가 경로를 돌려준다")
    void 적용_뒤_diff_는_비어_있다() throws Exception {
        SandboxWorkspace first = cloneInto("ws1");
        write(first.path(), MODIFIED, "class Mod {\n  int x = 2;\n}\n");
        String unifiedDiff = source.diff(first).unifiedDiff();

        SandboxWorkspace second = cloneInto("ws2");
        source.apply(second, unifiedDiff);

        assertThat(source.diff(second).isEmpty())
                .as("apply 가 인덱스를 갱신하므로 diff() 로 경로를 다시 세면 0건이다")
                .isTrue();
    }

    @Test
    @DisplayName("upstream 이 움직여 hunk 가 맞지 않으면 WorkspaceException 이다")
    void 맞지_않는_변경분은_예외다() throws Exception {
        SandboxWorkspace first = cloneInto("ws1");
        write(first.path(), MODIFIED, "class Mod {\n  int x = 2;\n}\n");
        String unifiedDiff = source.diff(first).unifiedDiff();

        SandboxWorkspace second = cloneInto("ws2");
        // upstream 이 그 사이 같은 줄을 바꿨다
        write(second.path(), MODIFIED, "class Mod {\n  int x = 99;\n}\n");

        assertThatThrownBy(() -> source.apply(second, unifiedDiff))
                .isInstanceOf(WorkspaceException.class);
    }

    @Test
    @DisplayName("파일 헤더가 없는 텍스트는 입히지 않는다")
    void 헤더_없는_텍스트는_거부한다() throws Exception {
        SandboxWorkspace ws = cloneInto("ws1");

        assertThatThrownBy(() -> source.apply(ws, "just some text\n"))
                .isInstanceOf(WorkspaceException.class);
    }

    private SandboxWorkspace cloneInto(String name) throws Exception {
        Path dir = root.resolve(name);
        try (Git git = Git.cloneRepository()
                .setURI(origin.toUri().toString())
                .setDirectory(dir.toFile())
                .call()) {
            // fetch() 와 같은 모양 — 실 경로 검증을 거친 값 타입으로 만든다
        }
        return SandboxWorkspace.under(dir, root);
    }

    private static void write(Path base, String relative, String content) throws Exception {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
