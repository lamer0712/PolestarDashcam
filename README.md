# Gallery+

Polestar 4의 내부 DVR HTTP API에서 파일을 조회하고, 선택한 영상·사진을 Android 갤러리에서 바로 접근할 수 있는 공용 미디어 폴더와 사용자가 지정한 폴더로 저장하는 독립 Android 앱입니다. OEM 갤러리의 앨범 중심 화면을 따르는 개인용 사이드로드 앱입니다.

## 사용 순서

1. 차량이 주차된 상태에서 앱을 엽니다. 앱이 백그라운드에서 DVR에 자동 연결합니다. 기본 주소는 `http://198.18.37.20`입니다.
2. 첫 화면의 **Albums**에서 **Loop videos / Emergency videos / Photos** 앨범을 선택합니다. 화면 구성은 OEM 갤러리처럼 왼쪽 사이드바와 `Exterior` 앨범 카드로 시작합니다.
3. 세부 목록에서 영상 썸네일을 누르면 같은 자리에서 미리 재생됩니다. Export하려면 우측 상단 편집 아이콘을 누른 뒤 파일을 선택하고 **Export**를 누릅니다. 각 분류를 처음 50개씩 요청하고, **Load more**로 추가 조회합니다.
4. 필요하면 상단 ▣ 버튼에서 원하는 폴더를 한 번 선택합니다. 이후 **Export** 시 파일은 Android 갤러리 공용 폴더와 선택한 폴더에 자동으로 복사됩니다. 다운로드는 foreground service로 실행됩니다.
5. 왼쪽 **Saved**는 설정에서 지정한 저장 폴더의 실제 파일 목록을 보여줍니다. 저장된 파일을 눌러 재생하거나 선택해 공유·USB 저장을 이용합니다. 일반적인 경우에는 이미 공용 미디어 폴더에 저장된 파일을 차량 파일 앱에서 바로 복사할 수 있습니다.

USB 저장은 Android Storage Access Framework를 사용합니다. AAOS 제조사 구현에 따라 USB 또는 폴더 선택기가 노출되지 않을 수 있습니다. 이때는 공용 미디어 폴더의 파일을 차량 파일 앱에서 복사하세요. 메일 앱 설치 여부와 첨부 용량 제한도 대상 앱에 따라 다릅니다.

## DVR 목록 모드

앱은 차량에서 파일 목록을 안정적으로 받을 수 있도록 목록 모드를 기본으로 켭니다. 화면 오른쪽 위에는 연결 설정 버튼을 표시하지 않으며, 기본 DVR 주소(`http://198.18.37.20`)로 바로 연결합니다.

- `POST /status`에 `{"app":"gallery","recording":"enter-file-list"}`를 보내고 `in-file-list` 상태를 확인합니다.
- 해당 작업이 끝나거나 취소·실패하면 `recording=normal`로 복귀하고 상태를 다시 읽어 확인합니다.
- 이미 OEM 갤러리가 `in-file-list`로 변경한 상태라면 이를 소유한 세션으로 취급하지 않고 변경하지 않습니다.
- 모드 변경 요청 전에 복구 대상 주소를 디스크에 저장합니다. 복귀 실패 또는 강제 종료 후 앱을 다시 열면 **녹화 복귀 재시도**가 표시됩니다. 복귀 확인 전에는 DVR 작업과 주소 변경을 막습니다.
- 앱 강제 종료·차량 전원 차단·네트워크 단절 시 즉시 복귀는 보장되지 않습니다. 차량 OEM 대시캠에서도 실제 녹화 상태를 확인하세요. `normal` API 응답은 물리 녹화 파일 생성 자체를 증명하지 않습니다.

## 저장과 오류 처리

- 원본 보관 위치: 앱 전용 `files/exports/{normal|emergency|photo}/{파일 식별 해시}/{파일명}`. 완료 후 공용 미디어 폴더와 사용자가 지정한 SAF 폴더에 자동 게시합니다.
- 앱 재시작 후 완료 파일을 다시 표시합니다. 공용 미디어 폴더에 게시된 파일은 앱 데이터와 별도로 남습니다.
- 파일은 128 KiB 버퍼로 스트리밍합니다. `.part` 임시 파일과 원 갤러리 방식의 open-ended HTTP Range(`bytes=offset-`)를 사용해 큰 파일의 연결 끊김을 이어받고, 앱 내부에서는 32 MiB 단위로만 읽은 뒤 재연결합니다. Content-Length·Content-Range 및 목록 크기(있는 경우)가 일치한 뒤 최종 이름으로 이동합니다.
- 중복 다운로드는 같은 URL·ID·시간·크기인 기존 완료 파일을 재사용합니다. 다른 분류의 동명 파일은 분리합니다.
- 실패·취소된 임시 파일은 제거합니다. 프로세스 강제 종료 시 남은 임시 파일은 다음 실행 때 정리합니다. 이어받기는 지원하지 않습니다.
- 한 파일 실패 시 나머지 선택 파일은 계속 처리하며 파일별 오류를 표시합니다. 취소 시 이미 완료된 파일은 보관합니다. 취소는 현재 네트워크 읽기가 끝나거나 타임아웃된 뒤 처리되므로 즉시 끝나지 않을 수 있습니다.
- 목록을 조회할 때 DVR `/thumbnail` 응답을 캐시해 각 항목 왼쪽에 썸네일을 표시합니다. 썸네일을 제공하지 않는 차량에서는 `미리보기` 자리표시자가 표시됩니다.
- 큰 파일은 원본 그대로 내보냅니다. 재인코딩·분할·재생·DVR 원본 삭제 기능은 없습니다.
- 앱 내부 저장본을 개별 삭제하는 UI는 0.2.0에 없습니다. 내보낸 뒤 필요하면 Android 앱 설정에서 저장공간을 관리하세요.

## 빌드 / 설치

JDK 17, Android SDK 35, SDK Build Tools 및 플랫폼 도구가 필요합니다. 검증에 사용한 Gradle은 8.9, Android Gradle Plugin은 8.7.3이며 기존 로컬 빌드 환경과 동일한 버전으로 고정했습니다.

```sh
cd /Users/home/PolestarDashcamExporter
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
# 다른 컴퓨터에서는 local.properties의 sdk.dir을 해당 Android SDK 위치로 설정합니다.
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.polestar.dashcamexporter/.MainActivity
```

Android 11(API 30) 이상이며 target SDK는 35입니다. 패키지는 `com.polestar.dashcamexporter`, 표시 이름은 **Gallery+**, 버전은 **0.4.10**입니다. Debug 서명 APK로 제공하며 OEM 시스템 UID나 OEM 서명을 사용하지 않습니다. 자동차 런처의 노출 및 일반 앱 설치 허용 여부는 차량 정책에 따릅니다.

0.1.1부터 기본 조회 모드에서는 차량별 `status` JSON에 `usable`/`recording` 필드가 없어도 HTTP/JSON 응답 성공으로 연결을 확인하고 실제 `mediaDirList`를 계속 호출합니다. 상태 필드는 root 또는 `state`, `status`, `data`, `result` 객체에 있을 때 표시합니다. 목록 모드를 직접 변경할 때만 안전한 복귀를 위해 `usable`/`recording`을 필수로 확인합니다. 0.1.2부터 `mediaDirList`가 `Not in file-list mode`(403/409)로 응답하면 목록 모드로 자동 재시도하고 완료 후 `normal` 복귀를 확인합니다. 0.1.3부터 목록 항목에 DVR 썸네일을 표시하고 60 MiB를 넘는 파일도 Range 이어받기로 재시도합니다. 0.1.4부터 DVR이 bounded Range를 403으로 거부하면 open-ended Range로 자동 재시도하며, DVR 세션 쿠키도 유지합니다. 0.2.0부터 앱 실행 시 자동 연결하고 공용 미디어 폴더에 바로 저장하며 저장된 영상을 재생할 수 있습니다. 0.3.0부터 사용자가 고른 폴더 자동 복사를 제공합니다. 0.4.0부터 앱 이름을 **Gallery+**로 바꾸고 OEM 갤러리와 같은 앨범 홈/세부 목록 흐름으로 UI를 재구성했습니다. 0.4.1부터 홈 화면 보조 문구와 연결 상태 캡슐을 제거하고, 폴더 설정을 톱니 아이콘으로 바꾸며, 최초 실행 폴더 지정과 더 선명한 선택 표시를 제공합니다. 0.4.2부터 영상 타일을 일반 모드에서 누르면 썸네일 자리에서 바로 재생하고, 편집 모드에서는 선택 후 Export할 수 있습니다. 0.4.3부터 선택 상태는 주황색 테두리만 남기고 체크 배지를 제거했으며, 편집 모드 다중 선택 토글을 안정화했습니다. 0.4.4부터 편집 모드 진입 시 전체 선택 버튼 때문에 영상 그리드가 아래로 움직이지 않도록 상단 행 높이를 고정했습니다. 또한 Saved 화면은 설정에서 지정한 저장 폴더의 실제 파일 목록과 동일하게 표시합니다. 0.4.5부터 앱 표시 이름과 알림 제목을 영어 **Gallery+**로 바꾸고, 홈 상단 제목에도 +를 표시하며, 구분선 아래 일반 메시지 박스를 숨깁니다. 0.4.6부터 런처가 Activity label을 직접 읽는 경우까지 맞도록 MainActivity label도 **Gallery+**로 명시합니다. 0.4.7부터 헤더 아래 진행률 표시를 제거하고 전체 화면 하단 한 줄 바에서 진행 상태/취소 또는 선택 수/Export 버튼을 보여줍니다. 선택 수 텍스트는 어두운 배경에서 보이도록 밝은 색으로 고정했습니다. 0.4.8부터 일반 상태/오류 메시지와 DVR 녹화 복귀 경고는 화면을 차지하지 않는 Toast로 표시합니다. 0.4.9부터 원 갤러리 재생 경로처럼 DVR 다운로드 Range를 `bytes=offset-` 형태로만 보내 60 MiB 이상 파일의 bounded Range 403을 피합니다. 0.4.10부터 썸네일 인라인 재생은 기본 VideoView 대신 AndroidX Media3 ExoPlayer를 사용합니다.

필수 권한은 인터넷·foreground data sync·wake lock입니다. Android 13 이상에서는 전송 알림 권한을 요청합니다. 알림을 거부해도 전송 자체는 가능합니다. 원하는 폴더를 지정할 때만 Android 시스템 폴더 선택기의 쓰기 권한을 사용하며, 광범위 저장소 권한·카메라·위치·차량 vendor 권한은 요청하지 않습니다.

## 모의 DVR / 에뮬레이터 테스트

```sh
python3 tools/mock_dvr.py
# 재생 UI까지 확인하려면 실제 MP4를 지정합니다.
python3 tools/mock_dvr.py --video-mp4 /path/to/sample.mp4
# 별도 터미널
adb reverse tcp:8765 tcp:8765
```

앱의 연결 설정에서 `http://127.0.0.1:8765`를 입력합니다. 기본 모의 영상은 전송 검사용 2 MiB의 결정적 바이트이며 재생 가능한 영상이 아닙니다. `--video-mp4`를 지정하면 재생 가능한 MP4를 DVR 영상으로 제공합니다. 사진은 작은 PNG입니다. normal 53개, emergency 2개, photo 2개의 OEM 구조 목록을 제공합니다. 모의 서버는 loopback에만 바인딩하고 다른 서비스나 차량을 호출하지 않습니다.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# AAOS는 현재 운전자 user가 0이 아닐 수 있습니다.
adb shell pm grant --user current com.polestar.dashcamexporter android.permission.POST_NOTIFICATIONS
adb shell am instrument -w com.polestar.dashcamexporter.test/androidx.test.runner.AndroidJUnitRunner
```

계측 테스트는 항상 loopback 모의 서버에만 연결합니다. 테스트 후 에뮬레이터 앱 설정은 모의 주소로 남을 수 있으므로 차량 테스트 전 기본 주소로 되돌리세요. 배포 APK의 새 설치 기본값은 DVR 주소입니다.

## 근거 / 검증

- 원본 분석: `/Users/home/Polestar4_Dashcam_Analysis_20260911/README.md` 및 `docs/POLESTAR4_DASHCAM_OEM_APK_ANALYSIS.md`.
- 추가로 제공된 OEM 갤러리 APK의 데이터 모델과 Retrofit interface를 확인했습니다. 응답 구조와 한계는 [API_NOTES.md](docs/API_NOTES.md)에 정리했습니다.
- 빌드·테스트 결과 및 실차에서 남은 확인 사항: [VALIDATION.md](docs/VALIDATION.md).
- Android 공식 문서: [파일 공유와 URI 읽기 권한](https://developer.android.com/training/secure-file-sharing/share-file), [공유 시트](https://developer.android.com/develop/ui/compose/sharing/send), [폴더 선택과 SAF 제약](https://developer.android.com/training/data-storage/shared/documents-files), [dataSync foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types).

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
