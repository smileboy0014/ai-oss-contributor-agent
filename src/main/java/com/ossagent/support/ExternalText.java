package com.ossagent.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 외부에서 들어온 텍스트를 담는 필드. <b>적재 전 시크릿 스크럽이 필요하다</b> — S-4.
 *
 * <p>대상은 우리가 쓴 것이 아닌 텍스트다 — 대상 저장소의 파일 내용, LLM 응답,
 * 빌드·테스트 출력, 예외 스택트레이스. 여기에는 토큰이 섞여 들어온다.
 * 대상 저장소가 시크릿을 커밋해 뒀을 수도 있고, 예외 메시지에 요청 URL 이
 * 통째로 담겨 나올 수도 있다.
 *
 * <p>이 애노테이션은 <b>표시만 한다.</b> 실제 스크럽 구현은 #28 이며,
 * 그 작업이 이 마커를 근거로 대상 필드를 찾는다. 마커가 없는 필드는
 * 스크럽에서 지나치게 되므로, 새 컬럼을 추가할 때 붙이는 것을 잊지 않는다.
 *
 * <p>{@code ExternalTextMarkerTest} 가 누락을 검사한다.
 *
 * @see <a href="file:../../../../../../.claude/rules/context/safety-boundaries.md">safety-boundaries.md S-4</a>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ExternalText {

    /** 무엇에서 온 텍스트인지. 스크럽 전략을 고를 때의 근거가 된다. */
    Source value();

    enum Source {
        /** 대상 저장소의 파일 내용 (CONTRIBUTING.md 등) */
        TARGET_REPOSITORY,
        /** LLM 응답 원문·요약 */
        LLM_RESPONSE,
        /** 샌드박스 빌드·테스트 출력 */
        BUILD_OUTPUT,
        /** 예외 메시지·스택트레이스 */
        EXCEPTION
    }
}
