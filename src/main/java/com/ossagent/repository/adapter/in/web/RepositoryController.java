package com.ossagent.repository.adapter.in.web;

import com.ossagent.repository.adapter.in.web.dto.RegisterRepositoryRequest;
import com.ossagent.repository.adapter.in.web.dto.RepositoryResponse;
import com.ossagent.repository.adapter.in.web.dto.ScanAcceptedResponse;
import com.ossagent.repository.adapter.in.web.dto.ScanProgressResponse;
import com.ossagent.repository.application.RegisterRepositoryUseCase;
import com.ossagent.repository.application.LaunchScanUseCase;
import com.ossagent.repository.application.RequestScanUseCase;
import com.ossagent.repository.application.ScanExecutionRegistry;
import com.ossagent.repository.application.ScanExecutionState;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {

    private final RegisterRepositoryUseCase registerRepository;
    private final RequestScanUseCase requestScanUseCase;
    private final LaunchScanUseCase launchScan;
    private final ScanExecutionRegistry scanExecutions;

    public RepositoryController(RegisterRepositoryUseCase registerRepository,
            RequestScanUseCase requestScanUseCase,
            LaunchScanUseCase launchScan,
            ScanExecutionRegistry scanExecutions) {
        this.registerRepository = registerRepository;
        this.requestScanUseCase = requestScanUseCase;
        this.launchScan = launchScan;
        this.scanExecutions = scanExecutions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryResponse register(@Valid @RequestBody RegisterRepositoryRequest request) {
        return RepositoryResponse.from(registerRepository.register(request.owner(), request.name(), request.url()));
    }

    @GetMapping
    public List<RepositoryResponse> list() {
        return registerRepository.findAll().stream().map(RepositoryResponse::from).toList();
    }

    /**
     * 스캔을 기동한다 — 🔴 <b>즉시 반환</b>한다 (FR-3 · NFR-1).
     *
     * <p>{@code requestScan} 이 {@code last_scanned_at} 을 남기고(짧은 트랜잭션),
     * {@code launch} 가 전용 풀에 던진다. 파이프라인은 분 단위라 여기서 기다리지 않는다.
     *
     * <p>⚠️ 순서가 중요하다 — <b>기록을 먼저</b> 한다. 기동이 409 로 거절되면 기록도
     * 남지 않아야 「요청했는데 아무 흔적이 없다」가 되지 않는다... 가 아니라 그 반대다.
     * 거절은 <b>아무 일도 일어나지 않았다</b>는 뜻이므로 기록도 남기지 않는 것이 맞고,
     * 그래서 {@code launch} 를 <b>먼저</b> 부른다.
     *
     * @throws com.ossagent.repository.domain.ScanAlreadyRunningException 진행 중 · 큐 초과 → 409
     */
    @PostMapping("/{id}/scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScanAcceptedResponse requestScan(@PathVariable Long id) {
        // 🔴 먼저 기동한다. 거절되면 예외가 나가고 last_scanned_at 도 건드리지 않는다 —
        //    받지도 않은 요청의 흔적을 남기지 않는다
        launchScan.launch(id);
        Instant acceptedAt = requestScanUseCase.requestScan(id);
        return ScanAcceptedResponse.of(id, acceptedAt);
    }

    /**
     * 진행 조회 — {@code 202} 가 준 {@code statusUrl} 이 가리키는 곳이다.
     *
     * <p>⚠️ 상태는 <b>프로세스 메모리</b>에 있다. 재기동하면 {@code IDLE} 로 돌아가고,
     * 인스턴스가 늘면 자기 것만 보인다 — {@code ScanExecutionRegistry} javadoc · #26.
     */
    @GetMapping("/{id}/scan")
    public ScanProgressResponse scanProgress(@PathVariable Long id) {
        return ScanProgressResponse.from(
                scanExecutions.stateOf(id).orElseGet(() -> ScanExecutionState.idle(id)));
    }
}
