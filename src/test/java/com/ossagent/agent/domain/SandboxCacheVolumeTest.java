package com.ossagent.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>다른 저장소가 같은 캐시를 쓰지 않는다</b> — #18 리뷰 · S-3 · Q-4.
 *
 * <h2>왜 이름 충돌이 안전 문제인가</h2>
 *
 * <p>{@code SandboxPipeline} 이 「이 저장소는 준비됐다」를 <b>볼륨 이름으로</b> 기억한다.
 * 두 저장소가 같은 이름을 얻으면 B 가 A 의 워밍으로 준비됐다고 표시되어
 * <b>A 의 의존성 캐시로 오프라인 실행</b>을 한다 — 그 결과가 바로 파이프라인이
 * 막으려던 <b>「코드는 멀쩡한데 테스트 실패」</b>다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SandboxCacheVolumeTest {

    @Test
    @DisplayName("🔴 접으면 같아지는 좌표가 서로 다른 볼륨을 얻는다")
    void 접으면_같아지는_좌표가_갈린다() {
        // 소문자화 + 비영숫자를 '-' 로 접기 + '-' 로 잇기 → 둘 다 "a-b-c" 가 된다
        SandboxCacheVolume left = SandboxCacheVolume.forRepository("a-b", "c");
        SandboxCacheVolume right = SandboxCacheVolume.forRepository("a", "b-c");

        assertThat(left.name()).isNotEqualTo(right.name());
    }

    @Test
    @DisplayName("🔴 대소문자만 다른 좌표도 갈린다 — GitHub 은 다른 저장소일 수 있다")
    void 대소문자만_다른_좌표가_갈린다() {
        assertThat(SandboxCacheVolume.forRepository("Foo", "Bar").name())
                .isNotEqualTo(SandboxCacheVolume.forRepository("foo", "bar").name());
    }

    @Test
    @DisplayName("같은 좌표는 항상 같은 볼륨이다 — 재기동해도 캐시를 찾는다")
    void 같은_좌표는_같은_볼륨이다() {
        assertThat(SandboxCacheVolume.forRepository("spring-projects", "spring-kafka").name())
                .isEqualTo(SandboxCacheVolume.forRepository("spring-projects", "spring-kafka").name());
    }

    @Test
    @DisplayName("사람이 읽을 수 있는 슬러그가 남는다 — 이름만 보고 어느 저장소인지 안다")
    void 읽을_수_있는_슬러그가_남는다() {
        assertThat(SandboxCacheVolume.forRepository("spring-projects", "spring-kafka").name())
                .contains("spring-kafka");
    }

    @Test
    @DisplayName("아주 긴 좌표도 볼륨 이름 규칙 안에 들어간다")
    void 긴_좌표도_규칙_안이다() {
        String longName = "x".repeat(300);

        SandboxCacheVolume volume = SandboxCacheVolume.forRepository(longName, longName);

        // 🔴 규칙을 넘으면 compact 생성자가 거부한다 — 여기까지 왔다는 것이 곧 통과다
        assertThat(volume.name().length()).isLessThanOrEqualTo(127);
    }

    @Test
    @DisplayName("전부 걸러지는 좌표는 거부한다 — 전역 공유 볼륨이 되어선 안 된다")
    void 전부_걸러지는_좌표는_거부한다() {
        assertThatThrownBy(() -> SandboxCacheVolume.forRepository("///", "///"))
                .isInstanceOf(SandboxPermanentException.class);
    }
}
