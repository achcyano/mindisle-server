package me.hztcm.mindisle.safety.service

data class SafetyScanResult(
    val highRisk: Boolean,
    val reasons: List<String>,
    val matchedTerms: List<String>
)

object SafetyScanner {
    private val riskPatterns = listOf(
        "不想活", "结束生命", "自杀", "自残", "割腕", "跳楼", "上吊",
        "去死", "活不下去", "解脱吧", "自我了断", "了结自己", "寻死",
        "kill myself", "suicide", "end my life", "want to die"
    )

    fun scan(text: String): SafetyScanResult {
        val normalized = text.lowercase()
        val hits = riskPatterns.filter { term ->
            normalized.contains(term.lowercase())
        }
        return SafetyScanResult(
            highRisk = hits.isNotEmpty(),
            reasons = if (hits.isNotEmpty()) listOf("SUICIDE_SELF_HARM_KEYWORD") else emptyList(),
            matchedTerms = hits
        )
    }

    const val CRISIS_MESSAGE =
        "我注意到你可能正处于非常艰难、甚至危险的时刻。你的安全最重要。" +
            "请立刻联系身边可信任的人，或拨打当地紧急求助电话/心理援助热线，并尽快联系你的主治医生。" +
            "我可以继续陪伴倾听，但不能替代专业危机干预。"
}
