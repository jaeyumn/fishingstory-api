package kr.fishingstory.inquiry.query.dto;

import java.time.Instant;

public record InquiryItem(long inquiryId, String title, String status, Instant createdAt, Instant updatedAt) { }
