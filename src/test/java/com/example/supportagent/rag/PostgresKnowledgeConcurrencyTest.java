package com.example.supportagent.rag;

import com.example.supportagent.config.SupportProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.ai.vectorstore.VectorStore;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="support.seed.enabled=false")
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named="RUN_POSTGRES_TESTS", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresKnowledgeConcurrencyTest {
    private static final String SUFFIX=UUID.randomUUID().toString().replace("-","");
    private static final String TABLE="support_vector_test_"+SUFFIX;
    private static final String NAMESPACE="test-"+SUFFIX;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("support.rag.vector-table",()->TABLE);
        registry.add("support.rag.index-namespace",()->NAMESPACE);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired Environment environment;
    @Autowired KnowledgeService service;
    @Autowired VectorStore vectorStore;
    @Autowired KnowledgeManifestRepository manifests;
    @Autowired SupportProperties config;
    @Autowired KnowledgeReranker reranker;
    @AfterAll void cleanup() {
        jdbc.update("delete from knowledge_manifests where id like ?", NAMESPACE+":%");
        // Only the randomly named table created for THIS test is dropped.
        if(!TABLE.matches("support_vector_test_[a-f0-9]{32}"))throw new IllegalStateException("unsafe test table");
        jdbc.execute("drop table if exists "+TABLE);
    }
    @Test void independentCoordinatorsSerializeUntilTransactionCommit() throws Exception {
        var one=new KnowledgeWriteCoordinator(manager,jdbc,environment);
        var two=new KnowledgeWriteCoordinator(manager,jdbc,environment);
        var workers=Executors.newFixedThreadPool(2);
        var acquired=new CountDownLatch(1); var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);
        String key=NAMESPACE+":lock";
        try {
            var first=workers.submit(()->one.write(key,()->{
                acquired.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return 1;
            }));
            assertThat(acquired.await(3,TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(()->{secondStarted.countDown();return two.write(key,()->2);});
            assertThat(secondStarted.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->second.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(3,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(3,TimeUnit.SECONDS)).isEqualTo(2);
        }finally{release.countDown();workers.shutdownNow();}
    }
    @Test void failedReplacementRollsBackBothVectorsAndManifest() {
        var request=new KnowledgeDocumentRequest("rollback","Test","test/rollback.md","E03 原始知识内容，先关闭设备再清理主刷。");
        service.upsert("test-tenant",request);
        int before=jdbc.queryForObject("select count(*) from "+TABLE,Integer.class);
        var failingStore=spy(vectorStore);
        doThrow(new IllegalStateException("injected embedding failure")).when(failingStore).add(anyList());
        var broken=new KnowledgeService(failingStore,config,manifests,new SimpleMeterRegistry(),reranker,12,true,NAMESPACE,
                new KnowledgeWriteCoordinator(manager,jdbc,environment));
        assertThatThrownBy(()->broken.upsert("test-tenant",new KnowledgeDocumentRequest("rollback","Changed","test/rollback.md","新的替换内容")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from "+TABLE,Integer.class)).isEqualTo(before);
        assertThat(manifests.findById(NAMESPACE+":test-tenant:rollback")).isPresent();
        assertThat(service.search("test-tenant","E03 原始知识 主刷")).anyMatch(chunk->chunk.text().contains("原始知识"));
        service.upsert("test-tenant",new KnowledgeDocumentRequest("rollback","Updated","test/rollback.md","E03 更新知识内容，必须先关闭设备。"));
        assertThat(service.search("test-tenant","E03 更新知识")).anyMatch(chunk->chunk.text().contains("更新知识"));
        assertThat(service.delete("test-tenant","rollback")).isTrue();
    }
    @Test void manifestFailureAlsoRollsBackAlreadyInsertedVectorChunks() {
        service.upsert("test-tenant",new KnowledgeDocumentRequest("manifest-failure","Original","test/original.md","E03 原始主刷知识。"));
        var before=jdbc.queryForList("select id::text from "+TABLE+" order by id",String.class);
        var failingManifest=mock(KnowledgeManifestRepository.class,org.mockito.AdditionalAnswers.delegatesTo(manifests));
        doThrow(new IllegalStateException("injected manifest failure")).when(failingManifest).save(any(KnowledgeManifest.class));
        var broken=new KnowledgeService(vectorStore,config,failingManifest,new SimpleMeterRegistry(),reranker,12,true,NAMESPACE,
                new KnowledgeWriteCoordinator(manager,jdbc,environment));
        assertThatThrownBy(()->broken.upsert("test-tenant",new KnowledgeDocumentRequest("manifest-failure","New","test/new.md",
                "新的向量内容必须随清单失败一起回滚。".repeat(100))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("select id::text from "+TABLE+" order by id",String.class)).isEqualTo(before);
        assertThat(service.search("test-tenant","E03 原始主刷知识")).anyMatch(chunk->chunk.source().equals("test/original.md"));
        service.delete("test-tenant","manifest-failure");
    }
}
