package kr.fishingstory.notice.query.dto;

import java.time.Instant;

public record NoticeItem(long noticeId, String title, boolean pinned, boolean published,
		Instant createdAt, Instant updatedAt) { }
