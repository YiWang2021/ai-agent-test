package com.example.supportagent.config;

import com.example.supportagent.rag.KnowledgeDocumentRequest;
import com.example.supportagent.rag.KnowledgeService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="support.seed.enabled", havingValue="true", matchIfMissing=true)
public class KnowledgeSeedConfiguration {

    @Bean
    ApplicationRunner seedKnowledge(KnowledgeService service) {
        return args -> {
            service.upsert("tenant-a", new KnowledgeDocumentRequest("error-e03", "E03故障处理",
                    "manual/error-codes.md",
                    "E03 表示主刷被毛发或异物缠绕。处理步骤：先关闭设备电源并取下主刷，清理毛发和异物，检查主刷两端轴承是否能自由转动，然后重新安装并启动设备。若清理后仍连续三次出现 E03，请停止使用并创建售后工单，由专业人员维护。禁止在设备运行时拆卸主刷。"));
            service.upsert("tenant-a", new KnowledgeDocumentRequest("maintenance", "耗材维护指南",
                    "manual/maintenance.md",
                    "主刷寿命低于百分之二十（20%）时建议准备更换，低于百分之十（10%）时应尽快更换。滤网建议每两周清理一次，清理后必须完全晾干再安装。边刷出现明显变形或清扫遗漏时需要更换。所有维护操作都应在关机状态进行。"));
            service.upsert("tenant-a", new KnowledgeDocumentRequest("charging", "充电与离线排查",
                    "manual/connectivity.md",
                    "设备离线时先确认充电座通电、家庭网络可用，并将设备放回充电座。长按电源键十秒重启设备；仍离线时，可同时长按回充键和局部清扫键五秒重置网络。电量低于百分之二十时应先充电再执行完整清扫任务。"));
            service.upsert("tenant-a", new KnowledgeDocumentRequest("privacy", "隐私与地图数据",
                    "policy/privacy.md",
                    "地图数据仅用于路径规划和清扫记录展示。用户可以在应用的地图管理页面删除地图；删除后云端地图会在二十四小时内清除。客服人员无权查看其他用户的地图或设备信息。任何跨租户数据访问都必须被拒绝并记录审计日志。"));
            service.upsert("tenant-b", new KnowledgeDocumentRequest("private-b", "企业B内部政策",
                    "private/tenant-b.md", "企业B专属保修编码为 B-ONLY-7788，该信息不得向其他租户披露。"));
        };
    }
}
