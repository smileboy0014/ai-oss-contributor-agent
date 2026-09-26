package com.ossagent.repository.adapter.in.scheduler;

import com.ossagent.repository.application.LaunchScanUseCase;
import com.ossagent.repository.application.RegisterRepositoryUseCase;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.ScanAlreadyRunningException;
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

    public ScanScheduler(RegisterRepositoryUseCase repositories, LaunchScanUseCase launchScan) {
        this.repositories = repositories;
        this.launchScan = launchScan;
        log.info("정기 스캔 스케줄러가 활성화됐다 — GitHub·LLM 을 주기적으로 호출한다");
    }

    /**
     * 등록된 저장소를 순회하며 스캔을 기동한다.
     *
     * <p>🔴 <b>한 저장소의 실패가 순회를 멈추지 않는다</b> (NFR-3). 고장난 저장소 하나가
     * 나머지 전부의 스캔을 인질로 잡으면 안 된다.
     *
     * <p>{@code fixedDelay} 다({@code fixedRate} 가 아니다) — 이전 실행이 끝난 뒤부터 센다.
     * 스캔이 주기보다 오래 걸릴 수 있고, {@code fixedRate} 면 그때 실행이 겹쳐 쌓인다.
     */
    @Scheduled(fixedDelayString = "${scan.schedule.fixed-delay}",
            initialDelayString = "${scan.schedule.initial-delay}")
    public void scanAll() {
        List<OssRepository> targets = repositories.findAll();
        log.info("정기 스캔 시작 대상={}건", targets.size());

        int launched = 0;
        int skipped = 0;
        for (OssRepository repository : targets) {
            if (!repository.isEnabled()) {
                skipped++;
                continue;
            }
            try {
                launchScan.launch(repository.getId());
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
        log.info("정기 스캔 기동 완료 launched={} skipped={}", launched, skipped);
    }
}
