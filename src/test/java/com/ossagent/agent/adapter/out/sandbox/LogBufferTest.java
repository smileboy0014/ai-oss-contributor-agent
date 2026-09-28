package com.ossagent.agent.adapter.out.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 컨테이너 로그 프레임이 <b>멀티바이트 문자 한가운데</b>에서 잘려도 깨지지 않는다 — #115.
 *
 * <p>Docker 의 demux 스트림은 임의 바이트 위치에서 프레임을 가른다. 프레임마다
 * {@code new String(bytes)} 를 하면 경계에 걸친 한 글자가 U+FFFD 둘이 되고, 그것이
 * {@code StageResult.summary}(재시도 프롬프트 · DB)와 diff 검사 입력에 섞인다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LogBufferTest {

    @Test
    @DisplayName("프레임 경계에 걸친 한글이 그대로 이어 붙는다")
    void 프레임_경계의_멀티바이트_문자가_깨지지_않는다() {
        byte[] all = "빌드 실패: 테스트 3건\n".getBytes(StandardCharsets.UTF_8);
        // 「실」(3바이트)의 두 번째 바이트 뒤에서 자른다
        int cut = "빌드 ".getBytes(StandardCharsets.UTF_8).length + 1;
        DockerContainerOperations.LogBuffer buffer = new DockerContainerOperations.LogBuffer(10_000);

        buffer.append(Arrays.copyOfRange(all, 0, cut));
        buffer.append(Arrays.copyOfRange(all, cut, all.length));

        assertThat(buffer.toLogs().output())
                .as("한 글자가 두 프레임에 걸쳐도 U+FFFD 없이 원문이어야 한다")
                .isEqualTo("빌드 실패: 테스트 3건\n")
                .doesNotContain("�");
    }

    @Test
    @DisplayName("상한을 넘으면 더 쌓지 않고 잘린 것으로 표시한다")
    void 상한을_넘으면_잘린_것으로_표시한다() {
        DockerContainerOperations.LogBuffer buffer = new DockerContainerOperations.LogBuffer(5);

        buffer.append("abcdefgh".getBytes(StandardCharsets.UTF_8));

        assertThat(buffer.toLogs().output()).isEqualTo("abcde");
        assertThat(buffer.toLogs().truncated()).isTrue();
    }

    @Test
    @DisplayName("스트림 끝에 남은 반쪽 바이트는 대체 문자로 접는다 — 조용히 버리지 않는다")
    void 끝에_남은_반쪽_바이트는_대체_문자다() {
        byte[] all = "가".getBytes(StandardCharsets.UTF_8);
        DockerContainerOperations.LogBuffer buffer = new DockerContainerOperations.LogBuffer(100);

        buffer.append(Arrays.copyOfRange(all, 0, 2));

        assertThat(buffer.toLogs().output()).isEqualTo("�");
    }
}
