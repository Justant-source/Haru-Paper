package com.harupaper.server.format;

/**
 * 포맷 문서 v2의 슬롯(slot). 행(row) 안에 1~2개 들어가며, 각 슬롯이 블록을 하나 담는다.
 * slot.id: 편집기 내부에서만 쓰는 uuid
 * width: 슬롯이 행에서 차지하는 비율 ("1/1" | "1/2" | "1/3" | "2/3")
 *   - 1슬롯 행: "1/1"만 허용
 *   - 2슬롯 행: ("1/2","1/2") | ("2/3","1/3") | ("1/3","2/3")만 허용
 * block: 기존 Block(type/props/style). null은 허용하지 않음
 */
public record Slot(String id, String width, Block block) {}
