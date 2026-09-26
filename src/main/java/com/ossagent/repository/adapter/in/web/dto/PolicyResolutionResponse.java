package com.ossagent.repository.adapter.in.web.dto;

import java.time.Instant;

/**
 * 보류 해소 결과 — Q-8 · #24.
 *
 * <p>🔴 <b>{@code aiContributionAllowed} 를 그대로 싣는다.</b> 「해소했다」만 돌려주면
 * 허용으로 풀었는지 금지로 닫았는지가 응답에 없다. 둘 다 정상적인 해소 결과라
 * (Q-8 — 「해소」는 「허용」이 아니다) 방향이 보이지 않으면 화면이 결과를 오독한다.
 *
 * <p>⚠️ <b>{@code resolutionNote} 를 되돌려주지 않는다.</b> 저장된 값은 스크럽·절단을 거친
 * 것이라 사람이 보낸 것과 다를 수 있는데, 그것을 해소 응답에 실으면 <b>「무엇이 지워졌나」를
 * 되돌려 보여주는 채널</b>이 된다. 마스킹된 자리를 되읽어 원문을 좁혀 가는 데 쓸 수 있다.
 * 저장된 사유는 저장소 상세 조회에서 본다.
 */
public record PolicyResolutionResponse(
        Long repositoryId,
        boolean aiContributionAllowed,
        Instant resolvedAt) {
}
