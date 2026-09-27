package com.ossagent.agent.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 컨테이너에 바인드할 <b>검증된</b> 호스트 디렉토리 — #17 · S-3.
 *
 * <h2>🔴 이 타입이 존재하는 이유</h2>
 *
 * <p>S-3 은 파일시스템 접근을 「작업 디렉토리만」으로 제한한다. 그런데 워크스페이스 경로는
 * <b>호출자가 주는 값</b>이라, 검증하지 않으면 그 제한이 호출자 신뢰에 기댄다.
 *
 * <table border="1">
 *   <caption>검증이 없을 때</caption>
 *   <tr><th>값</th><th>결과</th></tr>
 *   <tr><td>{@code ~/}</td>
 *       <td>신뢰할 수 없는 코드가 홈을 <b>RW 로</b> 잡는다 —
 *           {@code .env} · {@code ~/.gradle/gradle.properties} · {@code ~/.ssh} ·
 *           {@code ~/.docker/config.json}. 전부 S-4 대상이다</td></tr>
 *   <tr><td>{@code <root>/../..} · 심볼릭 링크</td><td>같은 결과</td></tr>
 * </table>
 *
 * <p><b>검증되지 않은 {@link Path} 로는 컨테이너를 만들 수 없다</b> — 어댑터가 받는 것은
 * 이 타입뿐이다. S-1 의 「push 직전 owner 어설션」과 정확히 같은 성격이고,
 * 없으면 이 PR 의 S-3 방어가 문서에 지나지 않는다.
 *
 * <h2>⚠ 이 타입이 막지 <b>못하는</b> 것</h2>
 *
 * <p>🔴 <b>워크스페이스 안의 심볼릭 링크는 축에 따라 답이 다르다</b> (2026-09-27 개정 · #18).
 * 여기 원래 「문제가 아니다」라고만 적혀 있었고, <b>그 문장이 참인 축은 하나뿐</b>이었다.
 *
 * <table border="1">
 *   <caption>같은 링크, 다른 결론</caption>
 *   <tr><th>축</th><th>누가 따라가나</th><th>결론</th></tr>
 *   <tr><td><b>컨테이너 실행</b> (#17)</td><td>컨테이너 프로세스</td>
 *       <td>✅ 문제 아님 — <b>컨테이너의 마운트 네임스페이스</b>에서 해석된다.
 *           {@code foo -> /etc/passwd} 는 컨테이너의 것이지 호스트 것이 아니다</td></tr>
 *   <tr><td>🔴 <b>호스트 쓰기</b> (#18)</td><td><b>우리 JVM</b></td>
 *       <td>🔴 <b>그대로 호스트 파일을 덮는다.</b> {@link #resolveInside} 가 막는다</td></tr>
 * </table>
 *
 * <p>「이미 판단된 것」으로 읽히면 다음 사람이 두 번째 줄을 다시 보지 않는다.
 * 마운트 <b>소스</b>를 막는 것({@link #under})과 <b>쓰기 대상</b>을 막는 것은 다른 일이다.
 *
 * <p>⚠ <b>TOCTOU</b> — 검증 시점과 컨테이너 생성 시점 사이에 경로가 심링크로 교체될 수 있다.
 * 워크스페이스 루트를 우리가 소유하므로 실위험은 낮고, {@link #path()} 가 <b>해석된
 * 실경로</b>를 돌려주는 것으로 창을 좁힌다. 완전히 닫지는 못한다.
 */
public record SandboxWorkspace(Path path) {

    public SandboxWorkspace {
        if (path == null) {
            throw new SandboxPermanentException("워크스페이스 경로는 필수다");
        }
    }

    /**
     * 🔴 워크스페이스 <b>안의</b> 경로로 푼다 — 밖으로 나가면 거부한다 (#18 · S-3).
     *
     * <h2>{@link #under} 와 다른 축이다</h2>
     *
     * <table border="1">
     *   <caption>둘이 막는 것</caption>
     *   <tr><th></th><th>무엇을 검증하나</th><th>입력의 출처</th></tr>
     *   <tr><td>{@code under}</td><td>마운트 <b>소스</b>가 루트 하위인가</td><td>우리 설정</td></tr>
     *   <tr><td><b>이것</b></td><td>쓰려는 <b>대상</b>이 워크스페이스 안인가</td>
     *       <td>🔴 <b>LLM 출력</b></td></tr>
     * </table>
     *
     * <p>모델이 {@code ../../etc/passwd} 를 돌려주면 우리가 그것을 <b>호스트에 쓴다.</b>
     * 계획 경로 검사({@code CodingOutOfPlanException})가 1차로 막지만, 계획 자체가
     * 대상 저장소 트리에서 나오므로 <b>그 검사만으로는 이 축이 닫히지 않는다.</b>
     *
     * <p>⚠️ {@code normalize()} 로 {@code ..} 를 접은 <b>뒤에</b> 본다. 접기 전에 보면
     * {@code a/../../b} 같은 것이 문자열로는 하위처럼 보인다.
     *
     * <h2>🔴 문자열 검사만으로는 새는 것이 있었다 — 심볼릭 링크 (2026-09-27 · #18 리뷰)</h2>
     *
     * <p>{@code normalize() + startsWith} 는 <b>링크를 모른다.</b> 대상 저장소가 심링크를
     * 커밋해 두면 JGit 이 그것을 <b>실제 심링크로 체크아웃</b>하고, 계획은 그 경로를
     * 「실재하는 파일」로 본다. 그 뒤 {@code Files.writeString} 이 링크를 따라가
     * <b>호스트의 임의 파일을 LLM 생성 내용으로 덮는다.</b>
     *
     * <p>⚠️ 이 클래스 상단의 「워크스페이스 안의 심링크는 문제가 아니다」는
     * <b>컨테이너 실행 축에만 참</b>이다 — 그쪽은 컨테이너의 마운트 네임스페이스에서
     * 해석된다. #18 이 연 <b>호스트 쓰기 축</b>에는 성립하지 않는다.
     *
     * <p>실측으로 확인했다 — 링크를 걸어 두면 문자열 검사는 {@code true} 를 돌려주고
     * 바깥 파일이 덮였다. 회귀는 {@code SandboxWorkspaceResolveTest} 의 심링크 표본 2건이다
     * (최종 구성요소 · <b>중간 디렉토리</b>. 마지막만 보면 두 번째가 샌다).
     *
     * <p>⚠️ <b>TOCTOU 는 여전히 남는다.</b> 검사와 쓰기 사이에 링크가 끼어들 수 있다.
     * 워크스페이스를 우리가 만들고 그 안을 도는 것이 clone 과 우리 쓰기뿐이라 창이 좁을 뿐,
     * 닫힌 것이 아니다.
     *
     * @throws SandboxPermanentException 워크스페이스 밖을 가리킨다 · 경로에 심링크가 있다
     */
    public Path resolveInside(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new SandboxPermanentException("워크스페이스 안의 경로는 필수다");
        }
        Path resolved = path.resolve(relativePath).normalize();
        if (!resolved.startsWith(path)) {
            // ⚠ 메시지에 해석된 절대경로를 넣지 않는다 — 호스트 구조가 로그로 나간다.
            //   ⚠ 입력 원문도 넣지 않는다 — 모델 출력이고 로그 인젝션 경로다
            throw new SandboxPermanentException(
                    "워크스페이스 밖을 가리킨다 — 상위 참조가 있다 (S-3)");
        }
        requireNoSymlink(resolved);
        return resolved;
    }

    /**
     * 🔴 경로에 심링크가 없음을 <b>실경로로</b> 확인한다.
     *
     * <p>아직 없는 파일을 만드는 것은 정상이므로 <b>존재하는 가장 깊은 조상</b>까지만
     * 해석한다. 「없으면 통과」가 아니라 <b>「있는 데까지 해석해서 안쪽인가」</b>다 —
     * 존재하지 않는 꼬리는 그 조상 아래에만 생길 수 있다.
     *
     * <p>⚠ 마지막 구성요소가 <b>워크스페이스 안을 가리키는</b> 심링크여도 거부한다.
     * 링크를 통해 쓰면 계획에 없는 파일이 바뀌고, 그 사실이 경로 이름에 드러나지 않는다.
     */
    private void requireNoSymlink(Path resolved) {
        if (Files.isSymbolicLink(resolved)) {
            throw new SandboxPermanentException("쓰기 대상이 심볼릭 링크다 (S-3)");
        }
        Path existing = resolved;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new SandboxPermanentException("경로를 해석할 수 없다 (S-3)");
            }
        }
        Path realAncestor = realPathOf(existing);
        if (!realAncestor.startsWith(realPathOf(path))) {
            throw new SandboxPermanentException(
                    "경로가 심볼릭 링크로 워크스페이스를 벗어난다 (S-3)");
        }
    }

    /**
     * 루트 하위임을 확인하고 값을 만든다. <b>유일한 생성 경로다.</b>
     *
     * @param candidate 호출자가 준 경로
     * @param root      {@code sandbox.workspace-root} — 이미 검증된 실경로여야 한다
     * @throws SandboxPermanentException 루트 밖이거나 디렉토리가 아니다
     */
    public static SandboxWorkspace under(Path candidate, Path root) {
        if (candidate == null) {
            throw new SandboxPermanentException("워크스페이스 경로는 필수다");
        }
        if (root == null) {
            // 🔴 루트가 없으면 「모든 경로가 루트 하위」가 되어 이 검증이 통째로 무력해진다.
            //    미설정을 통과로 다루지 않는다 — 기본값이 곧 위반이 되는 자리다
            throw new SandboxPermanentException(
                    "workspace-root 가 설정되지 않았다 — 경로 제한이 무력해진다 (S-3)");
        }

        Path resolved = realPathOf(candidate);
        // 🔴 해석된 실경로에도 같은 모양 검사를 건다. requireValidRoot 는 디렉토리가 아직
        //    없을 수 있어 toRealPath 를 못 하는데, 그 틈으로 심링크가 들어온다 —
        //    `/opt/a/b -> /` 는 깊이 3 이라 기동 검사를 통과하고, 여기서 `/` 로 풀리면
        //    「모든 경로가 루트 하위」가 다시 성립한다. 검사 자리가 하나면 그 틈이 남는다
        Path resolvedRoot = requireSafeRootShape(realPathOf(root));

        if (!resolved.startsWith(resolvedRoot) || resolved.equals(resolvedRoot)) {
            // 루트 자체도 거부한다. 루트를 통째로 내주면 다른 후보의 워크스페이스가 함께 노출된다
            throw new SandboxPermanentException(
                    "워크스페이스가 sandbox.workspace-root 하위가 아니다 (S-3)");
        }
        if (!Files.isDirectory(resolved)) {
            throw new SandboxPermanentException("워크스페이스가 디렉토리가 아니다 (S-3)");
        }
        return new SandboxWorkspace(resolved);
    }

    /**
     * {@code sandbox.workspace-root} 자체를 검증한다 — <b>기동 시점</b>에 부른다.
     *
     * <p>미설정·상대경로를 런타임까지 끌고 가면 첫 샌드박스 실행에서야 드러난다.
     *
     * <p>⚠ <b>디렉토리를 만들지도, 존재를 확인하지도 않는다.</b> 순수 검증이다 —
     * 설정 객체를 만드는 것만으로 파일시스템에 쓰면, 읽기전용 FS·권한 문제에서
     * <b>샌드박스를 쓰지 않는 경로까지 기동이 막힌다.</b> {@code SandboxConfig} 가
     * 「Docker 가 없다고 기동이 막히면 안 된다」고 한 것과 같은 원칙이다.
     * 생성은 조립 단계가 하고, 실패해도 기동을 막지 않는다 — 그때는 {@link #under}
     * 가 실행 시점에 거부한다.
     */
    public static Path requireValidRoot(Path root) {
        if (root == null || root.toString().isBlank()) {
            // 🔴 비어 있으면 「모든 경로가 루트 하위」가 된다 — 제한이 있는 척하면서 없는 상태다.
            //    구체적인 기본 루트는 위반이 아니지만, 루트가 없는 것은 위반이다
            throw new SandboxPermanentException(
                    "sandbox.workspace-root 가 비었다 — 경로 제한이 무력해진다 (S-3)");
        }
        // 상대경로는 작업 디렉토리에 따라 가리키는 곳이 달라진다. 기동 시점에 절대경로로
        // 확정해 두면 그 흔들림이 사라진다 — 운영자에게 절대경로를 강요할 이유는 없다
        Path absolute = root.toAbsolutePath().normalize();

        return requireSafeRootShape(absolute);
    }

    /**
     * 🔴 루트의 <b>모양</b>을 본다 — blank 를 막은 논리를 값에도 그대로 적용한다.
     *
     * <p>{@code /} 나 홈을 루트로 주면 「모든 경로가 루트 하위」가 blank 일 때와 똑같이
     * 성립한다. 같은 무력화가 다른 값으로 들어오는 것을 막지 않으면 앞의 검사가 무의미하다.
     *
     * <p>⚠ <b>두 곳에서 부른다</b> — 기동 시점(정규화된 경로)과 {@link #under}(해석된
     * 실경로). 기동 시점에는 디렉토리가 아직 없을 수 있어 {@code toRealPath} 를 못 하는데,
     * 한 곳에서만 검사하면 그 틈으로 심링크가 들어온다.
     */
    private static Path requireSafeRootShape(Path absolute) {
        if (absolute.getNameCount() < MIN_ROOT_DEPTH) {
            throw new SandboxPermanentException(
                    "sandbox.workspace-root 가 너무 얕다 — 시스템 디렉토리를 통째로 내주게 된다 (S-3)");
        }
        Path home = homeDirectory();
        if (home != null && absolute.equals(home)) {
            throw new SandboxPermanentException(
                    "sandbox.workspace-root 를 홈 디렉토리로 둘 수 없다 (S-3)");
        }
        return absolute;
    }

    /**
     * 루트가 가져야 할 최소 깊이.
     *
     * <p>{@code /} (0) · {@code /tmp} (1) 은 거부하고 {@code /tmp/oss-agent} (2)부터 받는다.
     * 정확한 선이 있는 것은 아니지만, <b>한 단계짜리 경로는 대개 시스템 디렉토리</b>다.
     */
    private static final int MIN_ROOT_DEPTH = 2;

    private static Path homeDirectory() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home).toAbsolutePath().normalize();
    }

    /** 컨테이너 안에서의 마운트 지점. 고정이다 — 대상 저장소가 정하지 않는다. */
    public String containerPath() {
        return "/workspace";
    }

    /**
     * Gradle 홈. <b>워크스페이스 안</b>이다 — 워밍·씨딩·실행이 같은 것을 본다.
     *
     * <p>🔴 wrapper 배포본이 여기 쌓인다. 워밍이 받아 둔 것을 실행이 그대로 쓰는 것이
     * 「network=none 에서 gradle 이 없다」를 막는 유일한 방법이다 —
     * wrapper 부트스트랩은 Gradle 이 시작되기 <b>전</b>이라 {@code --offline} 도 못 막는다.
     */
    public String containerGradleHome() {
        return containerPath() + "/.gradle";
    }

    private static Path realPathOf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            // 🔴 경로를 해석하지 못한 것을 「통과」로 다루지 않는다.
            //    원본 예외 메시지에 호스트 경로가 실려 나가지 않도록 우리 문자열만 남긴다
            throw new SandboxPermanentException("경로를 해석할 수 없다 (S-3)");
        }
    }
}
