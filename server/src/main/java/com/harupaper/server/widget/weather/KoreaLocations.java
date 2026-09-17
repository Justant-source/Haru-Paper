package com.harupaper.server.widget.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 대한민국 시·군·구 위치 목록. 기동 시 classpath의 widgets/korea-locations.json을 한 번 읽어
 * 불변 리스트로 들고 있는다(재조회 없음 — 행정구역은 자주 안 바뀐다).
 *
 * 목록은 server/scripts/build-korea-locations.py가 Nominatim으로 만든다(.temp/07 5.3절).
 * 좌표 출처: (c) OpenStreetMap contributors (ODbL).
 */
@Slf4j
@Component
public class KoreaLocations {

    private static final String RESOURCE_PATH = "widgets/korea-locations.json";

    private final List<KoreaLocation> all;

    public KoreaLocations() {
        this.all = load();
        log.info("대한민국 위치 목록 {}건 로드", all.size());
    }

    private List<KoreaLocation> load() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            var javaType = mapper.getTypeFactory().constructCollectionType(List.class, RawEntry.class);
            List<RawEntry> raw = mapper.readValue(in, javaType);
            List<KoreaLocation> loaded = raw.stream().map(RawEntry::toLocation).toList();
            return List.copyOf(loaded);
        } catch (IOException e) {
            // 이 파일은 빌드에 포함된 리소스라 배포본에서 없을 수 없다 — 있어야 할 게 없으면 기동을 막는다
            throw new IllegalStateException("failed to load " + RESOURCE_PATH, e);
        }
    }

    public List<KoreaLocation> all() {
        return all;
    }

    /** JSON 역직렬화 중간 타입. manual 필드는 없으면 false */
    private record RawEntry(String sido, String name, Double lat, Double lon, Boolean manual) {
        KoreaLocation toLocation() {
            return new KoreaLocation(sido, name == null ? "" : name, lat, lon, manual != null && manual);
        }
    }
}
