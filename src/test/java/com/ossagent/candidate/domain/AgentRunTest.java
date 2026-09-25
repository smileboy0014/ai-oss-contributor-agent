package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AgentRunTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);

    private static AgentRun running() {
        return AgentRun.start(1L, AgentRun.Stage.CODE, 1, CLOCK);
    }

    @Test
    void 시작하면_RUNNING_이고_시각이_주입된_Clock_에서_온다() {
        AgentRun run = running();

        assertThat(run.getStatus()).isEqualTo(AgentRun.RunStatus.RUNNING);
        assertThat(run.getStartedAt()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
        assertThat(run.getFinishedAt()).isNull();
    }

    @Test
    void 성공하면_토큰이_남는다() {
        AgentRun run = running();

        run.succeed(11, 22, CLOCK);

        assertThat(run.getStatus()).isEqualTo(AgentRun.RunStatus.SUCCEEDED);
        assertThat(run.getInputTokens()).isEqualTo(11);
        assertThat(run.getOutputTokens()).isEqualTo(22);
    }

    @Test
    void 실패해도_아는_토큰은_남는다() {
        AgentRun run = running();

        run.fail("TRUNCATED", 8, 4096, CLOCK);

        assertThat(run.getStatus()).isEqualTo(AgentRun.RunStatus.FAILED);
        assertThat(run.getOutputTokens())
                .as("절단은 실패지만 토큰은 나갔다 — 버리면 비용이 사라진다")
                .isEqualTo(4096);
    }

    @Test
    void 종단_상태에서는_어떤_전이도_일어나지_않는다_S6() {
        AgentRun succeeded = running();
        succeeded.succeed(1, 1, CLOCK);

        assertThatThrownBy(() -> succeeded.succeed(2, 2, CLOCK))
                .as("끝난 기록을 다시 쓰면 비용 장부가 조용히 바뀐다")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> succeeded.fail("X", CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 실패한_기록도_다시_쓸_수_없다_S6() {
        AgentRun failed = running();
        failed.fail("TIMEOUT", CLOCK);

        assertThatThrownBy(() -> failed.succeed(1, 1, CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void attempt_는_1_부터다() {
        assertThatThrownBy(() -> AgentRun.start(1L, AgentRun.Stage.CODE, 0, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
