package com.ossagent.candidate.adapter.in.web.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 페이지 응답.
 *
 * <p>⚠️ <b>Spring 의 {@code Page} 를 그대로 내보내지 않는다.</b> {@code PageImpl} 직렬화는 Spring 이
 * 스스로 경고하는 자리고(JSON 구조가 버전에 따라 바뀐다), 응답 계약에 프레임워크 타입이 들어온다.
 *
 * <p>정렬은 응답에 싣지 않는다 — <b>고정</b>이기 때문이다({@code ORDER BY id DESC}).
 * 바꿀 수 없는 것을 응답에 넣으면 바꿀 수 있는 것처럼 보인다.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
