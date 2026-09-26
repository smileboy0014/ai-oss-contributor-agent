package com.ossagent.agent.domain;

import java.util.List;

/**
 * 워밍 결과에서 <b>의존성 캐시만</b> 볼륨으로 옮긴다 — #17 · Q-4 ②.
 *
 * <h2>🔴 이 단계가 따로 있는 이유</h2>
 *
 * <p>볼륨에 쓰는 <b>유일한 단계</b>이고, 그래서 <b>신뢰할 수 없는 코드를 돌리지 않는다.</b>
 *
 * <table border="1">
 *   <caption>이 단계의 격리</caption>
 *   <tr><td>네트워크</td><td>없음</td></tr>
 *   <tr><td>명령</td><td>우리 {@code cp} — 기본 이미지에 있는 것</td></tr>
 *   <tr><td>워크스페이스</td><td><b>읽기전용</b> — 여기서 뭔가를 고칠 이유가 없다</td></tr>
 *   <tr><td>캐시 볼륨</td><td>RW</td></tr>
 * </table>
 *
 * <p>워밍이 볼륨을 직접 잡게 두면 대상 저장소의 빌드 스크립트가 볼륨 아무 곳에나 쓸 수
 * 있고, 그 오염은 후보 수명을 넘겨 <b>다음 워밍에서 네트워크가 열린 채로</b> 되살아난다.
 * 단계를 가르면 볼륨에 들어가는 것이 {@code modules-2} 하나로 좁혀진다.
 *
 * <p>⚠ {@code cp} 를 쓰는 것은 「명령은 argv · 쉘 미경유」 규칙의 <b>예외가 아니다</b> —
 * 쉘 없이 argv 로 넘긴다.
 *
 * <p>⚠ 옮기는 것이 {@code modules-2} 인 이유 — {@code GRADLE_RO_DEP_CACHE} 는
 * {@code modules-2} 를 <b>담고 있는</b> 디렉토리를 가리킨다. 볼륨 루트가 곧 그 디렉토리가
 * 되도록 {@code modules-2} 를 볼륨 바로 아래에 둔다.
 */
public record SeedCacheCommand(
        SandboxWorkspace workspace,
        SandboxCacheVolume cacheVolume,
        String image,
        SandboxLimits limits) implements SandboxCommand {

    public SeedCacheCommand {
        if (workspace == null || cacheVolume == null || limits == null) {
            throw new SandboxPermanentException("씨딩 요청의 필수 값이 비었다");
        }
        if (image == null || image.isBlank()) {
            throw new SandboxPermanentException("이미지는 필수다");
        }
    }

    public static SeedCacheCommand of(SandboxWorkspace workspace, SandboxCacheVolume cacheVolume,
            String javaVersion, String defaultImage, SandboxLimits limits) {
        return new SeedCacheCommand(workspace, cacheVolume,
                SandboxImages.of(javaVersion, defaultImage), limits);
    }

    /**
     * 🔴 우리 명령이다. {@code -a} 로 속성을 보존해 Gradle 이 캐시를 그대로 인식하게 한다.
     *
     * <p>⚠ 워밍이 아무것도 받지 못해 원본이 없으면 {@code cp} 가 0 이 아닌 코드로 끝난다.
     * 그것은 예외가 아니라 <b>결과</b>다 — 호출자가 종료코드로 판단한다.
     */
    @Override
    public List<String> argv() {
        return List.of("cp", "-a",
                workspace.containerGradleHome() + "/caches/modules-2",
                cacheVolume.containerPath() + "/modules-2");
    }
}
