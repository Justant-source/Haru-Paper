# 드래그 편집기 (`app/web/src/format-editor/`)

> 담당: 서버 세션 / 포맷 스키마 v2(행/슬롯) 편집기. 원본 계획은 `.temp/04-드래그편집기-작업지시서.md`(구현 완료 후 삭제 예정).
> 표기: **[확인됨·코드]** 실제 코드에서 확인 / **[미구현]** 계획에는 있으나 코드에 없음

## 1. 데이터 모델 — schemaVersion 2

원본: `app/web/src/types/format.ts`. 서버 스키마(`docs/server/format-schema.md`)와 필드명을 그대로 맞춘다.

```
FormatDocument { schemaVersion: 2, meta, style?, rows: Row[] }
Row            { id, slots: Slot[] }              — 슬롯 1개(1/1) 또는 2개
Slot           { id, width: SlotWidth, block: Block }
SlotWidth      = '1/1' | '1/2' | '1/3' | '2/3'
Block          { type: BlockType, props, style?: BlockStyle }
BlockType      = 'text' | 'image' | 'dateHeader' | 'weather'
```

- 2슬롯 행의 폭 조합은 3가지로 고정: `['1/2','1/2']`, `['2/3','1/3']`, `['1/3','2/3']` (`TWO_SLOT_WIDTH_PAIRS`) [확인됨·코드].
- `newRow(block)` — 블록 하나를 1/1 슬롯 행으로 감싼다. `newBlock(type)` — 타입별 기본 props(`text.props.text=''`, `image.props={assetId:'', widthPercent:100}`, `dateHeader.style.align='center'`, `weather.props.fields=[...]4종`) [확인됨·코드].

## 2. 컴포넌트 경계

| 파일 | 역할 |
|---|---|
| `LayoutEditor.tsx` | `DndContext` 하나를 열어 캔버스+팔레트를 감싼다. **`handleDragEnd`가 유일한 reducer** — 팔레트 드롭(새 행 추가) / 휴지통 드롭(삭제+5초 Undo 토스트) / 행 순서 변경 / 슬롯 순서·행 간 이동(대상 행이 이미 2슬롯이면 거부)을 전부 여기서 처리한다 [확인됨·코드] |
| `LayoutCanvas.tsx` | 순수 렌더링 + **리사이즈 제스처**(dnd-kit이 아니라 raw pointer 이벤트). 행마다 `useSortable`, 슬롯마다 `useSortable`, 휴지통 존은 `useDroppable({id:'trash-zone'})` [확인됨·코드] |
| `WidgetPalette.tsx` | 블록 4종 카드. `useDraggable({id:'widget-<type>', data:{type:'widget', blockType}})` — 캔버스 드롭 판정은 `LayoutEditor`가 `active.data.current.type === 'widget'`로 구분한다 [확인됨·코드] |
| `SlotContent.tsx` | 슬롯 내용의 **클라이언트 CSS 근사 렌더링**(순수 프레젠테이션, 상태 없음). 정확한 인쇄물은 서버 미리보기(`POST /api/formats/preview`)로 별도 확인한다 [확인됨·코드, `.temp/04` 3.3절 시그니처]. `text`/`dateHeader`의 글자 크기(pt→px)는 `lib/layout-math.ts`의 `ptToPx(pt, dpi)`로 환산한다(서버 `HtmlTemplateBuilder.convertPtToPx`와 같은 공식, dpi는 `DEFAULT_PRINTER_PROFILE.dpi`) [확인됨·코드] |
| `StyleForm.tsx` | 포맷 전체 스타일(`FormatStyle`: fontFamily, baseFontSizePt, lineHeight, marginMm, blockGapMm, divider) 편집 폼 |
| `BlockStyleForm.tsx` | 블록 스타일 화이트리스트(`BlockStyle`: align, fontSizePt, bold, marginTopMm, marginBottomMm) 편집 폼 |
| `blocks/{Text,Image,DateHeader,Weather}BlockForm.tsx` | 블록별 `props` 편집 폼 |
| `Preview.tsx` | 편집 중 문서를 `POST /api/formats/preview`로 보내 정확한 미리보기 PNG를 받는다(1초 디바운스) |

## 3. dnd-kit 사용 방식

- **`DndContext`는 `LayoutEditor` 최상단 하나뿐** — 팔레트→캔버스 드롭이 되려면 같은 컨텍스트여야 하기 때문 [확인됨·코드].
- `SortableContext`(`LayoutCanvas`, `verticalListSortingStrategy`)로 행 목록을 감싼다.
- `useSortable`: 행(`RowComponent`)과 슬롯(`SlotCard`) 각각에 — 슬롯 카드는 `layout-slot-handle`(핸들 영역)에만 `listeners`를 걸어 슬롯 전체를 탭해도 드래그가 시작되지 않게 한다.
- `useDroppable({id:'trash-zone'})`: 캔버스 하단 고정 휴지통.
- `useDraggable`: 팔레트 카드(`WidgetPalette`)에서만.
- **2슬롯 리사이즈는 dnd-kit이 아니다** — `ResizeHandle`이 `pointerdown/pointermove/pointerup`을 직접 듣고, 드래그 x좌표를 3개 폭 조합의 목표 위치와 비교해 가장 가까운 조합으로 스냅한다 [확인됨·코드]. `ResizeHandle`은 `TWO_SLOT_WIDTH_PAIRS`를 `types/format.ts`에서 import하지 않고 `LayoutCanvas.tsx` 안에 값을 그대로 복제해 갖고 있다(같은 3개 조합) [확인됨·코드] — 동작에는 영향 없지만 두 상수가 따로 관리된다는 점은 알아둘 것.

## 4. 드래그 결과 반영 (`LayoutEditor.handleDragEnd`)

1. **팔레트 카드 드롭** → `newRow(newBlock(blockType))`를 대상 행 다음(또는 끝)에 삽입.
2. **휴지통 드롭** → 슬롯 제거(빈 행은 통째로 제거) + `pendingDelete`에 이전 상태 저장 + 5초 뒤 자동 소멸하는 Undo 토스트.
3. **행 드래그** → `arrayMove`로 순서만 바꿈.
4. **슬롯 드래그**:
   - 같은 행 안 → `arrayMove`로 슬롯 순서 변경.
   - 다른 행으로 → 대상 행이 이미 2슬롯이면 드롭 거부. 아니면 이동해 새 슬롯을 `1/1`로 넣고, 결과가 2슬롯이면 둘 다 `1/2`로 맞춘다.

## 5. 저장 흐름 (`FormatEditPage.tsx`)

- 신규: `{ schemaVersion: 2, meta: {name:'', author:'', description:''}, style: DEFAULT_STYLE, rows: [] }`로 시작. 기존: `formatsApi.get(id)`로 로드.
- 프린터 폭: `deviceApi.get()` → `device.printerProfile.printableWidthPx`를 `LayoutCanvas`·`SlotContent`에 `printerWidthPx`로 내려준다(기본값 1300px(`app/web/src/types/device.ts`의 `DEFAULT_PRINTER_PROFILE`, 서버 `PrinterProfile.DEFAULT`와 같은 값), 로드 실패 시 그대로 기본값 사용). 이 값은 현재 `SlotContent`에서 쓰이지 않는 예비 prop이다.
- 저장 버튼(`handleSave`) → 신규면 `formatsApi.create(document)`, 기존이면 `formatsApi.update(id, document)` — **자동 저장 없음**, 명시적 저장 버튼만.
- 슬롯 탭(`onSelectSlot`) → `{rowId, slotId}`를 상태로 저장해 속성 편집 시트(`BottomSheet`)를 연다.
- "정확히 보기" → `previewOpen` 상태로 `Preview` 컴포넌트가 든 시트를 연다.
- 편집 중 이탈 시도 → `leaveOpen` 시트로 확인.

## 6. 스타일·i18n 계약

- CSS 클래스명은 `.temp/04` 3.4절 계약 그대로 코드에 쓰였다: `layout-editor`, `layout-canvas(-wrapper)`, `layout-row(-dragging)`, `layout-slot(-dragging)`, `layout-slot-handle`, `layout-trash-zone(-active)`, `layout-resize-handle`, `widget-palette(-header|-cards|-card|-card-dragging)`, `layout-undo-toast`, `slot-content(-wrapper)`, `empty-state`.
- 문구는 `useI18n()`의 `t(key)`로만 — 하드코딩된 한국어 문자열 없음. 키 예: `layoutCanvasEmpty`, `trashZoneLabel`, `deletedToast`, `undoDelete`, `addWidgetHint`, `widgetTypeText`/`widgetTypeImage`/`widgetTypeDateHeader`/`widgetTypeWeather`.

## 7. 알려진 근사치 (설계상 의도, 결함 아님)

- `SlotContent`는 인쇄 결과의 **CSS 근사**일 뿐이다. 폭·줄바꿈·폰트 렌더링이 실제 인쇄물과 픽셀 단위로 같다고 보장하지 않는다 — 정확한 확인은 항상 "정확히 보기"(서버 PNG)로 한다.
- `dateHeader` 날짜 포맷팅은 서버(`HtmlTemplateBuilder`)의 로직을 클라이언트에 그대로 재현한 것이다(월=0 기준 보정 포함) — 서버 쪽이 바뀌면 이 파일도 같이 고쳐야 한다.

## 8. `.temp/04` 통과 조건 상태 (2026-09-17 재확인)

`.temp/04-드래그편집기-작업지시서.md` 6절의 통과 조건 10개는 파일상 전부 미체크(`[ ]`)로 남아 있다. 코드는 구현돼 있지만, 브라우저 실사(육안 드래그 조작)를 거치지 않은 항목을 **[확인됨]으로 올리면 안 된다**(CLAUDE.md 절대금지 2). 항목별로 나눠 적는다.

| 조건 | 코드 존재 | 브라우저 실사 |
|---|---|---|
| `./gradlew compileJava && ./gradlew test` | 서버 담당(이 문서 범위 밖) | — |
| schemaVersion 1 → up-convert 조회·렌더·수정·재저장 | 서버 담당(이 문서 범위 밖) | — |
| `npm run build && npm run lint` 통과, 에러 0 | [확인됨·코드] 2026-09-17 직접 실행 — 빌드 0 에러, lint 오류 0(경고 7개: `react(purity)` `Date.now()` 호출 2건, `react(set-state-in-effect)` 5건. 동작에는 영향 없음) | 해당 없음(자동화 검증) |
| 팔레트→캔버스 드래그로 새 행 추가 | `LayoutEditor.handleDragEnd` 분기 존재 [확인됨·코드] | **[미검증]** |
| 슬롯 드래그로 순서/행 변경 | 존재 [확인됨·코드] | **[미검증]** |
| 2슬롯 리사이즈 손잡이 스냅 | 존재 [확인됨·코드] | **[미검증]** |
| 휴지통 드롭 삭제 + 5초 Undo | 존재 [확인됨·코드] | **[미검증]** |
| "정확히 보기" 서버 PNG 일치 | `Preview.tsx`가 기존과 같은 `POST /api/formats/preview` 호출 [확인됨·코드] | **[미검증]** |
| 슬롯 탭(드래그 아님) → 편집 폼 | `SlotCard`의 `onClick`과 드래그 핸들 분리 존재 [확인됨·코드] | **[미검증]** |
| 저장 후 재로드 시 행/슬롯 구조 복원 | `formatsApi.get`이 `document.rows`를 그대로 되돌림 [확인됨·코드] | **[미검증]** |

- **브라우저 실사가 끝나기 전까지 이 표의 [미검증] 항목들을 [확인됨]으로 올리지 않는다.** 실사 후에는 `.temp/04`의 체크박스도 같이 갱신하고, 전부 통과하면 그 파일을 삭제한다(CLAUDE.md "이 프로젝트의 목표" 절, `.temp/`는 완료되면 삭제).
