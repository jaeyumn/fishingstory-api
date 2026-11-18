package kr.fishingstory.notice.query.infrastructure.mybatis;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import kr.fishingstory.notice.query.dto.NoticeDetail;
import kr.fishingstory.notice.query.dto.NoticeItem;

@Mapper
public interface NoticeQueryMapper {
	@Select("SELECT EXISTS (SELECT 1 FROM fishingstory.users WHERE user_id=#{userId} AND status='ACTIVE' AND (NOT #{admin} OR role='ADMIN'))")
	boolean allowed(@Param("userId") long userId, @Param("admin") boolean admin);

	@Select("""
			SELECT notice_id, title, pinned, published, created_at, updated_at
			FROM fishingstory.notices WHERE (published OR #{admin})
			ORDER BY pinned DESC, notice_id DESC LIMIT #{limit} OFFSET #{offset}
			""")
	List<NoticeItem> list(@Param("admin") boolean admin, @Param("limit") int limit, @Param("offset") long offset);

	@Select("""
			SELECT notice_id, title, content, pinned, published, created_at, updated_at
			FROM fishingstory.notices WHERE notice_id=#{noticeId} AND (published OR #{admin})
			""")
	NoticeDetail detail(@Param("noticeId") long noticeId, @Param("admin") boolean admin);
}
