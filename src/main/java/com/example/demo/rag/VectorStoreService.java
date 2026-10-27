package com.example.demo.rag;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 纯内存、零依赖的「向量库」：手写 TF-IDF 向量化 + 余弦相似度检索。
 *
 * <p>这是阶段 2（RAG）的教学核心。目的不是做一个能打生产环境的检索引擎，
 * 而是把 RAG 最关键的一步——「文本怎么变成向量、怎么算相似度」——彻底拆开：
 *
 * <ol>
 *   <li><b>切分</b>：文档按段落切成 chunk（{@link #addDocument}）</li>
 *   <li><b>向量化</b>：所有 chunk 入库后统一算 TF-IDF 向量（{@link #build}）</li>
 *   <li><b>检索</b>：提问向量化 → 与每个 chunk 算余弦相似度 → 取 Top-K（{@link #search}）</li>
 * </ol>
 *
 * <p><b>TF-IDF 一句话</b>：一个词在片段里出现越多（TF 高）、在所有片段里越少见（IDF 高），
 * 它就越能代表这个片段。向量维度 = 词表大小，每个维度存「该词的 TF×IDF 权重」。
 *
 * <p><b>为什么是教学版而非真 Embedding</b>：TF-IDF 是「词面」向量，抓不到同义词（家长/父亲）；
 * 真 Embedding 模型能学词义。但流程完全一样，看懂这个，换真 Embedding 只是把 {@link #vectorOf} 换成调 API。
 */
@Service
public class VectorStoreService {

    /** 已入库的原始 chunk（未向量化） */
    private final List<RawChunk> raw = new ArrayList<>();

    /** 向量化后的 chunk */
    private final List<Chunk> chunks = new ArrayList<>();

    /** 词表：词 -> 列下标，保证所有向量维度对齐 */
    private final Map<String, Integer> vocab = new HashMap<>();

    /** 文档频率：词 -> 出现在多少个 chunk 里（算 IDF 用） */
    private final Map<String, Integer> df = new HashMap<>();

    /** 是否已 build（build 后词表与 IDF 锁定） */
    private boolean built = false;

    // ---------- 数据结构 ----------

    /** 入库时的原始片段 */
    private static class RawChunk {
        final String source;
        final String text;
        RawChunk(String source, String text) { this.source = source; this.text = text; }
    }

    /** 向量化后的知识片段 */
    public static class Chunk {
        public final String id;
        public final String source;
        public final String text;
        public final double[] vector;
        Chunk(String id, String source, String text, double[] vector) {
            this.id = id; this.source = source; this.text = text; this.vector = vector;
        }
    }

    /** 一条检索命中 */
    public static class Hit {
        public final Chunk chunk;
        public final double score; // 余弦相似度，越接近 1 越相关
        Hit(Chunk chunk, double score) { this.chunk = chunk; this.score = score; }
    }

    // ---------- 入库 ----------

    /**
     * 入库一篇文档：优先按 Markdown 标题（# 层级）切块，标题连同其下正文作为一个完整 chunk；
     * 没有标题的纯文本才回退按空行切。切好的 chunk 只暂存，等 {@link #build} 统一向量化。
     *
     * <p><b>为什么按标题切</b>：如果按空行/单行切，会把「## 标题」和它的正文条目拆成两个独立片段，
     * 检索命中标题时正文却不在同一块里，导致模型拿到「只有标题没有肉」的碎片。按标题切后，
     * 标题与正文永远在一起，命中标题 = 命中全部正文。
     *
     * @return 本次切出的有效 chunk 数
     */
    public synchronized int addDocument(String source, String content) {
        int added = 0;
        for (String part : splitByHeading(content)) {
            String text = part.trim();
            if (text.length() < 8) continue;
            raw.add(new RawChunk(source, text));
            added++;
        }
        return added;
    }

    /**
     * 按 Markdown 标题切块：遇到以 # 开头的行，就作为一个新块的起点；
     * 该标题行及其下所有非标题内容归入同一块，直到下一个标题出现。
     */
    private List<String> splitByHeading(String content) {
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (line.trim().startsWith("#")) {
                // 遇到新标题：先把上一块收尾
                if (cur.length() > 0) {
                    blocks.add(cur.toString());
                    cur = new StringBuilder();
                }
                cur.append(line).append("\n");
            } else {
                cur.append(line).append("\n");
            }
        }
        if (cur.length() > 0) {
            blocks.add(cur.toString());
        }
        // 如果整个文档都没有标题，回退按空行切
        if (blocks.size() <= 1 && !content.contains("\n#")) {
            return List.of(content.split("\n\\s*\n"));
        }
        return blocks;
    }

    /**
     * 全量重建向量：清空词表/DF/向量，重新扫所有 chunk 统计再算 TF-IDF。
     * <p>设计成「可重复调用」：增量追加文档后再次 {@link #build}，即得到包含新文档的完整索引。
     * 因为 IDF 依赖全局文档频率，任何新增都会影响所有词的 IDF，所以必须全量重算（当前数据量下成本可忽略）。
     */
    public synchronized void build() {
        vocab.clear();
        df.clear();
        chunks.clear();

        // 第一步：统计词表 + DF
        for (RawChunk rc : raw) {
            Set<String> terms = new HashSet<>(tokenize(rc.text)); // 去重：DF 按「文档」计
            for (String t : terms) {
                vocab.putIfAbsent(t, vocab.size());
                df.merge(t, 1, Integer::sum);
            }
        }
        int dim = vocab.size();
        // 第二步：算每个 chunk 的 TF-IDF 向量
        for (RawChunk rc : raw) {
            Map<String, Integer> tf = termFrequency(tokenize(rc.text));
            double[] vec = new double[dim];
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                Integer col = vocab.get(e.getKey());
                if (col != null) {
                    vec[col] = e.getValue() * idf(e.getKey());
                }
            }
            chunks.add(new Chunk("chunk-" + chunks.size(), rc.source, rc.text, vec));
        }
        built = true;
    }

    /** 列出已入库的文档（按来源聚合，含每个来源的 chunk 数） */
    public synchronized Map<String, Integer> listSources() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RawChunk rc : raw) {
            counts.merge(rc.source, 1, Integer::sum);
        }
        return counts;
    }

    // ---------- 检索 ----------

    /**
     * 检索：问题向量化 → 与所有 chunk 算余弦相似度 → 取 Top-K，
     * 并对每个命中 chunk 拼接其相邻 chunk（上下文增强），最后去重返回。
     *
     * <p><b>为什么拼接相邻块</b>：即使按标题切块，检索也可能只命中「标题块」而漏掉紧随其后的正文块。
     * 把命中的 chunk 与它前后相邻的块一起返回，能让模型拿到完整上下文，避免「只见标题不见正文」。
     */
    public synchronized List<Hit> search(String query, int topK) {
        if (!built) build(); // 惰性 build，防漏调
        double[] qv = vectorOf(query);
        List<Hit> hits = new ArrayList<>();
        for (Chunk c : chunks) {
            hits.add(new Hit(c, cosine(qv, c.vector)));
        }
        hits.sort((a, b) -> Double.compare(b.score, a.score));

        // 取 Top-K，并为每个命中拼接「同文档内」的相邻 chunk（前后各 1 块）
        int k = Math.min(topK, hits.size());
        Set<String> seen = new LinkedHashSet<>(); // 保序去重
        for (int i = 0; i < k; i++) {
            Chunk c = hits.get(i).chunk;
            int idx = chunkIndex(c);
            // 前一个相邻块（须同 source，避免跨文档拼接）
            if (idx > 0 && chunks.get(idx - 1).source.equals(c.source)) {
                seen.add(chunks.get(idx - 1).id);
            }
            seen.add(c.id);
            // 后一个相邻块（须同 source）
            if (idx + 1 < chunks.size() && chunks.get(idx + 1).source.equals(c.source)) {
                seen.add(chunks.get(idx + 1).id);
            }
        }

        // 按 id 还原成 Hit，并重新按相似度排序（相邻块用其自身与 query 的相似度）
        List<Hit> result = new ArrayList<>();
        for (Chunk c : chunks) {
            if (seen.contains(c.id)) {
                double s = cosine(qv, c.vector);
                if (s >= MIN_SCORE) { // 过滤完全无关的片段（相关度≈0），避免噪声进上下文
                    result.add(new Hit(c, s));
                }
            }
        }
        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result;
    }

    /** 最小相似度阈值：低于此值的片段视为无关，不送入模型 */
    private static final double MIN_SCORE = 0.02;

    /** 从 chunk id（chunk-N）反解其在 chunks 列表中的下标 */
    private int chunkIndex(Chunk c) {
        try {
            return Integer.parseInt(c.id.substring("chunk-".length()));
        } catch (Exception e) {
            return chunks.indexOf(c);
        }
    }

    public synchronized int size() {
        return chunks.size();
    }

    // ---------- 核心算法 ----------

    /** 把一段文本转成 TF-IDF 向量（维度 = 词表大小；检索时用锁定的词表投影） */
    private double[] vectorOf(String text) {
        int dim = vocab.size();
        double[] vec = new double[dim];
        Map<String, Integer> tf = termFrequency(tokenize(text));
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            Integer col = vocab.get(e.getKey());
            if (col != null) {
                vec[col] = e.getValue() * idf(e.getKey());
            }
        }
        return vec;
    }

    /** 简化中文分词：2-gram 滑动窗口（对中文友好，能覆盖「家庭」「family」等词与字组合） */
    private List<String> tokenize(String text) {
        String s = text.toLowerCase().replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", "");
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < s.length() - 1; i++) {
            tokens.add(s.substring(i, i + 2));
        }
        return tokens;
    }

    private Map<String, Integer> termFrequency(List<String> tokens) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);
        return tf;
    }

    /** IDF：出现越少的词权重越高。+1 平滑防除零 */
    private double idf(String term) {
        int d = df.getOrDefault(term, 0);
        return Math.log((double) (raw.size() + 1) / (d + 1)) + 1;
    }

    /** 余弦相似度：两向量夹角的余弦，值域 [-1,1]，越接近 1 越相似 */
    private double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
