package kr.fishingstory.inquiry.query.infrastructure.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.*;
import kr.fishingstory.inquiry.query.dto.*;

@Mapper
public interface InquiryQueryMapper {
	@Select("SELECT EXISTS (SELECT 1 FROM fishingstory.users WHERE user_id=#{user} AND status='ACTIVE' AND (NOT #{admin} OR role='ADMIN'))")
	boolean allowed(@Param("user") long user, @Param("admin") boolean admin);
	@Select("""
			SELECT inquiry_id, title, status, created_at, updated_at FROM fishingstory.inquiries
			WHERE (#{admin} OR user_id=#{user}) AND (#{status}='ALL' OR status=#{status})
			ORDER BY updated_at DESC, inquiry_id DESC LIMIT #{limit} OFFSET #{offset}
			""")
	List<InquiryItem> list(@Param("user") long user, @Param("admin") boolean admin, @Param("status") String status,
			@Param("limit") int limit, @Param("offset") long offset);
	@Select("""
			SELECT inquiry_id, title, status, created_at, updated_at FROM fishingstory.inquiries
			WHERE inquiry_id=#{id} AND (#{admin} OR user_id=#{user})
			""")
	InquiryItem detail(@Param("user") long user, @Param("admin") boolean admin, @Param("id") long id);
	@Select("""
			<script>
			SELECT message_id, sender_role, content, created_at FROM fishingstory.inquiry_messages
			WHERE inquiry_id=#{id}
			<if test="before != null">AND message_id &lt; #{before}</if>
			ORDER BY message_id DESC LIMIT #{limit}
			</script>
			""")
	List<InquiryMessageItem> messages(@Param("id") long id, @Param("before") Long before, @Param("limit") int limit);
}
