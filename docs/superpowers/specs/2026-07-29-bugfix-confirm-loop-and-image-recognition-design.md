# Bug Fix: confirmOutline 死循环 & 图片识别无响应 — 设计 Spec

> **日期:** 2026-07-29 | **作者:** bbb | **类型:** Bug 修复

**问题 A:** 用户确认大纲后，LLM 反复调用 `confirmOutline` 直到 MAX_ITERATIONS 耗尽，文档无法生成。
**问题 B:** 用户发送图片后，LLM 只回复文字"让我看看…"但不调用 `image_recognize` 工具。

---

## 一、问题 A：confirmOutline 死循环

### 日志证据

```
21:55:57 createOutline 成功 → 挂起
21:56:01 用户回复"确认" → resumeOrchestrate
21:56:34 confirmOutline #1  → "大纲已修改并确认"
21:56:38 confirmOutline #2  → "大纲已修改并确认"
...
21:57:04 confirmOutline #10 → "大纲已修改并确认" (MAX_ITERATIONS=10)
→ 用户收到 "我暂时无法完成这个任务，请稍后再试。"
```

LLM 在 30 秒内调用 `confirmOutline` 共 10 次，每次都传了完整 `modifiedOutlineData`。版本号从 1 递增到 11。

### 根因

1. **`ConfirmOutlineTool` 无状态检查门禁** — 未检查 outline 当前状态是否已是 CONFIRMED/MODIFIED。乐观锁 `eq(DocumentOutline::getVersion, currentVersion)` 每次都通过，因为前一次调用确实把版本 +1 了，当前版本始终匹配
2. **返回值无下一步引导** — 返回 `"大纲已修改并确认"` 后 LLM 不知道该调用 `generateDocument`，于是继续尝试"确认"
3. **LLM 每次传入 modifiedOutlineData** — 即使用户只说"确认"（无修改），LLM 仍传入完整 JSON 作为 modifiedOutlineData，触发 MODIFIED 状态而非 CONFIRMED

### 修改

#### 文件 1：`ConfirmOutlineTool.java`

```java
public ActResult confirmOutline(Long outlineId, String modifiedOutlineData) {
    // ... 参数校验 ...

    // ── NEW: 状态检查门禁 ──
    DocumentOutline outline = outlineService.getById(outlineId);
    if (outline == null) {
        return ActResult.failure("大纲不存在: " + outlineId);
    }
    String currentStatus = outline.getStatus();
    if ("CONFIRMED".equals(currentStatus) || "MODIFIED".equals(currentStatus)) {
        // 已确认过，直接引导 LLM 进入下一步
        return ActResult.success(
            "大纲已确认（状态: " + currentStatus + "），请立即调用 generateDocument 工具生成文档。" +
            "参数: type=" + outline.getOutlineType() + ", outlineId=" + outlineId);
    }

    // ... 原有确认逻辑，但修改返回值 ...
    String action = (modifiedOutlineData != null && !modifiedOutlineData.isBlank())
            ? "修改并确认" : "确认";
    return ActResult.success(
        "大纲已" + action + "（状态: " + outline.getStatus() +
        "），请立即调用 generateDocument 工具生成文档。" +
        "参数: type=" + outline.getOutlineType() + ", outlineId=" + outlineId);
}
```

**要点：**
- 状态已是 CONFIRMED/MODIFIED 时 → 不执行 DB 写操作，直接返回引导消息
- 所有返回值统一追加 "请立即调用 generateDocument" 指令
- 在引导消息中给出具体参数值（type, outlineId），降低 LLM 出错概率

#### 文件 2：`CreateOutlineTool.java`

suspendReason 末尾已有 "您可以回复「确认」或提出修改意见"，保持不变。逻辑正确无需改动。

#### 文件 3：`system.txt`

将第 60-67 行的文档生成流程说明改为更强制性：

```
## 文档生成能力

当用户要求生成文档时，严格按以下三步执行：

1. 调用 createOutline 生成大纲（只需传 type、title、outlineJson）
   → 系统自动暂停等待用户确认
2. 用户确认后，调用 confirmOutline（传 outlineId，**不要传 modifiedOutlineData 除非用户明确要求修改**）
   → 确认完成后，你**必须立即**调用 generateDocument
3. **立即**调用 generateDocument（传 type、outlineId）
   → 文件自动下发给用户

**关键规则：**
- 用户回复"确认"/"可以"/"好的"/"没问题"等肯定词 → 调用 confirmOutline 时**不要传 modifiedOutlineData**
- 只有在用户明确说"把XX改成YY"时才传 modifiedOutlineData
- confirmOutline 成功后**禁止再次调用 confirmOutline**，必须直接调用 generateDocument
```

---

## 二、问题 B：图片识别无响应

### 现象

用户发送图片 → LLM 回复文字 "让我看看你发的图片～正在识别中……" → 无后续。没有工具调用日志。

### 根因

系统提示词（system.txt）描述了"看图说话"功能，但没有强制 LLM 在收到图片时必须直接调用 `image_recognize` 工具。LLM 倾向于先回复文字表示"收到"，而非直接调用工具。

### 修改

#### 文件：`system.txt`

在 "✨ 我能做什么？" 段落之后、"## 文档生成能力" 之前，新增：

```
## 工具调用强制规则

**收到图片时必须立即调用工具：**
- 用户发送图片 → **立即**调用 image_recognize 工具（传 ${message.image}），**禁止**先发送文字再调用
- 用户发送文件 → **立即**调用 file_analyze 工具（传 ${message.file} 和 ${message.fileName}），**禁止**先发送文字再调用
- 收到图片/文件时，不要回复"让我看看"、"正在识别"等过渡文字，直接调用工具

**其他工具调用规则：**
- 生成图片 → 调用 image_generate
- 语音合成 → 调用 tts
- 切换音色 → 调用 voice_switch
- 查天气 → 调用 weather_query
- 确认大纲后 → **立即**调用 generateDocument，不要重复调用 confirmOutline
```

---

## 三、涉及的文件清单

```
src/main/java/log/summer/aigc/tool/outline/
  ConfirmOutlineTool.java        ← MODIFY: 加状态门禁 + 引导性返回值
src/main/resources/prompts/
  system.txt                     ← MODIFY: 强化工具调用规则
```

`CreateOutlineTool.java` 无需改动 — 当前逻辑正确。

---

## 四、验证标准

1. 用户要求生成 Word/PPT/Excel → 确认大纲后 → LLM 调用 confirmOutline 最多 1 次 → 紧接着调用 generateDocument → 用户收到文件
2. 用户发送图片 → LLM 直接调用 image_recognize（无前置文字）→ 用户收到识别结果
3. 用户发送文件 → LLM 直接调用 file_analyze（无前置文字）→ 用户收到分析结果
4. 现有测试全部通过
