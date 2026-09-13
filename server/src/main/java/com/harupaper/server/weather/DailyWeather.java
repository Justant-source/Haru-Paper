package com.harupaper.server.weather;

import java.time.Instant;
import java.time.LocalDate;

/** docs/server/weather.md 4절 원본 그대로. */
public record DailyWeather(
        LocalDate date, Integer tempMin, Integer tempMax, Integer precipProb,
        String skyText, Instant fetchedAt, String source) {}
