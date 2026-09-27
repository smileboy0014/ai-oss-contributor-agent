package com.ossagent.repository.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DocumentFingerprintTest {

    @Test
    void 같은_내용은_같은_지문이다() {
        assertThat(DocumentFingerprint.of("기여 방법")).isEqualTo(DocumentFingerprint.of("기여 방법"));
    }

    @Test
    void 한_글자만_달라도_지문이_달라진다() {
        assertThat(DocumentFingerprint.of("기여 방법"))
                .isNotEqualTo(DocumentFingerprint.of("기여 방법."));
    }

    @Test
    void 없음은_안정적인_관측이다() {
        // 🔴 「지문을 못 구했다」(null)와 다른 것이다. 없던 파일이 생기면 absent → 해시로
        //    바뀌고, 그것이 「규약이 생겼다」는 증거다
        assertThat(DocumentFingerprint.ABSENT.isAbsent()).isTrue();
        assertThat(DocumentFingerprint.ABSENT).isNotEqualTo(DocumentFingerprint.of(""));
    }

    @Test
    void 지문_형식이_아니면_거부한다() {
        // 느슨하게 받으면 「경로=사유」 같은 문자열이 지문 자리에 들어와 「바뀌었다」가
        // 아무 때나 참이 되고, 강등이 오탐으로 돈다
        assertThatThrownBy(() -> new DocumentFingerprint("CONTRIBUTING.md=UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentFingerprint("ABCDEF"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentFingerprint(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 내용이_null_이면_지문을_만들지_않는다() {
        assertThatThrownBy(() -> DocumentFingerprint.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
