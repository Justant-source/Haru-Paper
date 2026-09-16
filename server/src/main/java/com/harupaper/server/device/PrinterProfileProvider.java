package com.harupaper.server.device;

/**
 * Device 에이전트가 @Service로 구현한다. Format/Render 도메인은 "현재 프린터 프로필"이
 * 필요할 때 이 인터페이스만 보고, poll 미수신 시 PrinterProfile.DEFAULT를 돌려받는다.
 */
public interface PrinterProfileProvider {
    PrinterProfile getCurrentProfile();

    /** 특정 사용자 소유 기기의 프로필. 여러 기기가 있는 M6 이후 렌더링(사용자별)에서 쓴다. */
    PrinterProfile getCurrentProfile(String ownerUserId);
}
