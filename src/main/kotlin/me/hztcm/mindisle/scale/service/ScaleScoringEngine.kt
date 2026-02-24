package me.hztcm.mindisle.scale.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import me.hztcm.mindisle.db.ScaleQuestionType
import me.hztcm.mindisle.db.ScaleScoringMethod
import me.hztcm.mindisle.model.Gender
import java.math.BigDecimal
import java.math.RoundingMode

internal data class ScoreQuestionRow(
    val questionId: Long,
    val questionKey: String,
    val dimension: String?,
    val scorable: Boolean,
    val type: ScaleQuestionType = ScaleQuestionType.SINGLE_CHOICE
)

internal data class ScoreAnswerRow(
    val questionId: Long,
    val numericScore: BigDecimal?,
    val answerJson: String = "{}"
)

internal data class ScoreBandRow(
    val dimension: String?,
    val minScore: BigDecimal,
    val maxScore: BigDecimal,
    val levelCode: String,
    val levelName: String,
    val interpretation: String
)

internal data class ScoreDimensionResultRow(
    val dimensionKey: String,
    val dimensionName: String? = null,
    val rawScore: BigDecimal? = null,
    val averageScore: BigDecimal? = null,
    val standardScore: BigDecimal? = null,
    val levelCode: String? = null,
    val levelName: String? = null,
    val interpretation: String? = null,
    val extraMetrics: Map<String, BigDecimal> = emptyMap()
)

internal data class ScaleScoreComputationResult(
    val totalScore: BigDecimal?,
    val dimensionScores: Map<String, BigDecimal>,
    val overallMetrics: Map<String, BigDecimal> = emptyMap(),
    val dimensionResults: List<ScoreDimensionResultRow> = emptyList(),
    val resultFlags: List<String> = emptyList(),
    val bandLevelCode: String? = null,
    val bandLevelName: String? = null,
    val resultText: String? = null
)

internal object ScaleScoringEngine {
    private val json = Json { ignoreUnknownKeys = true }

    fun compute(
        method: ScaleScoringMethod,
        ruleJson: String,
        questions: List<ScoreQuestionRow>,
        answers: List<ScoreAnswerRow>,
        bands: List<ScoreBandRow>,
        userGender: Gender = Gender.UNKNOWN
    ): ScaleScoreComputationResult {
        val questionById = questions.associateBy { it.questionId }
        val answerByQuestionId = answers.associateBy { it.questionId }
        val scoredAnswers = answers.filter { row ->
            row.numericScore != null && questionById[row.questionId]?.scorable == true
        }
        val scoreByQuestionId = scoredAnswers.associate { it.questionId to (it.numericScore ?: BigDecimal.ZERO) }
        val totalScore = scoredAnswers.sumOfOrNull { it.numericScore ?: BigDecimal.ZERO }?.roundScale2()

        val rawDimensionScores = mutableMapOf<String, BigDecimal>()
        scoredAnswers.forEach { row ->
            val dimension = questionById[row.questionId]?.dimension ?: return@forEach
            val score = row.numericScore ?: return@forEach
            rawDimensionScores[dimension] = (rawDimensionScores[dimension] ?: BigDecimal.ZERO) + score
        }

        val ruleObj = parseRuleJson(ruleJson)
        val methodResult = when (method) {
            ScaleScoringMethod.SIMPLE_SUM -> computeSimple(totalScore)
            ScaleScoringMethod.PHQ9 -> computePhq9(totalScore, questions, scoreByQuestionId, answerByQuestionId, ruleObj)
            ScaleScoringMethod.GAD7 -> computeGad7(totalScore)
            ScaleScoringMethod.PSQI -> computePsqi(questions, scoreByQuestionId, answerByQuestionId)
            ScaleScoringMethod.SCL90 -> computeScl90(totalScore, questions, scoreByQuestionId)
            ScaleScoringMethod.EPQ -> computeEpq(questions, scoreByQuestionId, userGender, ruleObj)
        }

        val resolvedTotal = methodResult.totalScore ?: totalScore
        val resolvedDimensionScores = if (methodResult.dimensionScores.isNotEmpty()) {
            methodResult.dimensionScores
        } else {
            rawDimensionScores
        }
        val roundedDimensionScores = resolvedDimensionScores.mapValues { (_, value) -> value.roundScale2() }
        val resolvedDimensionResults = if (methodResult.dimensionResults.isNotEmpty()) {
            methodResult.dimensionResults.map { it.rounded() }
        } else {
            roundedDimensionScores.entries.sortedBy { it.key }.map { entry ->
                ScoreDimensionResultRow(
                    dimensionKey = entry.key,
                    rawScore = entry.value
                )
            }
        }

        val dbBand = if (methodResult.bandLevelCode == null && resolvedTotal != null) {
            bands.firstOrNull { band ->
                band.dimension == null && resolvedTotal >= band.minScore && resolvedTotal <= band.maxScore
            }
        } else {
            null
        }

        return ScaleScoreComputationResult(
            totalScore = resolvedTotal?.roundScale2(),
            dimensionScores = roundedDimensionScores,
            overallMetrics = methodResult.overallMetrics.mapValues { (_, value) -> value.roundScale2() },
            dimensionResults = resolvedDimensionResults,
            resultFlags = methodResult.resultFlags.distinct(),
            bandLevelCode = methodResult.bandLevelCode ?: dbBand?.levelCode,
            bandLevelName = methodResult.bandLevelName ?: dbBand?.levelName,
            resultText = methodResult.resultText ?: dbBand?.interpretation
        )
    }

    private fun computeSimple(totalScore: BigDecimal?): MethodScoreResult {
        return MethodScoreResult(totalScore = totalScore)
    }

    private fun computePhq9(
        totalScore: BigDecimal?,
        questions: List<ScoreQuestionRow>,
        scoreByQuestionId: Map<Long, BigDecimal>,
        answersByQuestionId: Map<Long, ScoreAnswerRow>,
        ruleObj: JsonObject?
    ): MethodScoreResult {
        if (totalScore == null) {
            return MethodScoreResult(totalScore = null)
        }
        val totalInt = totalScore.toInt()
        val (code, name) = when (totalInt) {
            in 0..4 -> "minimal" to "无抑郁"
            in 5..9 -> "mild" to "轻度抑郁"
            in 10..14 -> "moderate" to "中度抑郁"
            in 15..19 -> "moderately_severe" to "中重度抑郁"
            else -> "severe" to "重度抑郁"
        }
        val questionByKey = questions.associateBy { it.questionKey }
        val suicideKey = ruleObj?.string("suicideQuestionKey")?.ifBlank { null } ?: "q9"
        val functionImpactKey = ruleObj?.string("functionImpactQuestionKey")?.ifBlank { null } ?: "q10"
        val suicideScore = questionByKey[suicideKey]?.let { scoreByQuestionId[it.questionId] } ?: BigDecimal.ZERO
        val functionImpactScore = questionByKey[functionImpactKey]
            ?.let { answersByQuestionId[it.questionId] }
            ?.let { parseScoreFromAnswerJson(it.answerJson) }

        val flags = buildList {
            if (suicideScore >= BigDecimal.ONE) {
                add("SUICIDE_RISK")
            }
        }
        val metrics = buildMap {
            if (functionImpactScore != null) {
                put("functionImpact", functionImpactScore)
            }
        }
        val warning = if (flags.contains("SUICIDE_RISK")) {
            " 检测到自伤相关条目阳性，建议立即进行风险评估。"
        } else {
            ""
        }
        return MethodScoreResult(
            totalScore = totalScore,
            dimensionScores = mapOf("total" to totalScore),
            overallMetrics = metrics,
            dimensionResults = listOf(
                ScoreDimensionResultRow(
                    dimensionKey = "total",
                    dimensionName = "总分",
                    rawScore = totalScore,
                    levelCode = code,
                    levelName = name
                )
            ),
            resultFlags = flags,
            bandLevelCode = code,
            bandLevelName = name,
            resultText = "PHQ-9 总分 ${totalScore.roundScale2()} 分，分级：$name。$warning".trim()
        )
    }

    private fun computeGad7(totalScore: BigDecimal?): MethodScoreResult {
        if (totalScore == null) {
            return MethodScoreResult(totalScore = null)
        }
        val totalInt = totalScore.toInt()
        val (code, name) = when (totalInt) {
            in 0..4 -> "minimal" to "无焦虑"
            in 5..9 -> "mild" to "轻度焦虑"
            in 10..14 -> "moderate" to "中度焦虑"
            else -> "severe" to "重度焦虑"
        }
        return MethodScoreResult(
            totalScore = totalScore,
            dimensionScores = mapOf("total" to totalScore),
            dimensionResults = listOf(
                ScoreDimensionResultRow(
                    dimensionKey = "total",
                    dimensionName = "总分",
                    rawScore = totalScore,
                    levelCode = code,
                    levelName = name
                )
            ),
            bandLevelCode = code,
            bandLevelName = name,
            resultText = "GAD-7 总分 ${totalScore.roundScale2()} 分，分级：$name。"
        )
    }

    private fun computePsqi(
        questions: List<ScoreQuestionRow>,
        scoreByQuestionId: Map<Long, BigDecimal>,
        answersByQuestionId: Map<Long, ScoreAnswerRow>
    ): MethodScoreResult {
        val questionByKey = questions.associateBy { it.questionKey }
        fun scoreOf(key: String): Int {
            val qid = questionByKey[key]?.questionId ?: return 0
            return scoreByQuestionId[qid]?.toInt() ?: 0
        }

        val c1 = scoreOf("q9").coerceIn(0, 3)
        val c2 = map0To3(scoreOf("q2") + scoreOf("q5a"), maxRaw = 6)
        val sleepHours = questionByKey["q4"]?.let { q ->
            answersByQuestionId[q.questionId]?.let { parseDurationHours(it.answerJson) }
        } ?: BigDecimal.ZERO
        val c3 = when {
            sleepHours > BigDecimal("7") -> 0
            sleepHours >= BigDecimal("6") -> 1
            sleepHours >= BigDecimal("5") -> 2
            else -> 3
        }
        val bedTimeMinutes = questionByKey["q1"]?.let { q ->
            answersByQuestionId[q.questionId]?.let { parseTimeMinutes(it.answerJson) }
        }
        val wakeTimeMinutes = questionByKey["q3"]?.let { q ->
            answersByQuestionId[q.questionId]?.let { parseTimeMinutes(it.answerJson) }
        }
        val sleepEfficiency = computeSleepEfficiencyPercent(bedTimeMinutes, wakeTimeMinutes, sleepHours)
        val c4 = when {
            sleepEfficiency >= BigDecimal("85") -> 0
            sleepEfficiency >= BigDecimal("75") -> 1
            sleepEfficiency >= BigDecimal("65") -> 2
            else -> 3
        }
        val c5Raw = scoreOf("q5b") + scoreOf("q5c") + scoreOf("q5d") + scoreOf("q5e") + scoreOf("q5f") +
            scoreOf("q5g") + scoreOf("q5h") + scoreOf("q5i") + scoreOf("q5j")
        val c5 = map0To3(c5Raw, maxRaw = 27)
        val c6 = scoreOf("q6").coerceIn(0, 3)
        val c7 = map0To3(scoreOf("q7") + scoreOf("q8"), maxRaw = 6)

        val components = linkedMapOf(
            "C1" to c1,
            "C2" to c2,
            "C3" to c3,
            "C4" to c4,
            "C5" to c5,
            "C6" to c6,
            "C7" to c7
        )
        val totalScore = components.values.sum().toBigDecimal()
        val levelCode = if (totalScore <= BigDecimal("7")) "good" else "sleep_issue"
        val levelName = if (levelCode == "good") "睡眠质量良好" else "存在睡眠问题"
        val dimensionNames = mapOf(
            "C1" to "主观睡眠质量",
            "C2" to "入睡时间",
            "C3" to "睡眠时间",
            "C4" to "睡眠效率",
            "C5" to "睡眠障碍",
            "C6" to "催眠药物",
            "C7" to "日间功能障碍"
        )
        return MethodScoreResult(
            totalScore = totalScore,
            dimensionScores = components.mapValues { (_, value) -> value.toBigDecimal() },
            overallMetrics = mapOf("sleepEfficiency" to sleepEfficiency),
            dimensionResults = components.map { (key, value) ->
                ScoreDimensionResultRow(
                    dimensionKey = key,
                    dimensionName = dimensionNames[key],
                    rawScore = value.toBigDecimal()
                )
            },
            bandLevelCode = levelCode,
            bandLevelName = levelName,
            resultText = "PSQI 总分 ${totalScore.roundScale2()} 分，结论：$levelName。"
        )
    }

    private fun computeScl90(
        totalScore: BigDecimal?,
        questions: List<ScoreQuestionRow>,
        scoreByQuestionId: Map<Long, BigDecimal>
    ): MethodScoreResult {
        if (totalScore == null) {
            return MethodScoreResult(totalScore = null)
        }
        val scorableQuestions = questions.filter { it.scorable }
        val totalCount = scorableQuestions.size.coerceAtLeast(1).toBigDecimal()
        val totalAverage = totalScore.divide(totalCount, 6, RoundingMode.HALF_UP)

        val positiveScores = scoreByQuestionId.values.filter { it >= BigDecimal("2") }
        val positiveCount = positiveScores.size
        val positiveTotal = positiveScores.fold(BigDecimal.ZERO) { acc, it -> acc + it }
        val positiveMean = if (positiveCount == 0) BigDecimal.ZERO else {
            positiveTotal.divide(positiveCount.toBigDecimal(), 6, RoundingMode.HALF_UP)
        }

        val dimensionRows = scorableQuestions.filter { !it.dimension.isNullOrBlank() }
        val dimensionCount = dimensionRows.groupingBy { it.dimension!! }.eachCount()
        val dimensionSum = mutableMapOf<String, BigDecimal>()
        dimensionRows.forEach { question ->
            val score = scoreByQuestionId[question.questionId] ?: BigDecimal.ZERO
            val key = question.dimension!!
            dimensionSum[key] = (dimensionSum[key] ?: BigDecimal.ZERO) + score
        }
        val dimensionAverage = dimensionSum.mapValues { (key, score) ->
            val count = dimensionCount[key]?.toBigDecimal() ?: BigDecimal.ONE
            score.divide(count, 6, RoundingMode.HALF_UP)
        }

        val hasSevereFactor = dimensionAverage.values.any { it >= BigDecimal("3") }
        val positive = totalScore >= BigDecimal("160") || positiveCount >= 43 || hasSevereFactor
        val severity = severityByAverage(totalAverage)
        val factorNames = scl90FactorNames()
        val dimensionResults = dimensionAverage.entries.sortedBy { it.key }.map { (key, value) ->
            ScoreDimensionResultRow(
                dimensionKey = key,
                dimensionName = factorNames[key] ?: key,
                averageScore = value,
                levelCode = severityCodeByAverage(value),
                levelName = severityByAverage(value)
            )
        }

        return MethodScoreResult(
            totalScore = totalScore,
            dimensionScores = dimensionAverage,
            overallMetrics = mapOf(
                "totalAverage" to totalAverage,
                "positiveItemCount" to positiveCount.toBigDecimal(),
                "positiveMean" to positiveMean
            ),
            dimensionResults = dimensionResults,
            resultFlags = if (positive) listOf("SCL90_POSITIVE") else emptyList(),
            bandLevelCode = if (positive) "positive" else "negative",
            bandLevelName = if (positive) "筛查阳性" else "筛查阴性",
            resultText = "SCL-90 总分 ${totalScore.roundScale2()} 分，总均分 ${totalAverage.roundScale2()}，阳性项目数 $positiveCount，严重度：$severity。"
        )
    }

    private fun computeEpq(
        questions: List<ScoreQuestionRow>,
        scoreByQuestionId: Map<Long, BigDecimal>,
        userGender: Gender,
        ruleObj: JsonObject?
    ): MethodScoreResult {
        val dimensionRaw = mutableMapOf<String, BigDecimal>()
        questions.filter { it.scorable && !it.dimension.isNullOrBlank() }.forEach { question ->
            val score = scoreByQuestionId[question.questionId] ?: BigDecimal.ZERO
            val key = question.dimension!!
            dimensionRaw[key] = (dimensionRaw[key] ?: BigDecimal.ZERO) + score
        }

        val norms = parseEpqNorms(ruleObj)
        val useNorm = userGender == Gender.MALE || userGender == Gender.FEMALE
        val flags = mutableListOf<String>()
        if (!useNorm) {
            flags += "EPQ_NORM_PENDING_GENDER"
        }

        val dimensionResults = dimensionRaw.entries.sortedBy { it.key }.map { (dimension, raw) ->
            val norm = norms[dimension]
            val standard = if (!useNorm || norm == null) {
                null
            } else {
                val pair = if (userGender == Gender.MALE) norm.male else norm.female
                pair?.let { calculateTScore(raw, it.mean, it.sd) }
            }
            ScoreDimensionResultRow(
                dimensionKey = dimension,
                dimensionName = epqDimensionName(dimension),
                rawScore = raw,
                standardScore = standard,
                levelCode = standard?.let(::epqLevelCode),
                levelName = standard?.let(::epqLevelName)
            )
        }

        val dimensionScores = dimensionResults.associate { item ->
            item.dimensionKey to (item.standardScore ?: item.rawScore ?: BigDecimal.ZERO)
        }
        val totalScore = dimensionRaw.values.sumOfOrNull { it }?.roundScale2()
        val highlights = dimensionResults.joinToString("，") { item ->
            if (item.standardScore != null) {
                "${item.dimensionKey}(T)=${item.standardScore.roundScale2()}"
            } else {
                "${item.dimensionKey}(Raw)=${(item.rawScore ?: BigDecimal.ZERO).roundScale2()}"
            }
        }
        return MethodScoreResult(
            totalScore = totalScore,
            dimensionScores = dimensionScores,
            dimensionResults = dimensionResults,
            resultFlags = flags,
            resultText = if (highlights.isBlank()) null else "EPQ 维度分：$highlights。"
        )
    }

    private fun parseRuleJson(ruleJson: String): JsonObject? {
        if (ruleJson.isBlank()) {
            return null
        }
        return runCatching { json.parseToJsonElement(ruleJson).jsonObject }.getOrNull()
    }

    private fun parseEpqNorms(ruleObj: JsonObject?): Map<String, EpqNorm> {
        val normsObj = ruleObj?.get("norms") as? JsonObject ?: return emptyMap()
        return normsObj.mapNotNull { (dimension, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val male = (obj["male"] as? JsonObject)?.let { parseMeanSd(it) }
            val female = (obj["female"] as? JsonObject)?.let { parseMeanSd(it) }
            if (male == null && female == null) {
                null
            } else {
                dimension to EpqNorm(male = male, female = female)
            }
        }.toMap()
    }

    private fun parseMeanSd(obj: JsonObject): MeanSd? {
        val mean = obj.number("mean")?.toBigDecimal() ?: return null
        val sd = obj.number("sd")?.toBigDecimal() ?: return null
        if (sd == BigDecimal.ZERO) {
            return null
        }
        return MeanSd(mean = mean, sd = sd)
    }

    private fun calculateTScore(raw: BigDecimal, mean: BigDecimal, sd: BigDecimal): BigDecimal {
        return BigDecimal("50") + BigDecimal("10") * (raw - mean).divide(sd, 6, RoundingMode.HALF_UP)
    }

    private fun epqDimensionName(key: String): String = when (key.uppercase()) {
        "E" -> "内外向"
        "N" -> "神经质"
        "P" -> "精神质"
        "L" -> "掩饰性"
        else -> key
    }

    private fun epqLevelCode(t: BigDecimal): String = when {
        t >= BigDecimal("70") -> "very_high"
        t >= BigDecimal("60") -> "high"
        t >= BigDecimal("40") -> "normal"
        t >= BigDecimal("30") -> "low"
        else -> "very_low"
    }

    private fun epqLevelName(t: BigDecimal): String = when {
        t >= BigDecimal("70") -> "非常高"
        t >= BigDecimal("60") -> "偏高"
        t >= BigDecimal("40") -> "中等"
        t >= BigDecimal("30") -> "偏低"
        else -> "非常低"
    }

    private fun parseTimeMinutes(answerJson: String): Int? {
        val value = parseAnswerValue(answerJson) ?: return null
        val regex = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
        val match = regex.matchEntire(value.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return hour * 60 + minute
    }

    private fun parseDurationHours(answerJson: String): BigDecimal? {
        val element = parseAnswerJson(answerJson) ?: return null
        val obj = element as? JsonObject
        val hours = obj?.number("hours")
        if (hours != null) {
            return hours.toBigDecimal()
        }
        val minutes = obj?.number("minutes")
        if (minutes != null) {
            return minutes.toBigDecimal().divide(BigDecimal("60"), 6, RoundingMode.HALF_UP)
        }
        val rawValue = parseAnswerValue(answerJson)?.trim().orEmpty()
        rawValue.toDoubleOrNull()?.let { numeric ->
            return if (numeric <= 24.0) {
                numeric.toBigDecimal()
            } else {
                numeric.toBigDecimal().divide(BigDecimal("60"), 6, RoundingMode.HALF_UP)
            }
        }
        return null
    }

    private fun parseScoreFromAnswerJson(answerJson: String): BigDecimal? {
        val element = parseAnswerJson(answerJson) ?: return null
        return when (element) {
            is JsonPrimitive -> element.doubleOrNull?.toBigDecimal()
            is JsonObject -> element.number("score")?.toBigDecimal()
            else -> null
        }
    }

    private fun parseAnswerValue(answerJson: String): String? {
        val element = parseAnswerJson(answerJson) ?: return null
        return when (element) {
            is JsonPrimitive -> if (element.isString) element.content else element.content
            is JsonObject -> {
                val value = element["value"] as? JsonPrimitive
                val text = element["text"] as? JsonPrimitive
                when {
                    value != null && value.isString -> value.content
                    value != null -> value.content
                    text != null && text.isString -> text.content
                    text != null -> text.content
                    else -> null
                }
            }

            else -> null
        }
    }

    private fun parseAnswerJson(raw: String?): JsonElement? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()
    }

    private fun computeSleepEfficiencyPercent(
        bedTimeMinutes: Int?,
        wakeTimeMinutes: Int?,
        sleepHours: BigDecimal
    ): BigDecimal {
        if (bedTimeMinutes == null || wakeTimeMinutes == null) {
            return BigDecimal.ZERO
        }
        val inBed = if (wakeTimeMinutes > bedTimeMinutes) {
            wakeTimeMinutes - bedTimeMinutes
        } else {
            wakeTimeMinutes + 24 * 60 - bedTimeMinutes
        }.coerceAtLeast(1)
        val sleepMinutes = sleepHours.multiply(BigDecimal("60"))
        return sleepMinutes
            .multiply(BigDecimal("100"))
            .divide(inBed.toBigDecimal(), 6, RoundingMode.HALF_UP)
    }

    private fun map0To3(raw: Int, maxRaw: Int): Int {
        return when {
            raw <= 0 -> 0
            maxRaw == 6 && raw <= 2 -> 1
            maxRaw == 6 && raw <= 4 -> 2
            maxRaw == 27 && raw <= 9 -> 1
            maxRaw == 27 && raw <= 18 -> 2
            else -> 3
        }
    }

    private fun severityByAverage(avg: BigDecimal): String = when {
        avg < BigDecimal("1.5") -> "正常"
        avg < BigDecimal("2.5") -> "轻度"
        avg < BigDecimal("3.5") -> "中度"
        avg < BigDecimal("4.5") -> "偏重"
        else -> "严重"
    }

    private fun severityCodeByAverage(avg: BigDecimal): String = when {
        avg < BigDecimal("1.5") -> "normal"
        avg < BigDecimal("2.5") -> "mild"
        avg < BigDecimal("3.5") -> "moderate"
        avg < BigDecimal("4.5") -> "severe"
        else -> "critical"
    }

    private fun scl90FactorNames(): Map<String, String> {
        return mapOf(
            "somatization" to "躯体化",
            "obsessive_compulsive" to "强迫症状",
            "interpersonal_sensitivity" to "人际关系敏感",
            "depression" to "抑郁",
            "anxiety" to "焦虑",
            "hostility" to "敌对",
            "phobic_anxiety" to "恐怖",
            "paranoid_ideation" to "偏执",
            "psychoticism" to "精神病性",
            "additional" to "其他"
        )
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (!value.isString) {
            return null
        }
        return value.content
    }

    private fun JsonObject.number(key: String): Double? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.doubleOrNull
    }
}

private data class MethodScoreResult(
    val totalScore: BigDecimal?,
    val dimensionScores: Map<String, BigDecimal> = emptyMap(),
    val overallMetrics: Map<String, BigDecimal> = emptyMap(),
    val dimensionResults: List<ScoreDimensionResultRow> = emptyList(),
    val resultFlags: List<String> = emptyList(),
    val bandLevelCode: String? = null,
    val bandLevelName: String? = null,
    val resultText: String? = null
)

private data class MeanSd(
    val mean: BigDecimal,
    val sd: BigDecimal
)

private data class EpqNorm(
    val male: MeanSd? = null,
    val female: MeanSd? = null
)

private fun ScoreDimensionResultRow.rounded(): ScoreDimensionResultRow {
    return copy(
        rawScore = rawScore?.roundScale2(),
        averageScore = averageScore?.roundScale2(),
        standardScore = standardScore?.roundScale2(),
        extraMetrics = extraMetrics.mapValues { (_, value) -> value.roundScale2() }
    )
}

private fun <T> Iterable<T>.sumOfOrNull(selector: (T) -> BigDecimal): BigDecimal? {
    var count = 0
    var sum = BigDecimal.ZERO
    for (item in this) {
        sum += selector(item)
        count += 1
    }
    return if (count == 0) null else sum
}
