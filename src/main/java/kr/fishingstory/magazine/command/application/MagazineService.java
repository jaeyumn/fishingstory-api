package kr.fishingstory.magazine.command.application;

import java.time.Instant;
import java.util.*;
import java.io.*;
import java.security.MessageDigest;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import kr.fishingstory.common.error.*;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;
import kr.fishingstory.magazine.command.domain.Magazine;
import kr.fishingstory.magazine.command.infrastructure.jpa.MagazineStore;
import kr.fishingstory.magazine.query.dto.MagazineView;
import kr.fishingstory.magazine.query.dto.MagazineView.ImageRef;
import kr.fishingstory.magazine.query.infrastructure.mybatis.MagazineQueryMapper;
import kr.fishingstory.magazine.image.MagazineImageStorage;

@Service
public class MagazineService {
    public static final int MAX_IMAGE=5*1024*1024;
    private final MagazineStore store;private final MagazineQueryMapper query;private final UserCommandStore users;
    private final JdbcTemplate jdbc;private final SnowflakeIdGenerator ids;private final ObjectMapper json;
    private final ObjectProvider<MagazineImageStorage> images;
    public MagazineService(MagazineStore store,MagazineQueryMapper query,UserCommandStore users,JdbcTemplate jdbc,
            SnowflakeIdGenerator ids,ObjectMapper json,ObjectProvider<MagazineImageStorage> images) {
        this.store=store;this.query=query;this.users=users;this.jdbc=jdbc;this.ids=ids;this.json=json;this.images=images;
    }
    private void admin(long id){var user=users.lock(id).orElseThrow(()->new ApiException(ErrorCode.ACCESS_DENIED));
        if(!"ACTIVE".equals(user.status())||!"ADMIN".equals(user.role()))throw new ApiException(ErrorCode.ACCESS_DENIED);}
    private void serial(String key){jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))",(org.springframework.jdbc.core.RowCallbackHandler)rs->{},key);}
    private void snapshot(long id){store.flush();jdbc.update("INSERT INTO fishingstory.magazine_versions(magazine_id,revision,source_revision,title,content,category,subcategory,images_json,published,published_at,recorded_at) SELECT magazine_id,revision,source_revision,title,content,category,subcategory,images_json,published,published_at,updated_at FROM fishingstory.magazines WHERE magazine_id=?",id);}
    @Transactional public MagazineView.Detail receive(long userId,UUID source,Import command) {
        admin(userId);serial("magazine:"+source);
        String refs=json.writeValueAsString(command.images());
        for(var ref:command.images())if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fishingstory.magazine_images WHERE image_id=? AND owner_id=?)",Boolean.class,ref.imageId(),userId)))throw new ApiException(ErrorCode.VALIDATION_ERROR);
        var existing=store.bySource(source);Magazine row;
        if(existing.isEmpty()){
            if(command.expectedRevision()!=null)throw new ApiException(ErrorCode.STATE_CONFLICT);
            row=new Magazine(ids.nextId(),source,command.sourceRevision(),command.title(),command.content(),command.category(),command.subcategory(),refs,Instant.now());store.insert(row);
        }else{
            row=existing.get();
            if(row.matches(command.sourceRevision(),command.title(),command.content(),command.category(),command.subcategory(),refs))return query.detail(row.id(),true).detail(json);
            if(row.published()||command.expectedRevision()==null||command.expectedRevision()!=row.revision()||command.sourceRevision()<row.sourceRevision())throw new ApiException(ErrorCode.STATE_CONFLICT);
            row.change(command.sourceRevision(),command.title(),command.content(),command.category(),command.subcategory(),refs,Instant.now());
        }
        snapshot(row.id());return query.detail(row.id(),true).detail(json);
    }
    @Transactional public MagazineView.Detail publication(long userId,long id,long expectedRevision,boolean value,boolean confirmed) {
        admin(userId);if(!confirmed||expectedRevision<1)throw new ApiException(ErrorCode.VALIDATION_ERROR);
        var row=store.lock(id).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        // The immediately repeated state request is safe after a lost response.
        if(row.published()==value&&(row.revision()==expectedRevision||row.revision()==expectedRevision+1))return query.detail(id,true).detail(json);
        if(row.revision()!=expectedRevision)throw new ApiException(ErrorCode.STATE_CONFLICT);
        row.publication(value,Instant.now());snapshot(id);return query.detail(id,true).detail(json);
    }
    @Transactional public ImageUploaded upload(long userId,UUID id,InputStream input) {
        admin(userId);serial("magazine-image:"+id);
        final byte[] source;
        try{source=input.readNBytes(MAX_IMAGE+1);}catch(IOException failure){throw new ApiException(ErrorCode.VALIDATION_ERROR);}
        if(source.length==0||source.length>MAX_IMAGE)throw new ApiException(ErrorCode.VALIDATION_ERROR);
        String hash=sha(source);
        var found=jdbc.queryForList("SELECT owner_id,source_hash,width,height,bytes FROM fishingstory.magazine_images WHERE image_id=?",id);
        if(!found.isEmpty()){
            var old=found.getFirst();if(((Number)old.get("owner_id")).longValue()!=userId||!hash.equals(old.get("source_hash")))throw new ApiException(ErrorCode.STATE_CONFLICT);
            return new ImageUploaded(id,((Number)old.get("width")).intValue(),((Number)old.get("height")).intValue(),((Number)old.get("bytes")).intValue());
        }
        byte[] normalized;int width,height;
        try(var imageInput=ImageIO.createImageInputStream(new ByteArrayInputStream(source))){
            var readers=ImageIO.getImageReaders(imageInput);if(!readers.hasNext())throw new IllegalArgumentException();
            var reader=readers.next();
            try{reader.setInput(imageInput,true,true);width=reader.getWidth(0);height=reader.getHeight(0);
                if(!"png".equalsIgnoreCase(reader.getFormatName())||width<1||height<1||width>2400||height>2400||animatedPng(source))throw new IllegalArgumentException();
                var decoded=reader.read(0);if(decoded==null)throw new IllegalArgumentException();var output=new ByteArrayOutputStream();
                if(!ImageIO.write(decoded,"png",output))throw new IllegalArgumentException();normalized=output.toByteArray();
                if(normalized.length>MAX_IMAGE)throw new IllegalArgumentException();
            }finally{reader.dispose();}
        }catch(IOException|IllegalArgumentException failure){throw new ApiException(ErrorCode.VALIDATION_ERROR);}
        var storage=images.getIfAvailable();if(storage==null)throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        String key="magazine/"+id+"/"+sha(normalized)+".png";
        try{storage.put(key,normalized);}catch(RuntimeException failure){throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);}
        jdbc.update("INSERT INTO fishingstory.magazine_images(image_id,owner_id,source_hash,object_key,bytes,width,height,created_at) VALUES(?,?,?,?,?,?,?,now())",id,userId,hash,key,normalized.length,width,height);
        return new ImageUploaded(id,width,height,normalized.length);
    }
    @Transactional(readOnly=true) public MagazineView.Detail detail(long userId,long id,boolean admin) {
        allowed(userId,admin);var row=query.detail(id,admin);if(row==null)throw new ApiException(ErrorCode.NOT_FOUND);return row.detail(json);
    }
    @Transactional(readOnly=true) public MagazineView.Detail bySource(long userId,UUID id){allowed(userId,true);var row=query.bySource(id.toString());if(row==null)throw new ApiException(ErrorCode.NOT_FOUND);return row.detail(json);}
    @Transactional(readOnly=true) public MagazineView.Page list(long userId,int page,int limit){allowed(userId,false);
        if(page<0||page>10000||limit<1||limit>50)throw new ApiException(ErrorCode.VALIDATION_ERROR);
        var rows=query.list(limit+1,(long)page*limit);return new MagazineView.Page(List.copyOf(rows.subList(0,Math.min(limit,rows.size()))),rows.size()>limit);}
    @Transactional(readOnly=true) public ImageBody image(long userId,long magazineId,UUID id){
        var magazine=detail(userId,magazineId,false);
        if(magazine.images().stream().noneMatch(ref->ref.imageId().equals(id)))throw new ApiException(ErrorCode.NOT_FOUND);
        var rows=jdbc.queryForList("SELECT object_key,bytes FROM fishingstory.magazine_images WHERE image_id=?",id);if(rows.isEmpty())throw new ApiException(ErrorCode.NOT_FOUND);
        var storage=images.getIfAvailable();if(storage==null)throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        final byte[] png;
        String key=(String)rows.getFirst().get("object_key");
        try{
            png=storage.get(key,MAX_IMAGE);
            if(png.length!=((Number)rows.getFirst().get("bytes")).intValue()||!key.endsWith("/"+sha(png)+".png"))throw new IllegalStateException();
        }catch(RuntimeException failure){throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);}
        var current=detail(userId,magazineId,false);
        if(current.images().stream().noneMatch(ref->ref.imageId().equals(id)))throw new ApiException(ErrorCode.NOT_FOUND);
        return new ImageBody(id,"image/png",Base64.getEncoder().encodeToString(png));
    }
    private void allowed(long id,boolean admin){if(!query.allowed(id,admin))throw new ApiException(ErrorCode.ACCESS_DENIED);}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException();}}
    private static boolean animatedPng(byte[] bytes){for(int p=8;p+12<=bytes.length;){long length=Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes,p,4).getInt());if(length>bytes.length-p-12)return true;if(bytes[p+4]=='a'&&bytes[p+5]=='c'&&bytes[p+6]=='T'&&bytes[p+7]=='L')return true;p+=(int)length+12;}return false;}
    public record ImageUploaded(UUID imageId,int width,int height,int bytes) { }
    public record ImageBody(UUID imageId,String contentType,String base64) { }
    public record Import(int sourceRevision,Long expectedRevision,String title,String content,String category,String subcategory,List<ImageRef> images) {
        public Import {
            if(sourceRevision<1||expectedRevision!=null&&expectedRevision<1||title==null||content==null||category==null||subcategory==null||images==null)throw new ApiException(ErrorCode.VALIDATION_ERROR);
            if(images.stream().anyMatch(Objects::isNull))throw new ApiException(ErrorCode.VALIDATION_ERROR);
            title=title.strip();content=content.strip();images=List.copyOf(images);
            var categories=Map.of("encyclopedia",Set.of("general","species","techniques","rigs","equipment","basics","safety"),"seasonal",Set.of("general","monthly","strategy","regional","preparation","features","gear"));
            if(title.isBlank()||title.codePointCount(0,title.length())>200||title.codePoints().anyMatch(Character::isISOControl)||content.isBlank()||content.codePointCount(0,content.length())>100000||content.indexOf('\0')>=0||!categories.getOrDefault(category,Set.of()).contains(subcategory)||images.size()>8||images.stream().anyMatch(ref->ref==null||ref.imageId()==null||ref.altText()==null||ref.altText().codePointCount(0,ref.altText().length())>300||ref.altText().codePoints().anyMatch(Character::isISOControl))||images.stream().map(ImageRef::imageId).distinct().count()!=images.size())throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
