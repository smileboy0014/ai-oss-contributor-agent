package com.ossagent.agent.application;

import com.ossagent.agent.domain.BuildTool;
import com.ossagent.agent.domain.CodeSandbox;
import com.ossagent.agent.domain.DependencyCache;
import com.ossagent.agent.domain.ExecuteCommand;
import com.ossagent.agent.domain.SandboxCacheVolume;
import com.ossagent.agent.domain.SandboxCommand;
import com.ossagent.agent.domain.SandboxLimits;
import com.ossagent.agent.domain.SandboxPermanentException;
import com.ossagent.agent.domain.SandboxResult;
import com.ossagent.agent.domain.SandboxTransientException;
import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.SeedCacheCommand;
import com.ossagent.agent.domain.TargetCommandLine;
import com.ossagent.agent.domain.WarmCommand;
import com.ossagent.repository.domain.RepositoryCoordinates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 워밍 → 씨딩 → 실행 오케스트레이터 — #18 B4 · Q-4 · S-3.
 *
 * <h2>왜 한 자리에 모으나</h2>
 *
 * <p>세 단계는 <b>순서와 조합이 곧 안전 경계</b>다. 호출자가 직접 {@link CodeSandbox} 를
 * 부르면 「워밍만 하고 캐시 없이 실행」·「씨딩을 건너뛰고 오프라인 실행」 같은 조합이
 * 생기고, 그 결과는 예외가 아니라 <b>30분 뒤의 타임아웃</b>이라 리뷰에서도 안 보인다.
 *
 * <table border="1">
 *   <caption>Q-4 의 3단계</caption>
 *   <tr><th>단계</th><th>네트워크</th><th>명령</th><th>캐시 볼륨</th></tr>
 *   <tr><td>워밍</td><td>전용 네트워크</td><td><b>우리 것</b></td>
 *       <td>🔴 <b>마운트하지 않는다</b></td></tr>
 *   <tr><td>씨딩</td><td>없음</td><td>우리 {@code cp}</td><td>RW</td></tr>
 *   <tr><td>실행</td><td>없음</td><td>대상 저장소 것</td><td>RO</td></tr>
 * </table>
 *
 * <p>조합을 <b>타입이</b> 막는다({@link SandboxCommand} 가 sealed 다). 이 클래스가 더하는
 * 것은 <b>순서</b>와 <b>저장소당 워밍 1회</b>다.
 *
 * <h2>🔴 워밍 실패를 삼키지 않는다</h2>
 *
 * <p>워밍이 실패하면 캐시가 비고, 그 상태로 오프라인 실행을 하면 결과가
 * <b>「테스트 실패」로 오분류</b>된다 — 후보의 코드는 멀쩡한데 {@code FAILED} 가 된다.
 * 그래서 준비 실패는 <b>실행 전에</b> 예외로 끊는다. 「빌드 실패는 예외가 아니라 종료코드」는
 * <b>실행 단계</b>의 규칙이고, 여기는 실행 자체를 못 하게 된 경우다.
 *
 * <h2>⚠ 이 락이 막지 <b>못하는</b> 것 — 먼저 적는다</h2>
 *
 * <ul>
 *   <li>🔴 <b>다중 인스턴스</b>에서는 성립하지 않는다. 프로세스 내 락이라 워커가 둘이면
 *       같은 저장소를 동시에 워밍한다 — Q-4 가 「Q-3 과 함께」로 남겨 둔 항목이다</li>
 *   <li>🔴 <b>재기동하면 기억이 사라진다.</b> 볼륨에 이미 캐시가 있어도 한 번 더 워밍한다.
 *       볼륨 내용을 조회하는 능력이 없고, <b>있다고 가정하는 것보다 낭비하는 쪽</b>을 택했다</li>
 *   <li>실행 단계는 락 밖이다. 볼륨을 RO 로만 잡으므로 같은 저장소의 실행 여러 건이
 *       동시에 돌아도 서로를 오염시키지 않는다 — 여기서 직렬화하면 이득 없이 느려진다</li>
 * </ul>
 */
public class SandboxPipeline implements DependencyCache {

    private static final Logger log = LoggerFactory.getLogger(SandboxPipeline.class);

    private final CodeSandbox sandbox;
    private final String defaultImage;
    private final SandboxLimits warmLimits;
    private final SandboxLimits executeLimits;

    /** 이 프로세스가 이미 씨딩한 저장소들. 키는 볼륨 이름 — 저장소 1개 = 볼륨 1개 */
    private final Set<String> seeded = ConcurrentHashMap.newKeySet();

    /** 저장소당 락 — B6. 값이 늘기만 하지만 저장소 수만큼이라 유계다 */
    private final Map<String, Lock> warmLocks = new ConcurrentHashMap<>();

    public SandboxPipeline(CodeSandbox sandbox, String defaultImage,
            SandboxLimits warmLimits, SandboxLimits executeLimits) {
        if (sandbox == null) {
            throw new SandboxPermanentException("샌드박스 실행기는 필수다 (S-3)");
        }
        if (defaultImage == null || defaultImage.isBlank()) {
            throw new SandboxPermanentException("기본 이미지는 필수다");
        }
        if (warmLimits == null || executeLimits == null) {
            throw new SandboxPermanentException("자원 상한은 필수다 (S-3)");
        }
        this.sandbox = sandbox;
        this.defaultImage = defaultImage;
        this.warmLimits = warmLimits;
        this.executeLimits = executeLimits;
    }

    /**
     * 캐시를 준비한 뒤 대상 저장소 명령을 <b>네트워크 없이</b> 돌린다.
     *
     * <p>🔴 워크스페이스는 세 단계가 <b>같은 것</b>을 쓴다 — wrapper 배포본이 거기 쌓이고,
     * wrapper 부트스트랩은 Gradle 이 시작되기 <b>전</b>이라 {@code --offline} 이 닿지 않는다.
     *
     * @return 실행 결과. <b>0 아닌 종료코드는 예외가 아니다</b> — 게이트가 작동한 모습이다
     * @throws SandboxTransientException 준비가 타임아웃으로 실패했다 — 다시 하면 될 수 있다
     * @throws SandboxPermanentException 준비가 0 아닌 종료코드로 실패했다 — 같은 입력에 같은 결과다
     */
    public SandboxResult run(SandboxWorkspace workspace, RepositoryCoordinates coordinates,
            String javaVersion, TargetCommandLine command) {
        if (workspace == null || coordinates == null || command == null) {
            throw new SandboxPermanentException("샌드박스 실행 요청의 필수 값이 비었다");
        }
        SandboxCacheVolume cacheVolume =
                ensurePrepared(workspace, coordinates, command.buildTool(), javaVersion);

        return sandbox.run(ExecuteCommand.of(workspace, cacheVolume, command.buildTool(),
                javaVersion, defaultImage, command.argv(), executeLimits));
    }

    /**
     * 🔴 준비만 한다 — 실행은 호출자가 한다 ({@link DependencyCache}).
     *
     * <p>검증(#19)은 한 워크스페이스에서 <b>여러 명령</b>(컴파일 · 테스트 · diff)을 돌리므로
     * 「준비 + 실행 한 벌」이 맞지 않는다. 준비를 따로 노출하되 <b>볼륨을 돌려주어</b>
     * 호출자가 다른 볼륨으로 실행할 수 없게 한다.
     */
    @Override
    public SandboxCacheVolume ensurePrepared(SandboxWorkspace workspace,
            RepositoryCoordinates coordinates, BuildTool buildTool, String javaVersion) {
        if (workspace == null || coordinates == null || buildTool == null) {
            throw new SandboxPermanentException("캐시 준비 요청의 필수 값이 비었다");
        }
        SandboxCacheVolume cacheVolume =
                SandboxCacheVolume.forRepository(coordinates.owner(), coordinates.name());
        prepareCache(workspace, cacheVolume, javaVersion, buildTool);
        return cacheVolume;
    }

    /**
     * 워밍 → 씨딩. <b>저장소당 한 번</b>이고 락 안에서 돈다 — B6.
     *
     * <p>⚠ 락 안에서 이중 확인을 한다. 먼저 들어간 스레드가 씨딩을 끝냈으면
     * 뒤따라온 스레드는 <b>다시 워밍하지 않는다</b> — 그러지 않으면 락이 순서만 세우고
     * 「저장소당 1회」는 지켜지지 않는다.
     */
    private void prepareCache(SandboxWorkspace workspace, SandboxCacheVolume cacheVolume,
            String javaVersion, BuildTool buildTool) {
        if (seeded.contains(cacheVolume.name()) && workspaceWarmed(workspace, cacheVolume)) {
            return;
        }
        Lock lock = warmLocks.computeIfAbsent(cacheVolume.name(), key -> new ReentrantLock());
        lock.lock();
        try {
            boolean volumeSeeded = seeded.contains(cacheVolume.name());
            boolean warmed = workspaceWarmed(workspace, cacheVolume);
            if (volumeSeeded && warmed) {
                return;
            }
            // 🔴 「볼륨이 씨딩됐다」와 「이 워크스페이스가 워밍됐다」는 다른 사실이다 (#101).
            //    wrapper 배포본은 볼륨이 아니라 <workspace>/.gradle 에 있고, fetch 가 그 디렉토리를
            //    통째로 지운다. 볼륨 메모만 보고 건너뛰면 새 워크스페이스가 network=none 에서
            //    wrapper 를 받으려다 죽고, 그것이 「코드가 틀렸다」로 기록돼 3바퀴를 태웠다
            if (!warmed) {
                log.info("의존성 캐시 워밍 시작 volume={} (workspace 워밍 마커 없음)", cacheVolume.name());
                requireSucceeded("워밍",
                        sandbox.run(WarmCommand.of(workspace, buildTool,
                                javaVersion, defaultImage, warmLimits)));
                markWarmed(workspace, cacheVolume);
            }
            if (!volumeSeeded) {
                requireSucceeded("씨딩",
                        sandbox.run(SeedCacheCommand.of(workspace, cacheVolume,
                                javaVersion, defaultImage, warmLimits)));
                // 🔴 성공한 뒤에만 표시한다. 실패한 채로 표시하면 다음 후보가
                //    빈 캐시로 오프라인 실행을 하고, 그 결과가 「테스트 실패」로 기록된다
                seeded.add(cacheVolume.name());
            }
            log.info("의존성 캐시 준비 완료 volume={}", cacheVolume.name());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 워밍이 끝난 워크스페이스에 <b>우리가</b> 남기는 표식 — {@code GRADLE_USER_HOME} 안이다.
     *
     * <p>{@code fetch} 가 디렉토리를 비우면 표식도 사라져 다음 준비가 다시 워밍한다. 대상 코드가
     * 실행 단계(RW)에서 지워도 같다 — 틀리는 방향이 「한 번 더 워밍」이라 안전하다.
     * Gradle 내부 구조({@code wrapper/dists})를 들여다보지 않는 이유는 그것이 Gradle 버전마다
     * 바뀔 수 있어서다.
     */
    static final String WARM_MARKER = ".oss-agent-warmed";

    private static Path warmMarker(SandboxWorkspace workspace) {
        return workspace.path().resolve(".gradle").resolve(WARM_MARKER);
    }

    /** 표식의 내용이 <b>어느 저장소</b>의 워밍인지다 — 같은 디렉토리에 다른 저장소가 오면 다시 워밍한다. */
    private static boolean workspaceWarmed(SandboxWorkspace workspace, SandboxCacheVolume cacheVolume) {
        Path marker = warmMarker(workspace);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            return cacheVolume.name().equals(Files.readString(marker).strip());
        } catch (IOException e) {
            return false;
        }
    }

    private static void markWarmed(SandboxWorkspace workspace, SandboxCacheVolume cacheVolume) {
        Path marker = warmMarker(workspace);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, cacheVolume.name() + "\n");
        } catch (IOException e) {
            // ⚠ 표식을 못 남기면 다음 준비가 한 번 더 워밍할 뿐이다 — 조용히 넘기지는 않는다
            log.warn("워밍 표식을 남기지 못했다 — 다음 준비가 다시 워밍한다", e);
        }
    }

    /**
     * ⚠ <b>출력을 메시지에 싣지 않는다</b> — 대상 저장소의 빌드 출력이고 S-4 대상이다.
     * 종료코드와 단계 이름만으로 분류가 된다.
     */
    private static void requireSucceeded(String stage, SandboxResult result) {
        if (result.succeeded()) {
            return;
        }
        if (result.timedOut()) {
            throw new SandboxTransientException(stage + " 가 시간 상한을 넘었다 — 캐시를 준비하지 못했다");
        }
        throw new SandboxPermanentException(
                stage + " 가 실패했다 exit=" + result.exitCode() + " — 캐시를 준비하지 못했다");
    }
}
