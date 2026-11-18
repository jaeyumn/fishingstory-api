package kr.fishingstory.inquiry.query.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import kr.fishingstory.common.error.*;
import kr.fishingstory.inquiry.query.dto.*;
import kr.fishingstory.inquiry.query.infrastructure.mybatis.InquiryQueryMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InquiryQueryService {
	private final InquiryQueryMapper mapper;
	public InquiryQueryService(InquiryQueryMapper mapper) { this.mapper = mapper; }
	public Page list(long user, boolean admin, String status, int page, int limit) {
		check(user, admin);
		if (status == null || !List.of("ALL", "OPEN", "CLOSED").contains(status)
				|| page < 0 || page > 10000 || limit < 1 || limit > 50) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var rows = mapper.list(user, admin, status, limit + 1, (long) page * limit);
		return new Page(List.copyOf(rows.subList(0, Math.min(limit, rows.size()))), rows.size() > limit);
	}
	public Detail detail(long user, boolean admin, long id, Long before, int limit) {
		check(user, admin);
		if (limit < 1 || limit > 50 || (before != null && before <= 0)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var inquiry = mapper.detail(user, admin, id);
		if (inquiry == null) throw new ApiException(ErrorCode.NOT_FOUND);
		var rows = mapper.messages(id, before, limit + 1);
		boolean more = rows.size() > limit;
		var messages = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
		return new Detail(inquiry, messages, more ? messages.getLast().messageId() : null);
	}
	private void check(long user, boolean admin) { if (!mapper.allowed(user, admin)) throw new ApiException(ErrorCode.ACCESS_DENIED); }
	public record Page(List<InquiryItem> items, boolean hasNext) { }
	public record Detail(InquiryItem inquiry, List<InquiryMessageItem> messages, Long nextBefore) { }
}
