package kr.fishingstory.catchreport.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.catchreport.query.application.SpeciesCatalogService;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class SpeciesCatalogController {
    private final SpeciesCatalogService service;
    public SpeciesCatalogController(SpeciesCatalogService service) { this.service = service; }

    @Operation(summary = "공통 어종 검색 자료", description = "전체 활성 항목·별칭·분류 및 내용 버전. 앱에서 캐시하고 로컬 검색한다.")
    @GetMapping("/api/fish-species/catalog")
    public ResponseEntity<SpeciesCatalogService.Catalog> catalog(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.find(AuthenticatedUser.id(jwt)));
    }
    @Operation(summary = "어종 백과사전 상세", description = "공통 설명·분류·규정. 사용자 기록과 분리하며 규정으로 등록을 차단하지 않는다.")
    @GetMapping("/api/fish-species/{speciesId}")
    public ResponseEntity<SpeciesCatalogService.Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long speciesId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(AuthenticatedUser.id(jwt), speciesId));
    }
}
