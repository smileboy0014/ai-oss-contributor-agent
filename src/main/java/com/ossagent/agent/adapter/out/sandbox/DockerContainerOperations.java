package com.ossagent.agent.adapter.out.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Network;
import com.ossagent.agent.domain.SandboxTransientException;
import com.ossagent.support.ExternalAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * docker-java 로 {@link ContainerOperations} 를 구현한다 — #17.
 *
 * <h2>⚠ 이 클래스는 로직을 갖지 않는다 — 그것이 계약이다</h2>
 *
 * <p>docker-java 호출을 1:1 로 옮기고 예외를 번역할 뿐이다. 그래서 <b>Docker 없이 검증할 수
 * 없고, 미검증으로 둔다</b> — 자동 스위트는 샌드박스 컨테이너를 띄우지 않는다(Q-9).
 * 「S-3 관련 경로 커버리지 100%」의 <b>유일한 예외</b>이고, 숨기지 않고 적는다.
 *
 * <p>판단이 필요한 것은 전부 검증 가능한 자리에 있다 — 격리 설정은
 * {@link SandboxContainerSpec}, 수명주기는 {@link DockerCodeSandbox}.
 * <b>여기에 로직이 생기면 그쪽으로 올린다.</b>
 *
 * <p>🔴 {@code ProcessBuilder} 로 docker CLI 를 부르지 않는다. 훅이 막는 경로이고,
 * S-3 의 실행체를 만들면서 S-3 가드를 우회하는 것은 앞뒤가 맞지 않는다.
 */
@Component
@ExternalAdapter
public class DockerContainerOperations implements ContainerOperations {

    private static final Logger log = LoggerFactory.getLogger(DockerContainerOperations.class);

    private final DockerClient client;

    public DockerContainerOperations(DockerClient client) {
        this.client = client;
    }

    @Override
    public String create(ContainerCreation creation) {
        try {
            return client.createContainerCmd(creation.image())
                    .withCmd(creation.argv())
                    .withEnv(creation.env())
                    .withWorkingDir(creation.workingDir())
                    .withHostConfig(creation.hostConfig())
                    .withLabels(creation.labels())
                    .exec()
                    .getId();
        } catch (NotFoundException e) {
            // 🔴 이미지를 우리가 받아 오지 않는다 — pull 은 데몬 자격증명이 관여하는 행위라
            //    운영이 미리 받아 두는 쪽이 단순하다. 따라서 「없다」는 우리 결함이 아니라
            //    준비 상태의 문제이고, 준비되면 같은 요청이 성공한다
            throw new SandboxTransientException("이미지를 찾을 수 없다 — 미리 받아 두어야 한다");
        } catch (DockerException | IllegalStateException e) {
            throw daemonFailure("컨테이너 생성", e);
        }
    }

    @Override
    public void start(String containerId) {
        try {
            client.startContainerCmd(containerId).exec();
        } catch (DockerException | IllegalStateException e) {
            throw daemonFailure("컨테이너 시작", e);
        }
    }

    /**
     * ⚠ <b>타임아웃을 예외로 다루지 않는다.</b>
     *
     * <p>{@code awaitStatusCode(timeout, unit)} 은 상한 초과를 {@code DockerClientException}
     * 으로 던지는데, 그것을 잡으려면 {@code RuntimeException} 을 넓게 잡게 되고
     * <b>NPE 같은 진짜 결함까지 「타임아웃」으로 바뀐다.</b> 증상이 「가끔 타임아웃이 난다」로만
     * 보여 원인을 영영 못 찾는 종류다.
     *
     * <p>{@code awaitCompletion} 은 같은 것을 <b>{@code boolean} 으로</b> 돌려준다.
     * 예외를 제어 흐름으로 쓰지 않으면 그 혼동이 생기지 않는다.
     */
    @Override
    public WaitOutcome await(String containerId, Duration timeout) {
        try (WaitContainerResultCallback callback =
                client.waitContainerCmd(containerId).exec(new WaitContainerResultCallback())) {
            if (!callback.awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // 타임아웃은 예외가 아니라 결과다 — 이 제품에서 정상적으로 일어나는 일이고,
                // kill 여부는 호출자가 정한다
                return WaitOutcome.timeout();
            }
            Integer exitCode = callback.awaitStatusCode();
            return WaitOutcome.exited(exitCode == null ? -1 : exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 인터럽트를 「정상 종료」로 보고하면 호출자가 빌드가 끝난 줄 안다.
            // 컨테이너는 아직 살아 있으므로 타임아웃과 같은 처리(kill → remove)가 맞다
            return WaitOutcome.timeout();
        } catch (DockerException | IllegalStateException e) {
            throw daemonFailure("컨테이너 대기", e);
        } catch (IOException e) {
            // try-with-resources 의 close 가 던지는 것. 넓게 잡지 않는다 —
            // 위 javadoc 이 경고한 것을 여기서 어기면 NPE 가 「일시 장애」로 위장돼
            // 재시도 루프를 돈다. RuntimeException 은 그대로 전파시킨다
            throw daemonFailure("컨테이너 대기", e);
        }
    }

    /**
     * 🔴 <b>상한에 닿으면 더 쌓지 않는다.</b> 다 받아 놓고 자르면 수백 MB 로그에 우리
     * 프로세스가 죽는다 — 막아야 할 것은 <b>메모리</b>이고, 그것이 여기서 유계가 된다.
     *
     * <p>⚠ <b>스트림 자체를 끊지는 않는다.</b> 끊으려면 콜백 안에서 {@code onComplete()} 를
     * 불러야 하는데, docker-java 의 그것은 내부적으로 {@code close()} 다 — <b>리더 스레드가
     * 자기 스트림을 닫는</b> 꼴이라 거기서 난 {@code IOException} 이
     * {@code awaitCompletion} 에서 다시 튀어나온다. 그러면 <b>정상적인 절단마다
     * 경고와 스택트레이스가 찍힌다.</b> 시간은 아래 타임아웃이 이미 묶고 있으므로,
     * 검증하지 못한 동작으로 조금 더 빨리 끊는 것보다 이쪽이 낫다.
     *
     * <p>🔴 자체 타임아웃이 있는 이유 — kill 직후 조회가 매달릴 수 있고, 여기서 멈추면
     * 호출자의 {@code finally} 제거에 도달하지 못해 컨테이너가 남는다.
     */
    @Override
    public ContainerLogs logs(String containerId, int maxChars, Duration timeout) {
        // 🔴 콜백 스레드가 쓰고 이 스레드가 읽는다. 정상 완료는 래치가 happens-before 를
        //    주지만 타임아웃·인터럽트 경로에서는 콜백이 아직 쓰는 중일 수 있다 —
        //    그때 StringBuilder 를 쓰면 깨진 출력이나 IndexOutOfBounds 가 나고,
        //    증상은 「가끔 이상하다」로만 보인다
        LogBuffer buffer = new LogBuffer(maxChars);

        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                // 여기서 스트림을 끊지 않는다 — 위 javadoc 참조.
                // 상한을 넘은 프레임은 버려지므로 메모리는 더 늘지 않는다
                buffer.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        };

        try (ResultCallback.Adapter<Frame> open = client.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .exec(callback)) {
            if (!open.awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // 🔴 타임아웃도 「잘린 것」이다. 여기서 표시하지 않으면 잘린 로그가
                //    「완전한 로그」로 하류에 가고, LLM 이 그것을 전부로 읽고
                //    「테스트가 통과했다」로 판단하면 게이트가 무력해진다
                log.warn("로그 수집이 상한 시간 안에 끝나지 않았다 containerId={}", containerId);
                buffer.markTruncated();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            buffer.markTruncated();
        } catch (DockerException | IllegalStateException e) {
            throw daemonFailure("로그 수집", e);
        } catch (IOException e) {
            // 로그를 못 받은 것이 실행 결과를 버릴 이유는 아니다 — 잘린 것으로 표시한다
            log.warn("로그 수집을 닫는 중 오류 containerId={}", containerId, e);
            buffer.markTruncated();
        }
        return buffer.toLogs();
    }

    /**
     * 콜백 스레드와 호출 스레드가 함께 보는 버퍼.
     *
     * <p>🔴 동기화가 필요한 이유는 성능이 아니라 <b>가시성</b>이다. 타임아웃·인터럽트로
     * 빠져나올 때 콜백 스레드가 아직 쓰고 있을 수 있고, 그 상태에서 읽으면 깨진 문자열이
     * 판정 입력이 된다.
     */
    private static final class LogBuffer {

        private final StringBuilder buffer = new StringBuilder();
        private final int maxChars;
        private boolean truncated;

        LogBuffer(int maxChars) {
            this.maxChars = maxChars;
        }

        synchronized void append(String chunk) {
            if (truncated) {
                return;
            }
            int room = maxChars - buffer.length();
            if (chunk.length() >= room) {
                buffer.append(chunk, 0, Math.max(room, 0));
                truncated = true;
                return;
            }
            buffer.append(chunk);
        }

        synchronized void markTruncated() {
            truncated = true;
        }

        synchronized ContainerLogs toLogs() {
            return new ContainerLogs(buffer.toString(), truncated);
        }
    }

    @Override
    public void kill(String containerId) {
        try {
            client.killContainerCmd(containerId).exec();
        } catch (NotFoundException e) {
            log.debug("kill 대상 컨테이너가 이미 없다 containerId={}", containerId);
        } catch (DockerException | IllegalStateException e) {
            // 🔴 kill 실패로 제거까지 포기하지 않는다. 강제 제거가 아직 남아 있다
            log.warn("컨테이너를 강제 종료하지 못했다 containerId={}", containerId, e);
        }
    }

    @Override
    public boolean remove(String containerId) {
        try {
            client.removeContainerCmd(containerId)
                    .withForce(true)
                    // 익명 볼륨만 지운다. 🔴 우리 의존성 캐시는 이름 있는 볼륨이라
                    // 지워지지 않는다 — 의도한 것이다
                    .withRemoveVolumes(true)
                    .exec();
            return true;
        } catch (NotFoundException e) {
            return true;   // 이미 없다 = 남지 않았다
        } catch (DockerException | IllegalStateException e) {
            log.warn("컨테이너를 제거하지 못했다 containerId={}", containerId, e);
            return false;
        }
    }

    @Override
    public void ensureNetwork(String name) {
        try {
            boolean exists = client.listNetworksCmd().withNameFilter(name).exec().stream()
                    .map(Network::getName)
                    .anyMatch(name::equals);
            if (!exists) {
                client.createNetworkCmd().withName(name).exec();
            }
        } catch (DockerException | IllegalStateException e) {
            throw daemonFailure("워밍 네트워크 준비", e);
        }
    }

    /**
     * 🔴 데몬 예외 <b>원문을 들고 다니지 않는다</b> — S-4.
     *
     * <p>docker-java 예외 본문에는 요청 URL·헤더가 담기고, 그것이 로그나
     * {@code AgentRun.errorMessage} 로 흘러가는 것이 현실적인 유출 경로다.
     * 진단에 필요한 것은 어댑터가 마스킹을 통제할 수 있는 자리에서 남긴다.
     */
    private SandboxTransientException daemonFailure(String what, Exception e) {
        log.warn("Docker 데몬 호출 실패 what={}", what, e);
        return new SandboxTransientException(what + " 실패 — Docker 데몬을 확인한다");
    }
}
