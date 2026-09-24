# Gallery+

Gallery+는 Polestar 4의 대시캠 영상을 더 쉽게 보고, 저장하고, 휴대폰으로 옮기기 위한 Android 앱입니다. 차량 내부 DVR HTTP API에서 일반 영상, 비상 영상, 사진 목록을 읽어 오고, OEM 갤러리 앱과 비슷한 앨범 화면으로 보여줍니다.

기본 목표는 단순합니다. 차량 화면에서 대시캠 파일을 고르고, 원하는 폴더에 저장하거나, QR 코드로 휴대폰에 바로 보내는 것입니다.

## 주요 기능

- **OEM 갤러리와 비슷한 화면**: Albums, Saved 중심의 왼쪽 사이드바와 카드형 목록을 사용합니다.
- **DVR 파일 조회**: `Loop videos`, `Emergency videos`, `Photos` 목록을 DVR에서 가져옵니다.
- **썸네일과 전체화면 재생**: 영상과 사진을 목록에서 확인하고, 저장된 영상은 앱 안에서 전체화면으로 재생합니다.
- **선택 다운로드**: 원하는 파일만 선택해 사용자가 지정한 폴더에 저장합니다.
- **Saved 관리**: 저장된 파일을 다시 보고, 삭제하고, USB로 복사할 수 있습니다.
- **QR 기반 휴대폰 전송**: Saved 파일을 선택한 뒤 **Share**를 누르면 휴대폰으로 바로 보낼 수 있습니다.
- **대용량 다운로드 보강**: DVR 연결이 중간에 끊겨도 open-ended Range 방식으로 이어받기를 시도합니다.

## 빠른 사용법

1. 차량이 주차된 상태에서 **Gallery+**를 실행합니다.
2. 앱이 기본 DVR 주소 `http://198.18.37.20`에 자동 연결합니다.
3. 첫 화면의 **Albums**에서 `Loop videos`, `Emergency videos`, `Photos` 중 하나를 선택합니다.
4. 썸네일을 누르면 영상을 전체화면으로 볼 수 있습니다.
5. 저장하려면 우측 상단 **Select**를 누르고 파일을 선택한 뒤 **Download**를 누릅니다.
6. 저장된 파일은 왼쪽 **Saved**에서 다시 확인합니다.

처음 실행하거나 설정 아이콘을 누르면 다운로드 받을 폴더를 지정할 수 있습니다. 이후 다운로드한 파일은 선택한 폴더에 복사됩니다.

## 휴대폰으로 바로 전송하기

Saved 화면에서 파일을 선택하고 **Share**를 누르면 QR 코드가 표시됩니다. 휴대폰 카메라로 QR을 스캔하면 휴대폰 브라우저에 수신 페이지가 열리고, 차량 앱이 선택한 파일을 휴대폰으로 전송합니다.

전송 순서는 다음과 같습니다.

1. **Saved** 화면에서 보낼 파일을 선택합니다.
2. 아래쪽 **Share** 버튼을 누릅니다.
3. QR 코드가 준비될 때까지 기다립니다. 실제 전송 가능한 QR이 준비된 뒤에만 표시됩니다.
4. 휴대폰으로 QR을 스캔합니다.
5. 휴대폰 브라우저와 차량 화면의 QR 팝업을 그대로 열어 둡니다.
6. 전송이 끝나면 차량의 QR 팝업은 자동으로 닫힙니다.
7. 휴대폰 화면에서 **Save file(s)**를 눌러 파일을 저장합니다.

QR 팝업을 닫으면 전송 준비와 전송이 취소됩니다.

## Tailcat 전송과 개인 정보

Share 기능은 Tailcat을 사용합니다. 쉽게 말하면, 차량 앱과 휴대폰 브라우저가 파일을 주고받을 수 있도록 임시 통로를 만드는 방식입니다. Tailscale 계정이나 별도 로그인은 필요하지 않습니다.

중요한 점은 대시캠 영상이 Gallery+ 개발자 서버나 GitHub 서버에 업로드되지 않는다는 것입니다. 영상 파일 자체는 차량 앱에서 휴대폰 브라우저로 전송됩니다.

GitHub Pages에 있는 수신 페이지는 파일을 저장하는 서버가 아닙니다. 역할은 다음 정도입니다.

- 휴대폰이 파일을 받을 준비를 하게 함
- 차량 앱과 휴대폰이 연결될 수 있게 도와줌
- 전송이 끝난 뒤 저장 버튼을 보여줌

비유하면 GitHub Pages 수신 페이지는 물건을 보관하는 창고가 아니라 접수 창구입니다. 영상 파일은 그 창구에 맡겨지는 것이 아니라 차량에서 휴대폰으로 이동합니다.

네트워크 상태에 따라 큰 영상은 시간이 걸릴 수 있습니다. 브라우저 기반 전송은 환경에 따라 직접 연결이 아닌 중계 경로를 사용할 수도 있어 속도 차이가 날 수 있습니다.

수신 페이지는 받은 데이터를 4 MiB 단위로 휴대폰 브라우저 저장소에 기록합니다. 화면이 꺼지거나 다른 앱으로 전환되어 iOS가 브라우저를 멈추면 전송도 잠시 멈추지만, 같은 페이지로 돌아왔을 때 마지막으로 저장된 위치부터 자동으로 이어받습니다. 브라우저가 백그라운드에서 계속 실행되는지는 휴대폰 운영체제가 결정하므로 완전한 백그라운드 실행을 강제할 수는 없습니다.

## 저장 방식

- 앱 내부 원본 위치: `files/exports/{normal|emergency|photo}/{파일 식별 해시}/{파일명}`
- 사용자가 지정한 폴더에도 완료 파일을 복사합니다.
- Saved 화면은 지정된 폴더의 실제 파일 목록을 읽어 표시합니다.
- 실패하거나 취소된 다운로드의 임시 파일은 정리합니다.
- Saved에서 삭제하면 앱 내부 원본을 삭제합니다. 사용자가 지정한 SAF 폴더 파일은 앱이 임의로 삭제하지 않습니다.

USB 저장은 Android Storage Access Framework를 사용합니다. AAOS 제조사 구현에 따라 USB 또는 폴더 선택기가 노출되지 않을 수 있습니다. 이 경우 차량 파일 앱에서 저장 폴더의 파일을 직접 복사해야 할 수 있습니다.

## DVR 목록 모드

차량 DVR은 파일 목록 조회를 위해 별도 목록 모드가 필요할 수 있습니다. Gallery+는 OEM 갤러리 동작을 참고해 목록 모드를 유지하고, 작업이 끝나면 녹화 상태를 복구하려고 시도합니다.

- `POST /status`에 `{"app":"gallery","recording":"enter-file-list"}`를 보내고 `in-file-list` 상태를 확인합니다.
- 작업이 끝나거나 취소되면 `recording=normal` 복귀를 요청합니다.
- 앱 강제 종료, 차량 전원 차단, 네트워크 단절이 있으면 즉시 복귀를 보장할 수 없습니다.
- 복귀 확인이 필요한 경우 앱에서 복구 안내를 표시합니다.

## OEM 갤러리와의 차이

- 화면 흐름, DVR URL 구성, 파일 목록 모드, heartbeat는 OEM 갤러리 동작을 참고했습니다.
- 재생 엔진은 OEM 내부 플레이어가 아니라 AndroidX Media3 ExoPlayer입니다.
- OEM 앱은 차량 시스템 권한을 사용할 수 있지만 Gallery+는 일반 사이드로드 앱 권한으로 동작합니다.
- DVR 파일을 직접 삭제하거나 재인코딩하지 않습니다.
- 차량 펌웨어나 AAOS 정책에 따라 일부 동작은 제한될 수 있습니다.

## 빌드와 설치

필요한 환경은 JDK 17, Android SDK 35, Android SDK Build Tools, platform-tools입니다. 검증에 사용한 Gradle은 8.9, Android Gradle Plugin은 8.7.3입니다.

```sh
cd /Users/home/PolestarDashcamExporter
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.polestar.dashcamexporter/.MainActivity
```

앱 정보는 다음과 같습니다.

- 패키지: `com.polestar.dashcamexporter`
- 표시 이름: **Gallery+**
- Android: 11(API 30) 이상
- Target SDK: 35
- 서명: Debug 서명 APK

필수 권한은 인터넷, foreground data sync, wake lock입니다. Android 13 이상에서는 전송 알림 권한을 요청합니다. 알림을 거부해도 전송 자체는 가능합니다. 광범위 저장소 권한, 카메라, 위치, 차량 vendor 권한은 요청하지 않습니다.

## 모의 DVR / 에뮬레이터 테스트

```sh
python3 tools/mock_dvr.py
# 재생 UI까지 확인하려면 실제 MP4를 지정합니다.
python3 tools/mock_dvr.py --video-mp4 /path/to/sample.mp4

# 별도 터미널
adb reverse tcp:8765 tcp:8765
```

앱의 연결 설정에서 `http://127.0.0.1:8765`를 입력합니다. 기본 모의 영상은 전송 검사용 2 MiB 결정적 바이트이며 재생 가능한 영상이 아닙니다. `--video-mp4`를 지정하면 재생 가능한 MP4를 DVR 영상으로 제공합니다.

계측 테스트는 다음과 같이 실행합니다.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant --user current com.polestar.dashcamexporter android.permission.POST_NOTIFICATIONS
adb shell am instrument -w com.polestar.dashcamexporter.test/androidx.test.runner.AndroidJUnitRunner
```

## 문서

- 변경 이력: [CHANGELOG.md](CHANGELOG.md)
- API 메모: [docs/API_NOTES.md](docs/API_NOTES.md)
- 검증 기록: [docs/VALIDATION.md](docs/VALIDATION.md)
- Tailcat 수신 페이지: [docs/tailcat](docs/tailcat)
- OEM 분석 원본: `/Users/home/Polestar4_Dashcam_Analysis_20260911/README.md`, `docs/POLESTAR4_DASHCAM_OEM_APK_ANALYSIS.md`
