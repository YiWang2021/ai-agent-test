package com.example.supportagent.rag;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Local, deterministic BM25 reranking. It is not a neural cross-encoder. */
@Component
public class KnowledgeReranker {
    public record RankedChunk(RetrievedChunk chunk, double lexicalScore, double combinedScore) {}
    private static final Pattern TERMS = Pattern.compile("[a-z0-9_-]+|[\\p{IsHan}]+", Pattern.CASE_INSENSITIVE);

    public List<RankedChunk> rank(String query, List<RetrievedChunk> candidates) {
        if (candidates.isEmpty()) return List.of();
        Set<String> queryTerms = new HashSet<>(terms(query));
        List<List<String>> documents = candidates.stream().map(c -> terms(c.title() + " " + c.text())).toList();
        double averageLength = documents.stream().mapToInt(List::size).average().orElse(1);
        Map<String, Long> frequency = new HashMap<>();
        for (String term : queryTerms) frequency.put(term, documents.stream().filter(doc -> doc.contains(term)).count());
        List<RankedChunk> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            List<String> document = documents.get(i);
            double bm25 = 0;
            for (String term : queryTerms) {
                long tf = document.stream().filter(term::equals).count();
                double idf = Math.log(1 + (candidates.size() - frequency.get(term) + 0.5) / (frequency.get(term) + 0.5));
                bm25 += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * document.size() / Math.max(1, averageLength)));
            }
            RetrievedChunk chunk = candidates.get(i);
            double combined = 0.65 * chunk.score() + 0.35 * bm25 / (1 + bm25);
            ranked.add(new RankedChunk(chunk, bm25, combined));
        }
        return ranked.stream().sorted(Comparator.comparingDouble(RankedChunk::combinedScore).reversed()).toList();
    }

    private List<String> terms(String text) {
        List<String> result = new ArrayList<>();
        var matcher = TERMS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            if (Character.UnicodeScript.of(term.codePointAt(0)) == Character.UnicodeScript.HAN && term.length() > 1) {
                for (int i = 0; i < term.length() - 1; i++) result.add(term.substring(i, i + 2));
            } else result.add(term);
        }
        return result;
    }
}
