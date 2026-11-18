package kr.fishingstory.terms.query.infrastructure.mybatis;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import kr.fishingstory.terms.query.dto.CurrentTerms;

@Mapper
public interface TermsQueryMapper {

	@Select("""
			SELECT DISTINCT ON (policy_type)
			    terms_id, policy_type, version, title, content,
			    requires_consent, is_required, effective_at, published_at
			FROM fishingstory.terms
			WHERE published_at <= statement_timestamp()
			  AND effective_at <= statement_timestamp()
			ORDER BY policy_type, effective_at DESC
			""")
	List<CurrentTerms> findCurrent();

	@Select("""
			SELECT terms_id, policy_type, version, title, content,
			    requires_consent, is_required, effective_at, published_at
			FROM fishingstory.terms
			WHERE published_at <= statement_timestamp() AND effective_at > statement_timestamp()
			ORDER BY effective_at, terms_id LIMIT #{limit} OFFSET #{offset}
			""")
	List<CurrentTerms> findUpcoming(@Param("limit") int limit, @Param("offset") long offset);
}
