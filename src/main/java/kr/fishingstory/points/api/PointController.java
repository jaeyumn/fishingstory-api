package kr.fishingstory.points.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.points.query.application.PointQueryService;
import kr.fishingstory.points.query.dto.PointViews.*;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class PointController {
	private final PointQueryService queries;
	public PointController(PointQueryService queries) { this.queries = queries; }
	@Operation(summary = "공개 등록 포인트가 있는 지역", description = "ACTIVE 회원. 수역별 등록 수와 좌표 범위, 최대 200개와 truncated. 지역 자체의 예보를 제공하지 않는다.")
	@GetMapping("/api/fishing-regions")
	public ResponseEntity<Areas> regions(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "ALL") String waterType) {
		return ok(queries.regions(AuthenticatedUser.id(jwt), waterType));
	}
	@Operation(summary = "지역에 속한 공개 포인트 권역", description = "존재하지 않거나 해당 수역의 공개 포인트가 없으면 빈 목록. 권역 자체는 환경 조회 대상이 아니다.")
	@GetMapping("/api/fishing-regions/{regionId}/zones")
	public ResponseEntity<Areas> zones(@AuthenticationPrincipal Jwt jwt, @PathVariable long regionId,
			@RequestParam(defaultValue = "ALL") String waterType) {
		return ok(queries.zones(AuthenticatedUser.id(jwt), regionId, waterType));
	}
	@Operation(summary = "지역·권역·등록 포인트 통합 검색", description = "q는 1~80자. 포인트명/별칭 부분 검색, 지역·권역 결과는 별도 배열. 유형별 최대20개와 잘림 여부. 초성/외부 장소 검색 없음.")
	@GetMapping("/api/fishing-points/search")
	public ResponseEntity<Search> search(@AuthenticationPrincipal Jwt jwt, @RequestParam String q,
			@RequestParam(defaultValue = "ALL") String waterType) {
		return ok(queries.search(AuthenticatedUser.id(jwt), q, waterType));
	}
	@Operation(summary = "공개 등록 포인트 목록", description = "지역·권역·수역은 AND. 이름(C 정렬)·ID 오름차순, 기본20/최대50. 필터 변경 시 cursor 제거. 지도 전체 클러스터 자료가 아니다.")
	@GetMapping("/api/fishing-points")
	public ResponseEntity<Page> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) Long regionId, @RequestParam(required = false) Long zoneId,
			@RequestParam(defaultValue = "ALL") String waterType, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit) {
		return ok(queries.list(AuthenticatedUser.id(jwt), regionId, zoneId, waterType, cursor, limit));
	}
	@Operation(summary = "공개 등록 포인트 상세", description = "숨김/초안/미존재는404. demo=true는 가상 자료. 등록은 낚시 허용·접근 가능·안전 보증이 아니다. 환경 값·즐겨찾기·조황 위치는 제공하지 않는다.")
	@GetMapping("/api/fishing-points/{pointId}")
	public ResponseEntity<Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long pointId) {
		return ok(queries.detail(AuthenticatedUser.id(jwt), pointId));
	}
	private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
