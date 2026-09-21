package com.ossagent.repository.adapter.in.web;

import com.ossagent.repository.adapter.in.web.dto.RegisterRepositoryRequest;
import com.ossagent.repository.adapter.in.web.dto.RepositoryResponse;
import com.ossagent.repository.adapter.in.web.dto.ScanRequestedResponse;
import com.ossagent.repository.application.RegisterRepositoryUseCase;
import com.ossagent.repository.application.RequestScanUseCase;
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

    public RepositoryController(RegisterRepositoryUseCase registerRepository, RequestScanUseCase requestScanUseCase) {
        this.registerRepository = registerRepository;
        this.requestScanUseCase = requestScanUseCase;
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

    @PostMapping("/{id}/scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScanRequestedResponse requestScan(@PathVariable Long id) {
        Instant requestedAt = requestScanUseCase.requestScan(id);
        return new ScanRequestedResponse(id, "SCAN_REQUESTED", requestedAt);
    }
}
