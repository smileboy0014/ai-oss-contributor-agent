package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.candidate.application.ImplementationRegistry.ImplementationPhase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 후보·저장소 잠금 (#106). 워크스페이스가 저장소당 하나라 저장소 잠금이 본체다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ImplementationRegistryTest {

    private final ImplementationRegistry registry =
            new ImplementationRegistry(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    @DisplayName("🔴 같은 저장소의 두 번째 후보는 거절된다 — 겹치면 서로의 트리를 지운다")
    void 같은_저장소는_하나만() {
        assertThat(registry.tryStart(1L, 10L)).isTrue();

        assertThat(registry.tryStart(2L, 10L)).isFalse();
        assertThat(registry.tryStart(3L, 11L)).as("다른 저장소는 무관하다").isTrue();
    }

    @Test
    @DisplayName("마감하면 저장소 잠금이 풀리고 진행 상태는 남는다")
    void 마감은_잠금을_풀고_상태를_남긴다() {
        registry.tryStart(1L, 10L);

        registry.finish(1L, ImplementationPhase.SUCCEEDED, null);

        assertThat(registry.isRepositoryBusy(10L)).isFalse();
        assertThat(registry.tryStart(2L, 10L)).isTrue();
        assertThat(registry.stateOf(1L)).get()
                .extracting(ImplementationRegistry.ImplementationProgress::phase)
                .isEqualTo(ImplementationPhase.SUCCEEDED);
    }

    @Test
    @DisplayName("끝난 후보는 다시 잡을 수 있다 — 미룸 뒤 사람이 다시 누른다")
    void 끝난_후보는_다시_잡는다() {
        registry.tryStart(1L, 10L);
        registry.finish(1L, ImplementationPhase.DEFERRED, "일시 장애");

        assertThat(registry.tryStart(1L, 10L)).isTrue();
    }

    @Test
    @DisplayName("release 는 흔적을 남기지 않는다 — 잡았으나 실행에 못 들어간 경우")
    void release_는_흔적을_남기지_않는다() {
        registry.tryStart(1L, 10L);

        registry.release(1L);

        assertThat(registry.stateOf(1L)).isEmpty();
        assertThat(registry.isRepositoryBusy(10L)).isFalse();
    }
}
