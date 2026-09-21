package com.ossagent.candidate.adapter.in.web;

import com.ossagent.candidate.adapter.in.web.dto.CandidateSummary;
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
}
