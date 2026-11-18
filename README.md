# fishingstory-api

## 2026-11-10 탈퇴 취소·사진 예약 개선

V15의 회원별 Access 세대로 탈퇴 복구 후 이전 토큰을 거절한다. V16은 같은 사진 예약 키로 응답을 재확인하고 만료된 예약을 안전하게 취소할 수 있게 한다. 사진 R2 endpoint는 HTTPS origin이 필요하다. API 전체 `build --rerun-tasks`에서 539개 테스트와 실제 loopback HTTP 78요청이 통과했다. 격리 DB에서 V14→V16 기존 행을 보존했다. [실행 기록](../docs/reviews/Recovery_Validation_2026-11-10.md).

## 2026-11-02 조황 중심 테스트 준비 후 전체 검증

사용자 코드·테스트·빌드·HTTP 요청으로 앱 날씨 노출 제어를 구현하고 API 전체 build --rerun-tasks를 재실행했다. 서버 소스/테스트/Migration 수정 없이65 suite/531개·실패/오류/건너뜀0, 실제 loopback HTTP55요청이 첫 실행에 통과했다(730.85초). 앱 분석·전체217개·숨김 Android debug APK도 통과했다. 개발 DB/운영 적용·실제 외부/기기와 구분하며 기존 컨테이너/설정 유지·임시 자원 종료를 확인했다. [실행 보고서](../docs/reviews/Closed_Test_Visibility_Validation_2026-11-02.md).

## 2026-10-31 등록 포인트 검증 완료

사용자 명시 요청으로 관련26개(131.35초)·전체531개/65 suite 테스트와 API build(732.35초)·실제 HTTP55개가 첫 실행에서 통과했다(실패/오류/건너뜀0). 새 PointHttpTests1개(24요청)만 추가했고 구현 코드·기존 테스트/설정·Migration 수정은 없었다. 비교한 소스29개 해시 동일·기존 개발 컨테이너2개 유지·임시 자원 종료를 확인했다. [실행 보고서](../docs/reviews/Points_Validation_2026-10-31.md)·[자료](../validation/2026-10-31-points/). 개발/운영 DB V14 적용·실제 외부 API·앱/실기기는 미실행이다. 아래 개발 당시 미실행은 후속 검증 전 이력이다.

## 2026-10-31 등록 포인트 DB·조회 API — 코드 작성·실행 미검증

지역/권역/포인트/별칭 V14, ACTIVE 회원용 지역·권역 탐색/검색/목록/상세, 공개·수역·논리 참조·demo 경계를 작성했다. 검색은 유형별20개, 목록은 기본20/최대50·필터별 커서다. API/응답·더미 자료·테스트20개·후속 범위는 [현재 계약](../docs/points/Registered_Points_API.md), 사용자 직접 할 일은 [체크리스트](../docs/points/User_Action_Checklist.md)를 따른다. Flutter 기능 코드는 유지했고 지도/환경 연결/즐겨찾기/조황 좌표는 후속이다. 신규 의존성·설치·생성 DTO·기존 .env·실제 DB·외부 API는 변경/실행하지 않았다. 아래10-30 문서만 작업한 기록은 이전 상태다.

더미 장소는 `dev`와 명시 설정 `app.dev.seed-points=true`에서만 생성/조회하며 기본 false다. 예: `.\gradlew.bat bootRun --args='--spring.profiles.active=dev --app.dev.seed-points=true'`(사용자 서버 실행 시 V14·기존 미적용 Migration 적용 및 dev 더미 저장). prod에서는 설정 true여도 더미를 반환하지 않는다. 실제 운영 포인트 seed는0개이며 예시 좌표/이름을 운영 자료로 사용하지 않는다.

**테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행(Java21·Docker):**

```powershell
.\gradlew.bat test --tests 'kr.fishingstory.points.*' --tests kr.fishingstory.FishingstoryApiApplicationTests --tests kr.fishingstory.conditions.FishingConditionsApiTests
```

## 2026-10-30 포인트·물때날씨 목표 정리

[단일 등록 포인트·지역/권역·환경 연결 목표](../docs/points/Points_Weather_Tides.md)와 [현행 대조·첫 개발 단계](../docs/reviews/Points_Weather_Tides_2026-10-30.md)를 정리했다. 현재는 정적 대표 지역 5곳의 환경 조회이며 포인트 DB/API·지도·즐겨찾기·조황 좌표 저장은 미구현이다. 사용자 선택에 따라 이번 작업은 문서·설계만 완료했고 DB/조회 API는 후속 개발 제안이다. 코드·Migration·의존성·기존 설정은 변경하지 않았다. 포인트 탭 배치·홈 관심 포인트/즐겨찾기 연동·실제 매핑은 미정이다. 이번 문서 작업에서 테스트·빌드·HTTP 검증은 실행하지 않았다.

## 2026-10-30 약관 즉시·예정 발행 시각 보완 — 관련·전체 테스트 통과

사용자 요청으로 약관 생성/수정/발행 시각을 조회·가입 대상과 같은 DB 기준으로 통일했다. 발행은 관리자/약관 잠금·검토 버전 확인 뒤 `statement_timestamp()`를 읽는다. 기존 공개 조건·권한·발행본 불변·스키마/API는 유지하며 새 회귀8개를 작성했다. [개선 기록·최소 실행 명령](../docs/reviews/Terms_Publication_Clock_2026-10-30.md). 후속 명시 요청으로 관련18개·API 전체510개 테스트가 첫 실행에서 모두 통과했다(실패/오류/건너뜀0). 기존 실패2개·신규8개도 통과해 V24-01은 수정·검증 완료다. 패키지 build·앱·별도 HTTP·실기기는 재실행하지 않았다. 아래 실패는 수정 전 이력이다.

## 2026-10-30 도감 등록 문서·앱 UI 1차

[등록 목표162개](../docs/collection/Release1_Species_List.md)와 [UI/UX 목표·현재 구현 차이·다음 단계](../docs/collection/Collection_UIUX_2026-10-30.md)를 정리했다. 현재 Migration 소스 등록은7개다. 사용자 승인 범위에서 목록/검색 실루엣·획득 확인 전 상태·이미지 준비 중·상세 빈 상태·정식 어류 길이 도움말의 앱 1차 코드와 신규8개 테스트를 작성했다. 후속 명시 요청으로 앱 분석·관련15개/전체212개·Android debug APK·실제 HTTP31개가 통과했다. 당시 API 전체 build는502개 중약관2개, 단독5개 중1개가 실패했다([수정 전 보고서](../docs/reviews/Collection_UI_Validation_2026-10-30.md)). 이후 [DB 시각 보완](../docs/reviews/Terms_Publication_Clock_2026-10-30.md) 후 관련18개·전체510개 테스트는 통과했고 패키지 빌드는 재실행하지 않았다. 새 이미지 테스트의 핸들 해제만 보완했으며 실기기/화면 실행은 하지 않았다. 신규155개 등록·안전/측정·기타 이름 전용·수정/삭제 영향 안내는 후속이며 API·Migration·DB·설정·의존성은 변경하지 않았다. 아래10-27 실행 결과는 당시 구현 기준이다.

## 2026-10-27 도감·조황 개편

[최신 확정 정책·API·검수 보류](../docs/collection/Catalog_Redesign.md)를 적용했다. 나의 도감/전체 어종·3열·수역 필터, 공통 catalog/로컬 검색·메모리 캐시, 직접 입력, 분류/규정 안내 구조와 선택 무게(g)·누적 조황 수를 추가했다. V12는 기존 행을 보존하며 실제 어종/분류/별칭/규정 수치를 등록하지 않는다. V13은 조황·도감 무게를 2,000,000g(2톤) 이하로 제한한다. 넘는 기존 행이 있으면 안전한 오류로 중단한다(2026-10-27 격리 DB 테스트 통과, 개발/운영 DB 미적용). `afterEachMigrate__check_cultivar_original.sql` 콜백은 매 Migration 뒤 같은 트랜잭션에서 품종의 원종을 검사한다. 원종이 없거나, 검수된 SPECIES가 아니거나, 활성 품종의 원종이 비활성이면 그 Migration을 되돌린다. 개발/운영 DB 적용·외부 연동·종별 그림 제작은 수행하지 않는다. 새 의존성·생성 DTO 변경은 없다. 이번 실행 결과는 [개발 범위 검증 표](../docs/Local_MVP_Completion.md)를 따른다. 후속 명시 요청으로 Flutter 분석/199개/APK, API 전체 build/498개·실제 HTTP31개가 통과했다. 테스트 가정2건 보완과 실행 한계는 [실행 기록](../docs/reviews/Collection_Validation_2026-10-27.md)을 따른다. 아래 날짜별 상태는 당시 이력이다.

## 2026-10-24 물때날씨 기본 조회 — 로컬 검증 완료

ACTIVE 회원의 /api/fishing-conditions/locations와 /api/fishing-conditions를 추가했다. 기상청 단기/해상·국립해양조사원 고저조를 자료별 상태로 제공한다. 선택 서버 설정 FISHING_KMA_SERVICE_KEY·FISHING_TIDE_SERVICE_KEY(공공데이터포털 Decoding 키); 미설정은 준비 중이다. 기존 .env·V11·의존성은 유지한다. 실제 외부 API는 호출하지 않았다. 사용자 명시 요청으로 API build·전체 473개 테스트·실제 loopback HTTP 8개 검사를 통과했다. 최초 약관 1개 실패 후 단독/전체 재실행 통과 기록과 남은 한계는 [실행 검증](../docs/reviews/Conditions_Validation_2026-10-24.md), 계약은 [물때날씨](../docs/Fishing_Conditions.md)를 따른다.


## 2026-10-20 검수 보완 — 실행 미검증

조황 시간은 마이크로초 절삭·직접 LocalTime JDBC 매핑으로 저장/재시도 정밀도를 맞췄다. 조황 R2 호출은 4초/시도 2초 상한을 설정했다. 신규 V11은 READY 사진 순서 NULL을 금지하며 기존 위반 행이 있으면 데이터를 고치지 않고 중단한다. V1–V10은 유지한다. 회귀 5개(API 시간 1·사진 스키마 3·SDK timeout 1)를 작성하고 최신 Flyway 기대값을 11로 변경했다. 전체 상태·명령은 [완성도 검수](../docs/reviews/Local_Development_Completion_2026-10-20.md)를 따른다.

최소 사용자 실행(Java 21·Docker): `.\gradlew.bat test --tests kr.fishingstory.catchreport.api.CatchReportApiTests --tests kr.fishingstory.catchreport.api.CatchReportImageApiTests --tests kr.fishingstory.CatchReportImagesSchemaTests --tests kr.fishingstory.FishingstoryApiApplicationTests`. 실제 DB 적용/조회·R2·메일·커밋·배포는 수행하지 않았다.

최신 현황은 [개발 범위·검증 표](../docs/Local_MVP_Completion.md)를 따른다. 2026-10-17 사용자 지정 Spring 테스트·Flutter 매거진 2개·분석은 통과 보고가 있다. 아래 날짜별 미실행/실패 문구는 당시 이력이다. 2026-10-20 수정분도 2026-10-24 전체 회귀에 포함됐다. 실제 개발/운영 DB·R2·발행 연동은 별도다.

## 최신 변경: 비밀번호 변경 시도 제한·조황 작성 재시도 키·운영 노출 정리 (2026-10-09)

- 비밀번호 변경은 회원별 최근 15분 5회(성공/실패 합산, 형식 오류 제외)로 제한하고 초과 시 `429 PASSWORD_CHANGE_RATE_LIMITED`와 `Retry-After`를 반환한다. Valkey Lua `reserve-password-change-attempt.lua`를 사용한다. [비밀번호 변경](../docs/auth/Password_Change.md).
- **V9** `catch_reports.request_key`와 `(user_id, request_key)` partial UNIQUE를 추가했다. `POST /api/catch-reports`의 선택 `requestKey`가 같으면 내용이 같을 때 기존 조황(201, 빈 `collectionEvents`)을, 다를 때 409를 반환한다. [조과·도감](../docs/catchreport/Caught_Collection.md), [CATCH_REPORTS](../docs/database/CATCH_REPORTS.md).
- prod 프로필은 springdoc(`/v3/api-docs`, Swagger UI)과 `/test`를 등록하지 않는다. `R2_ENABLED`는 prod에서 true/false를 명시해야 기동한다. 개발 PostgreSQL 포트는 `127.0.0.1`에만 연다. 조황 작성·수정 API는 Command 입력 검증 실패만 400으로 바꾸고, 그 밖의 내부 오류는 500으로 둔다.
- Controller 13곳의 JWT `sub` 파싱을 `auth.api.AuthenticatedUser.id(jwt)`로 통합했다(동작 변경 없음).
- 테스트 작성: `PasswordChangeApiTests` 1개, `CatchReportApiTests` 3개, `CatchReportsSchemaTests` 1개, `ProductionProfileTests` 1개, `ProductionImageSettingTests` 1개, `AuthenticatedUserTests` 1개, `FishingstoryApiApplicationTests` V9 기대값. 테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행(Docker 필요):
  `.\gradlew.bat test --tests kr.fishingstory.auth.api.PasswordChangeApiTests --tests kr.fishingstory.auth.api.AuthenticatedUserTests --tests kr.fishingstory.catchreport.api.CatchReportApiTests --tests kr.fishingstory.CatchReportsSchemaTests --tests kr.fishingstory.ProductionProfileTests --tests kr.fishingstory.ProductionImageSettingTests --tests kr.fishingstory.FishingstoryApiApplicationTests`
  이후 전체 `.\gradlew.bat test`. `ProductionImageSettingTests`는 OS 환경변수에 `R2_ENABLED`가 없어야 의미가 있다.

## 최신 변경: 적대적 검토 보완 (2026-10-06)

조황 메모·공지 본문·어종/도감 검색어에 포함된 NUL을 DB 호출 전에 400/VALIDATION_ERROR로 거절하도록 보완하고 회귀 테스트 4개를 작성했다. 기존 행·도감·공개 상태 보존을 확인하는 테스트를 포함한다. 새 Migration·의존성·설정 변경은 없다. [검토 기록과 최소 실행 명령](../docs/reviews/Adversarial_Review_2026-10-06.md).

테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행. 아래 2026-10-05 통과는 이번 수정 전 결과다.

## 최신 사용자 검증 결과 (2026-10-05)

사용자가 이 저장소의 .\gradlew.bat test 전체 통과를 보고했다. 2026-09-28·30 변경 이후 최신 결과이며 테스트 개수·소요 시간은 제공받지 않았다. 에이전트는 재실행하지 않았다. 아래 개발 시점의 테스트 미실행 표기와 9월의 422개 통과는 이전 이력이다. 별도 빌드·실기기/API/메일/R2 연결·운영 DB Migration 적용은 미확인이다.

낚시이야기 API 서버. 이메일 인증·계정 관리·본인/공개 조황·도감·비공개 사진 API를 작성했다. 아래 기능별 실행 검증 상태를 구분한다.

## 최신 변경: 외부 연동 제외 MVP 보완 (2026-09-30)

문의 접수·대화·사용자/운영자 종료·재개(V8), 공개 조황 AND 필터, 관리자 약관 초안·발행·미래 시행본 사전 조회, 내 정보 role, 민감값을 제외한 요청 로그를 추가했다. 상세 범위·권한·보류 항목은 [개발 정리](../docs/Local_MVP_Completion.md)를 따른다. V1–V7·기존 데이터는 유지하며 실제 Migration 적용·정책 발행·관리자 지정은 하지 않았다.

2026-10-05 사용자 보고로 아래 전체 테스트가 통과했다. 별도 빌드·실제 HTTP 검증은 미확인이다. 향후 재검증 시 JDK 21·Docker를 준비하고 이 저장소에서 실행한다.

```powershell
.\gradlew.bat test
```

신규 InquiryApiTests·TermsManagementApiTests·ApiRequestLogFilterTests와 기존 공개 조황·내 정보·스키마 테스트를 작성/보완했다. Java 의존성 추가는 없고 application.yaml에 로그 MDC 패턴을 추가했다. [문의](../docs/Inquiries.md), [약관](../docs/Terms_Management.md), [로그](../docs/Request_Logging.md).

## 이전 사용자 검증 결과

이전 검증 결과(2026-09-27): 공개 조황 구현 이후 사용자가 Java 전체 테스트 **422개 통과**를 보고했다. 해당 날짜까지의 기능 검증 결과이며 이후 변경을 포함하지 않는다. 에이전트 재실행은 없으며 별도 빌드·실제 HTTP/R2 연동·운영 DB Migration 적용 결과는 미확인이다.

## 최근 변경: 내 조황 필터·사진 재시도 (2026-09-23 작성)

`GET /api/catch-reports/me`에 수역·조과·마스터 어종 필터를 추가했다. 기존 무필터 요청과 커서를 유지하며, 필터 변경 시 첫 페이지부터 다시 조회한다. 상세는 [내 조황 필터](../docs/catchreport/Own_Report_Filters.md)를 따른다.

사진 완료 재요청은 READY에 대해 204를 반환한다. DB 실패 후 재시도를 위해 임시 파일을 예약 만료까지 유지하고, 사진·조황 삭제와 예약 취소의 정리 행도 예약 만료 뒤에 제거한다. 기존 사진 DELETE는 PENDING 예약 취소도 지원한다. 상세는 [사진 계약](../docs/catchreport/Private_Photos.md)을 따른다. Migration·환경변수·의존성 변경은 없다.

사용자 실행 명령(Docker 필요):

```powershell
.\gradlew.bat test --tests kr.fishingstory.catchreport.api.CatchReportApiTests --tests kr.fishingstory.catchreport.api.CatchReportImageApiTests
```

테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행. 이전 단계의 사용자 전체 테스트 통과는 이 변경의 검증 결과가 아니다.

## 준비

- JDK 21, Docker Engine 또는 실행 중인 Docker Desktop
- API 저장소 루트에서 명령 실행
- `.env`가 없을 때만 `.env.example`을 복사하고 `POSTGRES_PASSWORD` 입력
- `.env`는 따옴표 없는 `KEY=value` 형식 사용. 로컬 비밀번호는 영문·숫자를 사용하면 Compose와 Java properties의 이스케이프 차이를 피할 수 있다.
- 기존 DB 볼륨이 있다면 처음 생성할 때 사용한 계정 설정을 유지한다. `.env` 변경만으로 DB 계정이 변경되지는 않는다.

## Docker로 전체 로컬 실행 (PowerShell)

API 실행 JAR는 멀티 스테이지 `Dockerfile` 안에서 생성하며 최종 이미지에는 Java 21 JRE와 JAR만 포함한다. Compose가 `dev` 프로필과 컨테이너 내부 DB·Valkey 주소를 주입하므로 IntelliJ 실행 설정은 필요하지 않다.

```powershell
docker compose up --build
```

코드를 변경하면 위 명령으로 API 이미지를 다시 빌드한다. 백그라운드 실행은 `docker compose up -d --build`, API 로그 확인은 `docker compose logs -f api`, 종료는 `docker compose down`을 사용한다. `down`은 컨테이너와 네트워크만 제거하며 이름 있는 DB·Valkey 볼륨은 유지한다. API가 설정이나 Migration 오류로 종료되면 자동 재시작하지 않으므로 로그의 최초 원인을 수정한 뒤 다시 시작한다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

정상 응답은 `{"status":"UP"}`이다. API는 호스트의 loopback `8080`에만 공개한다. `.env`는 이미지에 포함되지 않고 Compose가 실행 시 컨테이너에 전달한다.

## JVM에서 로컬 실행 (PowerShell)

```powershell
docker compose up -d postgres valkey
.\gradlew.bat bootRun --args='--spring.profiles.active=dev'
```

`dev` 프로필만 `.env`를 읽는다. `POSTGRES_*`로 localhost 연결을 구성하며 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`가 있으면 각각 우선한다. 운영은 `prod` 프로필과 세 DB 환경변수를 직접 주입한다. `.env` 원문은 커밋하거나 로그로 출력하지 않는다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

종료는 실행 터미널에서 Ctrl+C. DB·Valkey만 중지하려면 `docker compose stop postgres valkey`를 사용한다.

## DB와 Migration

- Flyway는 앱 시작 시 `src/main/resources/db/migration/`의 SQL을 적용한다.
- 앱 테이블과 Migration 이력은 `fishingstory` 스키마, PostGIS 확장은 `public` 스키마에 둔다.
- 연결 검색 경로는 `fishingstory, public`이며 Hibernate는 `ddl-auto=validate`로 검증만 수행한다.
- `V1__enable_postgis.sql`은 PostGIS 확장과 회원·인증·약관 관련 네 테이블을 생성한다. `V2__create_catch_reports.sql`은 `catch_reports`와 내 목록 정렬 인덱스를, `V3__create_fish_species_and_collection.sql`은 어종 Master·조황 어종·도감 Projection과 초기 7종을, `V4__create_catch_report_images.sql`은 사진 메타데이터를 생성한다. `V5__allow_custom_catch_report_species.sql`은 기존 행을 유지하면서 기타 어종 이름과 출처 제약을 추가한다. `V6__create_notices.sql`은 공지사항 테이블과 공개 목록 정렬 인덱스를 추가한다. `V7`은 공개 조황 정렬 partial index, `V8`은 문의·메시지, `V9`는 조황 작성 재시도 키(`request_key`)와 회원별 partial UNIQUE를 추가한다.
- Compose의 개발 PostgreSQL은 API·Valkey와 같이 `127.0.0.1:${POSTGRES_PORT}`에만 연다(2026-10-09). 기존 컨테이너는 `docker compose up -d postgres`로 재생성하면 적용되며 `postgres_data` 볼륨과 데이터는 유지된다. 다른 기기에서 DB에 직접 접속하는 구성은 지원하지 않는다.
- 실행 계정에는 앱 스키마 생성 권한이 필요하다. PostGIS가 없는 DB에서는 확장 설치 권한도 필요하므로 운영 DB 제공자의 권한 구성을 먼저 확인한다.
- 기존 V1이 적용된 DB에는 과거 V1 수정으로 체크섬 불일치가 발생할 수 있으므로 자동 repair나 데이터 삭제를 하지 않는다. 조황 스키마부터는 적용 이력을 보존하도록 새 Migration을 추가한다. 운영 적용 SQL 수정, 임의의 baseline 설정, 데이터 볼륨 삭제로 오류를 우회하지 않는다.

## 테스트와 빌드

비공개 조황 생성·어종·도감 Projection·내 목록 및 스키마 단독 검증(코드 작성 완료, 실행 미검증):

```powershell
.\gradlew.bat test --tests kr.fishingstory.catchreport.api.CatchReportApiTests --tests kr.fishingstory.CatchReportsSchemaTests --tests kr.fishingstory.FishCollectionSchemaTests
```

`POST /api/catch-reports`는 ACTIVE 회원의 `NO_CATCH` 또는 Master/기타 어종을 선택한 `CAUGHT` 기록을 PRIVATE로 생성하며 도감 Projection을 같은 트랜잭션에 반영한다. `GET /api/fish-species`는 수역별 활성 어종을 조회하고, `GET /api/catch-reports/me`는 본인 기록과 선택 어종을 낚시 날짜 최신순의 커서로 조회한다. 본인 상세·수정·삭제 `GET/PUT/DELETE /api/catch-reports/me/{reportId}`와 도감 재계산, 비공개 사진 예약·완료·조회·순서 변경·제거도 작성했다. 사진 API는 비공개 R2 버킷과 `R2_ENABLED=true` 및 `R2_ENDPOINT`·`R2_ACCESS_KEY_ID`·`R2_SECRET_ACCESS_KEY`·`R2_BUCKET_NAME` 설정이 필요하다. 사진·위치 공개는 포함하지 않는다. 상세는 `../docs/catchreport/Private_Photos.md`를 함께 따른다.

2026-09-27: `PATCH /api/catch-reports/me/{reportId}/visibility`, 공개 목록 `GET /api/catch-reports`, 공개 상세 `GET /api/catch-reports/{reportId}`를 작성했다. 공개 조회도 ACTIVE 회원 인증을 요구한다. 도감은 공개범위 변경으로 재계산하지 않으며 비공개 전환/삭제 이후 새 공개 조회는 차단한다. V7은 공개 정렬 인덱스만 추가한다. 상세·사용자 검증 명령은 [공개 조황](../docs/catchreport/Public_Reports.md). 테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행.

내 도감 조회 단독 검증(코드 작성 완료, 실행 미검증):

```powershell
.\gradlew.bat test --tests kr.fishingstory.collection.api.FishCollectionApiTests
```

`GET /api/fish-collection/me`는 전체 활성 Master 기준 진행률(구형 클라이언트 호환 필드, 2026-10-27 이후 앱은 표시하지 않음)과 필터된 도감 목록(`reportCount`·`maxWeightG`·`lastFishingDate` 포함)을 반환한다. 획득한 비활성 어종은 목록에 보존하지만 진행률에서는 제외한다. `GET /api/fish-collection/me/{speciesId}`는 획득 기록과 본인의 해당 어종 조황 최신 20건을 반환한다. 상세 계약은 `../docs/collection/My_Collection.md`를 따른다.

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

USER_AUTH 제약만 검증할 때는 다음 명령을 사용한다.

```powershell
.\gradlew.bat test --tests kr.fishingstory.UserAuthSchemaTests
```

테스트는 Spring Boot가 관리하는 별도의 PostGIS Testcontainer를 사용한다. `test` 프로필에서는 로컬 `.env`를 가져오지 않으며 개발 DB와 분리된 임시 DB에 Migration을 적용한다. Docker가 필요하고 최초 실행 시 이미지를 내려받을 수 있다.

검증 대상은 Migration 적용 이력, 기본 스키마, SRID 4326 geography 거리 연산과 USERS의 필수값·닉네임 중복 및 형식·권한·탈퇴 상태 제약이다. 결과는 `build/reports/tests/test/index.html`, 실행 JAR는 `build/libs/`에 생성된다. Unix에서는 `./gradlew`를 사용한다.

USER_AUTH는 인증 방식별 필수값·금지값, 회원당 로그인 수단 하나, EMAIL 계정에만 적용되는 이메일 중복 제한, 소셜 제공자별 식별자 중복 제한을 검증한다. 소셜 이메일 중복·변경·NULL 저장도 확인한다. 이메일 가입의 Argon2id 해싱·인증코드/가입 인증·회원/약관 참조 검증은 구현돼 있다. 소셜 제공자 응답 검증과 가입 연결은 아직 미구현이다.

설정 참고: [Spring 외부 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html), [Testcontainers 연동](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html).

TERMS만 검증할 때는 `.\gradlew.bat test --tests kr.fishingstory.TermsSchemaTests`를 사용한다.
TERMS 테스트는 Markdown 원문 저장, 유형별 버전·시행 시각 UNIQUE, 필수값과 공백값, 동의 속성 조합, 공개 시각 경계를 검증한다. 현재/미래 시행 버전 조회 및 관리자 초안·발행 API를 작성했으며 발행본은 API에서 수정·삭제하지 않는다. 2026-09-30 관리 기능 추가분은 실행 미검증이다.
동의 이력만 검증할 때는 `.\gradlew.bat test --tests kr.fishingstory.UserTermsAgreementHistorySchemaTests`를 사용한다.
동의 이력 테스트는 필수값·허용 action·사건 ID 중복 제한, 같은 버전의 동의/철회/재동의 보존과 조회 정렬을 확인한다. 참조·동의 대상 검증, 이력 수정/삭제 차단, 중복 요청·동시성 처리와 가입 트랜잭션은 아직 구현하지 않았다.
## 현재 약관 조회

`GET /api/terms/current`는 인증 없이 현재 공개·시행된 약관을 조회한다. OpenAPI는 `/v3/api-docs`, Swagger UI는 `/swagger-ui.html`에서 확인한다(dev·test만 인증 없이 허용). prod 프로필은 두 경로와 `/test`를 등록하지 않아 로그인 회원에게도 404다(2026-10-09). 구체적인 응답 계약은 Controller에서 생성한다.

단독 검증: `.\gradlew.bat test --tests kr.fishingstory.CurrentTermsApiTests`.
MyBatis Starter 4.1.0으로 조회 DTO를 반환하고 springdoc 3.1.1로 명세를 생성한다. 테스트는 MockMvc에서 실제 PostGIS Testcontainer의 MyBatis 쿼리까지 검증한다. 가입 허용 여부·최종 동의 검증·약관 발행 기능은 포함하지 않는다.
## 이메일 발송 한도 (Valkey)

로컬 Compose는 `valkey/valkey:8.1.10`을 loopback 포트에 실행한다. `VALKEY_HOST`·`VALKEY_PORT` 기본값은 localhost·6379이며 인증 서버에는 `VALKEY_PASSWORD`를 지정한다. 로컬 Compose에는 비밀번호를 설정하지 않는다. 기존 `.env`는 변경하지 않아도 기본 포트로 사용할 수 있다. Valkey까지 중지하려면 `docker compose stop postgres valkey`를 사용한다.

단독 검증: `.\gradlew.bat test --tests kr.fishingstory.auth.infrastructure.valkey.EmailSendQuotaTests`.
별도의 Valkey Testcontainer로 원자적 예약·확정·반환, 제한 시간·동시 요청을 검증한다. 이 단계에는 공개 발송 API나 실제 메일 전송이 없다. Valkey가 실행되지 않으면 추가된 Redis health 항목이 DOWN이 될 수 있다.

예약 저장소는 단일 Valkey 인스턴스를 전제로 한다. Compose의 AOF·noeviction은 로컬 구성이며 운영 영속성·복구 보장을 의미하지 않는다. 설정한 2초 연결/명령 제한시간은 Valkey 호출용이며 메일 제공자 호출 제한시간과 별개다.
## Resend 연동

기존 dev `.env` 또는 실행 환경에 `RESEND_API_KEY`, `MAIL_FROM_EMAIL`, `MAIL_FROM_NAME`을 설정한다. 기존 파일은 덮어쓰지 않는다. 세 값은 ResendEmailSender에서 읽으며 발신자는 `"이름" <주소>`로 구성한다. 값이 없으면 앱의 다른 기능은 시작할 수 있지만 발송 호출은 명시적으로 실패한다. 키·코드·응답 원문을 로그로 출력하지 않는다.

단독 검증: `.\gradlew.bat test --tests kr.fishingstory.mail.ResendEmailSenderTests --tests kr.fishingstory.auth.application.ReservedEmailDeliveryTests`.
Java HTTP 클라이언트로 Resend에 연결하며 별도 SDK는 없다. 테스트는 loopback HTTP 서버와 테스트용 키를 사용한다. 실제 자격증명·도메인·수신함 배달은 검증하지 않았다.

메일 전송 어댑터와 예약 결과 처리는 구현했지만 가입 인증코드 생성·저장·내부 발송 연결은 작성했다. 공개 인증메일 발송·코드 검증 API와 가입 인증 상태 발급도 작성했으며 실행 미검증이다. 상세 범위는 상위 `docs/auth/Resend_Email_Delivery.md`를 참고한다.
## 가입 인증코드 저장 (코드 작성 완료, 실행 미검증)

6자리 코드 생성, Valkey 저장 시점부터 5분 TTL, 새 코드 교체, 명확한 발송 실패 시 현재 작업의 코드만 폐기하는 내부 흐름을 작성했다. 공개 발송·코드 검증 API와 가입 인증 상태 발급 코드는 작성했으며 실행 미검증이다.
사용자 실행 명령: `.\gradlew.bat test --tests kr.fishingstory.auth.infrastructure.valkey.SignupEmailCodeStoreTests --tests kr.fishingstory.auth.application.SignupEmailCodeDeliveryTests`.
이번 단계의 테스트·빌드·HTTP 검증은 실행하지 않았다. 테스트 결과와 무관하게 기존 개발 DB의 V1 이력을 자동 변경하지 않는다.
## 가입 인증메일 발송 API (실행 미검증)

`POST /api/auth/signup/email-code`를 연결했다. 요청·응답 계약은 Swagger UI의 Controller 명세를 참고한다. 실제 호출하면 설정된 Resend로 메일을 보내므로 자동 검증에는 실제 키를 사용하지 않는다.

사용자 실행 명령: `.\gradlew.bat test --tests 'kr.fishingstory.auth.*' --tests kr.fishingstory.mail.ResendEmailSenderTests`.
테스트는 모의 서비스/발송자·테스트용 HTTP 서버·격리 Valkey를 사용한다. 에이전트는 테스트·빌드·HTTP 검증을 실행하지 않았다.

현재 전달 헤더는 무시하고 실제 연결 IP로 집계한다. 프록시 운영 배포 전에는 신뢰 프록시 설정을 별도로 완료해야 한다. 기존 개발 DB에 이전 V1이 적용되어 있다면 기존 체크섬 주의사항도 그대로 적용된다.
## 가입 코드 검증 (실행 미검증)

`POST /api/auth/signup/email-code/verify`: 이메일과 6자리 코드 검증 후 가입 토큰·만료시각을 반환한다. 요청·응답 계약은 Controller의 OpenAPI가 원본이다. 실패 5회 시 코드 폐기, 성공 시 코드 일회성 소비와 1시간 고정 TTL의 이메일 바인딩 토큰을 발급한다. 토큰 원문은 Valkey에 저장하지 않는다.

사용자 실행 명령: `.\gradlew.bat test --tests 'kr.fishingstory.auth.*' --tests kr.fishingstory.mail.ResendEmailSenderTests`.
이번 단계의 테스트·빌드·HTTP 검증은 실행하지 않았다. 토큰 조회·소비를 아래 최종 이메일 회원 생성 흐름에 연결했으며 실행 미검증이다.
## 공통 오류 처리 (실행 미검증)

`common/error`의 ErrorCode enum이 HTTP 상태·메시지를 정의하고, GlobalExceptionHandler가 ApiException과 MVC 예외를 ErrorResponse로 반환한다. 입력 오류에는 필드 정보를 추가하며 불필요한 null 필드는 생략한다. 예외 원문·입력값은 노출하지 않는다. 기존 인증 오류 code는 유지하되 예상하지 못한 예외는 500/INTERNAL_SERVER_ERROR, 저장소 연결·시간 초과는 503/SERVICE_UNAVAILABLE로 구분한다.

사용자 실행 명령: `.\gradlew.bat test --tests 'kr.fishingstory.common.error.*' --tests 'kr.fishingstory.auth.*' --tests kr.fishingstory.mail.ResendEmailSenderTests`.
테스트 코드만 작성·수정했으며 실행하지 않았다.

## 최종 이메일 회원 생성 (실행 미검증)

Valkey에서 이메일에 바인딩된 인증 토큰을 삭제 없이 확인한 뒤 회원·인증정보·약관 동의를 하나의 DB 트랜잭션에 저장한다. DB 실패 시 남은 인증 시간 안에서 재시도하고, 성공 시 커밋 후 Valkey 토큰을 삭제한다. 삭제 실패나 동시 요청의 중복 가입은 기존 EMAIL 계정의 이메일 UNIQUE로 막는다. 별도 토큰 사용 기록 테이블·JPA 코드·전용 테스트는 제거했다.

`POST /api/auth/signup/email`을 작성했다. 요청·응답 원본은 EmailSignupController와 EmailSignupCommand의 OpenAPI다. 이메일 인증·현재 이용약관 동의·만 14세 이상 확인·닉네임·비밀번호를 검증하고 회원·인증정보·AGREED 이력을 함께 저장한다. 201은 회원 생성 완료이며 로그인 토큰은 발급하지 않는다. 비밀번호는 공백 제거·정규화 없이 8~128자, 영문·숫자 포함이며 확인 값과 같아야 한다.

JPA 모델·Snowflake ID·Argon2id 해싱을 연결했다. 해싱 전과 DB 저장 직전에 Valkey 인증을 조회하고, 커밋 후에만 삭제한다. 삭제 실패는 성공 응답을 바꾸지 않으며, 중복 요청은 DB UNIQUE가 차단한다. 기존 계정을 덮어쓰거나 가입 토큰만으로 로그인하지 않는다. 상세 경계와 테스트는 상위 `docs/auth/Signup_Member_Creation.md`를 참고한다.

V1·개발 DB·기존 `.env`는 변경하지 않았다. 발행된 현재 이용약관이 없으면 가입을 거절한다. 약관 전문의 실제 발행 데이터는 아직 제공하지 않는다.

`SNOWFLAKE_WORKER_ID`는 0~1023이며 prod에서 필수, 개발·테스트 기본값은 단일 인스턴스용 0이다. 동시 프로세스마다 다른 값을 지정하고 시계 역행 상태에서 같은 worker ID를 재사용하지 않는다. 자동 worker 할당·재시작 시각 영속화는 없다. `PASSWORD_ARGON2_MEMORY_KIB`(19456), `PASSWORD_ARGON2_ITERATIONS`(2), `PASSWORD_ARGON2_PARALLELISM`(1)으로 해싱 비용을 설정한다. 의존성은 Boot가 관리하는 Spring Security crypto와 Bouncy Castle 1.85.2를 추가했으며 이후 로그인 단계에서 OAuth2 Resource Server Security Starter를 추가했다.

이번 단계 사용자 실행 명령(Docker 필요): `.\gradlew.bat test --tests 'kr.fishingstory.auth.*' --tests 'kr.fishingstory.common.id.*'`.
테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행.

비파괴 조회 사용자 실행 명령: `.\gradlew.bat test --tests kr.fishingstory.auth.infrastructure.valkey.SignupVerificationStoreTests`.

## 이메일 로그인·Refresh 재발급

이메일 로그인·JWT 인증·최초 Refresh 세션·개발용 임시 약관 코드는 작성되어 있으며, 2026-09-16 사용자가 이 단계까지 전체 테스트 BUILD SUCCESSFUL을 보고했다. 상세는 ../docs/auth/Email_Login.md를 참고한다.

POST /api/auth/refresh는 Authorization 헤더 없이 JSON refreshToken을 받아 현재 회원 상태/권한 확인 후 Access(15분)·Refresh(30일)를 교체한다. sessionId는 유지하고 기존 Refresh는 폐기한다. 동시 요청은 하나만 성공하며 응답 유실 시 재로그인이 필요하다. 상세 계약·실패 경계는 ../docs/auth/Token_Refresh.md와 Controller OpenAPI를 따른다. 현재 세션 로그아웃은 아래 구현을 참고한다. 전체 세션 삭제는 아직 없다.

이번 Refresh 변경의 사용자 실행 명령(Docker 필요): `.\gradlew.bat test --tests 'kr.fishingstory.auth.*'`.
테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행.

Refresh 변경 후 사용자 테스트 성공 보고를 받았다. 실행 범위·개수·별도 build·HTTP 검증은 확인하지 않았고 에이전트는 실행하지 않았다.

## 현재 기기 로그아웃 (실행 미검증)

POST /api/auth/logout은 유효한 Access Bearer JWT의 회원·세션 ID로 현재 Refresh 세션만 삭제한다. 본문은 필요하지 않으며 정상 또는 이미 종료된 세션은 204/no-store다. 다른 기기 세션은 유지한다. Access 만료 시 먼저 Refresh가 필요하며 로그아웃 전 Access는 원래 만료까지 최대 15분 유효하다.

기존 세션 인덱스와 Lua로 재발급 경합을 확인하고 최대 3회 시도한다. 반복 경합·저장소 연결 장애는 503이며 성공으로 처리하지 않는다. 계약과 한계는 ../docs/auth/Logout.md를 참고한다.

사용자 실행 명령(Docker 필요): `.\gradlew.bat test --tests 'kr.fishingstory.auth.*'`.
테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행.

## 내 정보 조회 (실행 미검증)

GET /api/users/me는 유효한 Access Bearer JWT의 회원 ID로 본인 정보만 조회한다. 현재 ACTIVE 이메일 계정의 userId(int64)·nickname·provider·email을 반환하며 Cache-Control은 no-store다. 탈퇴 유예·회원 삭제·인증정보 없음·소셜 계정은 403/ACCESS_DENIED다. MyBatis 단일 조회를 사용한다. 상세는 ../docs/auth/Current_User.md를 참고한다.

사용자 실행 명령(Docker 필요): `.\gradlew.bat test --tests kr.fishingstory.user.api.CurrentUserApiTests`.
테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행.

## 내 닉네임 변경 (실행 미검증)

PATCH /api/users/me/nickname은 Access Bearer JWT의 회원 본인 닉네임을 가입과 같은 정규화·문자·길이·대소문자 무시 중복 규칙으로 변경한다. 마지막 직접 설정 후 30일이 지나야 다른 값으로 바꿀 수 있으며 제한 응답은 409/NICKNAME_CHANGE_TOO_SOON과 남은 초를 제공한다. 회원 행 쓰기 잠금으로 같은 회원의 동시 변경을 직렬화한다.

같은 표시값 재요청은 설정 시각을 연장하지 않는 멱등 성공이다. 다만 `nickname_changed_at`이 없는 자동 닉네임은 최초 직접 설정으로 기록한다. 새 Migration·환경변수·의존성은 없다. 상세 계약은 ../docs/auth/Nickname_Change.md를 참고한다.

사용자 실행 명령(Docker 필요): `.\gradlew.bat test --tests kr.fishingstory.user.api.NicknameChangeApiTests`.
테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행.

## 이메일 비밀번호 변경 (사용자 테스트 통과)

PATCH /api/users/me/password는 ACTIVE 이메일 회원의 현재 비밀번호를 확인하고 가입과 같은 규칙으로 새 Argon2id 해시를 저장한다. 현재 값과 같은 새 비밀번호는 거절한다. 로그인과 변경은 같은 회원 행 잠금을 사용해 옛 비밀번호 로그인과 직렬화한다.

성공 전에 해당 회원의 모든 Refresh 세션을 Lua로 삭제하며 성공한 앱도 로컬 인증정보를 지우고 새 비밀번호 로그인을 요구한다. 기존 Access는 블랙리스트가 없어 원래 만료까지 최대 15분 유효할 수 있다. 새 Migration·환경변수·의존성은 없다. 상세 계약은 ../docs/auth/Password_Change.md를 참고한다.

사용자 실행 명령(Docker 필요): `.\gradlew.bat test --tests kr.fishingstory.auth.api.PasswordChangeApiTests --tests kr.fishingstory.auth.infrastructure.valkey.LoginSessionStoreTests`.
2026-09-19 사용자가 PasswordChangeApiTests·LoginSessionStoreTests·NicknameChangeApiTests 실행 성공을 보고했다. 에이전트가 직접 실행한 결과가 아니며 HTTP 통합 흐름은 별도다.

## 이메일 비밀번호 재설정 (실행 미검증)

로그아웃 상태에서 재설정 메일 발송, 코드 검증, 새 비밀번호 설정의 공개 API 3개를 작성했다. 발송 응답은 계정 존재 여부와 제공자 결과를 노출하지 않으며, 코드·토큰은 가입 목적과 분리한다. 최종 토큰은 한 번만 소비되고 성공 시 모든 Refresh 세션을 종료한다. 기존 Access는 최대 15분 남을 수 있다.

상세 계약과 사용자 실행 명령은 ../docs/auth/Password_Reset.md를 참고한다. 새 Migration·환경변수·의존성은 없다.

테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행.

## 회원 탈퇴 신청·30일 유예·취소 (실행 미검증)

`POST /api/users/me/withdrawal`은 명시적 확인 후 회원을 탈퇴 유예 상태로 전환하고 모든 Refresh 세션을 종료한다. 반복 요청은 최초 신청 시각을 연장하지 않는다. 로그인·Refresh와 일반 사용자 기능은 유예 계정을 거절한다.

로그인 화면에서는 취소 전용 이메일 발송·코드 검증·최종 취소 API로 30일 미만 동안 ACTIVE 상태를 복구한다. 가입·비밀번호 재설정과 코드·토큰 목적을 분리하며 계정 상태를 발송 응답과 한도 동작으로 노출하지 않는다. 실제 최종 삭제·공개 콘텐츠 익명화 작업은 아직 구현하지 않았다. 새 Migration·환경변수·의존성은 없다.

상세 계약과 사용자 실행 명령은 ../docs/auth/Withdrawal.md를 참고한다.

테스트·빌드·HTTP 검증 미실행 — 사용자 직접 실행.

- 2026-09-27: [기타 어종 직접 입력](../docs/catchreport/Custom_Species.md) DB/API/테스트 코드를 추가했다. V5 Migration은 기존 데이터를 보존한다. 기타 어종의 speciesId는 null이므로 갱신된 Flutter 앱과 함께 사용한다. 실행 미검증.

- 2026-09-27: V6 `notices`와 [공지사항 API](../docs/Notices.md)를 작성했다. ACTIVE 회원의 공개 목록·상세, ADMIN 토큰과 DB 현재 ACTIVE/ADMIN을 함께 검증하는 관리 목록·상세·작성·수정·공개/고정을 지원한다. 관리자 권한 변경·실제 공지 발행·실행 검증은 하지 않았다.

## Studio 매거진 추가 — 실행 검증 대기 (2026-10-17)

V10 매거진·이력·전용 이미지와 관리자 수신/공개/중단, 회원 조회 코드를 추가했다. MAGAZINE_R2_ENABLED 기본 false이며 기존 조황 R2_ENABLED와 독립이다. 실제 DB 적용·R2·발행·테스트·빌드는 실행하지 않았다. 계약과 사용자 실행 명령은 [매거진 연동](../docs/Magazines_Studio.md)을 따른다. 기존 사용자 변경을 보존했으며 커밋하지 않았다.

## 2026-10-17 사용자 매거진 실행 결과

사용자가 MagazineApiTests와 FishingstoryApiApplicationTests를 지정한 Gradle test의 BUILD SUCCESSFUL(1분 27초) 출력을 제공했다. 해당 범위의 컴파일·격리 DB 테스트 통과를 사용자 실행 결과로 기록한다. 전체 API 테스트·실제 개발 DB 적용·R2·발행 결과는 아니다. Flutter analyze는 magazine_repository.dart의 curly_braces_in_flow_control_structures info 1건을 보고했다. 해당 if에 중괄호를 추가했으며 에이전트 재실행은 하지 않았다. 수정 후 analyze와 flutter test test/features/magazine 결과는 아직 미확인이다.
