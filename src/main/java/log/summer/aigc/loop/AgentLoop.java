// 声明所属包，位于 aigc.loop 子包下，是 Agent 双循环的核心编排模块
package log.summer.aigc.loop;

import log.summer.aigc.context.UserContextHolder;
import log.summer.aigc.entity.Conversation;
import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.service.ChatPersistenceService;
import log.summer.aigc.session.SessionStateManager;
import log.summer.aigc.config.GlobalExceptionHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 双循环 Agent 编排器。  ← 原有 Javadoc 保留
 *
 * <h3>架构：内循环思考 + 外循环执行</h3>
 * ... 省略原有架构图注释 ...
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final ThinkLoop thinkLoop;
    private final ActLoop actLoop;
    private final ChatMemory chatMemory;
    private final GlobalExceptionHandler exceptionHandler;
    private final SessionStateManager sessionStateManager;
    private final ChatPersistenceService chatPersistenceService;

    private static final int MAX_ITERATIONS = 10;
    private static final long TIMEOUT_SECONDS = 300;

    /**
     * 编排单条用户消息的完整双循环流程。
     * @param msg   标准化后的用户消息（文字、图片、文件等）
     * @param sender 消息发送器，用于向用户推送回复
     */
    public void orchestrate(BotMessage msg, MessageSender sender) {
        //提取用户ID，后续操作的关键标识
        String userId = msg.userId();
        //挂起标志，初始为 false
        boolean suspended = false;

        //将当前用户ID设置到 ThreadLocal 中，供工具调用时获取
        UserContextHolder.setUserId(userId);
        //从数据库解析活跃对话ID，并设置到 ThreadLocal（可能为 null）
        UserContextHolder.setConversationId(resolveActiveConvId(userId));

        //根据用户ID查找挂起的对话记录
        Conversation suspendedConv = sessionStateManager.getSuspendedByUser(userId);
        //若存在挂起会话，则走专门的恢复流程
        if (suspendedConv != null) {
            //将对话ID修正为挂起的会话ID
            UserContextHolder.setConversationId(suspendedConv.getId());
            try {
                //委托给恢复编排方法，传入挂起会话对象
                resumeOrchestrate(msg, sender, suspendedConv);
            } finally {
                //无论是否发生异常，恢复流程结束后务必清理上下文
                UserContextHolder.clear();
            }
            //恢复处理完毕，直接返回，不再执行后续普通编排
            return;
        }

        //将本次用户消息追加到 ChatMemory（对话历史）
        appendUserMessage(userId, msg);

        // 记录编排开始时间，用于超时判断
        Instant start = Instant.now();
        //循环轮数计数器
        int round = 0;
        //标记：是否已经向用户发送了最终回答文本
        boolean finalAnswerSent = false;

        try {
            //条件1：未超过最大轮数；条件2：未超时（每次循环体开头再次检查）
            while (round < MAX_ITERATIONS) {
                //检查当前时间是否超过开始时间 + 超时阈值
                if (Instant.now().isAfter(start.plusSeconds(TIMEOUT_SECONDS))) {
                    //超时：给用户反馈并记录警告
                    sender.sendText(userId, "处理超时，请稍后再试。");
                    log.warn("[AGENT-LOOP] 超时 | userId={} | rounds={}", userId, round);
                    // 29. 跳出循环
                    break;
                }

                //轮数 +1
                round++;
                //调试日志，记录当前轮次
                log.debug("[AGENT-LOOP] 第 {} 轮 | userId={}", round, userId);

                // ── 内循环：LLM 思考与决策 ──
                //4调用 ThinkLoop 进行思考，传入当前对话记忆
                ThinkResult think = thinkLoop.think(userId, chatMemory);

                // ── 情况1：纯文本最终答案（无工具调用） ──
                //如果有最终答案且没有请求调用工具
                if (think.hasFinalAnswer() && !think.hasToolCalls()) {
                    //直接发送文本答案给用户
                    sender.sendText(userId, think.finalAnswer());
                    //标记最终答案已发送
                    finalAnswerSent = true;
                    // 36. 退出循环（任务完成）
                    break;
                }

                // ── 情况2：先发文本答案，同时还需要执行工具（如 “我先帮你查天气”） ──
                //如果有最终答案文本（可能伴随工具调用）
                if (think.hasFinalAnswer()) {
                    //先发送文本部分给用户
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                }

                // ── 外循环：工具执行与感知 ──
                //如果 LLM 决定要调用工具
                if (think.hasToolCalls()) {
                    //遍历每一个工具调用请求
                    for (var toolCall : think.toolCalls()) {
                        //委托 ActLoop 执行具体工具，返回执行结果
                        log.info("查询工具：{}", toolCall);
                        ActResult result = actLoop.execute(
                                toolCall, msg, userId, chatMemory, sender);

                        //判断执行结果是否需要挂起（例如等待用户上传文件）
                        if (result.suspend()) {
                            // 43. 处理挂起逻辑（保存状态，通知用户）
                            handleSuspend(userId, toolCall.name(), result, sender);
                            // 44. 设置挂起标志为 true
                            suspended = true;
                            // 45. 不再执行后续工具调用，直接跳出工具循环
                            break;
                        }
                    }
                    // 46. 如果发生了挂起，跳出外层主循环，结束编排
                    if (suspended) break;
                    // 47. 未挂起，继续下一轮思考（工具结果已回灌到 chatMemory）
                    continue;
                }

                // ── 情况3：无工具调用、无文本（LLM 空响应，理论上不应发生） ──
                // 48. 记录警告日志
                log.warn("[AGENT-LOOP] LLM 空响应 | userId={} | round={}", userId, round);
                // 49. 给用户一个兜底回复
                sender.sendText(userId, "我暂时无法处理这个请求，请换个方式试试。");
                // 50. 跳出循环
                break;
            }

            // ── 循环后处理：检查是否因为达到最大轮数强制退出 ──
            // 如果最终答案未发送 且 未挂起 且 达到了最大轮数
            if (!finalAnswerSent && !suspended && round >= MAX_ITERATIONS) {
                // 52. 给出提示，避免用户无响应
                sender.sendText(userId, "我暂时无法完成这个任务，请稍后再试。");
            }
        } catch (Exception e) {
            // 捕获编排过程中的任何未处理异常
            log.error("[AGENT-LOOP] 循环异常 | userId={}", userId, e);
            // 54. 通过统一异常处理器向用户发送友好提示
            exceptionHandler.handle(userId, sender, "AgentLoop", e);
        } finally {
            // ── 清理工作 ──
            // 如果当前没有挂起（正常结束或异常结束），则清除该用户的记忆和缓存
            if (!suspended) {
                // 清除 Spring AI 的对话记忆，释放内存
                chatMemory.clear(userId);
                //清除会话状态缓存（如果有）
                sessionStateManager.evictCache(userId);
            }
            //  清除 ThreadLocal 中的 userId 和 conversationId，防止内存泄漏和串用
            UserContextHolder.clear();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 恢复路径：处理之前挂起的会话
    // ═══════════════════════════════════════════════════════════

    /**
     * 从挂起点恢复编排。
     * @param msg          当前用户消息（可能是继续的指令或上传的文件）
     * @param sender       消息发送器
     * @param suspendedConv 之前挂起的对话对象
     */
    private void resumeOrchestrate(BotMessage msg, MessageSender sender,
                                   Conversation suspendedConv) {
        //获取用户ID
        String userId = msg.userId();
        //更新 ThreadLocal 中的 conversationId 为挂起的会话ID
        UserContextHolder.setConversationId(suspendedConv.getId());

        //清空当前对话记忆，为恢复做准备（避免残留）
        chatMemory.clear(userId);
        //从数据库恢复挂起前的对话消息到 ChatMemory
        sessionStateManager.restoreMessages(suspendedConv, chatMemory);
        //将当前用户消息（恢复时的输入）追加到记忆
        appendUserMessage(userId, msg);
        //清除数据库中的挂起标记，表示恢复流程已启动
        sessionStateManager.clearSuspend(suspendedConv.getId());

        boolean suspended = false;
        Instant start = Instant.now();
        int round = 0;
        boolean finalAnswerSent = false;

        try {
            while (round < MAX_ITERATIONS) {
                if (Instant.now().isAfter(start.plusSeconds(TIMEOUT_SECONDS))) {
                    sender.sendText(userId, "处理超时，请稍后再试。");
                    break;
                }

                round++;

                ThinkResult think = thinkLoop.think(userId, chatMemory);

                if (think.hasFinalAnswer() && !think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                    break;
                }

                if (think.hasFinalAnswer()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                }

                if (think.hasToolCalls()) {
                    for (var toolCall : think.toolCalls()) {
                        ActResult result = actLoop.execute(
                                toolCall, msg, userId, chatMemory, sender);

                        if (result.suspend()) {
                            // 65. 注意：恢复过程中再次挂起，需要获取正确的对话ID
                            long convId = resolveConvId(userId, suspendedConv.getId());
                            UserContextHolder.setConversationId(convId);
                            handleSuspend(userId, toolCall.name(), result, sender);
                            suspended = true;
                            break;
                        }
                    }
                    if (suspended) break;
                    continue;
                }

                sender.sendText(userId, "我暂时无法处理这个请求，请换个方式试试。");
                break;
            }

            if (!finalAnswerSent && !suspended && round >= MAX_ITERATIONS) {
                sender.sendText(userId, "我暂时无法完成这个任务，请稍后再试。");
            }
        } catch (Exception e) {
            log.error("[AGENT-LOOP] 恢复后异常 | userId={}", userId, e);
            exceptionHandler.handle(userId, sender, "AgentLoop-resume", e);
        } finally {
            if (!suspended) {
                chatMemory.clear(userId);
                sessionStateManager.evictCache(userId);
            }
            UserContextHolder.clear();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 处理工具执行后的挂起操作。
     * @param userId   用户ID
     * @param toolName 触发挂起的工具名称
     * @param result   工具执行结果，包含挂起原因和提示数据
     * @param sender   消息发送器
     */
    private void handleSuspend(String userId, String toolName,
                               ActResult result, MessageSender sender) {
        // 记录挂起日志（工具名、原因）
        log.info("[AGENT-LOOP] 挂起 | userId={} | tool={} | reason={}",
                userId, toolName, result.suspendReason());
        // 如果结果中包含给用户的提示数据，立即发送
        if (result.data() != null) sender.sendText(userId, result.data().toString());
        // 如果挂起原因文本不为空，也发送（例如 “请上传图片”）
        if (result.suspendReason() != null) sender.sendText(userId, result.suspendReason());

        // 解析当前对话ID（若暂无则使用0L作为后备）
        long convId = resolveConvId(userId, 0L);
        // 更新 ThreadLocal 中的 conversationId，确保后续持久化正确
        UserContextHolder.setConversationId(convId);
        // 委托 SessionStateManager 保存挂起状态（对话消息、挂起原因等）
        sessionStateManager.suspend(userId, convId, toolName, result.suspendReason(), chatMemory);
    }

    /**
     * 将 BotMessage 转换为 Spring AI 的 UserMessage 并加入对话记忆。
     */
    private void appendUserMessage(String userId, BotMessage msg) {
        // 根据消息类型构造合适的提示文本，存入 ChatMemory
        if (msg.hasText()) {
            // 文本消息：直接添加
            chatMemory.add(userId, new UserMessage(msg.text()));
        } else if (msg.hasImage()) {
            // 图片消息：添加占位描述（实际图片数据单独处理）
            chatMemory.add(userId, new UserMessage("[用户发送了一张图片]"));
        } else if (msg.hasFile()) {
            // 文件消息：添加文件名信息
            chatMemory.add(userId, new UserMessage("[用户发送了文件: " + msg.fileName() + "]"));
        }
    }

    /**
     * 从数据库解析当前活跃的对话ID。
     * @param userId   用户ID
     * @param fallback 若查询失败或不存在时的默认值
     * @return 对话ID
     */
    private long resolveConvId(String userId, long fallback) {
        try {
            // 调用持久化服务获取活跃对话
            Conversation c = chatPersistenceService.getActiveConversation(userId);
            // 若存在返回其ID，否则返回备用值
            return c != null ? c.getId() : fallback;
        } catch (Exception e) {
            // 查询异常时也返回备用值，保证流程不中断
            return fallback;
        }
    }

    /**
     * 获取活跃对话ID的包装，返回 Long（可为 null）。
     */
    private Long resolveActiveConvId(String userId) {
        try {
            Conversation c = chatPersistenceService.getActiveConversation(userId);
            // 返回对话ID，若无活跃对话则返回 null
            return c != null ? c.getId() : null;
        } catch (Exception e) {
            // 异常时返回 null，调用方会使用默认值
            return null;
        }
    }
}