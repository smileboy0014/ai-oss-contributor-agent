package com.ossagent.repository.adapter.in.scheduler;

import com.ossagent.repository.application.LaunchScanUseCase;
import com.ossagent.repository.application.RegisterRepositoryUseCase;
import com.ossagent.repository.application.RequestScanUseCase;
import com.ossagent.repository.application.ScanExecutionRegistry;
import com.ossagent.repository.application.ScanExecutionState;
import com.ossagent.repository.application.ScanPipelineResult;
import com.ossagent.repository.application.ScanProperties;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.ScanAlreadyRunningException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기 스캔 진입점 — #14 FR-5.
 *
 * <h2>🔴 진입점을 {@code adapter/in/scheduler} 에 두는 이유</h2>
 *
 * <p>규율 ②가 「진입점을 유형별(web·scheduler·event)로 분리」하라고 하고,
 * <b>「{@code worker} 프로필 분리(Q-3)가 {@code @Profile} 로 걸리는 자리」</b>라고 지목한
 * 그곳이다. Q-3 을 닫을 때 이 클래스에 {@code @Profile("worker")} 한 줄이 붙는다.
 *
 * <p>⚠️ 다만 <b>API 트리거 경로는 그 한 줄로 갈라지지 않는다.</b> 컨트롤러(web)가
 * {@link LaunchScanUseCase}(worker)를 같은 JVM 에서 직접 부르기 때문이고, 가르려면
 * 큐가 필요하다 — PRD §21 의 Redis Streams. Q-3 을 열어 둔 진짜 대가가 그것이다.
 *
 * <h2>🔴 기본 비활성 — 그리고 그것을 <b>실제로 배선</b>한다</h2>
 *
 * <p>{@code @ConditionalOnProperty} 가 <b>이 방어의 전부</b>다. {@code @Scheduled} 만 달아 두고
 * 설정에 {@code enabled: false} 를 적어 두면 <b>그 값을 읽는 코드가 없어</b> 플래그가
 * 장식이 되고, 스케줄러는 그대로 돈다. 「비활성이면 이 빈이 없다」를 테스트로 고정한다.
 *
 * <p>⚠️ 이것은 <b>비용·레이트리밋 방어</b>이지 S-6 방어가 아니다. 파이프라인이
 * {@code ANALYZED} 에서 멈추는 것은 플래그와 무관하게 성립한다.
 */
@Component
@ConditionalOnProperty(name = "scan.schedule.enabled", havingValue = "true")
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final RegisterRepositoryUseCase repositories;
    private final LaunchScanUseCase launchScan;
    private final RequestScanUseCase requestScan;
    private final ScanExecutionRegistry executions;
    private final ScanProperties properties;
    private final Clock clock;

    public ScanScheduler(RegisterRepositoryUseCase repositories, LaunchScanUseCase launchScan,
            RequestScanUseCase requestScan, ScanExecutionRegistry executions,
            ScanProperties properties, Clock clock) {
        this.repositories = repositories;
        this.launchScan = launchScan;
        this.requestScan = requestScan;
        this.executions = executions;
        this.properties = properties;
        this.clock = clock;
        log.info("정기 스캔 스케줄러가 활성화됐다 — GitHub·LLM 을 주기적으로 호출한다 "
                + "(기본 주기={})", properties.defaultInterval());
    }

    /**
     * 등록된 저장소를 순회하며 스캔을 기동한다.
     *
     * <p>🔴 <b>한 저장소의 실패가 순회를 멈추지 않는다</b> (NFR-3). 고장난 저장소 하나가
     * 나머지 전부의 스캔을 인질로 잡으면 안 된다.
     *
     * <p>⚠️ <b>주기가 된 저장소만 기동한다</b> (#26 FR-2). {@code fixedDelay} 는 「얼마나
     * 자주 <b>훑는가</b>」이고 저장소 주기는 「얼마나 자주 <b>도는가</b>」다 — 둘을 겹쳐
     * 쓰면 저장소별 주기(FR-1)를 표현할 자리가 없다. 훑는 주기는 저장소 주기보다
     * 촘촘해야 한다. 성기면 「주기가 됐는데 아무도 안 훑어서」 늦어진다.
     *
     * <p>{@code fixedDelay} 다({@code fixedRate} 가 아니다) — 이전 실행이 끝난 뒤부터 센다.
     * 스캔이 주기보다 오래 걸릴 수 있고, {@code fixedRate} 면 그때 실행이 겹쳐 쌓인다.
     */
    private boolean isDelayed(Long repositoryId, Instant now) {
        return executions.stateOf(repositoryId)
                .filter(state -> !state.isActive())
                .map(ScanExecutionState::lastResult)
                .map(ScanPipelineResult::delayedUntil)
                .map(now::isBefore)
                .orElse(false);
    }

    @Scheduled(fixedDelayString = "${scan.schedule.fixed-delay}",
            initialDelayString = "${scan.schedule.initial-delay}")
    public void scanAll() {
        Instant now = Instant.now(clock);
        List<OssRepository> targets = repositories.findAll();

        int launched = 0;
        int skipped = 0;
        int notDue = 0;
        for (OssRepository repository : targets) {
            if (!repository.isEnabled()) {
                skipped++;
                continue;
            }
            // 🔴 주기가 안 된 저장소는 건너뛴다 (FR-1·FR-2) — 전역 주기 하나로 돌리면
            //    활발한 저장소는 늦고 조용한 저장소는 레이트리밋을 태운다.
            //    판정은 엔티티가 한다(자기 데이터만으로 답한다 — architecture §3 Q1)
            if (!repository.isDueForScan(now, properties.defaultInterval())) {
                notDue++;
                continue;
            }
            // 🔴 지난 스캔이 「delayedUntil 까지 지연」으로 끝났으면 그 전에 다시 두드리지 않는다 (#108).
            //    레이트리밋은 지연이지 실패가 아니고(glossary), 값은 적혀 있는데 아무도 읽지 않았다
            if (isDelayed(repository.getId(), now)) {
                notDue++;
                continue;
            }
            try {
                launchScan.launch(repository.getId());
                // 🔴 스캔 시각을 남긴다 (#108). 컨트롤러는 남기는데 스케줄러는 launch 만 불러
                //    last_scanned_at 이 영영 NULL 이었고 isDueForScan 이 항상 참 — 저장소별 주기가
                //    죽고 fixed-delay(10분)마다 전부 재스캔했다. 순서는 컨트롤러와 같다: 기동이 거절되면
                //    받지도 않은 요청의 흔적을 남기지 않는다
                requestScan.requestScan(repository.getId());
                launched++;
            } catch (ScanAlreadyRunningException e) {
                // 진행 중이거나 큐가 찼다 — 정상이다. 다음 주기가 이어받는다
                skipped++;
            } catch (RuntimeException e) {
                // 🔴 삼키고 계속한다. 여기서 던지면 나머지 저장소가 이번 주기를 통째로 잃는다.
                //    ⚠ 예외는 마지막 인자로 — 스택트레이스를 잃지 않는다
                log.error("스캔 기동 실패 repositoryId={} — 나머지는 계속한다",
                        repository.getId(), e);
                skipped++;
            }
        }
        // 🔴 대상 수를 남긴다. 주기 필터가 **모든** 저장소를 걸러도 증상이 「아무 일도
        //    안 일어남」이라, 이 줄이 없으면 조용한 정지를 알아챌 수단이 없다
        log.info("정기 스캔 기동 완료 대상={}건 launched={} notDue={} skipped={}",
                targets.size(), launched, notDue, skipped);
    }
}
