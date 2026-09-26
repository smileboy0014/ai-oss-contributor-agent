package com.ossagent.agent.domain;

import java.util.List;

/**
 * 의존성 워밍 — <b>네트워크가 열리는 유일한 단계</b> (#17 · Q-4 ①).
 *
 * <h2>🔴 명령을 인자로 받지 않는다</h2>
 *
 * <p>여기는 네트워크가 열려 있다. 대상 저장소가 정한 명령을 여기서 돌리면
 * <b>임의 네트워크 행위를 대신 해 주는 셈</b>이다. 무엇을 돌릴지는 우리가 정한다 —
 * 생성자에 {@code argv} 가 없는 것이 그 규칙의 구현이다.
 *
 * <h2>🔴 캐시 볼륨을 마운트하지 않는다</h2>
 *
 * <p>이 단계는 <b>신뢰할 수 없는 빌드 스크립트를 네트워크가 열린 채로</b> 돌린다.
 * 볼륨에 쓸 수 있으면 {@code init.d/*.gradle} 을 심어 <b>다음 워밍에서 자동 실행</b>시킬 수
 * 있고, 볼륨은 후보 수명을 넘겨 지속되므로 그 오염이 남는다.
 *
 * <p>그래서 워밍은 <b>워크스페이스에만</b> 쓰고, 볼륨으로 옮기는 것은
 * {@link SeedCacheCommand} 가 우리 {@code cp} 로 네트워크 없이 한다.
 * 워크스페이스에 남는 오염은 후보와 함께 버려진다 — 지속되지 않는다.
 *
 * <p>부수 효과로 <b>wrapper 배포본이 워크스페이스에 남는다.</b> 실행 단계가 같은
 * 워크스페이스를 쓰므로 그대로 재사용되고, 그것이 「network=none 에서 gradle 이 없다」를
 * 막는 유일한 방법이다 — wrapper 부트스트랩은 Gradle 이 시작되기 <b>전</b>이라
 * {@code --offline} 이 닿지 않는다.
 */
public record WarmCommand(
        SandboxWorkspace workspace,
        BuildTool buildTool,
        String image,
        SandboxLimits limits) implements SandboxCommand {

    public WarmCommand {
        if (workspace == null || buildTool == null || limits == null) {
            throw new SandboxPermanentException("워밍 요청의 필수 값이 비었다");
        }
        if (image == null || image.isBlank()) {
            throw new SandboxPermanentException("이미지는 필수다");
        }
        buildTool.requireSupported();
    }

    public static WarmCommand of(SandboxWorkspace workspace, BuildTool buildTool,
            String javaVersion, String defaultImage, SandboxLimits limits) {
        return new WarmCommand(workspace, buildTool,
                SandboxImages.of(javaVersion, defaultImage), limits);
    }

    /**
     * 🔴 우리가 정한 고정 명령이다.
     *
     * <p>{@code testClasses} 인 이유 — {@code dependencies} 는 <b>루트 프로젝트의 구성만</b>
     * 해석한다. 실제 대상은 대부분 멀티프로젝트({@code spring-kafka} 포함)라 그것으로는
     * 캐시가 거의 비고, 실행 단계가 network=none 에서 죽는다.
     *
     * <p>⚠ {@code testClasses} 는 <b>컴파일까지 한다</b> — 즉 여기서 대상 저장소의 빌드
     * 로직이 실제로 돈다. 워밍 단계가 임의 코드 실행이라는 사실은 어차피 변하지 않지만,
     * 「의존성만 받는다」는 표현이 정확하지 않다는 것은 적어 둔다.
     *
     * <p>⚠ 이 선택이 충분한지는 <b>실측되지 않았다</b> — toolchain 프로비저닝 ·
     * dynamic version · SNAPSHOT 이 남는다. Q-4 를 「닫았다」고 적지 않은 이유다.
     */
    @Override
    public List<String> argv() {
        return List.of("./gradlew", "--no-daemon", "testClasses");
    }
}
