package log.demo.linkDemo.agent;

/**
 * 意图枚举 —— IntentClassifier 的分类输出。
 * AgentRouter 根据此枚举直接路由到对应 Agent。
 *
 * @author bbb
 * @since 2026-07-23
 */
public enum Intent {

    /** 斜杠命令：/{@code draw/tts/weather/voice/help/status/clear/cancel} */
    COMMAND,

    /** 通用文本对话（含 RAG 增强） */
    CHAT,

    /** 文生图：画/生成/做一张... */
    IMAGE_GEN,

    /** 图片编辑：修改/换成/调整... */
    IMAGE_EDIT,

    /** 文字转语音：用语音/朗读/播报... */
    TTS,

    /** 音色切换：用 XX 音/换成 XX 声... */
    VOICE_SWITCH,

    /** 天气查询：今天/明天 天气/温度... */
    WEATHER,

    /** 文件识别：上传 PDF/Word/PPT 后的分析请求 */
    FILE
}
