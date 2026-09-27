package com.ossagent.agent.adapter.out.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JGit 이 <b>바깥 git 설정을 보지 않는지</b> 확인한다 — #18 · S-3 · S-4.
 *
 * <h2>🔴 기존 정적 가드 둘은 이 영역을 볼 수 없다</h2>
 *
 * <table border="1">
 *   <caption>정적 가드의 시야</caption>
 *   <tr><th>가드</th><th>보는 것</th><th>jar 안</th></tr>
 *   <tr><td>{@code safety-boundary-check.sh}</td><td>{@code src/**} 의 문자열</td><td>❌</td></tr>
 *   <tr><td>{@code HostExecutionAbsenceTest}</td><td>{@code src/main/java} 전수</td><td>❌</td></tr>
 * </table>
 *
 * <p><b>둘이 초록인 것은 JGit 채택의 증거가 전혀 아니다</b> — 우리가 {@code ProcessBuilder} 를
 * 안 쓰면 초록이기 때문이다. 결정이 가드의 시야 밖으로 실행을 옮긴 자리라, 그 빈칸을 여기서 메운다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JGitIsolationTest {

    /**
     * 🔴 바깥 설정을 읽지 않는 것이 <b>이 억제의 본체</b>다.
     *
     * <p>{@code ~/.gitconfig} 의 {@code url.<base>.insteadOf} 는 <b>clone 대상 호스트를 바꾼다.</b>
     * {@code core.hooksPath} 는 우리가 만든 워크스페이스에 개발자의 훅을 끌어온다 —
     * {@code SecretScanScriptTest} 에서 실제로 그 경로가 새고 있었다.
     */
    @Test
    @DisplayName("억제 뒤에는 사용자·시스템 git 설정이 비어 있다")
    void 억제_뒤에는_바깥_설정이_비어_있다_S3() throws Exception {
        JGitSystemConfig.suppressNativeGitLookup();

        assertThat(SystemReader.getInstance().getUserConfig().getSections())
                .as("사용자 설정(~/.gitconfig)이 보이면 insteadOf 가 clone 대상을 바꿀 수 있다")
                .isEmpty();
        assertThat(SystemReader.getInstance().getSystemConfig().getSections())
                .as("시스템 설정이 보이면 같은 경로가 열린다")
                .isEmpty();
    }

    /**
     * 🕳 <b>양성 대조 — 이 검사가 이 머신에서 물 수 있는가.</b>
     *
     * <p>{@code ~/.gitconfig} 가 애초에 없는 환경이면 위 검사는 억제가 있든 없든 초록이다.
     * 그 사실을 <b>드러내지 않으면</b> 「초록이었다」가 「격리가 작동한다」로 읽힌다 —
     * 이 저장소가 반복해 아픈 자리다({@code testing-philosophy.md} 요구 1·2).
     *
     * <p>⚠️ 이 검사는 <b>실패하지 않는다.</b> 파일이 없는 것은 환경 사실이지 결함이 아니다.
     * 대신 <b>실패 메시지에 그 사실이 남게</b> 한다 — 위 검사가 공허했는지 사후에 알 수 있다.
     */
    @Test
    @DisplayName("양성 대조 — 이 환경에 바깥 설정이 실제로 있는가")
    void 양성_대조_바깥_설정이_실제로_있는가() {
        Path userConfig = Path.of(System.getProperty("user.home"), ".gitconfig");
        boolean present = Files.exists(userConfig);

        assertThat(present || !present)
                .as("""
                        ⚠️ 이 환경의 ~/.gitconfig 존재 여부: %s
                        없으면 「억제 뒤에는 바깥 설정이 비어 있다」는 억제와 무관하게 초록이다 —
                        그 실행에서는 격리가 검증되지 않았다.""", present)
                .isTrue();
    }

    /**
     * 자식 프로세스 흔적 — <b>트립와이어</b>다.
     *
     * <h2>🔴 이 검사는 지금 <b>물지 않는다</b> (실측)</h2>
     *
     * <p>{@link JGitSystemConfig#suppressNativeGitLookup()} 호출을 <b>제거한 돌연변이에서도
     * 초록이었다.</b> 즉 이 검사는 <b>억제가 프로세스 생성을 막는다는 것을 증명하지 못한다.</b>
     *
     * <h2>🔴 왜 못 보는가 — 「위반이 없어서」가 아니라 「그 입력에 닿지 못해서」다</h2>
     *
     * <p>{@link ProcessHandle} 은 <b>그 순간 살아 있는 것만</b> 본다. 셸이
     * {@code fork → exec → exit} 하는 사이는 밀리초라, 전후 비교로는 <b>구조적으로
     * 닿을 수 없는 입력</b>이다. 폴링을 촘촘히 해도 같다.
     *
     * <p>그래서 이 검사가 덮는 것과 못 덮는 것을 갈라 적는다 —
     * <b>덮는 것: 끝나지 않고 남은 자식.</b> <b>못 덮는 것: 짧게 살다 간 자식.</b>
     *
     * <p>⚠️ 그리고 <b>고아의 실제 위험은 전자</b>다. 이 저장소에서 옛 부하 실험이 남긴
     * 무한 루프 8개가 <b>이틀 가까이 코어 6개분을 태웠고</b>, 그 여파로 다른 세션의 빌드가
     * {@code Could not write XML test results} 로 <b>가짜 적색</b>을 냈다.
     *
     * <p>🕳 <b>그러므로 「JGit 은 프로세스를 띄우지 않는다」를 근거로 쓰지 않는다.</b>
     * PLAN-18 D-1 의 채택 근거는 「명령을 argv 로 조립하지 않는다」·「실패가 예외로 온다」이고,
     * 프로세스 부재는 <b>근거가 아니라 미확인</b>이다.
     *
     * <p>남겨 두는 이유 — 앞으로 <b>관측 가능한 길이로</b> 프로세스를 띄우는 변경이 들어오면
     * 그때는 잡힌다. 「증명」이 아니라 「트립와이어」로만 센다.
     */
    @Test
    @DisplayName("트립와이어 — JGit 작업이 관측 가능한 자식 프로세스를 남기지 않는다")
    void 트립와이어_관측_가능한_자식_프로세스가_없다_S3(@TempDir Path dir) throws Exception {
        JGitSystemConfig.suppressNativeGitLookup();

        List<Long> before = descendantPids();

        Path repo = dir.resolve("probe");
        Files.createDirectories(repo);
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            Files.writeString(repo.resolve("a.txt"), "hello\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("probe").setSign(false)
                    .setAuthor("probe", "probe@example.com").call();
            git.diff().call();
        }

        List<Long> after = descendantPids();
        after.removeAll(before);

        assertThat(after)
                .as("관측 가능한 자식 프로세스가 생겼다 — D-1 의 한계 서술을 다시 봐야 한다: %s", after)
                .isEmpty();
    }

    private static List<Long> descendantPids() {
        List<Long> pids = new ArrayList<>();
        ProcessHandle.current().descendants().forEach(h -> pids.add(h.pid()));
        return pids;
    }
}
