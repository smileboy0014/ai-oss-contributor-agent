package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>S-4 — 알림 값에 대상 저장소 텍스트가 실릴 자리가 없다</b> (#26).
 *
 * <p>알림은 밖으로 나가는 경로다. 지금은 로그·메트릭뿐이지만 이 능력이 존재하는 이유가
 * 「나중에 Slack·Webhook 을 붙인다」이고, 그때 필드가 있으면 <b>이슈 제목·본문이 그대로
 * 실려 나간다.</b>
 *
 * <h2>🔴 이름 거부목록을 쓰지 않는다</h2>
 *
 * <p>초안은 {@code title}·{@code body}·{@code reason} … 을 열거했다. 그것은
 * {@code testing-philosophy.md} 가 「거부목록으로 방어하지 않는다」로 금한 방식이고,
 * {@code summary}·{@code context} 같은 <b>목록에 없는 이름</b>으로 본문을 담으면 조용히 통과한다.
 *
 * <p><b>여집합으로 뒤집는다</b> — 「무엇이 본문인가」를 묻는 대신 <b>「본문이 들어갈 수
 * 있는 타입이 하나라도 있는가」</b>를 묻는다. 대상 저장소 텍스트는 {@code String} 으로만
 * 들어온다. 이름을 몰라도 <b>타입으로 판정</b>할 수 있다.
 */
class CandidateNotificationTest {

    @Test
    @DisplayName("🔴 문자열을 담을 자리가 아예 없다 — 식별자와 숫자뿐이다 (S-4)")
    void 문자열을_담을_자리가_없다() {
        RecordComponent[] components = CandidateNotification.class.getRecordComponents();

        assertThat(components)
                .as("🔴 모수가 0 이면 이 검사는 아무것도 보지 않은 것이다")
                .isNotEmpty();
        assertThat(Arrays.stream(components)
                .filter(component -> component.getType() == String.class)
                .map(RecordComponent::getName))
                .as("🔴 문자열이 하나라도 생기면 그 자리가 다음 사람에게 「여기 담으면 된다」로 "
                        + "읽히고, 그 순간 스크럽을 강제할 지점이 필요해진다 — "
                        + "「호출자가 기억하는」 구조가 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("🕳 이 검사가 못 보는 것 — 문자열을 품은 타입을 필드로 넣으면 통과한다")
    void 한계를_기록해_둔다() {
        // 새는 방향을 먼저 적는다. 위 검사는 **최상위 컴포넌트의 타입**만 본다 —
        // 본문을 품은 값 타입(record)이나 List<String> 을 넣으면 String 이 아니라서
        // 조용히 통과한다. 그때 막는 것은 이 테스트가 아니라 리뷰다.
        //
        // 검사를 전이적으로 넓히지 않는 이유: 그 순간 이것이 「타입 그래프를 훑는
        // 손수 짠 리플렉션」이 되고, 무엇을 막는지가 흐려진다. 지금 이 타입은
        // 식별자 둘 + 숫자 하나라 그 확장이 필요 없다.
        assertThat(CandidateNotification.class.getRecordComponents())
                .as("컴포넌트가 늘면 위 한계가 실제 위험이 된다 — 그때 이 주석을 다시 읽는다")
                .hasSize(3);
    }

    @Test
    @DisplayName("식별자가 없으면 거부한다")
    void 식별자는_필수다() {
        assertThatThrownBy(() -> new CandidateNotification(null, 1L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandidateNotification(1L, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이슈 번호는 양수다 — 0 이 「모른다」로 흘러들지 않게")
    void 이슈_번호는_양수다() {
        assertThatThrownBy(() -> new CandidateNotification(1L, 1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
