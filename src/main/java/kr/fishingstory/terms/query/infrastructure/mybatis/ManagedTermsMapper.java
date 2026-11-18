package kr.fishingstory.terms.query.infrastructure.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.*;
import kr.fishingstory.terms.query.dto.ManagedTerms;

@Mapper
public interface ManagedTermsMapper {
	@Select("SELECT EXISTS (SELECT 1 FROM fishingstory.users WHERE user_id=#{id} AND status='ACTIVE' AND role='ADMIN')")
	boolean admin(@Param("id") long id);
	@Select("""
			SELECT terms_id, policy_type, version, title, NULL::text AS content, effective_at, published_at, updated_at
			FROM fishingstory.terms ORDER BY terms_id DESC LIMIT #{limit} OFFSET #{offset}
			""")
	List<ManagedTerms> list(@Param("limit") int limit, @Param("offset") long offset);
	@Select("""
			SELECT terms_id, policy_type, version, title, content, effective_at, published_at, updated_at
			FROM fishingstory.terms WHERE terms_id=#{id}
			""")
	ManagedTerms detail(@Param("id") long id);
}
