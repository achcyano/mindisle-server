package me.hztcm.mindisle.scale.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.db.ScaleQuestionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ScaleAnswerEvaluatorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun singleChoiceShouldResolveOptionIdAndScore() {
        val options = listOf(
            ScaleOptionScore(optionId = 1, optionKey = "a", scoreValue = 0.toBigDecimal()),
            ScaleOptionScore(optionId = 2, optionKey = "b", scoreValue = 2.toBigDecimal())
        )
        val answer = json.parseToJsonElement("""{"optionId":2}""")
        val result = ScaleAnswerEvaluator.evaluate(
            type = ScaleQuestionType.SINGLE_CHOICE,
            scorable = true,
            reverseScored = false,
            options = options,
            answer = answer
        )
        assertEquals(2.toBigDecimal(), result.numericScore)
    }

    @Test
    fun multiChoiceShouldRejectWhenMissingOptions() {
        val options = listOf(
            ScaleOptionScore(optionId = 1, optionKey = "a", scoreValue = 1.toBigDecimal())
        )
        val answer = json.parseToJsonElement("""{"optionIds":[2]}""")
        assertFailsWith<AppException> {
            ScaleAnswerEvaluator.evaluate(
                type = ScaleQuestionType.MULTI_CHOICE,
                scorable = true,
                reverseScored = false,
                options = options,
                answer = answer
            )
        }
    }

    @Test
    fun timeAnswerShouldNormalizeToHhMm() {
        val result = ScaleAnswerEvaluator.evaluate(
            type = ScaleQuestionType.TIME,
            scorable = false,
            reverseScored = false,
            options = emptyList(),
            answer = json.parseToJsonElement(""""23:5"""")
        )
        val obj = result.normalizedAnswer as? JsonObject
        assertNotNull(obj)
        assertEquals("23:05", obj["value"]?.toString()?.trim('"'))
    }

    @Test
    fun durationAnswerShouldNormalizeToMinutes() {
        val result = ScaleAnswerEvaluator.evaluate(
            type = ScaleQuestionType.DURATION,
            scorable = false,
            reverseScored = false,
            options = emptyList(),
            answer = json.parseToJsonElement(""""6h"""")
        )
        val obj = result.normalizedAnswer as? JsonObject
        assertNotNull(obj)
        assertEquals("360", obj["minutes"]?.toString())
    }
}
