# Changelog

- 0.4.92: Tailcat 웹 수신을 4 MiB 단위로 브라우저의 영구 저장소에 체크포인트하고, 화면 꺼짐이나 앱 전환으로 연결이 끊기면 같은 QR 페이지에서 저장된 바이트부터 자동 재개합니다. 차량 앱은 휴대폰의 완료 확인을 받은 경우에만 전송 완료로 처리합니다.
- 0.4.91: Saved 화면의 Share 기능을 Tailcat 기반 직접 전송 흐름으로 정리했습니다. QR 팝업은 실제 전송용 주소가 준비된 뒤에만 표시하고, 전송 완료 시 자동으로 닫힙니다. GitHub Pages receiver는 주소를 노출하지 않는 단순 수신 화면으로 바꿨습니다.
- 0.4.90: Tailcat 버튼에서 Android Go bridge panic으로 앱이 종료되지 않도록 보호하고, QR을 항상 GitHub Pages receiver로 열도록 바꿨습니다. 차량 control 주소가 준비되지 않으면 폰 페이지의 `tc...` 주소를 차량에 수동 입력하는 fallback을 사용합니다.
- 0.4.89: Tailcat receiver 페이지를 GitHub Pages 정적 사이트(`docs/tailcat`)로 분리했습니다. Saved 전송 QR은 차량 control `tc...` 주소가 있으면 `https://lamer0712.github.io/PolestarDashcam/tailcat/`를 사용해 폰이 차량 로컬 HTTP 서버를 열지 않아도 주소 교환을 시작할 수 있습니다.
- 0.4.88: Tailcat 전송을 차량 control listener 방식으로 확장했습니다. Saved에서 선택 파일을 누르면 차량 앱이 먼저 `tc...` control 주소를 만들고 QR에 포함하며, 폰 브라우저는 자기 수신 주소를 Tailcat으로 차량에 되돌려 보낸 뒤 선택 파일을 받습니다. 로컬 `/tailcat-register`는 fallback으로 유지합니다.
- 0.4.87: Saved 화면에서 선택한 파일을 Tailcat으로 전송하는 버튼을 USB 복사 버튼 옆에 추가했습니다. 선택 파일 1개는 그대로 보내고, 여러 개를 선택하면 ZIP 하나로 묶어 iPhone Tailcat 수신 페이지로 자동 전송합니다.
- 0.4.86: Tailcat WASM 전송 PoC를 추가했습니다. Share 화면에서 iPhone Tailcat Receive 페이지 QR을 열고, iPhone에 표시된 `tc...` listener 주소를 입력하면 최신 Saved 파일 1개를 Tailcat으로 전송합니다. 이 경로는 Tailscale 계정이 필요 없지만 브라우저 DERP relay 경로라 대용량 영상은 느릴 수 있습니다.
- 0.4.85: 휴대폰 웹 DVR 탭이 `Loading...`에 머무르지 않도록 앱이 이미 가진 DVR 목록을 우선 반환하고, 캐시가 없을 때는 첫 페이지만 빠르게 조회합니다. 웹 DVR 요청은 15초 후 안내 문구로 전환합니다.
- 0.4.84: MP4/MOV `moov` fast-start 재배치를 롤백했습니다. 휴대폰 웹 공유에서 DVR과 Saved 영상 재생은 막고, 썸네일·사진 보기·파일 다운로드만 제공합니다.
- 0.4.83: 휴대폰 웹 공유 화면에서 DVR 탭은 유지하되 DVR 브라우저 재생은 막고, DVR 썸네일과 다운로드만 제공합니다. 새로 완료된 MP4/MOV 다운로드는 브라우저 재생 시작과 탐색이 빨라지도록 `moov` 메타데이터를 파일 앞쪽으로 옮깁니다.
- 0.4.82: 휴대폰 웹 페이지에서 Saved 첫 화면은 즉시 띄우고, DVR 목록은 DVR 탭을 열 때 `/dvr-list`로 지연 로드하도록 복구했습니다.
- 0.4.81: Tailscale 공유 첫 페이지가 DVR 목록 조회 때문에 지연되지 않도록 휴대폰 웹 페이지의 자동 DVR 조회를 끄고 Saved 파일을 즉시 표시합니다. Saved 영상 스트리밍은 파일 채널 seek와 1 MiB 버퍼를 사용합니다.
- 0.4.80: Share 서버 주소 선택에서 Tailscale `100.64.0.0/10` 주소를 허용하고 우선합니다. 차량 핫스팟은 폰에서 차량 앱으로 들어오는 연결이 막힐 수 있어, Share 화면 안내를 Tailscale 또는 같은 네트워크 기준으로 바꿨습니다.
- 0.4.78: 휴대폰 웹 UI를 Gallery+ 카드형 다크 테마로 개편하고 Saved/DVR 썸네일, 브라우저 내 영상 재생, 직접 다운로드를 추가했습니다. 웹 페이지 요청 때 DVR 목록을 앱 목록과 별도로 전체 조회합니다.
- 0.4.77: 휴대폰 파일 서버 포트를 `8787`로 고정해 QR 주소를 예측 가능한 형태로 유지합니다. 포트가 사용 중이면 서버 시작 오류를 표시합니다.
- 0.4.76: QR 파일 서버에 Gallery+가 현재 로드한 DVR 목록과 DVR 원본 스트림 중계 다운로드를 추가했습니다. Activity가 중지되어 heartbeat가 꺼지면 중계 서버도 함께 종료되며, 서버 accept 스레드는 단일 daemon으로 제한합니다.
- 0.4.75: Saved 화면에서 QR 코드로 읽기 전용 파일 서버를 열어 휴대폰 브라우저에서 저장 영상을 선택 다운로드할 수 있습니다. 핫스팟 로컬 주소만 사용하며 QR URL에는 토큰을 넣지 않습니다.
- 0.4.74: DVR이 알려준 폴더별 전체 파일 수에 도달하면 하단 자동 페이지 요청을 중지합니다. 마지막 페이지의 `hasMore`도 전체 개수 기준으로 계산합니다.
- 0.4.73: Compose가 목록 끝에서 타일을 재구성해도 같은 파일의 썸네일을 한 번만 요청하도록 시도 캐시를 추가했습니다. 목록 새로고침 때 캐시를 비웁니다.
- 0.4.71: 파일 크기를 알고 있으면 처음부터 전체 파일을 가리키는 open-ended Range(`bytes=0-`)를 한 번 요청합니다. DVR이 응답을 중간에 끊을 때만 받은 위치부터 다시 요청합니다.
- 0.4.70: 대용량 다운로드의 4개 병렬 bounded Range 요청을 제거하고, OEM처럼 한 스트림을 사용한 뒤 끊긴 위치에서 open-ended Range로 순차 재개합니다.
- 0.4.72: 전체화면 Dialog의 일시적인 window focus 상실로 heartbeat가 끊기지 않도록 실제 Activity 중지 시에만 앱 heartbeat를 중지합니다. 전체화면이 열린 동안 새 썸네일 요청도 시작하지 않습니다.
- 0.4.69: 화면 전환 때 heartbeat가 normal/file-list를 반복 전환하지 않도록 앱 생명주기에서 하나의 heartbeat만 유지합니다. 실패한 썸네일은 실패 캐시로 기록해 같은 파일을 반복 요청하지 않습니다.
- 0.4.68: 앱 화면이 사용 중인 동안 DVR 파일 목록 모드 heartbeat를 유지하고, 썸네일 요청을 최대 20개씩 처리합니다.
- 0.4.67: OEM 갤러리처럼 카드에서는 정지 썸네일만 표시하고, 카드를 누르면 전체화면 플레이어에서 재생합니다. 인라인 카드 재생 상태를 제거했습니다.
- 0.4.66: 하이브리드 썸네일 경로를 정리해 DVR 썸네일 우선, 실패한 영상만 스트림 프레임 추출, 두 경로 실패 시 조용히 건너뜁니다.
- 0.4.65: DVR 썸네일을 누르면 카드 내부가 아닌 전체화면 플레이어로 재생합니다.
- 0.4.64: 다운로드 코드에서 Android 공용 Movies/Polestar Dashcam 복사 호출을 제거해 지정 폴더 복사만 수행합니다.
- 0.4.63: DVR 썸네일이 없을 때 영상 URL을 직접 열어 재생과 같은 방식으로 첫 프레임을 추출합니다.
- 0.4.62: 썸네일 인라인 재생을 속도 선택 UI 없이 기본 1.5배속으로 재생합니다.
- 0.4.61: 인라인 썸네일 재생의 속도 선택 버튼을 제거하고 기본 1.0배속으로 고정했습니다.
- 0.4.60: 전체화면 재생에 직접 Slider 탐색바를 추가하고, 인라인 썸네일 재생에서 1.0x/1.5x 속도를 전환할 수 있습니다.
- 0.4.59: Android 공용 Movies/Polestar Dashcam 복사를 끄고 사용자가 지정한 폴더에만 최종 복사합니다.
- 0.4.58: DVR 영상 시간 표시를 시작·종료 모두 1시간(3600초) 앞당겨 표시합니다.
- 0.4.57: 진행바는 파일 개수·파일명·퍼센트만 표시하며, DVR 썸네일이 없으면 영상 앞부분 최대 8MB에서 프레임 추출을 시도합니다.
- 0.4.56: Saved 빈 목록 문구를 밝게 표시하고 삭제 버튼을 빨간색으로 구분했으며 USB 저장·공유 버튼을 임시로 숨겼습니다.
- 0.4.55: 진행바 왼쪽에 파일 개수만 다시 표시하고 용량 표시는 계속 숨깁니다.
- 0.4.54: 진행바 왼쪽에는 파일명만 표시하고 용량 표시는 제거했습니다. 진행률은 오른쪽 퍼센트로 표시합니다.
- 0.4.53: 진행바 왼쪽 표시에서 의미가 불분명한 파일 개수 숫자를 제거했습니다.
- 0.4.52: 다운로드 진행률 숫자를 항상 표시하고 Saved 전체화면 플레이어의 탐색바를 자동으로 숨기지 않습니다.
- 0.4.51: 다운로드 진행바 옆에 현재 진행률을 퍼센트로 표시합니다.
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

## Early version summary

0.1.1부터 기본 조회 모드에서는 차량별 `status` JSON에 `usable`/`recording` 필드가 없어도 HTTP/JSON 응답 성공으로 연결을 확인하고 실제 `mediaDirList`를 계속 호출합니다. 상태 필드는 root 또는 `state`, `status`, `data`, `result` 객체에 있을 때 표시합니다. 목록 모드를 직접 변경할 때만 안전한 복귀를 위해 `usable`/`recording`을 필수로 확인합니다. 0.1.2부터 `mediaDirList`가 `Not in file-list mode`(403/409)로 응답하면 목록 모드로 자동 재시도하고 완료 후 `normal` 복귀를 확인합니다. 0.1.3부터 목록 항목에 DVR 썸네일을 표시하고 60 MiB를 넘는 파일도 Range 이어받기로 재시도합니다. 0.1.4부터 DVR이 bounded Range를 403으로 거부하면 open-ended Range로 자동 재시도하며, DVR 세션 쿠키도 유지합니다. 0.2.0부터 앱 실행 시 자동 연결하고 공용 미디어 폴더에 바로 저장하며 저장된 영상을 재생할 수 있습니다. 0.3.0부터 사용자가 고른 폴더 자동 복사를 제공합니다. 0.4.0부터 앱 이름을 **Gallery+**로 바꾸고 OEM 갤러리와 같은 앨범 홈/세부 목록 흐름으로 UI를 재구성했습니다. 0.4.1부터 홈 화면 보조 문구와 연결 상태 캡슐을 제거하고, 폴더 설정을 톱니 아이콘으로 바꾸며, 최초 실행 폴더 지정과 더 선명한 선택 표시를 제공합니다. 0.4.2부터 영상 타일을 일반 모드에서 누르면 썸네일 자리에서 바로 재생하고, 편집 모드에서는 선택 후 Export할 수 있습니다. 0.4.3부터 선택 상태는 주황색 테두리만 남기고 체크 배지를 제거했으며, 편집 모드 다중 선택 토글을 안정화했습니다. 0.4.4부터 편집 모드 진입 시 전체 선택 버튼 때문에 영상 그리드가 아래로 움직이지 않도록 상단 행 높이를 고정했습니다. 또한 Saved 화면은 설정에서 지정한 저장 폴더의 실제 파일 목록과 동일하게 표시합니다. 0.4.5부터 앱 표시 이름과 알림 제목을 영어 **Gallery+**로 바꾸고, 홈 상단 제목에도 +를 표시하며, 구분선 아래 일반 메시지 박스를 숨깁니다. 0.4.6부터 런처가 Activity label을 직접 읽는 경우까지 맞도록 MainActivity label도 **Gallery+**로 명시합니다. 0.4.7부터 헤더 아래 진행률 표시를 제거하고 전체 화면 하단 한 줄 바에서 진행 상태/취소 또는 선택 수/Export 버튼을 보여줍니다. 선택 수 텍스트는 어두운 배경에서 보이도록 밝은 색으로 고정했습니다. 0.4.8부터 일반 상태/오류 메시지와 DVR 녹화 복귀 경고는 화면을 차지하지 않는 Toast로 표시합니다. 0.4.9부터 원 갤러리 재생 경로처럼 DVR 다운로드 Range를 `bytes=offset-` 형태로만 보내 60 MiB 이상 파일의 bounded Range 403을 피합니다. 0.4.10부터 썸네일 인라인 재생은 기본 VideoView 대신 AndroidX Media3 ExoPlayer를 사용합니다.

## Dated validation notes

### 2026-09-12 v0.4.9

- Matched the OEM Gallery large-file transfer behavior: video downloads now send open-ended `Range: bytes=<offset>-` requests instead of bounded `bytes=start-end` requests.
- The app still reads each response in controlled 32 MiB local chunks, then reconnects from the next offset so interrupted files can resume without waiting for a long DVR stream to close.
- Added regression coverage for 60 MiB+ downloads and DVRs that reject bounded ranges with HTTP 403.

### 2026-09-12 v0.4.10

- Replaced thumbnail inline playback from Android `VideoView` to AndroidX Media3 ExoPlayer, closer to the OEM Gallery player stack than the platform `VideoView`.
- Added loading/error overlays so playback failure does not remain as an unexplained black tile.
- Mock DVR can now serve a real MP4 with HTTP Range support for emulator playback checks.

### 2026-09-12 v0.4.11

- Enlarged detail-list thumbnails to match the OEM Gallery scale.
- Fixed album cards to a consistent width and left-aligned the album list.

### 2026-09-12 v0.4.12

- Enlarged video tiles to the same 309dp thumbnail size as the album cards.
- Increased DVR playback and download read timeouts to 60 seconds.
- Added an OEM-compatible download fallback: retry a rejected Range request with a direct stream, then switch back to open-ended Range resume if the direct stream is cut off.

### 2026-09-12 v0.4.13

- Disabled audio rendering in the inline preview so AAOS devices with unsupported DVR PCM audio do not leave a black video tile. Full downloaded files retain their original audio track.

### 2026-09-12 v0.4.14

- Saved videos open in an in-app full-screen player with audio enabled.
- Replaced the detail edit icon with a `선택` text button and use `×` to leave edit mode.

### 2026-09-13 v0.4.15

- Replaced font glyph gallery and album icons with drawn vector-style icons for consistent AAOS rendering.

### 2026-09-13 v0.4.16

- Replaced the header and Albums navigation icons with the original OEM Gallery vector resources.
- Updated Saved to use a Chrome-style download icon with a tray.

### 2026-09-13 v0.4.17

- Reduced video thumbnail width to 80% of the previous size and changed the tile height to the 16:9 video aspect ratio.

### 2026-09-13 v0.4.18

- Displayed each video's start and end time on one line in the detail list.

### 2026-09-13 v0.4.19

- Grouped DVR videos by recording date with a full-width date separator in the detail list.

### 2026-09-13 v0.4.20

- Matched the OEM open-ended `Range: bytes=0-` request for inline DVR playback so vehicle firmware that rejects an initial request without Range can serve the video.

### 2026-09-13 v0.4.21

- Matched the OEM direct GET for the first inline playback request and only relies on Range for resumed playback.
- Added a direct-stream-to-Range fallback when a DVR returns HTTP 403 during a large-file transfer.

### 2026-09-13 v0.4.22

- Preserved the partial file when a resumed Range request receives HTTP 403 instead of deleting up to 60 MiB already received.
- Removed the forced `Connection: close` header from resumed DVR requests.

### 2026-09-13 v0.4.23

- Added local video and photo frame extraction for Saved thumbnails so downloaded files show their own previews.

### 2026-09-13 v0.4.24

- Saved JPG and other photos now open in an in-app full-screen viewer instead of depending on an external image viewer.

### 2026-09-13 v0.4.25

- Video filenames and sizes are shown on one row with the name left-aligned and size right-aligned.

### 2026-09-13 v0.4.26

- A short HTTP 200 stream is no longer treated as a completed large download; the app continues from the received byte offset until the file-list size is reached.

### 2026-09-13 v0.4.27

- Download and DVR errors remain visible in a modal dialog with the full server message for vehicle-side diagnosis.

### 2026-09-13 v0.4.28

- Persisted transfer errors so a foreground-service interruption still appears as a dialog after returning to the app.

### 2026-09-13 v0.4.29

- Expanded video thumbnails to the full width of each grid card while preserving the 16:9 height ratio.

### 2026-09-13 v0.4.30

- Rechecked the OEM streaming path: playback uses a local proxy cache with direct GET first and open-ended Range only for cache misses/resumes; downloads retain the tested Range strategy and short-200 recovery.

### 2026-09-13 v0.4.31

- Added a 512 MiB Media3 local playback cache so DVR playback follows the OEM proxy-cache pattern.

### 2026-09-13 v0.4.32

- Files larger than 60 MiB now begin with the OEM-style direct stream, then resume from the received offset in app-controlled 32 MiB chunks using open-ended Range requests.

### 2026-09-13 v0.4.33

- DVR inline playback now enters and keeps `in-file-list` mode for the duration of the detail view, then restores `normal` when leaving it.

### 2026-09-13 v0.4.34

- Inline playback errors now show the concrete HTTP response code and server detail instead of only `ERROR_CODE_IO_BAD_HTTP_STATUS`.

### 2026-09-13 v0.4.35

- Forced playback cache misses to use OEM-compatible open-ended ranges, preventing Media3 bounded `Range` requests that the DVR rejects with HTTP 403.

### 2026-09-13 v0.4.36

- Increased the adaptive video grid card minimum width to 370dp so vehicle layouts show five cards per row instead of six.

### 2026-09-13 v0.4.37

- Propagated per-file download failures to the persistent error dialog instead of reporting them as a normal batch completion message.
