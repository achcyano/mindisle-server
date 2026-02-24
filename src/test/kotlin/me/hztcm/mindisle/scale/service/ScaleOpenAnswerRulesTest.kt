package me.hztcm.mindisle.scale.service

import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.db.ScaleQuestionType
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ScaleOpenAnswerRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun shouldRejectWhenTextTooShort() {
        assertFailsWith<AppException> {
            enforceOpenTextAnswerLength(
                questionType = ScaleQuestionType.TEXT,
                normalizedAnswer = json.parseToJsonElement("""{"text":"短"}"""),
                minChars = 2,
                maxChars = 10
            )
        }
    }

    @Test
    fun shouldRejectWhenTextTooLong() {
        assertFailsWith<AppException> {
            enforceOpenTextAnswerLength(
                questionType = ScaleQuestionType.TEXT,
                normalizedAnswer = json.parseToJsonElement("""{"text":"这是超长答案"}"""),
                minChars = 1,
                maxChars = 4
            )
        }
    }

    @Test
    fun shouldPassWhenTextInRange() {
        enforceOpenTextAnswerLength(
            questionType = ScaleQuestionType.TEXT,
            normalizedAnswer = json.parseToJsonElement("""{"text":"这是答案"}"""),
            minChars = 1,
            maxChars = 20
        )
    }
}
