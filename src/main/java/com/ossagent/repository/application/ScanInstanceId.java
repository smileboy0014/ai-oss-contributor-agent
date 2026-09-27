package com.ossagent.repository.application;

/**
 * 이 애플리케이션 인스턴스를 가리키는 식별자 — 스캔 자리를 <b>누가</b> 잡았는지 남긴다 (#26).
 *
 * <p>🔴 <b>진단용이다. 자리 잡기 판정에 쓰지 않는다.</b> 판정에 쓰면 「내가 잡은 것만
 * 내가 놓을 수 있다」가 되어, 죽은 인스턴스의 토큰이 남은 행을 <b>아무도 놓지 못한다</b> —
 * 리스가 막으려는 영구 잠금 그 자체다. 놓아주는 근거는 언제나 <b>리스 만료</b>다.
 *
 * <p>값 타입으로 두는 이유 — {@code String} 빈을 주입받으면 다른 {@code String} 빈과
 * 충돌하고, 그 충돌은 「어느 문자열이 주입됐는지 모르겠다」는 형태로 나타난다.
 * {@code SandboxInstanceId}(#17)와 같은 수법이다.
 *
 * <p>⚠️ 둘을 하나로 합치지 않았다. 저쪽은 {@code agent} 의 컨테이너 라벨이고 이쪽은
 * {@code repository} 의 실행 행이다 — 합치면 애그리거트를 넘는 import 가 생긴다(규율 ④).
 */
public record ScanInstanceId(String value) {

    public ScanInstanceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("인스턴스 식별자는 필수다");
        }
        if (value.length() > 64) {
            // 🔴 컬럼이 VARCHAR(64) 다. 넘으면 자리 잡기가 **DB 예외로** 죽는데,
            //    증상이 「스캔이 안 된다」라 원인을 찾기까지 멀다 — 기동에서 막는다
            throw new IllegalArgumentException(
                    "인스턴스 식별자가 64자를 넘는다: " + value.length());
        }
    }
}
