package com.ossagent.repository.domain;

import java.util.Locale;

/**
 * 이슈 텍스트에서 뽑은 신호 하나 — 관련 파일을 좁히는 데 쓰는 단위 (이슈 #15 FR-1).
 *
 * <p>🔴 <b>종류가 곧 신뢰도다.</b> 「{@code src/main/java/org/x/Foo.java} 를 고쳐야 한다」와
 * 「{@code listener} 가 안 된다」는 같은 무게일 수 없다. 앞의 것은 사람이 경로를 짚어 준 것이고
 * 뒤의 것은 낱말 하나다. 한 덩어리로 세면 흔한 낱말이 정확한 경로를 이긴다.
 *
 * @param value 정규화된 값. 종류마다 정규화 규칙이 다르다 — {@link Kind} 참조
 * @param kind  신호의 종류
 */
public record ContextKeyword(String value, Kind kind) {

    /**
     * 신호의 종류와 가중치.
     *
     * <p>⚠️ 가중치의 <b>절대값에는 의미가 없다.</b> 중요한 것은 순서와 간격이다 —
     * 경로 리터럴 하나가 낱말 여러 개를 이겨야 한다. 실측 0건에서 정한 값이라
     * 순위가 뒤집히는 사례가 나오면 여기서 조정한다.
     */
    public enum Kind {

        /**
         * 파일 경로 또는 파일 이름 — {@code src/main/java/org/x/Foo.java} · {@code Foo.java}.
         * 사람이 직접 짚어 준 것이라 가장 강하다.
         */
        PATH_LITERAL(100),

        /** 타입 이름 — {@code KafkaMessageListenerContainer}. 파일 이름과 직접 맞물린다 */
        TYPE_NAME(60),

        /** 패키지 — {@code org.springframework.kafka.listener}. 디렉터리와 맞물린다 */
        PACKAGE(25),

        /** 보통 낱말. 가장 약하다 — 이것만으로는 후보가 되지 못하게 둔다 */
        TERM(8);

        private final int weight;

        Kind(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    public ContextKeyword {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("키워드 값이 비어 있습니다");
        }
        if (kind == null) {
            throw new IllegalArgumentException("키워드 종류가 없습니다");
        }
        value = value.trim();
    }

    /**
     * 비교용 소문자 형태.
     *
     * <p>경로 매칭은 대소문자를 가리지 않는다 — 이슈 본문의 {@code foo.java} 와 저장소의
     * {@code Foo.java} 가 같은 것을 가리키는 일이 흔하다.
     */
    public String normalized() {
        return value.toLowerCase(Locale.ROOT);
    }

    public int weight() {
        return kind.weight();
    }

    /**
     * 🔴 <b>값을 찍지 않는다.</b> 대상 저장소 사람이 쓴 텍스트에서 나온 조각이고,
     * 임의 텍스트를 로그에 넣는 것 자체가 인젝션 경로다 — {@code logging.md}.
     */
    @Override
    public String toString() {
        return "ContextKeyword[kind=%s, length=%d]".formatted(kind, value.length());
    }
}
