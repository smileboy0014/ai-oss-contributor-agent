package com.ossagent.support.testing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * 실행된 SQL 원문을 모은다. <b>「무엇을 읽었는가」를 직접 보기 위한 것</b>이다.
 *
 * <p>Hibernate {@code Statistics} 에는 <b>컬럼 단위 정보가 없다.</b> 「엔티티 로드 0 → 따라서
 * TEXT 도 안 읽었다」는 유도는 가능하지만 우회적이고, 나중에 누가 엔티티 로드를 섞으면
 * <b>실패 메시지가 원인을 말해 주지 않는다</b> — {@code testing-philosophy.md} 원칙 3.
 *
 * <p>SQL 문자열을 단언하는 것은 「구현 내부에 의존하지 않는다」에 대한 <b>의도적 예외</b>다.
 * 검사 대상이 <b>생성된 SQL 자체</b>라 다른 방법이 없다 —
 * {@code ExternalTextMarkerTest} 가 리플렉션을 같은 이유로 둔 예외를 따른다.
 *
 * <p>⚠️ Hibernate 가 no-arg 생성자로 직접 인스턴스를 만든다. 테스트가 인스턴스를 잡을 수 없으므로
 * 수집기는 <b>static</b> 이어야 한다.
 */
public class CapturingStatementInspector implements StatementInspector {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    public static void clear() {
        STATEMENTS.clear();
    }

    /** 수집된 SELECT 문. 대소문자 차이를 흡수한다. */
    public static List<String> selects() {
        return STATEMENTS.stream()
                .filter(sql -> sql.stripLeading().toLowerCase().startsWith("select"))
                .toList();
    }
}
