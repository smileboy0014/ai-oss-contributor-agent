package com.ossagent.agent.adapter.out.sandbox;

/**
 * 이 애플리케이션 인스턴스를 가리키는 식별자 — 컨테이너 라벨에 실린다 (#17).
 *
 * <p>🔴 값 타입으로 두는 이유 — {@code String} 빈을 주입받으면 다른 {@code String} 빈과
 * 충돌하고, 그 충돌은 「어느 문자열이 주입됐는지 모르겠다」는 형태로 나타난다.
 *
 * <p>왜 필요한가 — 라벨이 {@code sandbox=true} 뿐이면 누수 컨테이너를 정리할 주체(#26)가
 * <b>「남의 것을 지워도 되는가」</b>라는 딜레마를 다시 만난다. 다중 인스턴스에서 자기 것만
 * 고를 수 있어야 한다.
 */
public record SandboxInstanceId(String value) {

    public SandboxInstanceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("인스턴스 식별자는 필수다");
        }
    }
}
