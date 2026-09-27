package com.ossagent.pullrequest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기준 브랜치가 경로에 조립되므로 값 타입이다.
 *
 * <p>{@code RepositoryCoordinates} 가 owner·name 을 제한한 것과 <b>같은 이유</b>이고,
 * 초안에서는 이 값만 그 규율에서 빠져 있었다 — 안전 리뷰가 짚은 불일치다.
 */
class BaseBranchTest {

    @Test
    @DisplayName("경로 세그먼트를 벗어나는 문자는 거부한다")
    void 경로_조작_문자를_거부한다() {
        // 🔴 여집합이다 — 허용 문자만 남기므로 새 변종이 나와도 목록을 고칠 필요가 없다
        for (String hostile : new String[] {
                "../../spring-projects/x", "%2e%2e/x", "..%2fx", "a\\b", "a@b", "a:b",
                "a?b", "a#b", "/main", "main/", "a//b", "a/./b", "a/../b", "  "}) {
            assertThatThrownBy(() -> new BaseBranch(hostile))
                    .as("입력=%s", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("정상 브랜치 이름은 통과한다 — 좁혀서 실제 저장소를 막지 않는다")
    void 정상_브랜치는_통과한다() {
        // 🔴 과차단 대조. 대상 저장소는 release/3.2.x · 1.0.x 같은 이름을 실제로 쓴다
        for (String legit : new String[] {"main", "master", "develop", "release/3.2.x",
                "1.0.x", "gh-pages", "feature_x", "v2.1.0"}) {
            assertThatCode(() -> new BaseBranch(legit)).as("입력=%s", legit)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("빈 값과 null 을 거부한다")
    void 빈_값을_거부한다() {
        assertThatThrownBy(() -> new BaseBranch(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BaseBranch("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기본값은 main 이다")
    void 기본값() {
        assertThat(BaseBranch.main().value()).isEqualTo("main");
    }
}
