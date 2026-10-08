package com.cau.capstone8.backend.topic;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TopicNameResolverTest {
    @Test void recognizesCuratedAliasesWithUnicodeAndWhitespaceNormalization() throws Exception {
        var resolver=new TopicNameResolver();
        for(String name:new String[]{"OS","ＯＳ"," operating-systems ","운영 체제"})
            assertThat(resolver.inspect(name).match().slug()).isEqualTo("operating-systems");
        assertThat(resolver.inspect("컴퓨터 통신망").match().slug()).isEqualTo("computer-networks");
        assertThat(resolver.inspect("DBMS").match().slug()).isEqualTo("databases");
    }
    @Test void broadAndNarrowInputsOnlyOfferScopesUntilTheUserChooses() throws Exception {
        var resolver=new TopicNameResolver();
        var math=resolver.inspect("수학");assertThat(math.status()).isEqualTo("BROAD");assertThat(math.match()).isNull();assertThat(math.candidates()).hasSize(3);
        var tcp=resolver.inspect("TCP 혼잡 제어");assertThat(tcp.status()).isEqualTo("RELATED");assertThat(tcp.match()).isNull();
        assertThat(tcp.candidates()).extracting(TopicNameResolver.Candidate::slug).containsExactly("computer-networks");
        assertThat(resolver.resolve("TCP 혼잡 제어","computer-networks").match().slug()).isEqualTo("computer-networks");
        assertThat(resolver.resolve("수학","linear-algebra").match().slug()).isEqualTo("linear-algebra");
    }
    @Test void unknownNamesAndConflictingContextsAreNotForcedIntoASupportedField() throws Exception {
        var resolver=new TopicNameResolver();
        for(String name:new String[]{"aa","aaa","인공지능","SMTP","소셜 네트워크","neural networks"}) {
            var result=resolver.inspect(name);assertThat(result.status()).as(name).isEqualTo("UNKNOWN");assertThat(result.candidates()).isEmpty();
        }
        assertThat(resolver.inspect("OS와 DBMS").match()).isNull();
        assertThat(resolver.inspect("OS와 DBMS").candidates()).extracting(TopicNameResolver.Candidate::slug).containsExactly("operating-systems","databases");
    }
    @Test void unrelatedChoicesCannotBypassTheNameCheck() throws Exception {
        var resolver=new TopicNameResolver();
        assertThatThrownBy(()->resolver.resolve("aa","computer-networks")).hasMessageContaining("표시된 후보");
        assertThatThrownBy(()->resolver.resolve("TCP 혼잡 제어","linear-algebra")).hasMessageContaining("표시된 후보");
        assertThatThrownBy(()->resolver.resolve("수학","computer-networks")).hasMessageContaining("표시된 후보");
    }
}
