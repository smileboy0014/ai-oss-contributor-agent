package com.ossagent.candidate.adapter.in.web;

import com.ossagent.candidate.adapter.in.web.dto.CandidateDetail;
import com.ossagent.candidate.adapter.in.web.dto.CandidateSummary;
import com.ossagent.candidate.adapter.in.web.dto.PageResponse;
import com.ossagent.candidate.application.CandidateQuery;
import com.ossagent.candidate.application.FindCandidatesUseCase;
import com.ossagent.candidate.domain.CandidateStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 후보 조회 진입점. <b>변환·위임만</b> 한다 — 판단과 스크럽은 UseCase 몫이다.
 *
 * <p>⚠️ <b>여기서 스크럽하지 않는다.</b> 트랜잭션이 이미 닫혀 있고, 스크럽 책임이 adapter 로 새면
 * 빠뜨리는 순간 토큰이 나간다(S-4). UseCase 가 넘겨주는 뷰는 <b>이미 걸러진</b> 값이다.
 *
 * <p>⚠️ <b>쓰기 엔드포인트를 만들지 않는다.</b> {@code select}·{@code implement}·{@code pull-request}
 * 는 사람의 승인 지점이고 #24 소관이다 — S-6.
 */
@RestController
@RequestMapping("/api/candidates")
@Validated
public class CandidateController {

    private final FindCandidatesUseCase findCandidates;

    public CandidateController(FindCandidatesUseCase findCandidates) {
        this.findCandidates = findCandidates;
    }

    /**
     * 목록. 필터는 전부 선택적이고, 미지정 축은 거르지 않는다.
     *
     * <p>정렬은 <b>고정</b>이다({@code id DESC}) — {@code sort} 파라미터를 받지 않는다.
     * 정렬 없는 페이지네이션은 행 순서를 보장하지 않고, 호출자가 바꾸면 페이지 경계가 흔들린다.
     *
     * <p>⚠️ {@code difficulty} 는 DB 가 {@code VARCHAR} 에 CHECK 가 없어 <b>자유 문자열</b>이다.
     * 없는 값은 400 이 아니라 빈 목록이다.
     */
    @GetMapping
    public PageResponse<CandidateSummary> list(
            @RequestParam(required = false) CandidateStatus status,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) BigDecimal minConfidence,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + CandidateQuery.DEFAULT_SIZE)
            @Min(1) @Max(CandidateQuery.MAX_SIZE) int size) {

        var found = findCandidates.findAll(
                new CandidateQuery(status, difficulty, minConfidence), page, size);
        return PageResponse.from(found.map(CandidateSummary::from));
    }

    /** 상세. 없는 후보는 {@code CandidateNotFoundException} → 404 ({@code support/web}). */
    @GetMapping("/{id}")
    public CandidateDetail detail(@PathVariable Long id) {
        return CandidateDetail.from(findCandidates.findDetail(id));
    }
}
