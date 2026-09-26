package com.ossagent.candidate.adapter.in.web;

import com.ossagent.candidate.adapter.in.web.dto.CandidateDetail;
import com.ossagent.candidate.adapter.in.web.dto.CandidateSummary;
import com.ossagent.candidate.adapter.in.web.dto.PageResponse;
import com.ossagent.candidate.adapter.in.web.dto.SelectionResponse;
import com.ossagent.candidate.application.CandidateQuery;
import com.ossagent.candidate.application.FindCandidatesUseCase;
import com.ossagent.candidate.application.SelectCandidateUseCase;
import com.ossagent.candidate.domain.CandidateStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 후보 조회 진입점. <b>변환·위임만</b> 한다 — 판단과 스크럽은 UseCase 몫이다.
 *
 * <p>⚠️ <b>여기서 스크럽하지 않는다.</b> 트랜잭션이 이미 닫혀 있고, 스크럽 책임이 adapter 로 새면
 * 빠뜨리는 순간 토큰이 나간다(S-4). UseCase 가 넘겨주는 뷰는 <b>이미 걸러진</b> 값이다.
 *
 * <h2>쓰기 엔드포인트는 <b>사람의 승인 지점만</b> 연다 — S-6</h2>
 *
 * <p>#24 가 {@code select} 와 {@code reject}(선택 취소)를 열었다. 둘 다 사람이 부르는 문이고,
 * <b>상태만 바꾸고 끝난다</b> — 여기서 다음 단계로 흘려보내지 않는다.
 *
 * <p>⚠️ <b>{@code implement}·{@code pull-request} 는 아직 없다.</b> 「나중에 추가」가 아니라
 * <b>지금 만들면 후보가 빠져나올 수 없는 상태에 갇히기 때문</b>이다 —
 * {@code implement} 는 {@code IMPLEMENTING} 에서 나갈 트리거가 없고,
 * {@code pull-request} 는 <b>PR 없이 종단 {@code PR_CREATED}</b> 를 만든다.
 * 실행기와 함께 열린다 — #18 · #23.
 */
@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    private final FindCandidatesUseCase findCandidates;
    private final SelectCandidateUseCase selectCandidate;

    public CandidateController(FindCandidatesUseCase findCandidates,
            SelectCandidateUseCase selectCandidate) {
        this.findCandidates = findCandidates;
        this.selectCandidate = selectCandidate;
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

    /**
     * 🔴 <b>사람이 후보를 고른다</b> — {@code ANALYZED → SELECTED}. S-6 첫 번째 게이트.
     *
     * <p>Q-5 가 {@code implement} 와 가른 문이다. 선정은 트리아지(비용 0)이고 착수는
     * LLM + 샌드박스 최대 30분이라, 「관심 있다」가 곧바로 30분짜리 실행이 되면 고를 수가 없다.
     *
     * <p>⚠️ <b>요청 바디가 없다.</b> 무엇을 고를지는 경로가 전부이고, 바디를 받기 시작하면
     * 「어떤 정책으로 고를지」 같은 것이 흘러들어와 게이트가 파라미터화된다.
     *
     * <p>⚠️ <b>멱등이 아니다.</b> 두 번째 호출은 {@code SELECTED → SELECTED} 가 아니라
     * 409 다 — 전이표에 그 전이가 없다. 승인은 「몇 번 눌러도 같은 결과」가 아니라
     * <b>한 번 일어난 사건</b>이어야 사후에 셀 수 있다.
     */
    @PostMapping("/{id}/select")
    public SelectionResponse select(@PathVariable Long id) {
        return SelectionResponse.from(id, selectCandidate.select(id));
    }

    /**
     * 🔴 <b>사람이 선택을 취소한다</b> — {@code SELECTED → REJECTED}. Q-5 확정 ②.
     *
     * <p>⚠️ <b>{@code DELETE} 가 아니라 {@code POST} 다.</b> 지우는 것이 아니라
     * <b>종단 상태로 전이시키는 행위</b>이고, 후보 행은 그대로 남아 「골랐다가 물렸다」는
     * 기록이 된다. {@code DELETE /{id}/select} 로 두면 「선정을 되돌린다」로 읽혀
     * {@code ANALYZED} 로 복귀할 것 같지만, 실제로는 <b>되돌릴 수 없다.</b>
     *
     * <p>⚠️ 자동 취소 경로는 만들지 않는다 — 스케줄러·이벤트가 이 UseCase 를 부르면
     * 「사람이 물렸다」가 증거로서 무의미해진다(S-6).
     */
    @PostMapping("/{id}/reject")
    public SelectionResponse reject(@PathVariable Long id) {
        return SelectionResponse.from(id, selectCandidate.reject(id));
    }
}
