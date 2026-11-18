package kr.fishingstory.terms.query.dto;

import java.time.Instant;

public record ManagedTerms(long termsId, String policyType, String version, String title,
		String content, Instant effectiveAt, Instant publishedAt, Instant updatedAt) { }
