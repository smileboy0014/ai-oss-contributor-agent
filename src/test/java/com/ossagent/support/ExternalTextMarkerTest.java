package com.ossagent.support;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * 외부에서 온 텍스트 컬럼에 스크럽 마커가 빠지지 않았는지 검사한다 — S-4.
 *
 * <p><b>고정 목록으로 검사하지 않는다.</b> 「지금 있는 6개 필드에 마커가 있는가」를 보면
 * 나중에 마커 없이 추가되는 컬럼을 영영 잡지 못한다. 대신 <b>규칙</b>을 검사한다 —
 * {@code TEXT} 로 매핑된 {@code String} 필드는 예외 없이 {@link ExternalText} 를 가져야 한다.
 *
 * <p>{@code TEXT} 컬럼은 이 프로젝트에서 곧 「길어서 따로 둔 외부 텍스트」를 뜻한다.
 * 대상 저장소 파일 · LLM 응답 · 빌드 출력 · 스택트레이스가 전부 여기 들어오고,
 * 마커가 없으면 #28 의 스크럽이 그 컬럼을 지나친다.
 *
 * <p>리플렉션으로 필드를 훑는 것은 {@code testing-philosophy.md} 의 「구현 내부 필드에
 * 의존하지 않는다」에 대한 <b>의도적 예외</b>다. 검사 대상이 「필드에 붙은 표시」 자체라
 * 다른 방법이 없다.
 */
class ExternalTextMarkerTest {

    private static final String BASE_PACKAGE = "com.ossagent";

    @Test
    void TEXT_로_매핑된_필드는_모두_외부텍스트_마커를_갖는다_S4() {
        List<String> unmarked = new ArrayList<>();

        for (Class<?> entity : entityClasses()) {
            for (Field field : entity.getDeclaredFields()) {
                if (!isTextColumn(field)) {
                    continue;
                }
                if (!field.isAnnotationPresent(ExternalText.class)) {
                    unmarked.add(entity.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(unmarked)
                .as("""
                        TEXT 컬럼에 @ExternalText 가 없다 — S-4.
                        이 컬럼은 #28 의 시크릿 스크럽에서 누락된다.
                        외부 텍스트가 아니라면 TEXT 가 아닌 타입을 쓰거나, 왜 예외인지 필드 주석에 남긴다.""")
                .isEmpty();
    }

    @Test
    void 검사_대상_엔티티를_실제로_찾았다() {
        // 스캔이 0건이면 위 테스트가 조용히 통과한다. 검증하지 않은 것을 통과라고 하지 않는다
        assertThat(entityClasses())
                .as("@Entity 클래스를 찾지 못했다 — 스캔 경로가 잘못됐을 수 있다")
                .hasSizeGreaterThanOrEqualTo(7);
    }

    private static boolean isTextColumn(Field field) {
        if (field.getType() != String.class) {
            return false;
        }
        Column column = field.getAnnotation(Column.class);
        return column != null && "TEXT".equalsIgnoreCase(column.columnDefinition());
    }

    private static List<Class<?>> entityClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String name = definition.getBeanClassName();
            if (name != null) {
                classes.add(ClassUtils.resolveClassName(name, null));
            }
        }
        return classes;
    }
}
