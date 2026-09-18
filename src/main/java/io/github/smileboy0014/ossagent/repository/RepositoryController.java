package io.github.smileboy0014.ossagent.repository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
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
    private final OssRepositoryJpaRepository repositories;

    public RepositoryController(OssRepositoryJpaRepository repositories) {
        this.repositories = repositories;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryResponse register(@Valid @RequestBody RegisterRepositoryRequest request) {
        if (repositories.existsByUrl(request.url())) {
            throw new IllegalArgumentException("Repository is already registered: " + request.url());
        }
        return RepositoryResponse.from(repositories.save(new OssRepository(request.owner(), request.name(), request.url())));
    }

    @GetMapping
    public List<RepositoryResponse> list() {
        return repositories.findAll().stream().map(RepositoryResponse::from).toList();
    }

    @PostMapping("/{id}/scan")
    @Transactional
    public ScanRequestedResponse requestScan(@PathVariable Long id) {
        OssRepository repository = repositories.findById(id)
                .orElseThrow(() -> new RepositoryNotFoundException(id));
        repository.markScanned();
        return new ScanRequestedResponse(id, "SCAN_REQUESTED", Instant.now());
    }

    public record RegisterRepositoryRequest(
            @NotBlank String owner,
            @NotBlank String name,
            @NotBlank String url) {
    }

    public record RepositoryResponse(Long id, String owner, String name, String url, boolean enabled, Instant lastScannedAt) {
        static RepositoryResponse from(OssRepository repository) {
            return new RepositoryResponse(repository.getId(), repository.getOwner(), repository.getName(), repository.getUrl(),
                    repository.isEnabled(), repository.getLastScannedAt());
        }
    }

    public record ScanRequestedResponse(Long repositoryId, String status, Instant requestedAt) {
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class RepositoryNotFoundException extends RuntimeException {
        RepositoryNotFoundException(Long id) {
            super("Repository not found: " + id);
        }
    }
}
