# Gallery+

Polestar 4의 내부 DVR HTTP API에서 파일을 조회하고, 선택한 영상·사진을 Android 갤러리에서 바로 접근할 수 있는 공용 미디어 폴더와 사용자가 지정한 폴더로 저장하는 독립 Android 앱입니다. OEM 갤러리의 앨범 중심 화면을 따르는 개인용 사이드로드 앱입니다.

## 사용 순서

1. 차량이 주차된 상태에서 앱을 엽니다. 앱이 백그라운드에서 DVR에 자동 연결합니다. 기본 주소는 `http://198.18.37.20`입니다.
2. 첫 화면의 **Albums**에서 **Loop videos / Emergency videos / Photos** 앨범을 선택합니다. 화면 구성은 OEM 갤러리처럼 왼쪽 사이드바와 `Exterior` 앨범 카드로 시작합니다.
3. 세부 목록에서 영상 썸네일을 누르면 OEM처럼 전체화면 플레이어로 재생됩니다. Download하려면 우측 상단 **Select**를 누른 뒤 파일을 선택하고 **Download**를 누릅니다. 각 분류는 처음 50개를 조회하며 스크롤이 하단에 닿으면 다음 페이지를 자동으로 요청합니다.
4. 최초 실행 또는 설정 아이콘에서 저장 폴더를 한 번 선택합니다. 이후 **Download** 시 파일은 선택한 폴더에만 자동으로 복사됩니다. 다운로드는 foreground service로 실행됩니다.
5. 왼쪽 **Saved**는 설정에서 지정한 저장 폴더의 실제 파일 목록을 보여줍니다. 저장된 파일을 눌러 재생하거나 선택해 공유·USB 저장을 이용합니다. 일반적인 경우에는 이미 공용 미디어 폴더에 저장된 파일을 차량 파일 앱에서 바로 복사할 수 있습니다.

USB 저장은 Android Storage Access Framework를 사용합니다. AAOS 제조사 구현에 따라 USB 또는 폴더 선택기가 노출되지 않을 수 있습니다. 이때는 공용 미디어 폴더의 파일을 차량 파일 앱에서 복사하세요. 메일 앱 설치 여부와 첨부 용량 제한도 대상 앱에 따라 다릅니다.

## 휴대폰으로 바로 전송하기: Share / Tailcat

Saved 화면에서 파일을 선택한 뒤 **Share**를 누르면 QR 코드가 표시됩니다. 휴대폰 카메라로 QR을 스캔하면 휴대폰 브라우저에 수신 페이지가 열리고, 차량 앱이 선택한 파일을 휴대폰으로 전송합니다. 전송이 끝나면 휴대폰 화면에서 **Save file(s)**를 눌러 저장합니다.

사용 흐름은 다음과 같습니다.

1. Gallery+의 **Saved** 화면으로 이동합니다.
2. 휴대폰으로 옮길 영상이나 사진을 선택합니다.
3. 아래쪽 **Share** 버튼을 누릅니다.
4. QR 코드가 준비될 때까지 기다립니다. 처음에는 준비 중 표시가 나오고, 실제 전송 가능한 QR이 만들어진 뒤에만 QR이 보입니다.
5. 휴대폰으로 QR을 스캔합니다.
6. 휴대폰 브라우저를 열어 둡니다. 차량 화면의 QR 팝업도 닫지 않습니다.
7. 전송이 완료되면 차량의 QR 팝업은 자동으로 닫히고, 휴대폰에서는 **Save file(s)** 버튼으로 파일을 저장합니다.

QR 팝업을 닫으면 전송 준비와 전송이 취소됩니다. 전송 중에는 차량 앱과 휴대폰 브라우저를 그대로 열어 두세요.

이 기능은 **Tailcat**을 사용합니다. 쉽게 말하면, 차량 앱과 휴대폰 브라우저가 서로 파일을 주고받을 수 있도록 임시 통로를 만드는 방식입니다. Tailscale 계정이나 별도 로그인은 필요하지 않습니다.

개인 정보 측면에서 중요한 점은, 대시캠 영상이 Gallery+ 개발자 서버나 GitHub 서버에 업로드되지 않는다는 것입니다. 영상 파일 자체는 차량 앱에서 휴대폰 브라우저로 전송됩니다. GitHub Pages에 있는 수신 페이지는 파일을 저장하는 창고가 아니라, 휴대폰이 전송을 받을 준비를 하도록 도와주는 **창구** 역할만 합니다.

비유하면 다음과 같습니다.

- GitHub Pages 수신 페이지: 접수 창구
- 차량 앱: 파일을 보내는 쪽
- 휴대폰 브라우저: 파일을 받는 쪽
- 대시캠 영상 파일: 창구에 맡겨지는 물건이 아니라 차량에서 휴대폰으로 이동하는 물건

따라서 QR을 스캔하면 GitHub Pages 주소가 열리지만, 영상이 GitHub에 저장되는 구조는 아닙니다. 페이지는 연결 준비와 저장 버튼 표시를 담당하고, 실제 영상 데이터는 차량과 휴대폰 사이에서 이동합니다.

큰 영상은 네트워크 상태에 따라 시간이 걸릴 수 있습니다. 특히 브라우저 기반 전송은 환경에 따라 직접 연결이 아닌 중계 경로를 사용할 수 있어 속도 차이가 날 수 있습니다. 그래도 파일 내용은 전송을 위해 외부 저장소에 업로드되는 방식이 아니라, 전송 통로를 통해 휴대폰으로 전달됩니다.

변경 이력은 [CHANGELOG.md](CHANGELOG.md)에 정리합니다.

## DVR 목록 모드

앱은 차량에서 파일 목록을 안정적으로 받을 수 있도록 목록 모드를 기본으로 켭니다. 화면 오른쪽 위에는 연결 설정 버튼을 표시하지 않으며, 기본 DVR 주소(`http://198.18.37.20`)로 바로 연결합니다.

- `POST /status`에 `{"app":"gallery","recording":"enter-file-list"}`를 보내고 `in-file-list` 상태를 확인합니다.
- 해당 작업이 끝나거나 취소·실패하면 `recording=normal`로 복귀하고 상태를 다시 읽어 확인합니다.
- 이미 OEM 갤러리가 `in-file-list`로 변경한 상태라면 이를 소유한 세션으로 취급하지 않고 변경하지 않습니다.
- 모드 변경 요청 전에 복구 대상 주소를 디스크에 저장합니다. 복귀 실패 또는 강제 종료 후 앱을 다시 열면 **녹화 복귀 재시도**가 표시됩니다. 복귀 확인 전에는 DVR 작업과 주소 변경을 막습니다.
- 앱 강제 종료·차량 전원 차단·네트워크 단절 시 즉시 복귀는 보장되지 않습니다. 차량 OEM 대시캠에서도 실제 녹화 상태를 확인하세요. `normal` API 응답은 물리 녹화 파일 생성 자체를 증명하지 않습니다.

## 저장과 오류 처리

- 원본 보관 위치: 앱 전용 `files/exports/{normal|emergency|photo}/{파일 식별 해시}/{파일명}`. 완료 후 사용자가 지정한 SAF 폴더에 복사합니다.
- 앱 재시작 후 완료 파일을 다시 표시합니다. Saved 화면은 지정된 SAF 폴더의 실제 파일 목록을 읽습니다.
- 파일은 128 KiB 버퍼로 스트리밍합니다. `.part` 임시 파일과 원 갤러리 방식의 open-ended HTTP Range(`bytes=offset-`)를 사용해 큰 파일의 연결 끊김을 이어받고, 앱 내부에서는 32 MiB 단위로만 읽은 뒤 재연결합니다. Content-Length·Content-Range 및 목록 크기(있는 경우)가 일치한 뒤 최종 이름으로 이동합니다.
- 중복 다운로드는 같은 URL·ID·시간·크기인 기존 완료 파일을 재사용합니다. 다른 분류의 동명 파일은 분리합니다.
- 실패·취소된 임시 파일은 제거합니다. 프로세스 강제 종료 시 남은 임시 파일은 다음 실행 때 정리합니다. 이어받기는 지원하지 않습니다.
- 한 파일 실패 시 나머지 선택 파일은 계속 처리하며 파일별 오류를 표시합니다. 취소 시 이미 완료된 파일은 보관합니다. 취소는 현재 네트워크 읽기가 끝나거나 타임아웃된 뒤 처리되므로 즉시 끝나지 않을 수 있습니다.
- 목록을 조회할 때 DVR `/thumbnail` 응답을 캐시해 각 항목 왼쪽에 썸네일을 표시합니다. 썸네일을 제공하지 않는 차량에서는 `미리보기` 자리표시자가 표시됩니다.
- 큰 파일은 원본 그대로 내보냅니다. 재인코딩·분할·재생·DVR 원본 삭제 기능은 없습니다.
- Saved에서 선택한 파일을 삭제할 수 있습니다. SAF 폴더의 파일은 앱이 임의로 삭제하지 않으며, 앱 내부 원본만 삭제합니다.

## OEM과의 차이

- 카드와 전체화면 재생 흐름, DVR URL 구성, 파일 목록 모드 및 5초 heartbeat는 OEM 갤러리 동작을 기준으로 구현했습니다.
- 재생 엔진은 OEM의 `GalleryVideoView`/IJK 래퍼가 아니라 AndroidX Media3 ExoPlayer입니다. 따라서 플레이어 내부의 코덱·버퍼링·오류 재시도 동작은 완전히 같다고 보장할 수 없습니다.
- OEM은 DVR의 `copyToTfCard` 같은 내부 복사 API를 사용할 수 있지만, Gallery+는 일반 앱 권한으로 HTTP 다운로드 후 SAF 폴더 또는 연결된 USB 폴더에 복사합니다.
- 대용량 다운로드는 OEM처럼 한 스트림을 사용합니다. 스트림이 끊기면 현재 위치부터 open-ended Range(`bytes=offset-`)로 순차 재개합니다. 차량 펌웨어의 세션 만료·응답 제한은 실차 검증이 필요합니다.
- OEM은 system UID와 차량 전용 권한을 사용합니다. Gallery+는 일반 앱이므로 차량 정책과 DVR 접근 권한에 따라 일부 동작이 제한될 수 있습니다.

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
