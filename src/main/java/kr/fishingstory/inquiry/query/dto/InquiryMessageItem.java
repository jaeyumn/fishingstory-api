package kr.fishingstory.inquiry.query.dto;

import java.time.Instant;

public record InquiryMessageItem(long messageId, String senderRole, String content, Instant createdAt) { }
