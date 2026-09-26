package com.ossagent.candidate.adapter.in.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 페이지 응답 매핑.
 *
 * <p>{@code totalPages} 계산 자체는 Spring 의 몫이라 다시 검증하지 않는다. 여기서 보는 것은
 * <b>우리 타입으로 옮길 때 값이 어긋나지 않는가</b>와 <b>Spring 타입이 응답에 새지 않는가</b>다.
 */
class PageResponseTest {

    @Test
    @DisplayName("Page 의 값이 그대로 옮겨진다")
    void Page_를_그대로_옮긴다() {
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.items()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("빈 페이지도 터지지 않는다")
    void 빈_페이지를_옮긴다() {
        PageResponse<String> response = PageResponse.from(Page.empty(PageRequest.of(0, 20)));

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages())
                .as("0건이면 페이지도 0이다 — 호출자가 1 을 기대하면 빈 목록을 계속 넘긴다")
                .isZero();
    }

    @Test
    @DisplayName("응답에 Spring 타입이 새지 않는다")
    void Spring_타입을_노출하지_않는다() {
        assertThat(PageResponse.class.getRecordComponents())
                .as("""
                        PageImpl 직렬화는 Spring 이 스스로 경고하는 자리다 — JSON 구조가 버전에 따라 바뀐다.
                        응답 계약에 프레임워크 타입이 들어오면 우리가 통제할 수 없는 것이 계약이 된다.""")
                .noneSatisfy(component -> assertThat(component.getType().getName())
                        .startsWith("org.springframework"));
    }
}
