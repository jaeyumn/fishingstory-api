package kr.fishingstory.notice.query.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.notice.query.dto.NoticeDetail;
import kr.fishingstory.notice.query.dto.NoticeItem;
import kr.fishingstory.notice.query.infrastructure.mybatis.NoticeQueryMapper;

@Service
@Transactional(readOnly = true)
public class NoticeQueryService {
	private final NoticeQueryMapper notices;
	public NoticeQueryService(NoticeQueryMapper notices) { this.notices = notices; }
	public Page list(long userId, boolean admin, int page, int limit) {
		requireAccess(userId, admin);
		if (page < 0 || page > 10000 || limit < 1 || limit > 50) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var fetched = notices.list(admin, limit + 1, (long) page * limit);
		boolean more = fetched.size() > limit;
		return new Page(more ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched), more);
	}
	public NoticeDetail detail(long userId, boolean admin, long id) {
		requireAccess(userId, admin);
		var notice = notices.detail(id, admin);
		if (notice == null) throw new ApiException(ErrorCode.NOT_FOUND);
		return notice;
	}
	private void requireAccess(long userId, boolean admin) {
		if (!notices.allowed(userId, admin)) throw new ApiException(ErrorCode.ACCESS_DENIED);
	}
	public record Page(List<NoticeItem> items, boolean hasNext) { }
}
