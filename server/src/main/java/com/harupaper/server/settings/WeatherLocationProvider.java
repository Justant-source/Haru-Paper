package com.harupaper.server.settings;

/**
 * Weather+Settings 에이전트가 @Service로 구현한다. Rendering 도메인은 날씨 위치가
 * 필요할 때 이 인터페이스만 본다.
 */
public interface WeatherLocationProvider {
    WeatherLocation getCurrent();
}
