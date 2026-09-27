package com.ossagent.candidate.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 「같은 실패가 반복되는가」를 판정하는 값 — #21 · FR-5.
 *
 * <h2>🕳 한계를 먼저 적는다 — <b>발화하지 않을 수 있다</b></h2>
 *
 * <p>{@code testing-philosophy.md} 가 「한계는 <b>오탐</b>이 아니라 <b>우회</b>를 적는다」로
 * 둔 순서 그대로, 조용히 통과하는 쪽을 맨 앞에 적는다.
 *
 * <p>🔴 <b>같은 오류인데 지문이 갈릴 수 있다.</b> 재료가 대상 저장소 빌드 출력이라
 * 타임스탬프·소요 시간·절대 경로·작업 디렉토리 이름이 섞이면 <b>매 바퀴 다른 문자열</b>이
 * 되고, 그러면 이 판정은 <b>한 번도 참이 되지 않는다.</b> 증상은 <b>아무것도 빨개지지
 * 않는 것</b>이다 — 조기 중단이 없었다는 사실은 로그에 남지 않는다.
 *
 * <p>⚠️ <b>그래도 정규화 규칙을 만들지 않는다.</b> 「타임스탬프를 지우고, 경로를 지우고,
 * 숫자를 지우고…」는 <b>거부목록</b>이고, 같은 저장소가 그 방식으로 세 번 연속 깨진 기록이
 * 있다({@code testing-philosophy.md} 「거부목록으로 방어하지 않는다」). 실측 없이 만든
 * 목록은 첫 대상 저장소에서 바로 빗나간다.
 *
 * <p>그래서 <b>실측 뒤에 정한다.</b> 그때까지 이 판정은 「반복이 명백할 때만 무는」
 * 보수적인 장치이고, 물지 못해도 <b>상한(3바퀴)이 뒤를 받친다</b> — 이것이 없어도
 * 무한 루프는 생기지 않는다. 그 점이 이 한계를 안고 갈 수 있는 이유다.
 *
 * <h2>🔴 원문을 들지 않는다 — S-4</h2>
 *
 * <p>재료는 빌드 출력과 모델 응답이다. 스크럽을 거쳤어도 <b>보관할 이유가 없는</b> 값이고,
 * 들고 있으면 {@code toString}·로그·예외 메시지로 샐 자리가 하나 더 생긴다.
 * 동일성 판정에 필요한 것은 <b>같은지 다른지</b>뿐이므로 해시로 충분하다.
 *
 * <p>⚠️ 해시는 <b>되돌릴 수 없지만 지울 수도 없다.</b> 이 값은 DB 에 저장하지 않고
 * 루프가 도는 동안 메모리에만 둔다 — 영속화하면 {@code @ExternalText} 등록 대상이 된다.
 */
public record FailureFingerprint(String value) {

    /** 충돌보다 로그 가독성이 중요하다. 48비트면 한 후보의 3바퀴 안에서 충분하다. */
    private static final int LENGTH = 12;

    public FailureFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("지문이 비어 있다");
        }
    }

    /**
     * 피드백에서 지문을 뜬다.
     *
     * <p>🔴 {@link CodingFeedback#kind()} 를 <b>함께</b> 넣는다. 지적 내용이 우연히 같아도
     * 「컴파일이 깨졌다」와 「리뷰가 변경을 요구했다」는 다른 실패다 — 종류를 빼면
     * 서로 다른 실패가 같은 지문이 되어 <b>정상 재시도가 조기 중단된다.</b>
     */
    public static FailureFingerprint of(CodingFeedback feedback) {
        if (feedback == null) {
            throw new IllegalArgumentException("피드백은 필수다");
        }
        StringBuilder material = new StringBuilder(feedback.kind().name());
        for (String point : feedback.points()) {
            // 🔴 구분자를 둔다. 없으면 ["ab","c"] 와 ["a","bc"] 가 같은 지문이 된다
            material.append('\u001F').append(point);
        }
        return new FailureFingerprint(hash(material.toString()));
    }

    private static String hash(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 구현에 있다 — 여기 오면 플랫폼이 깨진 것이다
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }

    /**
     * 🔴 <b>원문이 없으므로 이것이 전부다.</b> 그것이 의도다 —
     * 로그에 실려도 빌드 출력·리뷰 원문이 나가지 않는다.
     */
    @Override
    public String toString() {
        return value;
    }
}
