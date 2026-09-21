//! uci.rs — UCI 读写与安全提交。
//!
//! uci namespace 封装，rpcd 语义约束：
//! - commit 不存在：luci-base rpcd ACL 刻意不授予 uci.commit（强制走 apply），
//!   但授予 uci.apply + uci.confirm。apply{rollback:true} 内部已 commit + reload +
//!   启动回滚定时；confirm 取消回滚使改动永久生效。与 luci web 保存完全同路径
//! - add 不传 name/values：rpcd uci.add 只创建匿名空壳，带 values 会 INVALID_ARGS[2]，
//!   字段值由后续 set 填入（对齐 luci）
//! - uci.set 不接受空 list：空数组跳过不写（副作用：无法清空已选 list，
//!   rpcd 无 option-delete 接口；新增/无值场景=uci 无该 option=正确的空）
//! - reorder 实为 order：rpcd 方法名是 uci.order（ACL 只授予 order），params.sections
//!   为该 config 全部 section 名按目标顺序全量列出
//! - get 解包 .values：rpcd 返回 {values:{sections...}}，每个 section 带元数据键
//!   .type/.anonymous/.name
//!
//! 已知风险窗口（uci 模型固有）：
//! - staging 残留：多步写序列（add→set→commit）在 commit 前任一步失败，已落
//!   staging 的半成品会残留，此后任何一次不相关 apply 都会将其冲上线（uci apply
//!   提交全部 pending，config 参数不隔离）。重试路径（set 幂等 + 重新 commit）可自愈
//! - 确定性序依赖：serde_json 默认 Map=BTreeMap（未开 preserve_order feature）+
//!   本文件 BTreeMap。谁给 serde_json 加了 preserve_order，UI 列表序即变为
//!   服务器返回序（仍确定但会变）

use crate::error::UbusError;
use crate::rpc::RouterClient;
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::time::Duration;

/// rpcd apply 的回滚窗口秒数。跨网往返慢，120s 是给 confirm 留的余量
/// （跨网 confirm 余量，勿改）
pub const ROLLBACK_TIMEOUT_SECS: u64 = 120;

/// uci 与候选/探测类调用共用 8s 超时（uci 与候选/探测类调用共用）
pub const UCI_CALL_TIMEOUT: Duration = Duration::from_secs(8);

/// UCI section（get 的类型化产物，元数据键 .type/.anonymous/.name 归位）
#[derive(Debug, Clone, serde::Serialize)]
pub struct UciSection {
    pub name: String,
    pub section_type: String,
    pub anonymous: bool,
    /// 除元数据键外的全部 option（list 为数组、标量为字符串，与 rpcd 原样一致）
    pub options: BTreeMap<String, Value>,
}

impl RouterClient {
    /// uci.get → 解包 .values → 类型化 section 表。
    /// LuCI 返回原始 map 由调用方过滤 .type；Rust 侧类型化后直接产出
    /// UciSection（行为等价：调用方本来就只消费带 .type 的条目）。
    pub async fn uci_get(&self, config: &str) -> Result<BTreeMap<String, UciSection>, UbusError> {
        let payload = self
            .call_ubus("uci", "get", json!({"config": config}), UCI_CALL_TIMEOUT)
            .await?;
        let raw = payload
            .get("values")
            .and_then(|v| v.as_object())
            .cloned()
            .unwrap_or_else(|| payload.as_object().cloned().unwrap_or_default());
        let mut sections = BTreeMap::new();
        for (key, entry) in raw {
            let Some(obj) = entry.as_object() else { continue };
            let Some(section_type) = obj.get(".type").and_then(|v| v.as_str()) else {
                continue;
            };
            let name = obj
                .get(".name")
                .and_then(|v| v.as_str())
                .unwrap_or(&key)
                .to_string();
            let anonymous = obj.get(".anonymous").and_then(|v| v.as_bool()).unwrap_or(false);
            let mut options = BTreeMap::new();
            for (k, v) in obj {
                if !k.starts_with('.') {
                    options.insert(k.clone(), v.clone());
                }
            }
            sections.insert(key, UciSection { name, section_type: section_type.to_string(), anonymous, options });
        }
        Ok(sections)
    }

    /// uci.add：只传 config+type，返回新 section 名。
    /// 带上 name/values 会触发 rpcd INVALID_ARGS[2]。
    pub async fn uci_add(&self, config: &str, section_type: &str) -> Result<String, UbusError> {
        let payload = self
            .call_ubus(
                "uci",
                "add",
                json!({"config": config, "type": section_type}),
                UCI_CALL_TIMEOUT,
            )
            .await?;
        // 
        // 载荷截断进错误信息（可能是整段响应，塞满 logcat 无意义）
        payload
            .get("section")
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .ok_or_else(|| {
                let full = payload.to_string();
                let short: String = full.chars().take(200).collect();
                UbusError::InvalidResponse(format!(
                    "uci.add response missing section: {short}"
                ))
            })
    }

    /// uci.set：空数组 value 就地剔除（rpcd 拒绝空 list）；非对象 values 本地拒绝
    pub async fn uci_set(&self, config: &str, section: &str, values: Value) -> Result<(), UbusError> {
        let Some(obj) = values.as_object() else {
            return Err(UbusError::InvalidArgument(
                "uci_set values must be a JSON object".into(),
            ));
        };
        let mut cleaned = Map::new();
        for (key, value) in obj {
            if value.as_array().is_some_and(|a| a.is_empty()) {
                continue;
            }
            cleaned.insert(key.clone(), value.clone());
        }
        self.call_ubus(
            "uci",
            "set",
            json!({"config": config, "section": section, "values": cleaned}),
            UCI_CALL_TIMEOUT,
        )
        .await
        .map(|_| ())
    }

    /// uci.delete
    pub async fn uci_delete(&self, config: &str, section: &str) -> Result<(), UbusError> {
        self.call_ubus(
            "uci",
            "delete",
            json!({"config": config, "section": section}),
            UCI_CALL_TIMEOUT,
        )
        .await
        .map(|_| ())
    }

    /// uci.order：rpcd 不授予 reorder，方法名是 order；按目标顺序全量列出该 config
    /// 的全部 section 名（与 luci uci.callOrder 一致）
    pub async fn uci_order(&self, config: &str, sections: &[String]) -> Result<(), UbusError> {
        self.call_ubus(
            "uci",
            "order",
            json!({"config": config, "sections": sections}),
            UCI_CALL_TIMEOUT,
        )
        .await
        .map(|_| ())
    }

    /// 安全提交——UCI 写路径的唯一出口：
    /// 保存前 session 预检（失效自动重登，防 apply 成功但 confirm 过期失败 →
    /// 被 120s 回滚）→ apply{rollback:true, timeout:120} → confirm。
    /// 全程持 commit_lock 串行化：rpcd 的 rollback/confirm 是全局单槽，并发 commit
    /// 的 confirm 可能交错消耗、令其中一方被静默回滚（见模块头已知风险窗口）
    pub async fn uci_commit(&self, config: &str) -> Result<(), UbusError> {
        let _serial = self.commit_lock.lock().await;
        self.ensure_session().await?;
        self.call_ubus(
            "uci",
            "apply",
            json!({"config": config, "rollback": true, "timeout": ROLLBACK_TIMEOUT_SECS}),
            UCI_CALL_TIMEOUT,
        )
        .await?;
        self.call_ubus("uci", "confirm", json!({"config": config}), UCI_CALL_TIMEOUT)
            .await?;
        Ok(())
    }

    /// 便捷：set + commit。仅供集成测试锚定「set→commit」契约——app 运行路径走
    /// 分离导出（Kotlin 侧 add/set/commit 自编排），两套编排不可混用语义假设
    pub async fn uci_set_commit(
        &self,
        config: &str,
        section: &str,
        values: Value,
    ) -> Result<(), UbusError> {
        self.uci_set(config, section, values).await?;
        self.uci_commit(config).await
    }

    /// 便捷：add + set（有值时）+ commit，返回新 section 名。契约锚定用途同上
    pub async fn uci_add_commit(
        &self,
        config: &str,
        section_type: &str,
        values: Value,
    ) -> Result<String, UbusError> {
        let name = self.uci_add(config, section_type).await?;
        if values.as_object().is_some_and(|o| !o.is_empty()) {
            self.uci_set(config, &name, values).await?;
        }
        self.uci_commit(config).await?;
        Ok(name)
    }

    /// 便捷：delete + commit。契约锚定用途同上
    pub async fn uci_delete_commit(&self, config: &str, section: &str) -> Result<(), UbusError> {
        self.uci_delete(config, section).await?;
        self.uci_commit(config).await
    }
}
