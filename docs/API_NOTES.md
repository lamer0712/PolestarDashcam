# DVR API 근거와 구현 범위

2026-09-11, 사용자 제공 분석 문서 및 `갤러리_2.21.20250820P(8fc22987)_10020005.apk`의 클래스 구조를 읽어 확인했습니다. 원본 분석 디렉터리는 수정하지 않았고 OEM 소스나 APK를 새 프로젝트에 복제하지 않았습니다. 아래 JSON은 **APK 모델로 재구성한 예시**이며 실차에서 캡처한 응답이 아닙니다.

## OEM 클래스에서 확인한 계약

| 클래스 | 확인 내용 |
| --- | --- |
| `ecarx.gallery.extendsapi.dvr.http.ApiService` | GET/POST 경로, query 이름 및 JSON request body |
| `MediaDirNetBean` | 최상위 배열 `mediaList` |
| `MediaDir` | `type`, **`mediaType`**, `mediaPath`, `fileCount`, `thumbnail` |
| `FileListNetBean` | 최상위 배열 `fileList` |
| `DvrFile` | **`name`**, `id`, `mediaType`, `size`(long), `dateTime`(long), `duration` 등 |
| `State` | `usable`, `recording`; OEM model declares Boolean, but Polestar 4 vehicle response observed in browser uses `usable:"yes"` and also `parkingRecording`, `isEmmc` |
| `RequestStatus` | `app`, `recording` |
| `ResponseOk` / `ResponseError` | `result=ok` / `error`, `message` |
| `DvrHelper.getAllDvrAlbums` | 분류는 `type`이 아니라 `mediaType`으로 구분 |

```http
GET /status?app=gallery
GET /mediaDirList?app=gallery
GET /filelist?app=gallery&type=normal&startIndex=0&count=50&sort=newest-first&infoLevel=2
GET /filelist?app=gallery&type=emergency&startIndex=0&count=50&sort=newest-first&infoLevel=2
GET /filelist?app=gallery&type=photo&startIndex=0&count=50&sort=newest-first&infoLevel=2
```

```json
{"usable":"yes","recording":"normal","parkingRecording":"off","isEmmc":"TF"}
```

```json
{"mediaList":[{"type":"video","mediaType":"normal","mediaPath":"DCIM/Normal","fileCount":53}]}
```

```json
{"fileList":[{"mediaType":"normal","name":"clip.mp4","id":"42","size":2097152,"dateTime":1789080000,"duration":60}]}
```

다운로드 URL은 `http://198.18.37.20/{mediaPath}/{name}`입니다. 경로와 이름은 raw 값으로 간주하고 URL path를 인코딩합니다. 다른 host나 경로 순회는 허용하지 않고 redirect도 따르지 않습니다. HTTP는 DVR 주소와 로컬 에뮬레이터 테스트 주소에만 허용합니다. JSON의 누락된 필드를 빈 목록으로 처리하지 않고 명시적인 오류로 표시합니다.

파일 개수는 스냅샷마다 바뀔 수 있으므로 `fileCount`를 페이지 종료 조건으로 신뢰하지 않습니다. 반환된 항목 수만큼 `startIndex`를 증가시키고 빈 페이지가 올 때 종료합니다. 서버가 요청보다 작은 페이지를 반환해도 계속 조회할 수 있습니다. 이미 로드된 파일과 완전히 같은 페이지가 반복되면 오류를 표시합니다. 녹화 중 목록이 변하면 중복을 제거하되 모든 시점의 파일을 빠짐없이 조회한다는 보장은 없습니다.

`dateTime`은 초/밀리초 epoch를 휴리스틱으로 표시합니다. 정확한 실차 단위와 `duration` 단위는 미확인이므로 duration은 UI에 노출하지 않습니다. `mediaPath`가 이미 URL 인코딩된 값이거나 예상 외 구조일 경우 실제 응답에 맞춘 추가 조정이 필요합니다.

선택적인 모드 변경은 다음과 같습니다.

```http
POST /status
Content-Type: application/json; charset=utf-8

{"app":"gallery","recording":"enter-file-list"}
```

복귀 요청은 같은 경로에 `{"app":"gallery","recording":"normal"}`이며, 성공 응답 `{"result":"ok"}`와 GET 상태 재확인을 요구합니다. 목록 모드의 응답 상태 문자열은 `in-file-list`입니다.

`copyToTfCard`는 OEM DVR 모듈의 복사 동작으로 보이며 Android에서 선택한 USB 폴더로 내보내는 기능과 동일하다는 근거가 없습니다. 이 앱의 USB 복사는 Android DocumentsContract/SAF로 구현했고 DVR의 삭제·이동·복사 API는 호출하지 않습니다.

실차에서 확인된 status 응답의 `usable`은 문자열 `yes`/`no`입니다. 앱은 Boolean·숫자 0/1·문자열 yes/no·true/false를 허용하고, `parkingRecording`·`isEmmc` 같은 추가 필드는 무시합니다. 일반 앱 UID의 DVR 접근 허용, 내부 라우팅, OEM 갤러리와의 동시 실행 및 실제 녹화 영향은 차량 검증이 필요합니다.
