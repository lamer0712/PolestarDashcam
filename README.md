# Polestar Dashcam Exporter

Polestar 4의 내부 DVR HTTP API에서 파일을 조회하고, 선택한 영상·사진을 내려받아 Android 공유 시트 또는 시스템 폴더 선택기로 내보내는 독립 Android 앱입니다. OEM 앱이 아닌 개인용 사이드로드 앱입니다.

## 사용 순서

1. 차량이 주차된 상태에서 앱을 열고 **DVR 연결**을 누릅니다. 기본 주소는 `http://198.18.37.20`입니다.
2. **일반 / 긴급 / 사진** 탭에서 파일을 선택합니다. 각 분류를 처음 50개씩 요청하고, **다음 목록 불러오기**로 추가 조회합니다. 전체 선택은 현재 탭에 불러온 파일에만 적용됩니다.
3. **선택 다운로드**를 누릅니다. 전송 진행률과 취소 버튼을 표시하며, 완료된 파일은 앱 내부 저장소에 보관됩니다. 다운로드는 foreground service로 실행됩니다.
4. **저장됨** 탭에서 파일을 선택한 뒤 **공유 / 메일**을 누릅니다. 공유 시트에서 메일·파일전송 앱 등을 선택합니다. 실제 전송은 선택한 앱에서 진행하며 메일을 자동 발송하지 않습니다. 공유는 한 번에 최대 50개입니다.
5. **USB / 폴더 저장**을 누르면 시스템 폴더 선택기가 열립니다. USB의 하위 폴더를 선택하고 접근을 허용하면 복사합니다. 기존 파일명과 겹치면 번호를 붙여 보존합니다.

USB는 일반 파일 경로를 직접 탐색하지 않고 Android Storage Access Framework를 사용합니다. AAOS 제조사 구현에 따라 USB 또는 폴더 선택기가 노출되지 않을 수 있습니다. 이때는 공유 기능을 사용하세요. 메일 앱 설치 여부와 첨부 용량 제한도 대상 앱에 따라 다릅니다.

## DVR 목록 모드

앱은 차량에서 파일 목록을 안정적으로 받을 수 있도록 목록 모드를 기본으로 켭니다. 화면 오른쪽 위에는 연결 설정 버튼을 표시하지 않으며, 기본 DVR 주소(`http://198.18.37.20`)로 바로 연결합니다.

- `POST /status`에 `{"app":"gallery","recording":"enter-file-list"}`를 보내고 `in-file-list` 상태를 확인합니다.
- 해당 작업이 끝나거나 취소·실패하면 `recording=normal`로 복귀하고 상태를 다시 읽어 확인합니다.
- 이미 OEM 갤러리가 `in-file-list`로 변경한 상태라면 이를 소유한 세션으로 취급하지 않고 변경하지 않습니다.
- 모드 변경 요청 전에 복구 대상 주소를 디스크에 저장합니다. 복귀 실패 또는 강제 종료 후 앱을 다시 열면 **녹화 복귀 재시도**가 표시됩니다. 복귀 확인 전에는 DVR 작업과 주소 변경을 막습니다.
- 앱 강제 종료·차량 전원 차단·네트워크 단절 시 즉시 복귀는 보장되지 않습니다. 차량 OEM 대시캠에서도 실제 녹화 상태를 확인하세요. `normal` API 응답은 물리 녹화 파일 생성 자체를 증명하지 않습니다.

## 저장과 오류 처리

- 저장 위치: 앱 전용 `files/exports/{normal|emergency|photo}/{파일 식별 해시}/{파일명}`.
- 앱 재시작 후 완료 파일을 다시 표시합니다. 앱 삭제/데이터 삭제 시 보관 파일도 지워지므로 필요한 영상은 공유 또는 폴더 저장으로 내보내세요.
- 파일은 128 KiB 버퍼로 스트리밍합니다. `.part` 임시 파일과 32 MiB HTTP Range 구간을 사용해 큰 파일의 연결 끊김을 이어받고, Content-Length·Content-Range 및 목록 크기(있는 경우)가 일치한 뒤 최종 이름으로 이동합니다.
- 중복 다운로드는 같은 URL·ID·시간·크기인 기존 완료 파일을 재사용합니다. 다른 분류의 동명 파일은 분리합니다.
- 실패·취소된 임시 파일은 제거합니다. 프로세스 강제 종료 시 남은 임시 파일은 다음 실행 때 정리합니다. 이어받기는 지원하지 않습니다.
- 한 파일 실패 시 나머지 선택 파일은 계속 처리하며 파일별 오류를 표시합니다. 취소 시 이미 완료된 파일은 보관합니다. 취소는 현재 네트워크 읽기가 끝나거나 타임아웃된 뒤 처리되므로 즉시 끝나지 않을 수 있습니다.
- 목록을 조회할 때 DVR `/thumbnail` 응답을 캐시해 각 항목 왼쪽에 썸네일을 표시합니다. 썸네일을 제공하지 않는 차량에서는 `미리보기` 자리표시자가 표시됩니다.
- 큰 파일은 원본 그대로 내보냅니다. 재인코딩·분할·재생·DVR 원본 삭제 기능은 없습니다.
- 앱 내부 저장본을 개별 삭제하는 UI는 0.1.3에 없습니다. 내보낸 뒤 필요하면 Android 앱 설정에서 저장공간을 관리하세요.

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

Android 11(API 30) 이상이며 target SDK는 35입니다. 패키지는 `com.polestar.dashcamexporter`, 표시 이름은 **Dashcam Exporter**, 버전은 **0.1.3**입니다. Debug 서명 APK로 제공하며 OEM 시스템 UID나 OEM 서명을 사용하지 않습니다. 자동차 런처의 노출 및 일반 앱 설치 허용 여부는 차량 정책에 따릅니다.

0.1.1부터 기본 조회 모드에서는 차량별 `status` JSON에 `usable`/`recording` 필드가 없어도 HTTP/JSON 응답 성공으로 연결을 확인하고 실제 `mediaDirList`를 계속 호출합니다. 상태 필드는 root 또는 `state`, `status`, `data`, `result` 객체에 있을 때 표시합니다. 목록 모드를 직접 변경할 때만 안전한 복귀를 위해 `usable`/`recording`을 필수로 확인합니다. 0.1.2부터 `mediaDirList`가 `Not in file-list mode`(403/409)로 응답하면 목록 모드로 자동 재시도하고 완료 후 `normal` 복귀를 확인합니다. 0.1.3부터 목록 항목에 DVR 썸네일을 표시하고 60 MiB를 넘는 파일도 Range 이어받기로 재시도합니다.

필수 권한은 인터넷·foreground data sync·wake lock입니다. Android 13 이상에서는 전송 알림 권한을 요청합니다. 알림을 거부해도 전송 자체는 가능합니다. 광범위 저장소 권한, 카메라, 위치, 차량 vendor 권한은 요청하지 않습니다.

## 모의 DVR / 에뮬레이터 테스트

```sh
python3 tools/mock_dvr.py
# 별도 터미널
adb reverse tcp:8765 tcp:8765
```

앱의 연결 설정에서 `http://127.0.0.1:8765`를 입력합니다. 모의 영상은 전송 검사용 2 MiB의 결정적 바이트이며 재생 가능한 영상이 아닙니다. 사진은 작은 PNG입니다. normal 53개, emergency 2개, photo 2개의 OEM 구조 목록을 제공합니다. 모의 서버는 loopback에만 바인딩하고 다른 서비스나 차량을 호출하지 않습니다.

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
