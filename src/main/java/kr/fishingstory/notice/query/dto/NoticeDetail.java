package kr.fishingstory.notice.query.dto;

import java.time.Instant;

public record NoticeDetail(long noticeId, String title, String content, boolean pinned, boolean published,
		Instant createdAt, Instant updatedAt) { }
