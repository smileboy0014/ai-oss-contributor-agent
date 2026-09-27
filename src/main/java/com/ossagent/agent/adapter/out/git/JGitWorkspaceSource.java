package com.ossagent.agent.adapter.out.git;

import com.ossagent.agent.adapter.out.sandbox.SandboxProperties;
import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.TargetWorkspaceSource;
import com.ossagent.agent.domain.WorkspaceDiff;
import com.ossagent.agent.domain.WorkspaceException;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.ExternalAdapter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 대상 저장소를 워크스페이스로 가져온다 — #18 · S-3 · S-4.
 *
 * <h2>🔴 익명으로 clone 한다</h2>
 *
 * <p>자격증명을 URL 에 실으면(<code>https://x-access-token:&lt;PAT&gt;@github.com/…</code>)
 * <b>토큰이 {@code .git/config} 에 파일로 앉는다.</b> 그 워크스페이스는
 * {@code WarmCommand}·{@code ExecuteCommand} 가 <b>RW 로 바인드</b>하고, 거기서
 * <b>신뢰할 수 없는 빌드 스크립트가 돈다.</b>
 *
 * <p>⚠️ {@code SandboxCommand} 가 세운 방어는 <b>환경변수 축에만</b> 있다
 * (「환경변수를 받는 자리가 없다」). <b>파일 축은 비어 있었다.</b> 그리고 Q-4 의 잔여 위험
 * 문장이 「호스트는 안전하다 — 바인드 2개 · 소켓 없음 · <b>시크릿 없음</b> · 자원 상한」이라고
 * 단언하는데, <b>토큰이 들어가면 그 문장이 거짓이 된다.</b>
 *
 * <p>대상은 공개 저장소이므로 토큰이 필요 없다. 🔴 <b>「레이트리밋이 걸리니 토큰을 붙이자」가
 * 나올 자리</b>라 여기 못 박는다 — clone 은 REST 레이트리밋과 별개 축이다.
 *
 * <h2>🔴 여기서 push 하지 않는다 — S-1</h2>
 *
 * <p>원격 쓰기는 <b>#22</b> 뿐이고 거기에 owner 어설션이 붙는다.
 * 부재는 {@code JGitPushAbsenceTest} 가 ArchUnit 으로 고정한다 —
 * <b>표면이 좁다는 것을 방어로 세지 않는다.</b>
 */
@Component
@ExternalAdapter
public class JGitWorkspaceSource implements TargetWorkspaceSource {

    private static final Logger log = LoggerFactory.getLogger(JGitWorkspaceSource.class);

    /**
     * 🔴 얕은 clone. 전체 히스토리는 필요 없고 {@code spring-framework} 급은 수 GB 다.
     *
     * <p>⚠️ <b>깊이 1 로 충분한 이유</b> — diff 를 <b>인덱스 대 작업 트리</b>로 만들기
     * 때문이다(아래 {@link #diff}). 부모 커밋을 보지 않으므로 히스토리가 필요 없다.
     *
     * <p>🕳 히스토리를 요구하는 작업이 생기면 이 값을 올리기 <b>전에</b> 그 작업이 정말
     * 히스토리를 봐야 하는지부터 본다. 깊이를 늘리는 것은 <b>수 GB 를 받는 결정</b>이고,
     * 샌드박스 워크스페이스는 실행마다 새로 만들어진다.
     */
    private static final int SHALLOW_DEPTH = 1;

    private final Path workspaceRoot;

    public JGitWorkspaceSource(SandboxProperties properties) {
        this.workspaceRoot = properties.workspaceRoot();
        // 🔴 기동 시 한 번 — 이 빈이 올라온 이상 JGit 이 바깥 설정을 읽지 않는다.
        //    호출 시점에 걸면 「첫 호출 전에 다른 JGit 사용처가 먼저 돈다」는 경로가 남는다
        JGitSystemConfig.suppressNativeGitLookup();
    }

    @Override
    public SandboxWorkspace fetch(RepositoryCoordinates coordinates, String branchName) {
        if (coordinates == null) {
            throw new WorkspaceException("대상 저장소 좌표는 필수다");
        }
        if (branchName == null || branchName.isBlank()) {
            throw new WorkspaceException("작업 브랜치 이름은 필수다 — PRD §14");
        }

        Path target = workspaceRoot.resolve(coordinates.owner() + "__" + coordinates.name());
        try {
            // 🔴 이전 실행의 산출물이 다음 판정을 오염시키지 않게 매번 새로 만든다.
            //    #17 이 「컨테이너는 실행마다 새로 만들고 끝나면 지운다」로 세운 규율과 같은 축이다
            deleteRecursively(target);
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new WorkspaceException("워크스페이스를 만들지 못했다: " + target, e);
        }

        // 🔴 검증된 타입으로 만든다 — 루트 밖이면 여기서 막힌다 (S-3)
        SandboxWorkspace workspace = SandboxWorkspace.under(target, workspaceRoot);

        String url = "https://github.com/" + coordinates.owner() + "/" + coordinates.name() + ".git";
        try (Git git = Git.cloneRepository()
                .setURI(url)                       // 🔴 익명 — 자격증명을 싣지 않는다 (S-4)
                .setDirectory(workspace.path().toFile())
                .setDepth(SHALLOW_DEPTH)
                .setCloneSubmodules(false)         // 🔴 임의 URL 을 따라가지 않는다
                .call()) {

            hardenRepositoryConfig(git);
            git.checkout().setCreateBranch(true).setName(branchName).call();

        } catch (GitAPIException | IOException e) {
            // ⚠ 예외 메시지에 URL 이 실려 온다. 익명이므로 자격증명은 없지만,
            //   원문을 그대로 올리지 않고 우리 어휘로 감싼다 — logging.md
            throw new WorkspaceException(
                    "대상 저장소를 가져오지 못했다 repo=%s/%s".formatted(
                            coordinates.owner(), coordinates.name()), e);
        }

        log.info("워크스페이스 준비 완료 repo={}/{} branch={} depth={}",
                coordinates.owner(), coordinates.name(), branchName, SHALLOW_DEPTH);
        return workspace;
    }

    /**
     * 이후 JGit 작업이 <b>워크스페이스의 훅</b>을 보지 않게 한다.
     *
     * <h2>🔴 필터를 여기서 막지 <b>않는다</b> — 막을 수 없기 때문이다</h2>
     *
     * <p>초안은 {@code [filter] required = false} 를 쓰고 「필터를 껐다」고 적어 뒀다.
     * <b>그 줄은 아무것도 막지 못했다.</b> 두 가지가 동시에 틀렸다.
     *
     * <ol>
     *   <li>🔴 <b>JGit 이 읽지 않는 키다.</b> {@code TreeWalk} 바이트코드를 뜯어 확인했다 —
     *       조회하는 것은 {@code filter.<b>&lt;드라이버명&gt;</b>.smudge} 와
     *       {@code filter.<b>&lt;드라이버명&gt;</b>.useJGitBuiltin} 뿐이고,
     *       <b>서브섹션이 {@code .gitattributes} 가 지정한 드라이버 이름</b>이다.
     *       서브섹션 없이 쓴 값은 어떤 조회에도 매치되지 않는다.
     *       덧붙여 git 의 {@code required} 는 「실패 시 중단할 것인가」이지
     *       <b>「실행할 것인가」가 아니다</b> — {@code false} 여도 필터는 돈다</li>
     *   <li>🔴 <b>순서도 늦다.</b> 이 메서드는 {@code cloneRepository().call()} 이
     *       초기 체크아웃까지 끝낸 <b>뒤에</b> 불린다</li>
     * </ol>
     *
     * <h2>그래서 필터를 실제로 막는 것은 {@link JGitSystemConfig}다</h2>
     *
     * <p>⚠️ JGit 은 smudge 필터를 <b>정말로 셸로 실행한다</b> — {@code DirCacheCheckout} 이
     * {@code FS.runInShell} 로 {@code sh -c "<명령>"} 을 띄운다(바이트코드 확인).
     * 그러나 <b>그 명령 문자열은 {@code .gitattributes} 가 아니라 git config 에서 온다.</b>
     *
     * <table border="1">
     *   <caption>필터 명령이 올 수 있는 곳</caption>
     *   <tr><th>출처</th><th>상태</th></tr>
     *   <tr><td>{@code ~/.gitconfig} · 시스템 · {@code ~/.config/jgit/config}</td>
     *       <td>✅ {@link JGitSystemConfig} 가 <b>빈 설정</b>으로 만든다. 생성자에서 걸므로
     *           <b>clone 보다 먼저</b>다</td></tr>
     *   <tr><td>저장소 {@code .git/config}</td>
     *       <td>✅ 우리가 clone 으로 만든 것이다 — <b>원격은 config 를 보내지 못한다</b></td></tr>
     * </table>
     *
     * <p>🕳 <b>그러므로 이 방어는 억제 하나에 걸려 있다.</b> 누가 {@code suppressNativeGitLookup}
     * 을 걷어내면 <b>그 순간 실제로 뚫린다</b> — 여기 필터 관련 코드가 없는 것이
     * 「안 막는다」가 아니라 <b>「다른 곳에서 막는다」</b>임을 이 주석이 전한다.
     *
     * <h2>훅은 여기서 막아도 늦지 않다</h2>
     *
     * <p>JGit 의 훅 목록은 {@code PreCommit}·{@code CommitMsg}·{@code PostCommit}·{@code PrePush}
     * 넷뿐이고 <b>{@code post-checkout} 이 없다.</b> 체크아웃 중 훅이 돌 자리가 없으므로
     * clone 뒤에 고정해도 실질 손실이 없다.
     */
    private static void hardenRepositoryConfig(Git git) throws IOException {
        StoredConfig config = git.getRepository().getConfig();
        config.setString("core", null, "hooksPath", "/dev/null");
        config.save();
    }

    @Override
    public WorkspaceDiff diff(SandboxWorkspace workspace) {
        if (workspace == null) {
            throw new WorkspaceException("워크스페이스는 필수다");
        }
        try (Git git = Git.open(workspace.path().toFile());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                DiffFormatter formatter = new DiffFormatter(out)) {

            formatter.setRepository(git.getRepository());
            // 인덱스 대 작업 트리 — 얕은 clone 에서도 부모 커밋이 필요 없다
            List<DiffEntry> entries = formatter.scan(
                    new org.eclipse.jgit.dircache.DirCacheIterator(
                            git.getRepository().readDirCache()),
                    new FileTreeIterator(git.getRepository()));
            formatter.format(entries);

            Set<String> paths = new HashSet<>();
            for (DiffEntry entry : entries) {
                // 🔴 삭제는 newPath 가 /dev/null 이다. 양쪽을 다 담아야
                //    「계획 밖 파일을 지웠다」가 판정에서 빠지지 않는다
                addIfReal(paths, entry.getOldPath());
                addIfReal(paths, entry.getNewPath());
            }
            return new WorkspaceDiff(out.toString(StandardCharsets.UTF_8), paths);

        } catch (IOException e) {
            throw new WorkspaceException("변경분을 읽지 못했다", e);
        }
    }

    private static void addIfReal(Set<String> paths, String path) {
        if (path != null && !DiffEntry.DEV_NULL.equals(path)) {
            paths.add(path);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new WorkspaceException("워크스페이스를 비우지 못했다: " + p, e);
                }
            });
        }
    }
}
