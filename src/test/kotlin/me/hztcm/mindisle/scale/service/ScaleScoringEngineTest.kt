package me.hztcm.mindisle.scale.service

import me.hztcm.mindisle.db.ScaleScoringMethod
import me.hztcm.mindisle.model.Gender
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScaleScoringEngineTest {

    @Test
    fun phq9ShouldReturnSeverityAndRiskHint() {
        val questions = listOf(
            ScoreQuestionRow(1, "q1", "total", true),
            ScoreQuestionRow(2, "q2", "total", true),
            ScoreQuestionRow(3, "q9", "total", true)
        )
        val answers = listOf(
            ScoreAnswerRow(1, 2.toBigDecimal()),
            ScoreAnswerRow(2, 2.toBigDecimal()),
            ScoreAnswerRow(3, 1.toBigDecimal())
        )
        val result = ScaleScoringEngine.compute(
            method = ScaleScoringMethod.PHQ9,
            ruleJson = """{"suicideQuestionKey":"q9"}""",
            questions = questions,
            answers = answers,
            bands = emptyList()
        )
        assertEquals("mild", result.bandLevelCode)
        assertNotNull(result.resultText)
        assertTrue(result.resultText.contains("风险评估"))
    }

    @Test
    fun gad7ShouldReturnModerateBand() {
        val questions = listOf(
            ScoreQuestionRow(1, "q1", "total", true),
            ScoreQuestionRow(2, "q2", "total", true),
            ScoreQuestionRow(3, "q3", "total", true),
            ScoreQuestionRow(4, "q4", "total", true),
            ScoreQuestionRow(5, "q5", "total", true)
        )
        val answers = listOf(
            ScoreAnswerRow(1, 2.toBigDecimal()),
            ScoreAnswerRow(2, 2.toBigDecimal()),
            ScoreAnswerRow(3, 2.toBigDecimal()),
            ScoreAnswerRow(4, 2.toBigDecimal()),
            ScoreAnswerRow(5, 2.toBigDecimal())
        )
        val result = ScaleScoringEngine.compute(
            method = ScaleScoringMethod.GAD7,
            ruleJson = "{}",
            questions = questions,
            answers = answers,
            bands = emptyList()
        )
        assertEquals("moderate", result.bandLevelCode)
        assertEquals("中度焦虑", result.bandLevelName)
    }

    @Test
    fun psqiShouldComputeSevenComponents() {
        val questions = listOf(
            ScoreQuestionRow(1, "q1", "time", true),
            ScoreQuestionRow(2, "q2", "c2", true),
            ScoreQuestionRow(3, "q3", "time", true),
            ScoreQuestionRow(4, "q4", "duration", false),
            ScoreQuestionRow(5, "q5a", "c2", true),
            ScoreQuestionRow(6, "q5b", "c5", true),
            ScoreQuestionRow(7, "q5c", "c5", true),
            ScoreQuestionRow(8, "q5d", "c5", true),
            ScoreQuestionRow(9, "q5e", "c5", true),
            ScoreQuestionRow(10, "q5f", "c5", true),
            ScoreQuestionRow(11, "q5g", "c5", true),
            ScoreQuestionRow(12, "q5h", "c5", true),
            ScoreQuestionRow(13, "q5i", "c5", true),
            ScoreQuestionRow(14, "q5j", "c5", true),
            ScoreQuestionRow(15, "q6", "c6", true),
            ScoreQuestionRow(16, "q7", "c7", true),
            ScoreQuestionRow(17, "q8", "c7", true),
            ScoreQuestionRow(18, "q9", "c1", true)
        )
        val answers = listOf(
            ScoreAnswerRow(1, null, """{"value":"23:00"}"""),
            ScoreAnswerRow(2, 2.toBigDecimal()),
            ScoreAnswerRow(3, null, """{"value":"07:00"}"""),
            ScoreAnswerRow(4, null, """{"value":"6h","minutes":360,"hours":6.0}"""),
            ScoreAnswerRow(5, 2.toBigDecimal()),
            ScoreAnswerRow(6, 1.toBigDecimal()),
            ScoreAnswerRow(7, 1.toBigDecimal()),
            ScoreAnswerRow(8, 1.toBigDecimal()),
            ScoreAnswerRow(9, 1.toBigDecimal()),
            ScoreAnswerRow(10, 1.toBigDecimal()),
            ScoreAnswerRow(11, 1.toBigDecimal()),
            ScoreAnswerRow(12, 1.toBigDecimal()),
            ScoreAnswerRow(13, 1.toBigDecimal()),
            ScoreAnswerRow(14, 1.toBigDecimal()),
            ScoreAnswerRow(15, 1.toBigDecimal()),
            ScoreAnswerRow(16, 2.toBigDecimal()),
            ScoreAnswerRow(17, 2.toBigDecimal()),
            ScoreAnswerRow(18, 2.toBigDecimal())
        )
        val result = ScaleScoringEngine.compute(
            method = ScaleScoringMethod.PSQI,
            ruleJson = "{}",
            questions = questions,
            answers = answers,
            bands = emptyList()
        )
        assertEquals("sleep_issue", result.bandLevelCode)
        assertEquals(7, result.dimensionScores.size)
        assertTrue((result.totalScore ?: 0.toBigDecimal()) > BigDecimal.ZERO)
    }

    @Test
    fun scl90ShouldMarkPositiveWhenAnyFactorAtLeastThree() {
        val questions = (1L..10L).map { id ->
            ScoreQuestionRow(id, "q$id", "anxiety", true)
        }
        val answers = (1L..10L).map { id ->
            ScoreAnswerRow(id, 3.toBigDecimal())
        }
        val result = ScaleScoringEngine.compute(
            method = ScaleScoringMethod.SCL90,
            ruleJson = "{}",
            questions = questions,
            answers = answers,
            bands = emptyList()
        )
        assertEquals("positive", result.bandLevelCode)
        assertTrue(result.resultFlags.contains("SCL90_POSITIVE"))
    }

    @Test
    fun epqShouldReturnPendingFlagWhenGenderUnknown() {
        val questions = listOf(
            ScoreQuestionRow(1, "q1", "E", true),
            ScoreQuestionRow(2, "q2", "N", true),
            ScoreQuestionRow(3, "q3", "P", true),
            ScoreQuestionRow(4, "q4", "L", true)
        )
        val answers = listOf(
            ScoreAnswerRow(1, 1.toBigDecimal()),
            ScoreAnswerRow(2, 1.toBigDecimal()),
            ScoreAnswerRow(3, 1.toBigDecimal()),
            ScoreAnswerRow(4, 1.toBigDecimal())
        )
        val ruleJson = """
            {
              "norms": {
                "E": {"male":{"mean":11.48,"sd":4.76},"female":{"mean":12.18,"sd":4.70}},
                "N": {"male":{"mean":11.04,"sd":5.56},"female":{"mean":13.86,"sd":5.68}},
                "P": {"male":{"mean":6.14,"sd":3.54},"female":{"mean":4.66,"sd":3.06}},
                "L": {"male":{"mean":13.62,"sd":4.74},"female":{"mean":14.88,"sd":4.56}}
              }
            }
        """.trimIndent()
        val result = ScaleScoringEngine.compute(
            method = ScaleScoringMethod.EPQ,
            ruleJson = ruleJson,
            questions = questions,
            answers = answers,
            bands = emptyList(),
            userGender = Gender.UNKNOWN
        )
        assertTrue(result.resultFlags.contains("EPQ_NORM_PENDING_GENDER"))
    }
}
