package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.account.AccountException;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Provider discovery is evidence for browsing, never approval of a learning field. */
@Service
public class TopicDiscoveryService {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final JsonMapper json=JsonMapper.builder().build();
    public TopicDiscoveryService(JdbcTemplate jdbc,@Value("${topic-preparation.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.enabled=enabled;
    }
    public record Sample(String title,List<String> authors) {}
    public record Group(String category,int bookCount,List<Sample> samples) {}
    public record Provider(String id,String status,int bookCount,Integer statusCode) {}
    public record Field(String id,String name,String englishName,String parentName,int bookCount,List<Provider> providers,List<Sample> samples) {}
    public record Discovery(UUID id,String query,String status,List<Group> groups,List<Field> fields) {}
    public record Job(UUID id,UUID token,long userId,String query) {}

    @Transactional
    public Discovery start(long user,String value) {
        String name=TopicNameResolver.validateName(value);
        if(!enabled) throw new AccountException(409,"PREPARATION_UNAVAILABLE","지금은 책 검색을 시작할 수 없어요. 잠시 후 다시 확인해 주세요.");
        jdbc.queryForList("SELECT id FROM backend.app_user WHERE id=? FOR UPDATE",user);
        var existing=jdbc.queryForList("SELECT id FROM backend.topic_discovery WHERE user_id=? AND query=? AND status<>'FAILED' AND created_at>now()-interval '1 hour' AND (result IS NULL OR result->>'protocolVersion'='common-fields-v1') ORDER BY created_at DESC LIMIT 1",UUID.class,user,name);
        if(!existing.isEmpty()) return detail(user,existing.getFirst());
        if(jdbc.queryForObject("SELECT count(*) FROM backend.topic_discovery WHERE user_id=? AND created_at>now()-interval '1 hour'",Integer.class,user)>=10)
            throw new AccountException(429,"DISCOVERY_LIMIT","검색 요청이 많아요. 잠시 후 다시 시도해 주세요.");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO backend.topic_discovery(id,user_id,query,status) VALUES (?,?,?,'QUEUED')",id,user,name);
        return detail(user,id);
    }
    @Transactional(readOnly=true)
    public Discovery detail(long user,UUID id) {
        var rows=jdbc.queryForList("SELECT id,query,status,result::text FROM backend.topic_discovery WHERE id=? AND user_id=?",id,user);
        if(rows.isEmpty())throw new ResourceNotFoundException("검색 요청을 찾을 수 없습니다.");
        var row=rows.getFirst();var groups=new ArrayList<Group>();
        if(row.get("result")!=null) for(var group:json.readTree((String)row.get("result")).path("groups")) {
            var samples=new ArrayList<Sample>();
            for(var sample:group.path("samples")) {var authors=new ArrayList<String>();for(var a:sample.path("authors"))authors.add(a.asString());samples.add(new Sample(sample.path("title").asString(),List.copyOf(authors)));}
            groups.add(new Group(group.path("category").asString(),group.path("bookCount").asInt(),List.copyOf(samples)));
        }
        var fields=new ArrayList<Field>();
        if(row.get("result")!=null) for(var field:json.readTree((String)row.get("result")).path("fields")) {
            var sources=new ArrayList<Provider>();
            for(var source:field.path("providers"))sources.add(new Provider(source.path("id").asString(),source.path("status").asString(),source.path("bookCount").asInt(),source.has("statusCode")?source.path("statusCode").asInt():null));
            var samples=new ArrayList<Sample>();
            for(var sample:field.path("samples")) {var authors=new ArrayList<String>();for(var a:sample.path("authors"))authors.add(a.asString());samples.add(new Sample(sample.path("title").asString(),List.copyOf(authors)));}
            fields.add(new Field(field.path("id").asString(),field.path("name").asString(),field.path("englishName").asString(),field.path("parentName").asString(),field.path("bookCount").asInt(),List.copyOf(sources),List.copyOf(samples)));
        }
        return new Discovery(id,(String)row.get("query"),(String)row.get("status"),List.copyOf(groups),List.copyOf(fields));
    }
    /** Validate ownership and the exact observed scope before persisting a selection. */
    public String selection(long user,String name,UUID id,String category) {
        if(id==null || category==null)throw invalid();
        var found=detail(user,id);
        if(!found.status().equals("FOUND") || !TopicRequestService.normalize(name).equals(TopicRequestService.normalize(found.query()))
                || found.groups().stream().noneMatch(g->g.category().equals(category) && g.bookCount()>0))throw invalid();
        String parent=category.split("-",-1)[1];
        String normalized=java.text.Normalizer.normalize(found.query(),java.text.Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+","").toLowerCase(Locale.ROOT);
        return json.writeValueAsString(Map.of("id",id.toString(),"query",found.query(),"category",category,
                "slug","search-"+hash(normalized+"\0"+category).substring(0,20),
                "parentCode","SRC-"+hash(parent).substring(0,16),"parentName",parent));
    }
    public String selection(long user,String name,UUID id,String category,String commonFieldId) {
        if(commonFieldId==null)return selection(user,name,id,category);
        if(id==null || category!=null)throw invalid();
        var found=detail(user,id);
        if(!found.status().equals("FOUND") || !TopicRequestService.normalize(name).equals(TopicRequestService.normalize(found.query()))
            || found.fields().stream().noneMatch(f->f.id().equals(commonFieldId) && f.bookCount()>0))throw invalid();
        // Take every scope value from the owned immutable result. A client selects only an ID.
        var result=json.readTree(jdbc.queryForObject("SELECT result::text FROM backend.topic_discovery WHERE id=? AND user_id=?",String.class,id,user));
        for(var field:result.path("fields")) if(commonFieldId.equals(field.path("id").asString()))
            return json.writeValueAsString(Map.of("id",id.toString(),"query",found.query(),"commonFieldId",commonFieldId,
                "registryHash",field.path("registryHash").asString(),"slug",field.path("slug").asString(),
                "parentCode",field.path("parentCode").asString(),"parentName",field.path("parentName").asString(),
                "name",field.path("name").asString(),"englishName",field.path("englishName").asString()));
        throw invalid();
    }

    @Transactional
    public Job claim() {
        if(!enabled)return null;
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('topic-preparation-v1'))",Object.class);
        jdbc.update("UPDATE backend.topic_discovery SET status='FAILED',claim_token=null,lease_until=null,updated_at=now() WHERE status='SEARCHING' AND lease_until<now()");
        if(jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM backend.topic_catalog_refresh WHERE status='RUNNING') + (SELECT count(*) FROM backend.topic_discovery WHERE status='SEARCHING')
                 + (SELECT count(*) FROM backend.topic_request WHERE status IN ('CHECKING','COLLECTING'))
                 + (SELECT count(*) FROM backend.topic_content_preparation WHERE status='PREPARING')
                 + (SELECT count(*) FROM backend.topic_question_preparation WHERE status IN ('GENERATING','REVIEWING'))
            """,Integer.class)>0)return null;
        var rows=jdbc.queryForList("SELECT id,user_id,query FROM backend.topic_discovery WHERE status='QUEUED' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty())return null;
        var row=rows.getFirst();UUID id=(UUID)row.get("id"),token=UUID.randomUUID();
        jdbc.update("UPDATE backend.topic_discovery SET status='SEARCHING',claim_token=?,lease_until=now()+interval '4 minutes',updated_at=now() WHERE id=?",token,id);
        return new Job(id,token,((Number)row.get("user_id")).longValue(),(String)row.get("query"));
    }
    @Transactional
    public void complete(Job job,JsonNode result) {
        String status=result.path("status").asString();
        if(!Set.of("FOUND","NO_RESULTS").contains(status) || !job.query().equals(result.path("query").asString())
            || !Set.of("yes24","common-fields").contains(result.path("provider").asString()) || !result.path("groups").isArray())throw new IllegalArgumentException("invalid discovery result");
        boolean common="common-fields".equals(result.path("provider").asString());
        if(common && (!result.path("groups").isEmpty() || !result.path("fields").isArray() || result.path("fields").size()>10))
            throw new IllegalArgumentException("invalid common field result");
        var seen=new HashSet<String>();
        for(var group:result.path("groups")) {
            String category=group.path("category").asString();
            if(!category.startsWith("국내도서-") || category.split("-",-1).length<2 || category.split("-",-1)[1].isBlank() || category.length()>240
                || !seen.add(category) || group.path("bookCount").asInt()<1 || group.path("bookCount").asInt()>100 || !group.path("samples").isArray() || group.path("samples").size()>4)
                throw new IllegalArgumentException("invalid discovery scope");
        }
        if(common)for(var field:result.path("fields")) {
            String id=field.path("id").asString();
            if(!id.matches("[a-z][a-z0-9-]{1,80}") || !seen.add(id) || !("field-"+id).equals(field.path("slug").asString())
                || !field.path("parentCode").asString().matches("SRC-[0-9a-f]{16}")
                || !field.path("registryHash").asString().matches("sha256:[0-9a-f]{64}")
                || !validLabel(field.path("name")) || !validLabel(field.path("englishName")) || !validLabel(field.path("parentName"))
                || field.path("bookCount").asInt()<1 || field.path("bookCount").asInt()>80
                || !field.path("providers").isArray() || field.path("providers").size()<3 || field.path("providers").size()>4
                || !field.path("samples").isArray() || field.path("samples").size()>4)
                throw new IllegalArgumentException("invalid common field scope");
            var sources=new HashSet<String>();int count=0;
            for(var source:field.path("providers")) {
                if(!Set.of("yes24","open_library","google_books","national_library").contains(source.path("id").asString())
                    || !sources.add(source.path("id").asString()) || !Set.of("collected","provider_failed","not_configured").contains(source.path("status").asString())
                    || ("not_configured".equals(source.path("status").asString()) && !"national_library".equals(source.path("id").asString()))
                    || source.path("bookCount").asInt()<0 || source.path("bookCount").asInt()>20
                    || (!"collected".equals(source.path("status").asString()) && source.path("bookCount").asInt()!=0)
                    || (source.has("statusCode") && (source.path("statusCode").asInt()<100 || source.path("statusCode").asInt()>599)))
                    throw new IllegalArgumentException("invalid common field sources");
                count+=source.path("bookCount").asInt();
            }
            if(!sources.containsAll(Set.of("yes24","open_library","google_books")) || count<field.path("bookCount").asInt())throw new IllegalArgumentException("invalid common field count");
        }
        if(status.equals("FOUND")==seen.isEmpty())throw new IllegalArgumentException("discovery result count differs");
        int updated=jdbc.update("UPDATE backend.topic_discovery SET status=?,result=cast(? as jsonb),claim_token=null,lease_until=null,updated_at=now() WHERE id=? AND status='SEARCHING' AND claim_token=? AND lease_until>now()",status,json.writeValueAsString(result),job.id(),job.token());
        if(updated!=1)throw new IllegalStateException("discovery claim expired");
    }
    @Transactional
    public void fail(Job job) {jdbc.update("UPDATE backend.topic_discovery SET status='FAILED',claim_token=null,lease_until=null,updated_at=now() WHERE id=? AND status='SEARCHING' AND claim_token=?",job.id(),job.token());}
    private static boolean validLabel(JsonNode value){return value.isString() && !value.asString().isBlank() && value.asString().length()<=120;}
    private AccountException invalid(){return new AccountException(400,"INVALID_DISCOVERY_CHOICE","검색 결과에서 책을 모을 분류를 다시 선택해 주세요.");}
    private static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
