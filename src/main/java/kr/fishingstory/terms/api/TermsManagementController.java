package kr.fishingstory.terms.api;

import java.net.URI;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.terms.application.TermsManagementService;
import kr.fishingstory.terms.query.TermsManagementQuery;
import kr.fishingstory.terms.query.dto.ManagedTerms;

@RestController
@RequestMapping("/api/admin/terms")
@SecurityRequirement(name = "bearerAuth")
public class TermsManagementController {
	private final TermsManagementService commands;
	private final TermsManagementQuery queries;
	public TermsManagementController(TermsManagementService commands, TermsManagementQuery queries) {
		this.commands = commands; this.queries = queries;
	}
	@GetMapping
	public ResponseEntity<TermsManagementQuery.Page> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int limit) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(AuthenticatedUser.id(jwt), page, limit));
	}
	@GetMapping("/{id}")
	public ResponseEntity<ManagedTerms> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.detail(AuthenticatedUser.id(jwt), id));
	}
	@PostMapping
	@Operation(summary = "약관 초안 작성", description = "현재 스키마의 이용약관/개인정보처리방침만 지원하며 동의 속성은 유형에 따라 고정된다.")
	public ResponseEntity<Created> create(@AuthenticationPrincipal Jwt jwt, @RequestBody Request request) {
		long id = commands.create(AuthenticatedUser.id(jwt), request.policyType(), request.draft());
		return ResponseEntity.created(URI.create("/api/admin/terms/" + id)).cacheControl(CacheControl.noStore()).body(new Created(id));
	}
	@PutMapping("/{id}")
	@Operation(summary = "미발행 초안 수정", description = "유형은 변경하지 않으며 발행 후에는 수정·삭제·시행 연기를 제공하지 않는다.")
	public ResponseEntity<Void> edit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody Edit request) {
		commands.edit(AuthenticatedUser.id(jwt), id, request.draft());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
	@PostMapping("/{id}/publish")
	@Operation(summary = "약관 발행", description = "expectedUpdatedAt은 검토한 초안의 updatedAt이며 변경되었으면 409다. immediately=true는 현재 시행, false는 미래 시행 시각을 유지한다. 반복 발행은 기존 버전을 보존한다.")
	public ResponseEntity<Void> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody Publication request) {
		commands.publish(AuthenticatedUser.id(jwt), id, request.immediately(), request.expectedUpdatedAt());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
	public record Created(long termsId) { }
	public record Publication(@NotNull Boolean immediately, @NotNull Instant expectedUpdatedAt) { }
	public record Request(String policyType, String version, String title, String content, Instant effectiveAt) {
		TermsManagementService.Draft draft() { return new TermsManagementService.Draft(version, title, content, effectiveAt); }
	}
	public record Edit(String version, String title, String content, Instant effectiveAt) {
		TermsManagementService.Draft draft() { return new TermsManagementService.Draft(version, title, content, effectiveAt); }
	}
}
