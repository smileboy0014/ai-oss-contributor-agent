package com.ossagent.agent.adapter.out.git;

import java.io.IOException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

/**
 * 🔴 JGit 이 <b>개발자·운영 호스트의 git 설정을 읽지 않게</b> 막는다 — #18 · S-3 · S-4.
 *
 * <h2>막는 것 둘</h2>
 *
 * <table border="1">
 *   <caption>기본 동작과 위험</caption>
 *   <tr><th>기본 JGit</th><th>왜 위험한가</th></tr>
 *   <tr><td>시스템 설정 위치를 찾으려 <b>네이티브 {@code git} 을 실행</b></td>
 *       <td>프로세스를 띄운다. 「JGit 은 프로세스를 안 띄운다」가 거짓이 되고,
 *           정적 가드 둘은 jar 안을 못 봐 <b>알려주지 않는다</b></td></tr>
 *   <tr><td>{@code ~/.gitconfig} · 시스템 설정을 <b>읽는다</b></td>
 *       <td>{@code core.hooksPath} · {@code url.<base>.insteadOf} · 프록시 자격증명이
 *           우리 동작에 끼어든다. 특히 {@code insteadOf} 는 <b>clone 대상 호스트를 바꾼다</b></td></tr>
 * </table>
 *
 * <p>즉 이것은 편의 설정이 아니라 <b>격리</b>다. {@code SecretScanScriptTest} 가
 * {@code GIT_CONFIG_NOSYSTEM}·{@code GIT_CONFIG_GLOBAL} 로 같은 격리를 하는 것과 같은 성격이고,
 * 그쪽은 실제로 <b>개발자의 {@code core.hooksPath} 가 새어 들어오는 것</b>을 막고 있었다.
 *
 * <h2>⚠️ 전역 상태다</h2>
 *
 * <p>{@link SystemReader#setInstance} 는 JVM 전역이다. 그래서 <b>한 번만</b> 적용하고,
 * 적용 사실을 멱등으로 만든다. 여러 번 불려도 같은 결과여야 테스트와 운영이 같은 경로를 탄다.
 */
public final class JGitSystemConfig {

    private static volatile boolean applied;

    private JGitSystemConfig() {
    }

    /**
     * 시스템·사용자 git 설정을 <b>비어 있는 것으로</b> 고정한다. 멱등이다.
     *
     * <p>🔴 {@code openSystemConfig}·{@code openUserConfig} 가 <b>빈 설정</b>을 돌려주므로
     * JGit 이 그 위치를 찾을 이유가 없어지고, 네이티브 {@code git} 탐색 경로도 닫힌다.
     */
    public static synchronized void suppressNativeGitLookup() {
        if (applied) {
            return;
        }
        SystemReader.setInstance(new IsolatedSystemReader(SystemReader.getInstance()));
        applied = true;
    }

    /**
     * 바깥 설정을 보지 않는 {@link SystemReader}.
     *
     * <p>⚠️ 위임 대상을 감싸는 이유 — 시간·문자열 같은 나머지 동작까지 우리가 구현하면
     * JGit 내부 변경에 따라 조용히 어긋난다. <b>막아야 할 둘만</b> 덮어쓴다.
     */
    private static final class IsolatedSystemReader extends SystemReader {

        private final SystemReader delegate;

        private IsolatedSystemReader(SystemReader delegate) {
            this.delegate = delegate;
        }

        /** 🔴 시스템 설정을 읽지 않는다 — 네이티브 {@code git} 탐색이 여기서 일어난다. */
        @Override
        public FileBasedConfig openSystemConfig(Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        /** 🔴 {@code ~/.gitconfig} 를 읽지 않는다 — {@code insteadOf} 가 clone 대상을 바꾼다. */
        @Override
        public FileBasedConfig openUserConfig(Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        /**
         * 🔴 {@code ~/.config/jgit/config} 도 읽지 않는다.
         *
         * <p>JGit 고유 설정 파일이고, 여기에도 격리를 깨는 값이 들어갈 수 있다.
         * 셋 중 하나만 열어 두면 격리가 「대체로 된다」가 되는데, 그것은 격리가 아니다.
         */
        @Override
        public FileBasedConfig openJGitConfig(Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        /** 우리 저장소의 설정이라 위임하지 않고 비운다. */
        @Override
        public StoredConfig getUserConfig() {
            return loadedEmpty();
        }

        @Override
        public StoredConfig getSystemConfig() {
            return loadedEmpty();
        }

        /**
         * ⚠ 지역 변수의 <b>정적 타입</b>이 {@code EmptyConfig} 여야 한다.
         * {@code FileBasedConfig} 로 받으면 {@code load()} 의 선언 예외가 살아나
         * 호출부가 잡아야 하고, 그 catch 가 「비어 있다」를 「읽기 실패」로 뭉갠다.
         */
        private static EmptyConfig loadedEmpty() {
            EmptyConfig empty = new EmptyConfig(null, FS.DETECTED);
            empty.load();
            return empty;
        }

        @Override
        public String getenv(String variable) {
            return delegate.getenv(variable);
        }

        @Override
        public String getProperty(String key) {
            return delegate.getProperty(key);
        }

        @Override
        public String getHostname() {
            return delegate.getHostname();
        }

        @Override
        public long getCurrentTime() {
            return delegate.getCurrentTime();
        }

        @Override
        public int getTimezone(long when) {
            return delegate.getTimezone(when);
        }
    }

    /** 파일을 건드리지 않는 설정 — {@code load} 가 아무것도 읽지 않고 {@code save} 는 거부한다. */
    private static final class EmptyConfig extends FileBasedConfig {

        private EmptyConfig(Config parent, FS fs) {
            super(parent, null, fs);
        }

        @Override
        public void load() {
            clear();
        }

        @Override
        public void save() {
            // 🔴 조용히 무시하지 않는다. 여기로 오면 우리가 바깥 설정을 쓰려 한 것이고,
            //    그 시도는 격리가 깨졌다는 신호다
            throw new UnsupportedOperationException(
                    "격리된 git 설정에는 쓸 수 없다 — 바깥 설정을 건드리려는 경로가 생겼다 (S-3)");
        }

        @Override
        public boolean isOutdated() {
            return false;
        }
    }
}
