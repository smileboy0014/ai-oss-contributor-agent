package com.ossagent.repository.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 좌표 검증 — 「우리가 어느 URL 을 부르는지 통제하고 있다」는 전제를 지키는 자리.
 *
 * <p>좌표는 사용자가 등록한 대상 저장소에서 오는 <b>외부 입력</b>이고, 어댑터가 이것을
 * {@code "/repos/%s/%s"} 로 조립해 요청 경로에 넣는다. 그 전제 위에 S-1(읽기 전용 표면)과
 * 예외·로그의 {@code path=} 가 서 있다.
 */
class RepositoryCoordinatesTest {

    @Test
    void 정상_좌표를_받아들인다() {
        RepositoryCoordinates coordinates =
                new RepositoryCoordinates("spring-projects", "spring-kafka");

        assertThat(coordinates.fullName()).isEqualTo("spring-projects/spring-kafka");
        assertThat(coordinates.toString()).isEqualTo("spring-projects/spring-kafka");
    }

    @Test
    void fullName_문자열에서_만든다() {
        assertThat(RepositoryCoordinates.parse(" spring-projects/spring-kafka "))
                .isEqualTo(new RepositoryCoordinates("spring-projects", "spring-kafka"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.boot", "spring_boot", "spring-boot", "Spring2"})
    @DisplayName("GitHub 이 실제로 쓰는 이름 형태를 막지 않는다")
    void 허용된_문자를_막지_않는다(String name) {
        assertThat(new RepositoryCoordinates("owner", name).name()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring?kafka",   // 쿼리 경계가 밀린다
            "spring#kafka",   // 프래그먼트 경계가 밀린다
            "spring kafka",   // 공백
            "spring/kafka",   // 경로 구분자
            "spring%2Fkafka", // 인코딩된 경로 구분자
            "..",             // 상위 경로
            "."
    })
    @DisplayName("경로를 벗어나게 만드는 문자를 거부한다")
    void 경로를_흔드는_문자를_거부한다_S1(String name) {
        assertThatThrownBy(() -> new RepositoryCoordinates("owner", name))
                .as("어댑터가 이 값을 요청 경로에 문자열로 조립한다. 여기서 막지 않으면 "
                        + "우리가 어느 URL 을 부르는지 통제하지 못한다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 비어_있는_좌표를_거부한다() {
        assertThatThrownBy(() -> new RepositoryCoordinates(null, "spring-kafka"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepositoryCoordinates("spring-projects", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryCoordinates.parse("spring-kafka"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
