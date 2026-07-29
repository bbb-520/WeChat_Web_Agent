# Bug Fix: confirmOutline 死循环 & 图片识别无响应 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复两个由 LLM 工具调用行为导致的 bug：(A) confirmOutline 死循环导致文档永不生成，(B) 用户发图后 LLM 不调用 image_recognize 工具。

**Architecture:** 两个 bug 都在工具层和提示词层修复，不涉及 AgentLoop 编排逻辑变更。ConfirmOutlineTool 加状态门禁阻止重复确认，system.txt 加强制工具调用指令。

**Tech Stack:** Java 21, Spring Boot 3.2.10, Spring AI + DashScope

## Global Constraints

- 不修改 AgentLoop、ActLoop 的编排逻辑
- 不修改数据库 schema
- 不引入新依赖
- ConfirmOutlineTool 已有 `outlineService` (IService<DocumentOutline>) 可直接调用 `getById()`
- 系统提示词路径: `src/main/resources/prompts/system.txt`

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `ConfirmOutlineTool.java` | Modify | 加状态门禁 + 引导性返回值 |
| `system.txt` | Modify | 强制工具调用规则 |

---

### Task 1: ConfirmOutlineTool — 状态门禁 + 引导返回值

**Files:**
- Modify: `src/main/java/log/summer/aigc/tool/outline/ConfirmOutlineTool.java`

**Interfaces:**
- Consumes: `IDocumentOutlineService.getById(Long)` (from MyBatis Plus IService)
- Produces: 返回值统一包含下一步指令 `"请立即调用 generateDocument 工具生成文档。参数: type=..., outlineId=..."`

- [ ] **Step 1: 读取当前 ConfirmOutlineTool.java 确认最新内容**

```bash
cat src/main/java/log/summer/aigc/tool/outline/ConfirmOutlineTool.java
```

- [ ] **Step 2: 修改 confirmOutline 方法，在参数校验和 userId 获取之间插入状态检查门禁**

将当前方法体（第 39-67 行）替换为以下内容：

```java
    @Tool(name = "confirmOutline",
          description = "确认或修改文档大纲。用户确认大纲后调用此工具，将大纲状态从DRAFT转为CONFIRMED/MODIFIED。" +
                        "modifiedOutlineData为可选参数，如果用户有修改意见则传入修改后的大纲JSON；" +
                        "用户只说'确认'/'好的'等肯定词时不要传 modifiedOutlineData。" +
                        "注意：用户ID由系统自动注入，调用时无需填写。调用成功后必须立即调用 generateDocument。")
    public ActResult confirmOutline(
            @ToolParam(description = "要确认的大纲ID") Long outlineId,
            @ToolParam(description = "修改后的大纲JSON（可选，用户确认无修改时不传）")
            String modifiedOutlineData) {

        if (outlineId == null) {
            return ActResult.failure("请提供要确认的大纲ID");
        }

        // BUG FIX: pull userId from the per-request context.
        String userId = UserContextHolder.getUserId();
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return ActResult.failure("无法识别当前用户，会话上下文丢失，请重新发起对话");
        }

        // ── NEW: 状态检查门禁 —— 防止 LLM 反复调用 confirmOutline ──
        DocumentOutline outline = outlineService.getById(outlineId);
        if (outline == null) {
            return ActResult.failure("大纲不存在: " + outlineId + "，请重新生成大纲");
        }
        String currentStatus = outline.getStatus();
        if ("CONFIRMED".equals(currentStatus) || "MODIFIED".equals(currentStatus)) {
            log.info("[CONFIRM-OUTLINE] 大纲已确认，跳过重复调用 → 引导 LLM 进入 generateDocument | outlineId={} | status={}",
                    outlineId, currentStatus);
            return ActResult.success(
                    "大纲已确认（状态: " + currentStatus + "），无需重复确认。" +
                    "请立即调用 generateDocument 工具生成文档。" +
                    "参数: type=" + outline.getOutlineType() + ", outlineId=" + outlineId);
        }
        // ── END 状态检查门禁 ──

        boolean hasModification = modifiedOutlineData != null && !modifiedOutlineData.isBlank();
        boolean updated = outlineService.confirmOutline(outlineId, hasModification ? modifiedOutlineData : null);
        if (!updated) {
            log.warn("[CONFIRM-OUTLINE] 确认失败（大纲不存在或版本冲突） | outlineId={} | userId={}",
                    outlineId, userId);
            return ActResult.failure("大纲确认失败：大纲不存在或已被修改，请重新生成大纲");
        }

        // 重新查询获取最新状态
        outline = outlineService.getById(outlineId);
        String action = hasModification ? "修改并确认" : "确认";
        log.info("[CONFIRM-OUTLINE] 大纲已{} | outlineId={} | userId={} | newStatus={}",
                action, outlineId, userId, outline != null ? outline.getStatus() : "?");

        String docType = outline != null ? outline.getOutlineType() : "WORD";
        return ActResult.success(
                "大纲已" + action + "（状态: " + (outline != null ? outline.getStatus() : "CONFIRMED") + "），" +
                "请立即调用 generateDocument 工具生成文档。" +
                "参数: type=" + docType + ", outlineId=" + outlineId);
    }
```

注意：需要在文件顶部新增 import：

```java
import log.summer.aigc.entity.DocumentOutline;
```

- [ ] **Step 3: 验证编译通过**

```bash
cd "D:\Desktop\projectPractice\hm\summer-dev" && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/log/summer/aigc/tool/outline/ConfirmOutlineTool.java
git commit -m "fix: add status gate to ConfirmOutlineTool to prevent LLM infinite loop

- Check if outline is already CONFIRMED/MODIFIED before updating
- Return guiding message with next step (generateDocument + params)
- Update @Tool description to clarify when NOT to pass modifiedOutlineData"
```

---

### Task 2: system.txt — 强制工具调用规则

**Files:**
- Modify: `src/main/resources/prompts/system.txt`

- [ ] **Step 1: 将文档生成部分（第 60-67 行）替换为强制性流程指令**

定位到：

```
## 文档生成能力

你可以帮用户生成 Word、PPT、Excel 文档。流程如下：

1. 理解用户需求 -> 调用 createOutline 工具生成结构化大纲（只需要传 type、title、outlineJson 三个参数）
2. 系统会自动暂停等待用户确认/修改大纲
3. 用户确认后 -> 调用 confirmOutline 工具确认大纲（传 outlineId，可选传 modifiedOutlineData）
4. 大纲确认后 -> 调用 generateDocument 工具渲染最终文件（传 type、outlineId 即可，用户与会话ID系统自动注入）
5. 文件通过聊天直接下发给用户

三步工具调用链：createOutline → confirmOutline → generateDocument

大纲 JSON 格式示例：
{
  "title": "文档标题",
  "sections": [
    {"title": "章节标题", "points": ["要点1", "要点2"]}
  ]
}

支持的类型：WORD, PPT, EXCEL
```

替换为：

```
## 文档生成能力（严格流程）

当用户要求生成文档时，你**必须严格**按以下三步执行，不可跳过、不可重复：

1. **createOutline** — 生成大纲（只需传 type、title、outlineJson），系统自动暂停等用户确认
2. **confirmOutline** — 用户确认后调用（传 outlineId）
   - 用户说"确认"/"可以"/"好的"/"没问题"/"行"等肯定词 → **不要传 modifiedOutlineData**
   - 只有用户明确说"把XX改成YY"时才传 modifiedOutlineData
   - **确认成功后禁止再次调用 confirmOutline**
3. **generateDocument** — confirmOutline 成功后**必须立即调用**（传 type、outlineId）

**关键规则：**
- confirmOutline 最多调用一次，之后必须调用 generateDocument
- generateDocument 调用后文件会自动下发给用户

大纲 JSON 格式示例：
{
  "title": "文档标题",
  "sections": [
    {"title": "章节标题", "points": ["要点1", "要点2"]}
  ]
}

支持的类型：WORD, PPT, EXCEL
```

- [ ] **Step 2: 在 "✨ 我能做什么？" 段落之后、"## 文档生成能力" 之前，新增工具调用强制规则**

在系统提示词 "✨ 我能做什么？" 段落之后、`## 文档生成能力` 行之前（即 `/status — 当前状态` 之后的空行处）插入：

```
## 工具调用强制规则

**收到图片时：**
- 用户发送图片 → **立即**调用 image_recognize 工具，**禁止**先发送"让我看看"、"正在识别"等过渡文字
- 传参：image = ${message.image}

**收到文件时：**
- 用户发送文件 → **立即**调用 file_analyze 工具，**禁止**先发送过渡文字
- 传参：fileBytes = ${message.file}, fileName = ${message.fileName}

**其他规则：**
- 调用任何工具时，**不要**主动填写用户ID或会话ID，系统会自动注入
- 如果工具返回错误，根据错误信息调整后重试，不要放弃
- confirmOutline 成功后**禁止**再次调用 confirmOutline，必须直接调用 generateDocument
```

注意：原来的 "## 重要说明" 段落末尾的两条规则已合并到上面，需要删除原 "## 重要说明" 段落（第 87-89 行）以避免重复。

- [ ] **Step 3: 删除重复的 "## 重要说明" 段落**

删除 system.txt 末尾的：

```
## 重要说明
- 调用任何工具时，**不要**主动填写用户ID或会话ID，系统会自动注入
- 图片/语音/文档生成时，**直接调用工具即可**，用户消息会正常收到结果
```

- [ ] **Step 4: 验证文件完整性**

```bash
wc -l src/main/resources/prompts/system.txt
cat src/main/resources/prompts/system.txt
```

确认无重复段落、编码正常。

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/prompts/system.txt
git commit -m "fix: add mandatory tool-calling rules to system prompt

- Require image_recognize/file_analyze to be called immediately on media input
- Forbid preamble text before tool calls for images/files
- Make document generation flow rules more imperative
- Prevent confirmOutline re-invocation via explicit prohibition"
```

---

## Self-Review

**1. Spec coverage:**
- Bug A 状态门禁: Task 1 ✓
- Bug A 引导返回值: Task 1 ✓
- Bug A 系统提示词强化: Task 2 ✓
- Bug B 图片识别强制调用: Task 2 ✓
- Bug B 文件识别强制调用: Task 2 ✓

**2. Placeholder scan:** 无 TBD/TODO/占位符。所有代码块都是实际内容。

**3. Type consistency:** Task 1 使用 `outlineService.getById()` (IService 标准方法) 和 `DocumentOutline` entity (已有类型)，无命名冲突。
