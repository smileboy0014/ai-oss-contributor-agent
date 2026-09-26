package com.ossagent.repository.adapter.in.web.dto;

import com.ossagent.repository.application.ResolvePolicyPendingUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 사람이 보류를 해소한다 — Q-8 · #24.
 *
 * <p>🔴 <b>{@code allowed} 가 {@code Boolean} 이고 {@code @NotNull} 이다.</b>
 * {@code boolean} 원시타입이면 필드를 빠뜨린 요청이 <b>조용히 {@code false}</b> 가 된다.
 * 여기서 기본값은 「금지」라 안전해 보이지만, 그것은 <b>사람이 판단하지 않은 금지</b>이고
 * 근거 없는 판정이 {@code resolvedAt} 과 함께 박힌다. 빠뜨렸으면 400 이어야 한다.
 *
 * <p>🔴 <b>{@code note} 는 필수다.</b> 근거 없는 해소는 나중에 재검토할 수 없다 —
 * 도메인도 같은 것을 요구하고({@code RepositoryPolicy.resolvePending}), 여기 검증은
 * <b>그 규칙을 대신하는 것이 아니라 400 으로 빨리 되돌려주는 것</b>이다.
 *
 * <p>⚠️ 상한이 컬럼({@code VARCHAR(1024)})보다 짧은 이유 — 스크럽이 마스킹하며 길이를
 * <b>늘릴 수 있다.</b> 도메인이 한 번 더 자르지만, 여기서 걸리면 잘린 줄 모르고 저장되는
 * 대신 400 을 받는다.
 */
public record PolicyResolutionRequest(
        @NotNull Boolean allowed,
        @NotBlank @Size(max = ResolvePolicyPendingUseCase.MAX_NOTE_LENGTH) String note) {
}
