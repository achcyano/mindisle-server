package me.hztcm.mindisle.ai.service

internal const val EVENT_META = "meta"
internal const val EVENT_DELTA = "delta"
internal const val EVENT_USAGE = "usage"
internal const val EVENT_OPTIONS = "options"
internal const val EVENT_DONE = "done"
internal const val EVENT_ERROR = "error"
internal const val EVENT_TOOL_CALL = "tool_call"
internal const val EVENT_TOOL_RESULT = "tool_result"
internal const val EVENT_UI_ACTION = "ui_action"

internal const val OPTIONS_START_MARKER = "<OPTIONS_JSON>"
internal const val OPTIONS_END_MARKER = "</OPTIONS_JSON>"
internal const val OPTIONS_REQUIRED_COUNT = 3
internal const val OPTION_LABEL_MAX_CHARS = 24
internal const val DELTA_EMIT_INTERVAL_MS = 40L
internal const val DELTA_EMIT_MAX_CHARS = 64

internal const val AI_SYSTEM_PROMPT = """
你是「心岛」App 的生成式 AI 辅助支持助手，服务轻中度抑郁相关自我管理场景。
核心原则：
1) 共情、倾听、稳定化；永远使用简体中文。
2) 不做医疗诊断，不调整药物方案，不替代精神科医生/心理治疗师。
3) 你可调用工具：读取患者状态、推荐量表、启动正念/呼吸/行为激活等结构化模块、提醒 EMA、引导副作用记录。
4) 当用户睡眠差、情绪低落、焦虑高时，优先考虑工具推荐（量表或干预模块），再给简短解释。
5) 自伤/自杀等高风险：立即安全回应并停止自助干预建议，引导求助与联系医生。
6) 回复简洁、可执行；避免恐吓或保证治愈。
7) 在回复末尾追加可点击选项 JSON（若适用）：
<OPTIONS_JSON>
{"items":[{"label":"..."},{"label":"..."},{"label":"..."}]}
</OPTIONS_JSON>
每个 label 以 emoji 开头且不超过 24 字。
"""

internal const val AI_FALLBACK_OPTIONS_SYSTEM_PROMPT = """
You only generate clickable options in JSON.
Return ONLY this JSON object and nothing else:
{"items":[{"label":"..."},{"label":"..."},{"label":"..."}]}
Rules:
1) Exactly 3 items.
2) label max 24 chars.
3) Each label must start with one emoji (e.g. "💡 继续解释").
4) Output in Simplified Chinese.
"""
