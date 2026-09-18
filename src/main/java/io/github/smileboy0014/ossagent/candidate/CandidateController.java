package io.github.smileboy0014.ossagent.candidate;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    @GetMapping
    public List<CandidateSummary> list() {
        return List.of();
    }

    public record CandidateSummary(Long id, Long issueId, CandidateStatus status, double confidence) {
    }
}
