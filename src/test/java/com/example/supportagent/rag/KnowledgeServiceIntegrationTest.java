package com.example.supportagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KnowledgeServiceIntegrationTest {

    @Autowired
    KnowledgeService service;

    @Autowired
    KnowledgeManifestRepository manifestRepository;

    @Test
    void upsertReplacesOldChunksAndDeleteRemovesManifest() {
        service.upsert("tenant-a", new KnowledgeDocumentRequest(
                "replace-demo", "旧版说明", "manual/replace.md", "旧内容：红色按钮用于启动。"));
        var originalIds = manifestRepository.findById("tenant-a:replace-demo").orElseThrow().chunkIdList();
        assertThat(originalIds).isNotEmpty().allSatisfy(id ->
                assertThat(UUID.fromString(id).toString()).isEqualTo(id));
        service.upsert("tenant-a", new KnowledgeDocumentRequest(
                "replace-demo", "新版说明", "manual/replace.md", "新版内容：蓝色按钮用于启动。"));
        assertThat(manifestRepository.findById("tenant-a:replace-demo").orElseThrow().chunkIdList())
                .containsExactlyElementsOf(originalIds);

        assertThat(service.search("tenant-a", "蓝色按钮")).anyMatch(c -> c.text().contains("蓝色"));
        assertThat(manifestRepository.findById("tenant-a:replace-demo")).isPresent();

        assertThat(service.delete("tenant-a", "replace-demo")).isTrue();
        assertThat(manifestRepository.findById("tenant-a:replace-demo")).isEmpty();
    }

    @Test
    void retrievalNeverReturnsAnotherTenantsCanary() {
        assertThat(service.search("tenant-a", "B-ONLY-7788"))
                .noneMatch(c -> c.text().contains("B-ONLY-7788"));
    }
}
