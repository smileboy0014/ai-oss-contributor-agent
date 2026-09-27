package com.ossagent.candidate.adapter.in.web.dto;

import com.ossagent.candidate.application.CreateDraftPrUseCase;

/**
 * Draft PR 생성 결과 — S-6 세 번째 게이트의 응답.
 *
 * <p>🔴 <b>PR 본문·제목을 싣지 않는다</b> (S-4). 대상 저장소 템플릿·빌드 출력·LLM 리뷰가
 * 섞인 텍스트이고, 스크럽을 통과했더라도 우리 API 응답에 다시 실을 이유가 없다.
 * 사람이 봐야 하는 것은 <b>PR 주소</b>이고 내용은 거기 있다.
 *
 * @param reusedExisting 🔴 <b>이미 열려 있던 PR 을 붙였는가.</b> {@code true} 면 우리가 이번에
 *                       만들지 않았다는 뜻이다 — 사람이 그 사실을 알아야 「왜 내가 방금 만든
 *                       PR 에 내 변경이 없지」를 추적할 수 있다
 * @param terminal       항상 {@code true} — {@code PR_CREATED} 는 종단이다
 */
public record DraftPrResponse(Long candidateId, int prNumber, String prUrl, String forkUrl,
                              String from, String to, boolean terminal, boolean reusedExisting) {

    public static DraftPrResponse from(CreateDraftPrUseCase.Result result) {
        return new DraftPrResponse(
                result.candidateId(),
                result.pullRequest().number(),
                result.pullRequest().url(),
                result.pullRequest().forkUrl(),
                result.transition().from().name(),
                result.transition().to().name(),
                result.transition().to().isTerminal(),
                result.reusedExisting());
    }
}
