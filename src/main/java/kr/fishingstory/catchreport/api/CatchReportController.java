package kr.fishingstory.catchreport.api;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.catchreport.command.application.CreateCatchReportCommand;
import kr.fishingstory.catchreport.command.application.CreateCatchReportService;
import kr.fishingstory.catchreport.command.application.ChangeCatchReportService;
import kr.fishingstory.catchreport.query.application.MyCatchReportQueryService;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@Validated
@RestController
@SecurityRequirement(name = "bearerAuth")
public class CatchReportController {
	private final CreateCatchReportService createService;
	private final ChangeCatchReportService changeService;
	private final MyCatchReportQueryService queryService;

	public CatchReportController(CreateCatchReportService createService, ChangeCatchReportService changeService,
			MyCatchReportQueryService queryService) {
		this.createService = createService;
		this.changeService = changeService;
		this.queryService = queryService;
	}

	@Operation(summary = "비공개 조황 등록",
			description = "ACTIVE 회원이 NO_CATCH 또는 마스터/기타 어종이 있는 CAUGHT 조황을 등록한다. 어종별 speciesId와 customName 중 하나를 전달하며 기타 어종은 도감에서 제외한다. 공개범위와 위치 공개범위는 PRIVATE로 고정한다. "
					+ "선택 requestKey(32자리 소문자 hex)를 보내면 같은 회원·같은 키의 재요청은 새 조황을 만들지 않는다. 저장된 내용과 같으면 기존 조황을 201과 빈 collectionEvents로 반환하고, 다르면 409 STATE_CONFLICT다. 수정 요청의 requestKey는 사용하지 않는다.")
	@PostMapping("/api/catch-reports")
	public ResponseEntity<CreateCatchReportService.Result> create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody Request request) {
		var result = createService.create(AuthenticatedUser.id(jwt), request.requestKey(), command(request));
		return ResponseEntity.created(URI.create("/api/catch-reports/me/" + result.reportId()))
				.cacheControl(CacheControl.noStore()).body(result);
	}

	@Operation(summary = "내 조황 목록", description = "ACTIVE 회원 본인의 조황만 낚시 날짜, 생성 시각, ID 역순으로 조회한다. 수역·조과·마스터 어종 필터를 함께 적용할 수 있다. 필터 변경 시 cursor를 비워 첫 페이지부터 조회한다.")
	@GetMapping("/api/catch-reports/me")
	public ResponseEntity<MyCatchReportQueryService.Page> mine(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
			@RequestParam(required = false) @Size(max = 300) String cursor,
			@RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|SEA|FRESHWATER") String waterType,
			@RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|CAUGHT|NO_CATCH") String catchStatus,
			@RequestParam(required = false) @Min(1) Long speciesId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(queryService.find(AuthenticatedUser.id(jwt), cursor, limit, waterType, catchStatus, speciesId));
	}

	@Operation(summary = "내 조황 상세", description = "ACTIVE 회원 본인의 조황만 조회한다.")
	@GetMapping("/api/catch-reports/me/{reportId}")
	public ResponseEntity<kr.fishingstory.catchreport.query.dto.CatchReportItem> detail(
			@AuthenticationPrincipal Jwt jwt, @PathVariable long reportId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(queryService.findOne(AuthenticatedUser.id(jwt), reportId));
	}

	@Operation(summary = "내 조황 수정", description = "본인 조황과 영향받은 도감 기록을 같은 트랜잭션에서 갱신한다.")
	@PutMapping("/api/catch-reports/me/{reportId}")
	public ResponseEntity<Void> update(@AuthenticationPrincipal Jwt jwt, @PathVariable long reportId,
			@Valid @RequestBody Request request) {
		changeService.update(AuthenticatedUser.id(jwt), reportId, command(request));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@Operation(summary = "내 조황 삭제", description = "본인 조황을 삭제하고 영향받은 도감 기록을 재계산한다.")
	@DeleteMapping("/api/catch-reports/me/{reportId}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long reportId) {
		changeService.delete(AuthenticatedUser.id(jwt), reportId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@PatchMapping("/api/catch-reports/me/{reportId}/visibility")
	@Operation(summary = "내 조황 공개범위 변경", description = "기록 내용만 공개하며 사진과 위치는 비공개를 유지한다. 도감 기록은 변경하지 않는다.")
	public ResponseEntity<Void> visibility(@AuthenticationPrincipal Jwt jwt, @PathVariable long reportId,
			@Valid @RequestBody VisibilityRequest request) {
		changeService.changeVisibility(AuthenticatedUser.id(jwt), reportId, request.visibility());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	public record VisibilityRequest(@NotNull @Pattern(regexp = "PRIVATE|PUBLIC") String visibility) { }

	private CreateCatchReportCommand command(Request request) {
		if (request.species() != null && request.species().stream().anyMatch(java.util.Objects::isNull)) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		// Only the command's input checks become 400; later programming errors must remain server errors.
		try {
			return new CreateCatchReportCommand(request.catchStatus(), request.waterType(),
					request.fishingDate(), request.fishingTime(), request.memo(),
					request.species() == null ? List.of() : request.species().stream()
							.map(item -> new CreateCatchReportCommand.SpeciesSelection(item.speciesId(), item.customName(), item.maxLengthCm(), item.maxWeightG()))
							.toList());
		} catch (IllegalArgumentException invalid) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
	}

	public record Request(
			@NotNull @Pattern(regexp = "CAUGHT|NO_CATCH") String catchStatus,
			@NotNull @Pattern(regexp = "SEA|FRESHWATER") String waterType,
			@NotNull LocalDate fishingDate,
			LocalTime fishingTime,
			String memo,
			List<@Valid SpeciesRequest> species,
			@Pattern(regexp = "[0-9a-f]{32}") String requestKey) { }

	public record SpeciesRequest(Long speciesId, String customName, BigDecimal maxLengthCm, BigDecimal maxWeightG) { }
}
