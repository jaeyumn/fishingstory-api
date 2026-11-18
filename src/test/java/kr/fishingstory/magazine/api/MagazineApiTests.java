package kr.fishingstory.magazine.api;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.magazine.image.MagazineImageStorage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="app.magazine.images.enabled=false") @ActiveProfiles("test") @AutoConfigureMockMvc
@Import(MagazineApiTests.Configuration.class)
class MagazineApiTests {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Autowired AccessTokenIssuer tokens;@Autowired FakeImages images;
    String admin,user;UUID source;
    @BeforeEach void prepare(){
        jdbc.update("DELETE FROM fishingstory.magazine_versions");jdbc.update("DELETE FROM fishingstory.magazines");jdbc.update("DELETE FROM fishingstory.magazine_images");
        jdbc.update("DELETE FROM fishingstory.users");
        jdbc.update("INSERT INTO fishingstory.users(user_id,nickname,nickname_key,age_confirmed_at,role) VALUES(101,'관리자','관리자',now(),'ADMIN'),(102,'회원','회원',now(),'USER')");
        admin=token(101,"ADMIN");user=token(102,"USER");source=UUID.randomUUID();images.objects.clear();images.fail=false;
    }
    @Test void importsIdempotentlyAndPublishesOnlyAfterSeparateConfirmation()throws Exception{
        String first=receive(source,body(1,null,"원고",List.of()));String id=json.readTree(first).get("magazineId").asText();
        assertThat(json.readTree(first).get("magazineId").isString()).isTrue();
        mvc.perform(get("/api/magazines/"+id).header("Authorization",user)).andExpect(status().isNotFound());
        String replay=receive(source,body(1,null,"원고",List.of()));assertThat(json.readTree(replay).get("magazineId").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.magazines",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.magazine_versions",Integer.class)).isEqualTo(1);
        mvc.perform(post("/api/admin/magazines/"+id+"/publication").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"published\":true,\"confirmed\":false}")).andExpect(status().isBadRequest());
        publication(id,1,true);publication(id,1,true);
        mvc.perform(get("/api/magazines/"+id).header("Authorization",user)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.published").value(true));
        mvc.perform(get("/api/magazines").header("Authorization",user)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].magazineId").value(id));
        publication(id,2,false);mvc.perform(get("/api/magazines/"+id).header("Authorization",user)).andExpect(status().isNotFound());
    }
    @Test void preventsStaleImportsAndEditingPublicContentUntilExplicitlyHidden()throws Exception{
        String id=json.readTree(receive(source,body(1,null,"원문",List.of()))).get("magazineId").asText();publication(id,1,true);
        mvc.perform(put("/api/admin/magazines/imports/"+source).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(body(2,2L,"변경",List.of()))).andExpect(status().isConflict());
        publication(id,2,false);
        mvc.perform(put("/api/admin/magazines/imports/"+source).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(body(2,2L,"변경",List.of()))).andExpect(status().isConflict());
        receive(source,body(2,3L,"변경",List.of()));
        mvc.perform(put("/api/admin/magazines/imports/"+source).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(body(1,4L,"낡은 원문",List.of()))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT content FROM fishingstory.magazines",String.class)).isEqualTo("변경");
    }
    @Test void checksJwtRoleAndCurrentDatabaseRoleAndActiveReaders()throws Exception{
        String id=json.readTree(receive(source,body(1,null,"원문",List.of()))).get("magazineId").asText();
        for(String credentials:List.of(user,token(102,"ADMIN"),token(101,"USER"))){
            mvc.perform(put("/api/admin/magazines/imports/"+UUID.randomUUID()).header("Authorization",credentials).contentType(MediaType.APPLICATION_JSON).content(body(1,null,"금지",List.of()))).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/magazines/by-source/"+source).header("Authorization",credentials)).andExpect(status().isForbidden());
            mvc.perform(put("/api/admin/magazine-images/"+UUID.randomUUID()).header("Authorization",credentials).contentType("image/png").content(png(0xff0000))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/magazines")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING',withdrawal_requested_at=now() WHERE user_id=102");
        mvc.perform(get("/api/magazines").header("Authorization",user)).andExpect(status().isForbidden());
        jdbc.update("UPDATE fishingstory.users SET role='USER' WHERE user_id=101");
        mvc.perform(post("/api/admin/magazines/"+id+"/publication").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"published\":true,\"confirmed\":true}")).andExpect(status().isForbidden());
    }
    @Test void validatesIndependentImagesAndOnlyServesPublishedAttachments()throws Exception{
        UUID image=UUID.randomUUID();byte[] png=png(0xff0000);
        upload(image,png);upload(image,png);
        mvc.perform(put("/api/admin/magazine-images/"+image).header("Authorization",admin).contentType("image/png").content(png(0x0000ff))).andExpect(status().isConflict());
        assertThat(images.objects).hasSize(1);
        String id=json.readTree(receive(source,body(1,null,"원문",List.of(Map.of("imageId",image.toString(),"altText","직접 검토한 사진"))))).get("magazineId").asText();
        mvc.perform(get("/api/magazines/"+id+"/images/"+image).header("Authorization",user)).andExpect(status().isNotFound());
        publication(id,1,true);
        mvc.perform(get("/api/magazines/"+id+"/images/"+image).header("Authorization",user)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.contentType").value("image/png")).andExpect(jsonPath("$.imageId").value(image.toString()));
        mvc.perform(get("/api/magazines/"+id+"/images/"+UUID.randomUUID()).header("Authorization",user)).andExpect(status().isNotFound());
        publication(id,2,false);mvc.perform(get("/api/magazines/"+id+"/images/"+image).header("Authorization",user)).andExpect(status().isNotFound());
    }
    @Test void rejectsBadImageAndInputWithoutChangingStoredRows()throws Exception{
        for(byte[] bytes:List.of("<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[5242881]))mvc.perform(put("/api/admin/magazine-images/"+UUID.randomUUID()).header("Authorization",admin).contentType("image/png").content(bytes)).andExpect(status().isBadRequest());
        for(String body:List.of(body(0,null,"본문",List.of()),body(1,null,"\0본문",List.of()),body(1,null,"본문",List.of(Map.of("imageId",UUID.randomUUID().toString(),"altText","없는 사진")))))mvc.perform(put("/api/admin/magazines/imports/"+source).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        images.fail=true;mvc.perform(put("/api/admin/magazine-images/"+UUID.randomUUID()).header("Authorization",admin).contentType("image/png").content(png(0xff0000))).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.magazines",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.magazine_images",Integer.class)).isZero();
    }
    private String receive(UUID id,String body)throws Exception{return mvc.perform(put("/api/admin/magazines/imports/"+id).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();}
    private void publication(String id,long revision,boolean published)throws Exception{mvc.perform(post("/api/admin/magazines/"+id+"/publication").header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedRevision",revision,"published",published,"confirmed",true)))).andExpect(status().isOk());}
    private String body(int revision,Long expected,String content,List<?> images){var map=new LinkedHashMap<String,Object>();map.put("sourceRevision",revision);map.put("expectedRevision",expected);map.put("title","검토 원고");map.put("content",content);map.put("category","encyclopedia");map.put("subcategory","basics");map.put("images",images);return json.writeValueAsString(map);}
    private void upload(UUID id,byte[] png)throws Exception{mvc.perform(put("/api/admin/magazine-images/"+id).header("Authorization",admin).contentType("image/png").content(png)).andExpect(status().isOk());}
    private String token(long id,String role){return "Bearer "+tokens.issue(id,role,UUID.randomUUID().toString()).value();}
    private static byte[] png(int rgb)throws Exception{var image=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,rgb);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
    static class FakeImages implements MagazineImageStorage {
        final Map<String,byte[]> objects=new ConcurrentHashMap<>();boolean fail;
        public void put(String key,byte[] value){if(fail)throw new IllegalStateException("fixture");objects.put(key,value.clone());}
        public byte[] get(String key,int maxBytes){if(fail||!objects.containsKey(key))throw new IllegalStateException("fixture");return objects.get(key).clone();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Configuration {
        @Bean @ServiceConnection PostgreSQLContainer postgres(){return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));}
        @Bean FakeImages magazineImages(){return new FakeImages();}
    }
}
