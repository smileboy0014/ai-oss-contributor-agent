package com.ossagent.agent.domain;

/**
 * 다시 보낼 가치가 있는 실패 — Docker 데몬 부재·일시 장애, 이미지가 로컬에 없음.
 *
 * <p>⚠ <b>이미지 부재를 여기로 분류하는 이유</b> — 우리는 이미지를 받아 오지 않는다(§4).
 * pull 은 데몬 자격증명이 관여하는 행위라 운영이 미리 받아 두는 쪽이 단순하다.
 * 따라서 「없다」는 우리 코드의 결함이 아니라 <b>운영 준비 상태</b>의 문제이고,
 * 준비되면 같은 요청이 성공한다.
 */
public class SandboxTransientException extends SandboxException {

    public SandboxTransientException(String message) {
        super(message);
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
