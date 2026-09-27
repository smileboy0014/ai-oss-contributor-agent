package com.ossagent.candidate.adapter.in.web;

import com.ossagent.candidate.adapter.in.web.dto.CandidateDetail;
import com.ossagent.candidate.adapter.in.web.dto.CandidateSummary;
import com.ossagent.candidate.adapter.in.web.dto.DraftPrResponse;
import com.ossagent.candidate.adapter.in.web.dto.PageResponse;
import com.ossagent.candidate.adapter.in.web.dto.SelectionResponse;
import com.ossagent.candidate.application.CandidateQuery;
import com.ossagent.candidate.application.CreateDraftPrUseCase;
import com.ossagent.candidate.application.FindCandidatesUseCase;
import com.ossagent.candidate.application.ImplementCandidateUseCase;
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
 * <p>#18 이 {@code implement}(착수)를 열었다. 🔴 <b>실행기와 함께</b> 열었다는 것이 요점이다 —
 * {@code IMPLEMENTING} 에서 나갈 트리거가 없으면 후보가 갇히고, 그래서 #24 는 이 문을
 * 열지 않았다. 검증기가 아직 배선되지 않았더라도 후보는 <b>{@code FAILED} 로 떨어진다</b>
 * (종단이고, 그 자체가 사람에게 넘기는 신호다 — S-6).
 *
 * <p>#23 이 {@code pull-request} 를 열어 <b>승인 지점 셋이 전부 열렸다.</b> 여기도 같은 강제를
 * 지켰다 — PR 생성기와 <b>같은 PR 에서</b> 열었다. 먼저 열었다면 <b>PR 없이 종단
 * {@code PR_CREATED}</b> 가 만들어졌을 것이고, 종단이라 빠져나올 수도 없다.
 */
@RestController
@RequestMapping("/api/candidates")
public class CandidateController {

    private final FindCandidatesUseCase findCandidates;
    private final SelectCandidateUseCase selectCandidate;
    private final ImplementCandidateUseCase implementCandidate;
    private final CreateDraftPrUseCase createDraftPr;

    public CandidateController(FindCandidatesUseCase findCandidates,
            SelectCandidateUseCase selectCandidate,
            ImplementCandidateUseCase implementCandidate,
            CreateDraftPrUseCase createDraftPr) {
        this.findCandidates = findCandidates;
        this.selectCandidate = selectCandidate;
        this.implementCandidate = implementCandidate;
        this.createDraftPr = createDraftPr;
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

    /**
     * 🔴 <b>사람이 착수를 지시한다</b> — {@code SELECTED → IMPLEMENTING}. S-6 <b>두 번째 게이트</b>.
     *
     * <p>선정(트리아지)과 다르다 — 이 호출은 <b>LLM + 샌드박스 최대 30분</b>을 태운다.
     * Q-5 가 둘을 가른 이유이고, 「관심 있다」가 곧바로 30분짜리 실행이 되면 고를 수가 없다.
     *
     * <h2>🔴 여기서 PR 까지 가지 않는다</h2>
     *
     * <p>PRD §24 시퀀스는 이 호출 하나가 <b>Draft PR 생성까지</b> 하는 것으로 그려져 있으나
     * <b>그 다이어그램이 틀렸다</b>(#30). 그대로 구현하면 <b>세 번째 게이트가 사라지고
     * S-2 까지 뚫린다.</b> PR 생성은 {@code POST /{id}/pull-request} 로 #23 이 연다.
     *
     * <h2>🔴 {@code PolicyClearance} 를 받지 않는다</h2>
     *
     * <p>파라미터·요청 바디로 받으면 <b>외부가 통행증을 주입</b>할 수 있고 게이트가 껍데기가 된다.
     * 통행증은 {@code AnalyzeRepositoryPolicyUseCase.clearanceFor} 가 <b>후보의 저장소로</b>
     * 발급하는 것만 유효하다 — {@code ApprovalGateArchitectureTest} 가 그 사실을 고정한다.
     *
     * <p>⚠️ 보류·금지 저장소면 <b>403</b> 이다 (S-5). 보류는 시간이나 재시도로 풀리지 않고
     * {@code POST /api/repositories/{id}/policy/resolution} 으로 <b>사람이 해소</b>한다 (Q-8).
     *
     * <p>⚠️ <b>멱등이 아니다.</b> 두 번째 호출은 409 다 — {@code select} 와 같은 이유로,
     * 승인은 「몇 번 눌러도 같은 결과」가 아니라 <b>한 번 일어난 사건</b>이어야 한다.
     */
    @PostMapping("/{id}/implement")
    public SelectionResponse implement(@PathVariable Long id) {
        return SelectionResponse.from(id, implementCandidate.implement(id));
    }

    /**
     * 🔴 <b>사람이 Draft PR 을 내보낸다</b> — {@code READY_FOR_PR → PR_CREATED}.
     * S-6 <b>세 번째</b>이자 마지막 게이트이고, <b>여기서 자동화가 끝난다.</b>
     *
     * <p>#24 가 이 문을 열지 않은 이유는 일정이 아니라 <b>지금 열면 PR 없이 종단
     * {@code PR_CREATED} 가 되기 때문</b>이었다. #23 이 PR 생성기와 <b>같은 PR 에서</b>
     * 열므로 그 조건이 해소됐다 — {@code CandidateApprovalApiTest} 의 404 회귀를
     * 함께 고치는 것이 그 강제였다.
     *
     * <p>⚠️ <b>셋이 열렸다고 하나로 합치지 않는다.</b> #18 이 {@code implement} 를 열어
     * 게이트 셋이 모두 서 있지만, 「{@code implement} 가 PR 까지 흘려보내면 반려」는 그대로다
     * (S-6). PRD §24 시퀀스가 그렇게 그려져 있었고 <b>그 다이어그램이 틀렸다</b>(#30).
     *
     * <p>⚠️ <b>요청 바디가 없다.</b> {@code select} 와 같은 이유다 — 제목·본문을 받기
     * 시작하면 게이트가 파라미터화되고, 그러면 외부가 대상 저장소에 나가는 텍스트를
     * 직접 주입할 수 있게 된다(S-4).
     *
     * <p>⚠️ <b>멱등이 아니다.</b> 두 번째 호출은 409 다 — {@code PR_CREATED} 는 종단이라
     * 나가는 전이가 없다. 「승인을 몇 번 눌러도 같다」가 되면 「사람이 한 번 승인했다」를
     * 사후에 셀 수 없다.
     *
     * <p>🔵 다만 <b>upstream 에 이미 열린 PR 이 있는 경우</b>는 다르다 — 후보가 아직
     * {@code READY_FOR_PR} 이고 PR 만 앞서 만들어진 복구 상황이라, 새로 만들지 않고
     * 그것을 붙이고 200 을 준다({@code reusedExisting=true}). 남의 저장소에 두 번째 PR 을
     * 열지 않는 것이 S-2 다.
     */
    @PostMapping("/{id}/pull-request")
    public DraftPrResponse createPullRequest(@PathVariable Long id) {
        return DraftPrResponse.from(createDraftPr.create(id));
    }
}
