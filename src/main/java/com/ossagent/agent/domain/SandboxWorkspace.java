package com.ossagent.agent.domain;

import java.io.IOException;
import java.nio.file.Files;
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
 * <p><b>워크스페이스 <i>안</i>의 심볼릭 링크는 문제가 아니다.</b> 바인드 안의 링크는
 * <b>컨테이너의 마운트 네임스페이스</b>에서 해석된다 — {@code foo -> /etc/passwd} 는
 * 컨테이너의 {@code /etc/passwd} 이지 호스트 것이 아니다. {@code ..} 로 올라가도 컨테이너
 * 루트다. 막아야 할 것은 <b>마운트 소스 자체</b>이고, 그것이 아래 검증이다.
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
        Path resolvedRoot = realPathOf(root);

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
     * <p>미설정·상대경로·부재를 런타임까지 끌고 가면, 첫 샌드박스 실행에서야 드러난다.
     */
    public static Path requireValidRoot(Path root) {
        if (root == null || root.toString().isBlank()) {
            throw new SandboxPermanentException(
                    "sandbox.workspace-root 는 필수다 — 없으면 경로 제한이 무력해진다 (S-3)");
        }
        if (!root.isAbsolute()) {
            // 상대경로는 작업 디렉토리에 따라 가리키는 곳이 달라진다
            throw new SandboxPermanentException(
                    "sandbox.workspace-root 는 절대경로여야 한다 (S-3): " + root);
        }
        Path resolved = realPathOf(root);
        if (!Files.isDirectory(resolved)) {
            throw new SandboxPermanentException(
                    "sandbox.workspace-root 가 디렉토리가 아니다 (S-3): " + root);
        }
        return resolved;
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
