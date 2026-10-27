package com.example.demo.rag;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 启动时把几篇「示例私有文档」喂进知识库，演示 RAG。
 *
 * <p>内容故意写成「模型训练数据里没有」的私有知识（模拟鼎校/题库系统的内部文档）。
 * 这样你才能直观看到：直接问模型它答不出，但走 RAG 检索后就能照着资料答对。
 */
@Component
public class KnowledgeBaseSeeder {

    private final VectorStoreService store;

    public KnowledgeBaseSeeder(VectorStoreService store) {
        this.store = store;
    }

    @PostConstruct
    public void seed() {
        store.addDocument("鼎校·家长学生关联表设计", ""
                + "tb_parent_student_rel 是家长与学生的关联表，用 family_id 把家长和学生绑定到同一个家庭。\n"
                + "一个家长可以有多个孩子，一个孩子也可以被多个家长（父亲、母亲）共同管理。\n"
                + "family_id 采用雪花算法生成的 bigint(20)，全局唯一。\n"
                + "查询某个学生的所有家长：select * from tb_parent_student_rel where student_id = ?。\n"
                + "学生主键 student_id 统一使用 varchar(64)。");

        store.addDocument("鼎校·阿里云 OSS 上传方案", ""
                + "图片上传已从后端中转改为 STS 临时凭证前端直传，不再经过后端服务器。\n"
                + "bucket 是 studio-zero-test，region 是 oss-cn-shanghai。\n"
                + "前端先向后端申请 STS 临时凭证（AccessKeyId/Secret/SecurityToken），再用它直传 OSS。\n"
                + "预签名 PUT URL 上传时曾遇到 SignatureDoesNotMatch，原因是签名参数顺序或编码不一致。\n"
                + "改用 STS 临时凭证后，签名由阿里云 SDK 自动处理，不再手工拼签名。");

        store.addDocument("鼎校·果园游戏核心表", ""
                + "学生果园游戏系统设计了五张核心表：果园（主表）、土地、植物、背包/篮子、生命周期日志。\n"
                + "每块土地种植一棵植物，植物有生长阶段和成熟时间。\n"
                + "OrchardPlantVO 需要按 gridId 分组展示，同组内按成熟时间倒序（nullsLast）。\n"
                + "背包/篮子记录学生收获的果实数量。\n"
                + "生命周期日志记录每棵植物从播种到收获的完整过程，用于回放成长轨迹。");

        store.addDocument("题库·试卷作答隔离方案", ""
                + "题库系统支持从 Excel 模板批量导入题目，从第 3 行开始读取，已导入 1000 道符号解码题。\n"
                + "学生作答时，试卷题目与正在编辑的题目要隔离，避免作答中题目被修改导致答案错乱。\n"
                + "边界场景包括：作答中修改题目、删除题目、放弃提交，都需要做快照或版本隔离。\n"
                + "做法是给试卷生成时对题目做一份快照副本，作答读快照，编辑改原题。");

        store.build();
    }
}
