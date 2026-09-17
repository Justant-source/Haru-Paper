#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
대한민국 시·군·구(+ 일반구) 좌표 목록을 만들어 server/src/main/resources/widgets/korea-locations.json 으로 낸다.
weather 위젯의 PropField.KOREA_LOCATION 선택지 원본이다(.temp/07-위젯그리드-작업지시서.md 5.3절).

행정구역 이름은 이 파일 안에 고정으로 둔다(아래 SIDO_ORDER·DIVISIONS·GENERAL_DISTRICTS).
2026-09-18 기준 확인한 것 — Wikipedia·나무위키·언론 보도로 교차 확인했다(요청받은 대로 "기억"이 아니라
이번에 찾아본 값이지만, 행정구역 개편은 이 스크립트 실행 시점에 다시 한 번 눈으로 확인하는 걸 권한다):
  - 대구광역시 군위군: 2023-07-01 경상북도에서 편입
  - 인천광역시: 2026-07-01 개편으로 중구+동구 → 제물포구, 서구 분구(서구/검단구), 영종구 신설
    (2군 8구 → 2군 9구). "인천시 제물포구·영종구 및 검단구 설치 등에 관한 법률"(2024년 제정)
  - 경기도 화성시: 2026-02-01 개청, 만세구·효행구·병점구·동탄구 4개 일반구 신설(인구 50만 기준 15년 만)
  - 경기도 부천시: 2024-01 원미구·소사구·오정구 3개 일반구 부활(2016년 폐지됐던 것)
  - 강원특별자치도(2023-06)·전북특별자치도(2024-01) 명칭 반영

좌표는 절대 "기억"으로 쓰지 않는다 — 전부 Nominatim(OpenStreetMap)으로 실시간 조회한다.
출처 표기: 좌표 (c) OpenStreetMap contributors (ODbL). https://www.openstreetmap.org/copyright
Nominatim 사용정책(1req/s 이하, 식별 가능한 User-Agent)을 지킨다: https://operations.osmfoundation.org/policies/nominatim/

사용법:
  python3 build-korea-locations.py                 # 처음부터(또는 캐시 이어서) 실행, 약 5~6분
  python3 build-korea-locations.py --cache PATH     # 중간 캐시 파일 위치 지정(기본 /tmp/haru-korea-locations-cache.json)
  python3 build-korea-locations.py --out PATH       # 출력 JSON 위치(기본 ../src/main/resources/widgets/korea-locations.json)

캐시 파일은 이어서 실행할 수 있게 (sido,name) -> 결과를 저장한다. 실행이 중간에 끊겨도
다시 실행하면 이미 성공한 항목은 다시 조회하지 않는다.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
# 식별 가능한 User-Agent [기본값 — Nominatim 사용정책 요구사항]
USER_AGENT = "haru-paper-location-builder/1.0 (https://github.com/Justant-source/Haru-Paper)"
# 1초에 1건 이하 요청 — 요청 사이 최소 1.1초 sleep [기본값, Nominatim 사용정책보다 여유를 둠]
MIN_INTERVAL_SECONDS = 1.1

# 대한민국 범위 [기본값]: 마라도(33.1N)~고성(38.6N), 백령도(124.6E)~독도(131.9E)를 덮는 사각형
# (PropField.java의 KOREA_LAT_MIN/MAX, KOREA_LON_MIN/MAX와 반드시 같은 값이어야 한다)
KOREA_LAT_MIN, KOREA_LAT_MAX = 33.0, 39.0
KOREA_LON_MIN, KOREA_LON_MAX = 124.0, 132.0

SIDO_ORDER = [
    "서울특별시", "부산광역시", "대구광역시", "인천광역시", "광주광역시", "대전광역시", "울산광역시",
    "세종특별자치시", "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도",
    "경상북도", "경상남도", "제주특별자치도",
]

# 일반구가 있는 시. name에는 "OO시 OO구" 형태로 둘 다(시 항목과 구 항목) 넣는다(.temp/07 5.3절)
GENERAL_DISTRICTS = {
    "수원시": ["장안구", "권선구", "팔달구", "영통구"],
    "성남시": ["수정구", "중원구", "분당구"],
    "안양시": ["만안구", "동안구"],
    "부천시": ["원미구", "소사구", "오정구"],  # 2024-01 부활
    "안산시": ["상록구", "단원구"],
    "고양시": ["덕양구", "일산동구", "일산서구"],
    "용인시": ["처인구", "기흥구", "수지구"],
    "화성시": ["만세구", "효행구", "병점구", "동탄구"],  # 2026-02-01 개청
    "청주시": ["상당구", "서원구", "흥덕구", "청원구"],
    "천안시": ["동남구", "서북구"],
    "전주시": ["완산구", "덕진구"],
    "포항시": ["남구", "북구"],
    "창원시": ["의창구", "성산구", "마산합포구", "마산회원구", "진해구"],
}

# 시·도별 기초자치단체(자치구/시/군) 이름. 일반구가 있는 시는 시 이름만 적고, 구는 GENERAL_DISTRICTS에서 붙인다
DIVISIONS = {
    "서울특별시": [
        "종로구", "중구", "용산구", "성동구", "광진구", "동대문구", "중랑구", "성북구", "강북구", "도봉구",
        "노원구", "은평구", "서대문구", "마포구", "양천구", "강서구", "구로구", "금천구", "영등포구", "동작구",
        "관악구", "서초구", "강남구", "송파구", "강동구",
    ],
    "부산광역시": [
        "중구", "서구", "동구", "영도구", "부산진구", "동래구", "남구", "북구", "해운대구", "사하구",
        "금정구", "강서구", "연제구", "수영구", "사상구", "기장군",
    ],
    "대구광역시": [
        "중구", "동구", "서구", "남구", "북구", "수성구", "달서구", "달성군", "군위군",
    ],
    # 2026-07-01 개편 반영(중구+동구 → 제물포구, 서구 분구, 영종구 신설)
    "인천광역시": [
        "미추홀구", "연수구", "남동구", "부평구", "계양구", "서구", "검단구", "제물포구", "영종구",
        "강화군", "옹진군",
    ],
    "광주광역시": ["동구", "서구", "남구", "북구", "광산구"],
    "대전광역시": ["동구", "중구", "서구", "유성구", "대덕구"],
    "울산광역시": ["중구", "남구", "동구", "북구", "울주군"],
    "세종특별자치시": [],  # 하위 기초자치단체 없음 — 시·도 자체 항목(name="")만
    "경기도": [
        "수원시", "성남시", "의정부시", "안양시", "부천시", "광명시", "평택시", "동두천시", "안산시", "고양시",
        "과천시", "구리시", "남양주시", "오산시", "시흥시", "군포시", "의왕시", "하남시", "용인시", "파주시",
        "이천시", "안성시", "김포시", "화성시", "광주시", "양주시", "포천시", "여주시",
        "가평군", "양평군", "연천군",
    ],
    "강원특별자치도": [
        "춘천시", "원주시", "강릉시", "동해시", "태백시", "속초시", "삼척시",
        "홍천군", "횡성군", "영월군", "평창군", "정선군", "철원군", "화천군", "양구군", "인제군", "고성군", "양양군",
    ],
    "충청북도": [
        "청주시", "충주시", "제천시",
        "보은군", "옥천군", "영동군", "증평군", "진천군", "괴산군", "음성군", "단양군",
    ],
    "충청남도": [
        "천안시", "공주시", "보령시", "아산시", "서산시", "논산시", "계룡시", "당진시",
        "금산군", "부여군", "서천군", "청양군", "홍성군", "예산군", "태안군",
    ],
    "전북특별자치도": [
        "전주시", "군산시", "익산시", "정읍시", "남원시", "김제시",
        "완주군", "진안군", "무주군", "장수군", "임실군", "순창군", "고창군", "부안군",
    ],
    "전라남도": [
        "목포시", "여수시", "순천시", "나주시", "광양시",
        "담양군", "곡성군", "구례군", "고흥군", "보성군", "화순군", "장흥군", "강진군", "해남군", "영암군",
        "무안군", "함평군", "영광군", "장성군", "완도군", "진도군", "신안군",
    ],
    "경상북도": [
        "포항시", "경주시", "김천시", "안동시", "구미시", "영주시", "영천시", "상주시", "문경시", "경산시",
        "의성군", "청송군", "영양군", "영덕군", "청도군", "고령군", "성주군", "칠곡군", "예천군", "봉화군",
        "울진군", "울릉군",
    ],
    "경상남도": [
        "창원시", "진주시", "통영시", "사천시", "김해시", "밀양시", "거제시", "양산시",
        "의령군", "함안군", "창녕군", "고성군", "남해군", "하동군", "산청군", "함양군", "거창군", "합천군",
    ],
    "제주특별자치도": ["제주시", "서귀포시"],  # 행정시(기초자치단체 아님)지만 위치 선택지로는 필요
}


def build_names(sido):
    """이 시·도의 name 목록(시·도 자체 ""부터, 일반구는 '시 구' 형태로 추가)"""
    names = [""]
    for base in DIVISIONS.get(sido, []):
        names.append(base)
        for district in GENERAL_DISTRICTS.get(base, []):
            names.append(f"{base} {district}")
    return names


def retry_queries(sido, name):
    """1차 질의가 실패하면 시도할 대안 질의들(문서 5.3절: name만 / OO시청·OO군청·OO구청)"""
    queries = []
    primary = f"{sido} {name}".strip() if name else sido
    queries.append(primary)
    if name:
        queries.append(name)
        last_token = name.split()[-1]
        if last_token.endswith(("시", "군", "구")):
            office = last_token + "청"
            queries.append(f"{sido} {office}")
            queries.append(office)
    seen = set()
    out = []
    for q in queries:
        if q not in seen:
            seen.add(q)
            out.append(q)
    return out


def in_korea_bounds(lat, lon):
    return KOREA_LAT_MIN <= lat <= KOREA_LAT_MAX and KOREA_LON_MIN <= lon <= KOREA_LON_MAX


class RateLimiter:
    def __init__(self, min_interval):
        self.min_interval = min_interval
        self.last_call = 0.0

    def wait(self):
        elapsed = time.monotonic() - self.last_call
        remaining = self.min_interval - elapsed
        if remaining > 0:
            time.sleep(remaining)
        self.last_call = time.monotonic()


def nominatim_search(query, limiter):
    params = {
        "q": query,
        "countrycodes": "kr",
        "format": "jsonv2",
        "limit": "1",
        "accept-language": "ko",
    }
    url = NOMINATIM_URL + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})

    body = None
    # 일시적인 타임아웃·연결 오류는 흔하다 — 같은 질의를 한 번 더 시도해 본 뒤에 포기한다
    # (retry_queries의 "다른 질의로 재시도"와는 별개의, 네트워크 계층 재시도)
    for network_attempt in range(2):
        limiter.wait()
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                body = resp.read().decode("utf-8")
            break
        except Exception as e:  # noqa: BLE001 - urllib은 TimeoutError/URLError/OSError 등 다양하게 던진다
            print(f"    [네트워크 오류, 시도 {network_attempt + 1}/2] {query!r}: {e}", file=sys.stderr)
    if body is None:
        return None
    try:
        results = json.loads(body)
    except json.JSONDecodeError:
        print(f"    [응답 파싱 실패] {query!r}", file=sys.stderr)
        return None
    if not results:
        return None
    first = results[0]
    try:
        lat = float(first["lat"])
        lon = float(first["lon"])
    except (KeyError, ValueError, TypeError):
        return None
    return lat, lon


def load_cache(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {}
    except json.JSONDecodeError:
        print(f"[경고] 캐시 파일이 손상돼 있어 무시한다: {path}", file=sys.stderr)
        return {}


def save_cache(path, cache):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=2)
    import os
    os.replace(tmp, path)


def cache_key(sido, name):
    return f"{sido}::{name}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", default="/tmp/haru-korea-locations-cache.json",
                         help="중간 결과 캐시 파일(재실행 시 이어서 함)")
    parser.add_argument("--out", default=None,
                         help="출력 JSON 경로(기본: 이 스크립트 기준 ../src/main/resources/widgets/korea-locations.json)")
    parser.add_argument("--manual", default=None,
                         help="끝까지 실패한 항목의 수동 좌표 JSON({\"sido::name\": [lat, lon]}). "
                              "형식은 --cache 파일과 같다")
    args = parser.parse_args()

    import os
    out_path = args.out
    if out_path is None:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        out_path = os.path.join(script_dir, "..", "src", "main", "resources", "widgets", "korea-locations.json")
    out_path = os.path.abspath(out_path)

    manual = {}
    if args.manual:
        with open(args.manual, "r", encoding="utf-8") as f:
            manual = json.load(f)

    cache = load_cache(args.cache)
    limiter = RateLimiter(MIN_INTERVAL_SECONDS)

    total = sum(len(build_names(sido)) for sido in SIDO_ORDER)
    done = 0
    failures = []
    results = []  # (sido, name, lat, lon, manual_flag)

    for sido in SIDO_ORDER:
        for name in build_names(sido):
            done += 1
            key = cache_key(sido, name)
            label = f"{sido} {name}".strip() if name else sido
            if key in cache and cache[key].get("lat") is not None:
                entry = cache[key]
                results.append((sido, name, entry["lat"], entry["lon"], entry.get("manual", False)))
                print(f"[{done}/{total}] (캐시) {label} -> {entry['lat']}, {entry['lon']}")
                continue

            found = None
            try:
                for query in retry_queries(sido, name):
                    coords = nominatim_search(query, limiter)
                    if coords is None:
                        continue
                    lat, lon = coords
                    if in_korea_bounds(lat, lon):
                        found = (lat, lon)
                        break
                    print(f"    [범위 밖] {query!r} -> {lat},{lon} — 다음 질의 시도", file=sys.stderr)
            except Exception as e:  # noqa: BLE001 - 항목 하나의 예상 밖 오류로 전체 실행을 죽이지 않는다
                print(f"    [예상 밖 오류] {label}: {e}", file=sys.stderr)

            if found is None:
                if key in manual:
                    lat, lon = manual[key]
                    cache[key] = {"lat": round(lat, 4), "lon": round(lon, 4), "manual": True}
                    results.append((sido, name, cache[key]["lat"], cache[key]["lon"], True))
                    print(f"[{done}/{total}] (수동) {label} -> {lat}, {lon}")
                else:
                    failures.append((sido, name))
                    print(f"[{done}/{total}] [실패] {label}", file=sys.stderr)
            else:
                lat, lon = found
                lat_r, lon_r = round(lat, 4), round(lon, 4)
                cache[key] = {"lat": lat_r, "lon": lon_r, "manual": False}
                results.append((sido, name, lat_r, lon_r, False))
                print(f"[{done}/{total}] {label} -> {lat_r}, {lon_r}")

            if done % 10 == 0:
                save_cache(args.cache, cache)

    save_cache(args.cache, cache)

    if failures:
        print("\n=== 끝까지 실패한 항목 (수동 보완 필요) ===", file=sys.stderr)
        for sido, name in failures:
            print(f"  {cache_key(sido, name)}", file=sys.stderr)
        print(f"{len(failures)}건 실패 — --manual 파일로 좌표를 채운 뒤 다시 실행하면 이어서 처리된다.",
              file=sys.stderr)

    # 시·도 순서 → name 가나다순(파이썬 기본 문자열 정렬이 현대 한글 음절 순서와 일치한다)
    results.sort(key=lambda r: (SIDO_ORDER.index(r[0]), r[1]))

    out = []
    for sido, name, lat, lon, is_manual in results:
        item = {"sido": sido, "name": name, "lat": lat, "lon": lon}
        if is_manual:
            item["manual"] = True
        out.append(item)

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"\n{len(out)}개 항목을 {out_path} 에 썼다 (실패 {len(failures)}건).")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
