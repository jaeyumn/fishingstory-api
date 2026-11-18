package kr.fishingstory.terms.api;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

import kr.fishingstory.terms.query.dto.CurrentTerms;
import kr.fishingstory.terms.query.infrastructure.mybatis.TermsQueryMapper;

@RestController
public class TermsController {

	private final TermsQueryMapper termsQueryMapper;

	public TermsController(TermsQueryMapper termsQueryMapper) {
		this.termsQueryMapper = termsQueryMapper;
	}

	@Operation(summary = "현재 약관 조회", description = "인증 없이 조회한다. DB 조회 시점에 공개·시행된 정책의 유형별 최신 버전과 Markdown 전문을 반환한다. "
			+ "해당 버전이 없는 유형은 생략하며 전체가 없으면 items는 빈 배열이다. 이 응답은 가입 허용 여부를 보장하지 않는다.")
	@GetMapping("/api/terms/current")
	public ResponseEntity<CurrentTermsResponse> current() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new CurrentTermsResponse(termsQueryMapper.findCurrent()));
	}

	public record CurrentTermsResponse(List<CurrentTerms> items) {
	}

	@Operation(summary = "시행 예정 약관 조회", description = "인증 없이 발행된 미래 시행본만 조회한다. 가입 필수 동의 대상을 변경하지 않는다. 미발행 초안은 포함하지 않는다.")
	@GetMapping("/api/terms/upcoming")
	public ResponseEntity<UpcomingTermsResponse> upcoming(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int limit) {
		if (page < 0 || page > 10000 || limit < 1 || limit > 50) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var rows = termsQueryMapper.findUpcoming(limit + 1, (long) page * limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new UpcomingTermsResponse(
				List.copyOf(rows.subList(0, Math.min(limit, rows.size()))), rows.size() > limit));
	}
	public record UpcomingTermsResponse(List<CurrentTerms> items, boolean hasNext) { }
}
