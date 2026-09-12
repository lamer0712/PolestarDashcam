- 0.4.50: Saved 전체화면 영상 재생에 Media3 탐색바와 재생/일시정지 컨트롤을 표시합니다.
- 0.4.49: Saved 화면에서 선택한 파일을 삭제할 수 있고, 다운로드 완료 후 저장 목록 갱신 실패가 앱 종료로 이어지지 않도록 보호합니다.
- 0.4.48: OEM 갤러리와 동일하게 썸네일 요청에 파일 `dateTime`을 `time` 파라미터로 전달해 누락 썸네일을 줄입니다.
- 0.4.47: 썸네일 요청의 모든 개별 오류를 무시해 50개 파일 목록을 끝까지 표시하고 오류 팝업을 띄우지 않습니다.
- 0.4.46: DVR 썸네일 생성 오류(code 11)는 해당 파일만 썸네일 없이 건너뛰고 목록 조회를 계속합니다.
- 0.4.45: 재생 상세 화면에서 실제로 5초마다 DVR heartbeat를 보내고, 화면을 닫으면 중지합니다.
- 0.4.44: 한 파일의 DVR 다운로드·공용 폴더 저장·지정 폴더 복사를 하나의 연속 진행률로 표시합니다.
- 0.4.43: Export 작업이 종료된 뒤 선택 상태를 해제하도록 시점을 조정했습니다.
- 0.4.42: 썸네일 길게 누르기로 선택 모드에 진입하고, Export 완료 시 전체 선택을 해제합니다.
- 0.4.41: 인라인 영상 재생 상세 화면에서도 OEM 방식의 5초 DVR heartbeat를 유지합니다.
- 0.4.40: 원 갤러리처럼 파일 목록 모드를 5초마다 heartbeat로 유지해 대용량 재생·다운로드 중 DVR 세션 만료 403을 방지합니다.
- 0.4.39: 미디어 URL이 403이면 `app=gallery` 쿼리 컨텍스트를 붙인 동일 URL로 한 번 재시도합니다.
- 0.4.38: 미디어 URL HTTP 403 시 파일 목록 모드 재진입 후 다운로드 재시도.
# 검증 기록

검증일: 2026-09-12. 프로젝트: `/Users/home/PolestarDashcamExporter`.

0.4.11 보정: 세부 목록 썸네일을 원 갤러리 크기에 맞춰 확대하고 앨범 카드를 고정 폭으로 왼쪽 정렬했습니다.

0.4.12 보정: 영상 타일을 앨범 카드와 같은 309dp로 확대했습니다. DVR 읽기 제한 시간을 60초로 늘리고, Range 403 시 원 갤러리 방식의 단일 스트림으로 재시도한 뒤 단일 스트림이 중단되면 open-ended Range 이어받기로 전환합니다. 관련 fallback 단위 테스트를 추가했습니다.

0.4.13 보정: AAOS에서 지원하지 않는 DVR PCM 오디오 때문에 미리보기가 검정 화면으로 남지 않도록 인라인 미리보기의 오디오 렌더링을 끕니다. 다운로드 파일에는 원본 오디오를 그대로 유지합니다.

0.4.14 보정: Saved 영상 클릭 시 전체 화면 플레이어를 열고 오디오 트랙을 활성화했습니다. 세부 화면 편집 진입은 `선택` 글씨 버튼, 편집 종료는 `×` 버튼으로 바꿨습니다.

0.4.15 보정: 앱 상단 갤러리 아이콘과 Albums 아이콘을 유니코드 글리프 대신 직접 그린 아이콘으로 교체해 AAOS 글꼴에 따른 모양 차이를 없앴습니다.

0.4.10 보정: 썸네일 인라인 재생을 Android 기본 VideoView에서 AndroidX Media3 ExoPlayer로 변경했습니다. 재생 준비 중/실패 상태를 타일에 표시해 검정 화면만 남지 않게 했고, mock DVR은 실제 MP4 및 HTTP Range 응답을 지원합니다.

0.4.9 보정: 원 갤러리 APK에서 확인한 대용량 읽기 방식에 맞춰 DVR 다운로드 Range를 `bytes=offset-` open-ended 형태로만 보내도록 변경했습니다. 앱 내부에서는 32 MiB 단위로 읽고 재연결해 60 MiB 이상 파일의 스트림 끊김과 bounded Range 403을 피합니다.

0.4.8 보정: 일반 상태/오류 메시지와 DVR 녹화 복귀 경고는 화면을 차지하지 않는 Toast로 표시하도록 변경했습니다.  헤더 구분선 아래 진행률 표시를 제거하고 전체 화면 하단 한 줄 바에서 진행 상태/취소 또는 선택 수/Export 버튼을 보여주도록 변경했습니다. 선택 수 텍스트는 밝은 색으로 고정했습니다.  런처가 Activity label을 직접 읽는 경우까지 맞도록 MainActivity label도 Gallery+로 명시했습니다. 앱 표시 이름과 알림 제목을 영어 Gallery+로 바꾸고, 홈 상단 제목을 Gallery+로 변경했습니다. 구분선 아래 일반 메시지 박스를 제거했습니다.  편집 모드 진입 시 전체 선택 버튼 때문에 영상 그리드가 아래로 움직이지 않도록 상단 행 높이를 고정했습니다. Saved 화면은 설정에서 지정한 SAF 폴더의 실제 문서 목록을 읽어 표시하도록 변경했습니다. 선택 상태는 주황색 테두리만 남기고 우상단 체크 배지를 제거했습니다. 편집 모드에서는 체크박스 중복 토글을 없애고 타일 클릭만으로 다중 선택이 안정적으로 동작하게 했습니다. 영상 타일을 일반 모드에서 누르면 썸네일 자리에서 바로 재생합니다. 편집 모드에서는 같은 타일 클릭이 선택으로 동작해 Export 흐름을 유지합니다. 앱 이름은 Gallery+로 변경하고 홈 화면 보조 문구와 연결 상태 캡슐을 제거했습니다. 폴더 설정은 톱니 아이콘으로 바꾸고, 저장 폴더가 없으면 최초 실행 시 폴더 선택기를 띄웁니다. 선택된 영상·사진은 주황색 테두리와 체크 배지로 더 명확히 표시합니다. 앱 시작 시 자동 연결하며, 선택한 영상·사진은 Android 갤러리 공용 폴더와 사용자가 고른 SAF 폴더에 자동 복사됩니다. 저장된 영상은 설치된 동영상 앱으로 재생할 수 있습니다. 32 MiB Range 이어받기, bounded Range 403 대체, DVR 세션 쿠키 유지도 포함합니다.

## 완료

| 검증 | 결과 |
| --- | --- |
| `:app:testDebugUnitTest` | 20 tests, 0 failures, 0 errors |
| `:app:lintDebug` | 통과. 신규 의존성 버전 안내 및 여유 공간 API 권고 경고만 남음 |
| `:app:assembleDebug` | 성공, versionName 0.4.10 / versionCode 19 |
| APK 서명 검사 | `apksigner verify --print-certs` 성공, Android Debug 서명 |
| AAOS API 35 에뮬레이터 설치/실행 | 성공, 운전자 user 10, 1920×1200 |
| 계측 테스트 | 8 tests 모두 통과 |
| 앱 종료 후 재실행 | 저장된 영상/사진 2개 표시 확인 |
| 시스템 폴더 선택기 | Download 하위 `DashcamExporter-Test` 생성 및 접근 허용 |
| 실제 SAF 폴더 복사 | `normal_000.mp4` 2 MiB 복사, 앱 내부 파일 크기 재조회 검증 후 `폴더 복사 1/1개 완료` 표시 |
| 실제 Android 공유 시트 실행 | 성공. 에뮬레이터에 영상 공유 대상 앱이 없어 `No apps can perform this action` 표시 |
| iCloud Drive APK 복사 | 원본/프로젝트 artifacts/iCloud 복사본 SHA-256 일치 |
| 사용자 지정 폴더 설정 | SAF 폴더 URI를 영속 권한으로 저장하고 다음 다운로드부터 자동 복사 |
| 갤러리+ UI 스크린샷 | AAOS API 35 에뮬레이터에서 앨범 홈과 Loop videos 세부 목록 캡처 |
| 인라인 재생 ExoPlayer | AndroidX Media3 ExoPlayer로 DVR URL을 썸네일 영역에서 재생, 실제 MP4 mock DVR에서 프레임 표시 확인 |
| 편집 모드 다중 선택 | 체크박스 중복 토글 제거, 타일 클릭 기반 다중 선택 흐름 계측 테스트 통과 |
| 편집 모드 위치 안정화 | 전체 선택 버튼 영역 높이를 고정해 일반/편집 모드 전환 시 그리드 시작 위치 유지 |
| Saved 폴더 동기화 | 지정 SAF 폴더가 있으면 내부 저장소 대신 해당 폴더의 문서 목록을 표시 |
| 앱 이름과 메시지 영역 | APK application label/Activity label/알림 제목 Gallery+ 확인, 홈 제목 Gallery+ 적용, 일반 메시지 박스 제거 |
| 진행률 위치 | 헤더 아래 progress 영역 제거, busy 상태 진행률과 취소 버튼을 하단 한 줄 바에 표시 |
| 메시지 표시 방식 | 일반 상태/오류 메시지와 DVR 녹화 복귀 경고를 헤더/하단 고정 UI가 아닌 Toast로 표시 |
| Export 바 가독성 | 설명 줄 제거, 선택 수 텍스트를 밝은 색으로 고정 |

단위 테스트는 OEM 스키마·누락/오류 응답·한글/공백/특수문자 URL·경로 순회 차단·정확한 페이지 query·64비트 크기·분류 불일치·바이트 보존·동명 파일 분리·완료 파일 재사용·HTTP 오류/redirect·HTML 오류 문서·크기 불일치·취소 임시 파일 정리·빈/잘린 스트림·chunked 다운로드·모드 요청과 readback을 검사합니다.

계측 테스트는 화면에서 연결/선택/다운로드, 3개 분류 조회, 페이지 끝까지 조회, 분류별 실패 후 재시도, 취소 후 녹화 복귀, 복귀 실패의 영속 기록과 재시도, 기존 OEM 목록 세션 유지, 화면 재생성, 단일/다중 공유 MIME·URI·읽기 권한 및 FileProvider 경로 제한을 검사합니다.

모의 서버와 앱 저장본의 2 MiB 바이트 SHA-256:

```text
91d3beb88a9b2f778a6c44a1c53b63d3c79931845a9aef84b3fb414610bd1938
```

SAF 복사본은 앱에서 스트림 복사와 크기 재조회를 확인했습니다. AAOS 사용자 저장소 권한 때문에 ADB shell로 복사본을 읽어 별도 해시를 비교하는 검증은 완료하지 못했습니다. 물리 USB에 대한 검증이 아닌 에뮬레이터 로컬 폴더 저장 검증입니다.

## 제공 APK

파일명: `GalleryPlus-v0.4.10-20260912-2003.apk`

- 크기: 27,971,142 bytes (약 26.7 MiB)
- 프로젝트 사본: `/Users/home/PolestarDashcamExporter/artifacts/GalleryPlus-v0.4.10-20260912-2003.apk`
- iCloud Drive 사본: `/Users/home/Library/Mobile Documents/com~apple~CloudDocs/GalleryPlus-v0.4.10-20260912-2003.apk`
- 최신 사본: `/Users/home/Library/Mobile Documents/com~apple~CloudDocs/GalleryPlus-latest.apk`
- 호환 최신 사본: `/Users/home/Library/Mobile Documents/com~apple~CloudDocs/PolestarDashcamExporter-latest.apk`
- SHA-256: `ea8853441651d8f3c52d3ac4f20c4671d3a466bbee2aa12bf4577fbf3f8e058b`

로컬 iCloud Drive 폴더에 기록하고 해시를 확인했습니다. 다른 기기까지 iCloud 동기화가 완료되었는지는 확인하지 않았습니다. APK에는 모의 파일이나 테스트 주소 설정이 포함되지 않으며 새 설치 기본 주소는 `http://198.18.37.20`입니다. 앱 표시 이름과 다운로드 알림 제목은 `Gallery+`입니다.

## 실차 확인 필요

1. 일반 앱 UID로 `198.18.37.20`에 접근할 수 있는지, OEM 내부 네트워크 라우팅/권한 제약 여부.
2. 실제 JSON 및 mediaPath/name 인코딩, 시간/크기 단위, 재생 가능한 실차 파일의 다운로드.
3. 목록 모드가 필요한지와 작업 종료 후 실제 DVR 녹화가 재개되는지.
4. 차량 폴더 선택기에서 USB 노출과 쓰기 허용, 큰 파일 및 USB 분리 오류.
5. 차량에 설치된 메일/공유 앱에서 첨부를 읽고 전송할 수 있는지 및 용량 제한.

## 화면 증거

- [시작 화면](screenshots/01-start.png)
- [SAF 폴더 복사 완료](screenshots/02-folder-export.png)
- [공유 시트: 대상 앱 없음](screenshots/03-share-sheet.png)
- [앨범 중심 화면과 자동 연결](screenshots/05-custom-folder-gallery.png)
- [갤러리+ 앨범 홈](screenshots/06-gallery-plus-home.png)
- [갤러리+ Loop videos 세부 목록](screenshots/07-gallery-plus-detail.png)
- [하단 한 줄 Export 바](screenshots/15-bottom-export-bar-one-line.png)
- [ExoPlayer 인라인 재생](screenshots/23-exoplayer-playing-user10.png)

빌드 및 테스트 로그 사본은 `artifacts/`에 보관합니다.

## 2026-09-12 v0.4.9 large-file DVR range validation

- OEM Gallery APK analysis: `com.danikula.videocache.HttpUrlSource.openConnection()` sends `Range: bytes=<offset>-` for resumed video reads; it does not use bounded `bytes=start-end` requests.
- `DvrHttpHelper` lists and status endpoints use Retrofit, while direct `downloadFile()` is a streamed body and playback uses the video cache/proxy path.
- Updated Gallery+ downloads to send only open-ended Range requests for known-size DVR media while locally limiting each read to 32 MiB before reconnecting.
- Unit regression `usesOemStyleOpenEndedRangesForLargeDownloads` verifies a >32 MiB file succeeds when bounded ranges would receive HTTP 403, and `retriesA60MbPlusFileAfterUnexpectedEndOfStream` verifies all retry ranges remain open-ended.


## 2026-09-12 v0.4.10 inline playback validation

- OEM Gallery uses a dedicated player stack (`ecarx.gallery.videoview`, Doikki/Ijk classes) rather than Android platform `VideoView`.
- Replaced inline tile playback with AndroidX Media3 ExoPlayer and added loading/error overlays.
- Mock DVR now supports `--video-mp4` and HTTP `Range` responses so emulator playback can be validated with a real MP4 instead of synthetic transport bytes.
- Emulator user 10 playback check succeeded with `tools/mock_dvr.py --port 8765 --video-mp4 /tmp/galleryplus-playable-test.m4v`; screenshot: `screenshots/23-exoplayer-playing-user10.png`.

## 2026-09-13 v0.4.16 OEM icon validation

- Header Gallery and Albums navigation now use vector resources extracted from the original OEM Gallery APK.
- Saved uses a Chrome-style download arrow and tray icon.

## 2026-09-13 v0.4.17 thumbnail layout validation

- Video tiles use a 248dp width, approximately 80% of the previous 309dp width, with a 16:9 aspect ratio instead of a square frame.

## 2026-09-13 v0.4.18 time-label validation

- Video start and end times are rendered on one line in the detail list.

## 2026-09-13 v0.4.19 date-group validation

- DVR video tiles are grouped by `yyyy-MM-dd` with a full-width date header for each recording date.

## 2026-09-13 v0.4.20 inline playback request validation

- Inline Media3 playback sends `Range: bytes=0-` with identity encoding, matching the working DVR download/player request path.

## 2026-09-13 v0.4.21 DVR compatibility validation

- Inline playback now begins with the OEM direct GET behavior; resumed reads use Range only after a seek or interrupted stream.
- Large-file download retries a direct-stream HTTP 403 with an open-ended Range request from the current byte offset.

## 2026-09-13 v0.4.22 large-file retry validation

- Partial files are preserved during resumed Range errors, and resumed requests no longer force `Connection: close`.

## 2026-09-13 v0.4.23 saved-thumbnail validation

- Saved video tiles extract a frame with `MediaMetadataRetriever`; saved photos use the local image bytes for their thumbnail.

## 2026-09-13 v0.4.24 photo viewer validation

- Saved photos open in an in-app full-screen viewer using the SAF/file URI, including vehicles without an external image viewer activity.

## 2026-09-13 v0.4.25 file metadata layout validation

- DVR video filenames and byte sizes share one row with opposing alignment.

## 2026-09-13 v0.4.26 large-file completion validation

- HTTP 200 responses shorter than the expected file-list size are resumed instead of being marked complete.

## 2026-09-13 v0.4.27 error-dialog validation

- Transfer failures remain in a modal error dialog so the HTTP status and DVR response can be photographed for diagnosis.

## 2026-09-13 v0.4.28 service-error validation

- Foreground-service interruptions persist an error message and show it when the app screen is reopened.

## 2026-09-13 v0.4.29 thumbnail-card validation

- Video thumbnails fill their grid card width and use an automatically calculated 16:9 height.

## 2026-09-13 v0.4.30 OEM streaming analysis

- OEM playback uses `HttpProxyCacheServer`/`HttpUrlSource`: direct GET at offset zero, then `Range: bytes=offset-` for cache misses and resumed reads.

## 2026-09-13 v0.4.31 playback-cache validation

- Inline playback uses a persistent 512 MiB Media3 `SimpleCache` with an HTTP upstream, matching the OEM local proxy-cache architecture.

## 2026-09-13 v0.4.32 large-file chunk validation

- Files above 60 MiB start with a direct stream and switch to 32 MiB local reads plus open-ended Range resume after an early EOF.

## 2026-09-13 v0.4.33 playback DVR-mode validation

- Inline playback enters `in-file-list` before opening the DVR URL and restores `normal` when leaving the detail view.

## 2026-09-13 v0.4.34 playback-error diagnostics

- Media3 HTTP playback errors display the concrete response code and response detail on the video tile.

## 2026-09-13 v0.4.35 playback-range validation

- Playback cache upstream requests now unset DataSpec length so every DVR resume uses `bytes=offset-` rather than bounded ranges.

## 2026-09-13 v0.4.36 vehicle-grid validation

- Adaptive video grid minimum width is 370dp, targeting five cards per row on the vehicle display.

## 2026-09-13 v0.4.37 batch-error validation

- Per-file download errors now reach the persistent dialog with the HTTP/DVR details; successful files remain saved.
