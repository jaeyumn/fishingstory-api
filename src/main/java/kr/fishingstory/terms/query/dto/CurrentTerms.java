package kr.fishingstory.terms.query.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

public record CurrentTerms(
		long termsId,
		String policyType,
		String version,
		String title,
		@Schema(description = "Markdown 원문") String content,
		boolean requiresConsent,
		boolean isRequired,
		OffsetDateTime effectiveAt,
		OffsetDateTime publishedAt) {
}
