package kr.fishingstory.terms.query;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import kr.fishingstory.common.error.*;
import kr.fishingstory.terms.query.dto.ManagedTerms;
import kr.fishingstory.terms.query.infrastructure.mybatis.ManagedTermsMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TermsManagementQuery {
	private final ManagedTermsMapper mapper;
	public TermsManagementQuery(ManagedTermsMapper mapper) { this.mapper = mapper; }
	public Page list(long user, int page, int limit) {
		check(user);
		if (page < 0 || page > 10000 || limit < 1 || limit > 50) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var rows = mapper.list(limit + 1, (long) page * limit);
		return new Page(List.copyOf(rows.subList(0, Math.min(limit, rows.size()))), rows.size() > limit);
	}
	public ManagedTerms detail(long user, long id) {
		check(user);
		var row = mapper.detail(id);
		if (row == null) throw new ApiException(ErrorCode.NOT_FOUND);
		return row;
	}
	private void check(long user) { if (!mapper.admin(user)) throw new ApiException(ErrorCode.ACCESS_DENIED); }
	public record Page(List<ManagedTerms> items, boolean hasNext) { }
}
