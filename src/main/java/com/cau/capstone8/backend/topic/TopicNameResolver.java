package com.cau.capstone8.backend.topic;

import com.cau.capstone8.backend.account.AccountException;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Local, curated names. Related terms propose a scope; they never assign one. */
@Service
public class TopicNameResolver {
    public record Candidate(String slug,String name,String parentCode,String parentName) {}
    public record Resolution(String status,String message,List<Candidate> candidates) {
        public Candidate match() { return "MATCH".equals(status)?candidates.getFirst():null; }
    }
    private record Entry(Candidate candidate,List<String> terms,List<String> exclusions) {}
    private final Map<String,Entry> exact=new HashMap<>();
    private final Map<String,String> groups=new HashMap<>();
    private final List<Entry> entries=new ArrayList<>();

    public TopicNameResolver() throws IOException {
        var json=JsonMapper.builder().build();
        try(var input=new ClassPathResource("topic-name-resolution.json").getInputStream()) {
            var root=json.readTree(input);
            if (!"topic-name-resolution-v1".equals(root.path("version").asString())) throw new IllegalArgumentException("unsupported topic name registry");
            var slugs=new HashSet<String>();
            for(var node:root.path("topics")) {
                var candidate=new Candidate(node.path("slug").asString(),node.path("name").asString(),node.path("parentCode").asString(),node.path("parentName").asString());
                if (!candidate.slug().matches("[a-z][a-z0-9-]{1,119}") || candidate.name().isBlank() || !Set.of("CS","MAT").contains(candidate.parentCode()) || !slugs.add(candidate.slug()))
                    throw new IllegalArgumentException("invalid topic name registry entry");
                var aliases=new ArrayList<>(strings(node.path("aliases")));aliases.add(candidate.name());aliases.add(candidate.slug());
                var terms=new ArrayList<>(strings(node.path("relatedTerms")));terms.addAll(aliases);
                var entry=new Entry(candidate,List.copyOf(terms),strings(node.path("excludedTerms")));entries.add(entry);
                for(String alias:aliases) {
                    var previous=exact.putIfAbsent(normalize(alias),entry);
                    if (previous!=null && !previous.candidate().slug().equals(candidate.slug())) throw new IllegalArgumentException("ambiguous exact alias");
                }
            }
            for(var group:root.path("groups")) for(String alias:strings(group.path("aliases"))) {
                String key=normalize(alias),code=group.path("code").asString();
                if (!Set.of("CS","MAT").contains(code) || exact.containsKey(key) || groups.putIfAbsent(key,code)!=null) throw new IllegalArgumentException("ambiguous group alias");
            }
        }
    }

    public Resolution inspect(String value) {
        String name=validateName(value),key=normalize(name);
        var entry=exact.get(key);
        if(entry!=null) return new Resolution("MATCH","등록된 분야를 찾았어요.",List.of(entry.candidate()));
        String group=groups.get(key);
        if(group!=null) return new Resolution("BROAD","더 구체적인 분야를 선택해 주세요.",entries.stream().map(Entry::candidate).filter(c->c.parentCode().equals(group)).toList());
        var related=entries.stream().filter(e->e.exclusions().stream().noneMatch(t->contains(name,t)) && e.terms().stream().anyMatch(t->contains(name,t))).map(Entry::candidate).toList();
        if(!related.isEmpty()) return new Resolution("RELATED","입력한 주제와 관련된 분야예요. 선택하면 해당 분야 전체로 살펴봐요.",related);
        return new Resolution("UNKNOWN","일치하는 지원 분야를 찾지 못했어요. 다른 이름으로 검색해 주세요.",List.of());
    }

    public Resolution resolve(String name,String selectedSlug) {
        var result=inspect(name);
        if(selectedSlug==null) return result;
        var selected=result.candidates().stream().filter(c->c.slug().equals(selectedSlug)).findFirst()
                .orElseThrow(()->new AccountException(400,"INVALID_TOPIC_CHOICE","분야 이름을 다시 확인하고 표시된 후보에서 선택해 주세요."));
        return new Resolution("MATCH","선택한 분야로 준비해요.",List.of(selected));
    }
    public static String validateName(String value) {
        if(value==null) throw new AccountException(400,"INVALID_TOPIC_REQUEST","분야 이름을 입력해 주세요.");
        String clean=Normalizer.normalize(value,Normalizer.Form.NFKC).strip().replaceAll("[\\s\\p{Z}]+"," ");
        if(clean.length()<2 || clean.length()>120 || clean.chars().anyMatch(Character::isISOControl) || !clean.matches(".*[\\p{L}\\p{N}].*"))
            throw new AccountException(400,"INVALID_TOPIC_REQUEST","분야 이름을 2~120자로 입력해 주세요.");
        return clean;
    }
    private static String normalize(String value) { return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}_-]+",""); }
    private static boolean contains(String value,String term) {
        String input=Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String keyword=Normalizer.normalize(term,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if(keyword.matches(".*[a-z].*")) {
            String expression=Pattern.quote(keyword).replace(" ","\\E[\\s_-]+\\Q");
            return Pattern.compile("(?<![a-z0-9])"+expression+"(?![a-z0-9])").matcher(input).find();
        }
        return normalize(input).contains(normalize(keyword));
    }
    private static List<String> strings(tools.jackson.databind.JsonNode node) {
        var result=new ArrayList<String>();for(var item:node) { String value=item.asString();if(value.isBlank()) throw new IllegalArgumentException("blank registry term");result.add(value); }return List.copyOf(result);
    }
}
