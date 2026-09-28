package com.ossagent.repository.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 저장소별 스캔 주기 판정 — #26 FR-1·FR-2.
 *
 * <p>고정 시각으로 본다. {@code Instant.now()} 를 쓰면 경계 케이스가 시계에 따라 흔들린다.
 */
class OssRepositoryScanIntervalTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    private OssRepository repository() {
        return new OssRepository("spring-projects", "spring-kafka",
                "https://github.com/spring-projects/spring-kafka");
    }

    @Nested
    @DisplayName("한 번도 안 돌았을 때")
    class NeverScanned {

        @Test
        @DisplayName("🔴 즉시 대상이다 — 아니면 새로 등록한 저장소가 영원히 스캔되지 않는다")
        void 한_번도_안_돌았으면_즉시_대상이다() {
            assertThat(repository().isDueForScan(NOW, DEFAULT_INTERVAL))
                    .as("🔴 「모르니 건너뛴다」로 두면 주기 필터가 모든 신규 저장소를 영구히 거른다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("스캔 시각을 남긴 뒤 (#108)")
    class AfterMarkScanned {

        @Test
        @DisplayName("🔴 markScanned 뒤 주기 안이면 대상이 아니다 — 스케줄러가 이것을 남겨야 주기가 산다")
        void 스캔_시각을_남기면_주기_안에는_대상이_아니다() {
            OssRepository repository = repository();
            repository.markScanned(NOW);

            assertThat(repository.isDueForScan(NOW.plus(Duration.ofMinutes(10)), DEFAULT_INTERVAL))
                    .as("이 값이 안 남으면 fixed-delay 마다 전부 재스캔한다 — #26 의 주기가 통째로 무효였다")
                    .isFalse();
            assertThat(repository.isDueForScan(NOW.plus(DEFAULT_INTERVAL), DEFAULT_INTERVAL)).isTrue();
        }
    }

    @Nested
    @DisplayName("전역 기본 주기")
    class DefaultInterval {

        @Test
        @DisplayName("주기가 덜 지났으면 대상이 아니다")
        void 주기_전이면_대상이_아니다() {
            OssRepository repository = repository();
            repository.markScanned(NOW.minus(Duration.ofMinutes(59)));

            assertThat(repository.isDueForScan(NOW, DEFAULT_INTERVAL)).isFalse();
        }

        @Test
        @DisplayName("🔴 정각은 대상이다 — 경계를 배타로 잡으면 실질 주기가 두 배가 된다")
        void 정각은_대상이다() {
            OssRepository repository = repository();
            repository.markScanned(NOW.minus(DEFAULT_INTERVAL));

            assertThat(repository.isDueForScan(NOW, DEFAULT_INTERVAL))
                    .as("🔴 고정 주기 스케줄러에서 정각이 매번 한 박자씩 밀리면 주기가 배가 된다")
                    .isTrue();
        }

        @Test
        @DisplayName("주기가 지났으면 대상이다")
        void 주기_후면_대상이다() {
            OssRepository repository = repository();
            repository.markScanned(NOW.minus(Duration.ofHours(2)));

            assertThat(repository.isDueForScan(NOW, DEFAULT_INTERVAL)).isTrue();
        }
    }

    @Nested
    @DisplayName("저장소별 주기가 기본값을 이긴다 — FR-1")
    class PerRepositoryInterval {

        @Test
        @DisplayName("저장소 주기가 짧으면 기본값보다 먼저 대상이 된다")
        void 저장소_주기가_짧으면_먼저_대상이_된다() {
            OssRepository repository = repository();
            repository.updateScanInterval(10);
            repository.markScanned(NOW.minus(Duration.ofMinutes(15)));

            assertThat(repository.isDueForScan(NOW, DEFAULT_INTERVAL))
                    .as("저장소 주기 10분이 기본값 1시간을 이겨야 한다")
                    .isTrue();
        }

        @Test
        @DisplayName("저장소 주기가 길면 기본값이 지나도 대상이 아니다")
        void 저장소_주기가_길면_기본값이_지나도_대상이_아니다() {
            OssRepository repository = repository();
            repository.updateScanInterval(360);
            repository.markScanned(NOW.minus(Duration.ofHours(2)));

            assertThat(repository.isDueForScan(NOW, DEFAULT_INTERVAL)).isFalse();
        }

        @Test
        @DisplayName("🔴 0·음수는 「무제한」이 아니다 — 기본값으로 떨어진다")
        void 비양수_주기는_기본값으로_떨어진다() {
            OssRepository zero = repository();
            zero.updateScanInterval(0);
            zero.markScanned(NOW.minus(Duration.ofMinutes(30)));

            assertThat(zero.isDueForScan(NOW, DEFAULT_INTERVAL))
                    .as("🔴 0 이 통과하면 주기가 사라져 스케줄러가 매 주기마다 그 저장소를 돌린다")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("현재 시각·기본 주기는 필수다 — Clock 주입을 우회하지 못하게")
    void 필수값이_없으면_거부한다() {
        OssRepository repository = repository();

        assertThatThrownBy(() -> repository.isDueForScan(null, DEFAULT_INTERVAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.isDueForScan(NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
