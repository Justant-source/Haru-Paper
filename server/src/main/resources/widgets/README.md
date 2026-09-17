# server/src/main/resources/widgets/

위젯이 쓰는 정적 데이터 리소스.

## korea-locations.json

`weather` 위젯의 `PropField.KOREA_LOCATION` 선택지 원본(대한민국 시·도 17개 + 시·군·구, 일반구 포함,
약 280여 개). `KoreaLocations`(기동 시 로드)와 `GET /api/widgets/locations`(`KoreaLocationController`)가
이 파일을 쓴다.

- 만드는 스크립트: `server/scripts/build-korea-locations.py` (행정구역 이름은 스크립트 안에 고정)
- **좌표 출처: © OpenStreetMap contributors (ODbL).** [Nominatim](https://nominatim.openstreetmap.org/)으로
  이름마다 1회씩 조회해서 만들었다(https://www.openstreetmap.org/copyright, Nominatim 사용정책
  https://operations.osmfoundation.org/policies/nominatim/).
- 각 항목: `{"sido": "경기도", "name": "성남시 분당구", "lat": 37.3827, "lon": 127.1189}`.
  시·도 자체를 고르는 항목은 `"name": ""`. Nominatim이 끝까지 못 찾아 수동으로 채운 좌표는
  `"manual": true`가 붙는다.
- 행정구역이 바뀌면(분구·통합·명칭 변경) 스크립트의 `DIVISIONS`/`GENERAL_DISTRICTS`를 고치고 다시 실행한다.
