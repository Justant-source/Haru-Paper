package com.harupaper.server.device;

/**
 * Device 에이전트가 @Service로 구현한다. Format/Render 도메인은 "현재 프린터 프로필"이
 * 필요할 때 이 인터페이스만 보고, poll 미수신 시 PrinterProfile.DEFAULT를 돌려받는다.
 */
public interface PrinterProfileProvider {

    /**
     * 특정 사용자 소유 기기의 프로필. 여러 기기가 있는 M6 이후에는 이것이 유일한 조회 방법이다
     * (인자 없는 getCurrentProfile()은 "아무 기기나 하나" 폴백이라 멀티유저에서 틀린 답을 줄 수 있어
     * 삭제됐다 — 2026-09-17). ownerUserId가 null이거나 그 사용자의 기기가 없으면 DEFAULT를 돌려받는다.
     */
    PrinterProfile getCurrentProfile(String ownerUserId);
}
