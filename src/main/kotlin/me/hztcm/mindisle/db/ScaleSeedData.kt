package me.hztcm.mindisle.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.math.BigDecimal

object ScaleSeedData {
    fun seedDefaultsIfEmpty() {
        listOf(
            buildPhq9(),
            buildGad7(),
            buildPsqi(),
            buildScl90(),
            buildEpq88()
        ).forEach(::seedScaleVersionIfAbsent)
    }

    private fun seedScaleVersionIfAbsent(seed: SeedScale) {
        val now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        val scaleEntityId = ScalesTable.selectAll().where {
            ScalesTable.code eq seed.code
        }.firstOrNull()?.get(ScalesTable.id) ?: ScalesTable.insert {
            it[code] = seed.code
            it[name] = seed.name
            it[description] = seed.description
            it[status] = ScaleStatus.PUBLISHED
            it[createdAt] = now
            it[updatedAt] = now
        }[ScalesTable.id]

        val exists = ScaleVersionsTable.selectAll().where {
            (ScaleVersionsTable.scaleId eq scaleEntityId) and
                (ScaleVersionsTable.version eq seed.version)
        }.firstOrNull()
        if (exists != null) {
            return
        }

        val versionId = ScaleVersionsTable.insert {
            it[scaleId] = scaleEntityId
            it[version] = seed.version
            it[status] = ScaleStatus.PUBLISHED
            it[publishedAt] = now
            it[configJson] = seed.configJson
            it[createdAt] = now
            it[updatedAt] = now
        }[ScaleVersionsTable.id]

        seed.questions.forEach { question ->
            val questionId = ScaleQuestionsTable.insert {
                it[ScaleQuestionsTable.versionId] = versionId
                it[questionKey] = question.questionKey
                it[orderNo] = question.orderNo
                it[type] = question.type
                it[dimension] = question.dimension
                it[required] = question.required
                it[scorable] = question.scorable
                it[reverseScored] = question.reverseScored
                it[stem] = question.stem
                it[hint] = question.hint
                it[note] = question.note
                it[optionSetCode] = question.optionSetCode
                it[metaJson] = question.metaJson
            }[ScaleQuestionsTable.id]

            question.options.forEach { option ->
                ScaleOptionsTable.insert {
                    it[ScaleOptionsTable.questionId] = questionId
                    it[optionKey] = option.optionKey
                    it[orderNo] = option.orderNo
                    it[label] = option.label
                    it[scoreValue] = option.scoreValue
                    it[extJson] = option.extJson
                }
            }
        }

        ScaleScoringRulesTable.insert {
            it[ScaleScoringRulesTable.versionId] = versionId
            it[method] = seed.method
            it[ruleJson] = seed.ruleJson
            it[createdAt] = now
        }
        seed.bands.forEach { band ->
            ScaleResultBandsTable.insert {
                it[ScaleResultBandsTable.versionId] = versionId
                it[dimension] = band.dimension
                it[minScore] = band.minScore
                it[maxScore] = band.maxScore
                it[levelCode] = band.levelCode
                it[levelName] = band.levelName
                it[interpretation] = band.interpretation
            }
        }
    }

    private fun buildPhq9(): SeedScale {
        val options = likert0To3Options()
        return SeedScale(
            code = "PHQ9",
            name = "患者健康问卷抑郁量表（PHQ-9）",
            description = "用于评估最近两周抑郁相关症状频率。",
            version = 2,
            method = ScaleScoringMethod.PHQ9,
            ruleJson = """{"suicideQuestionKey":"q9","functionImpactQuestionKey":"q10"}""",
            configJson = """
                {
                  "timeWindow": "近两周",
                  "defaultOptionSet": "LIKERT_0_3",
                  "dimensions": [
                    {"key":"total","name":"总分","min":0,"max":27,"description":"9个核心题目总分"}
                  ]
                }
            """.trimIndent(),
            bands = listOf(
                SeedBand(null, BigDecimal("0"), BigDecimal("4"), "minimal", "无抑郁", "症状较轻，建议继续观察。"),
                SeedBand(null, BigDecimal("5"), BigDecimal("9"), "mild", "轻度抑郁", "存在轻度症状，可结合生活方式调整。"),
                SeedBand(null, BigDecimal("10"), BigDecimal("14"), "moderate", "中度抑郁", "建议尽快进行专业评估。"),
                SeedBand(null, BigDecimal("15"), BigDecimal("19"), "moderately_severe", "中重度抑郁", "建议尽快就医并持续随访。"),
                SeedBand(null, BigDecimal("20"), BigDecimal("27"), "severe", "重度抑郁", "高风险，建议尽快接受专业干预。")
            ),
            questions = listOf(
                SeedQuestion("q1", 1, "做事情提不起兴趣或没有乐趣", note = "最近两周", options = options),
                SeedQuestion("q2", 2, "感到心情低落、沮丧或绝望", note = "最近两周", options = options),
                SeedQuestion("q3", 3, "入睡困难、睡不安稳或睡眠过多", note = "最近两周", options = options),
                SeedQuestion("q4", 4, "感到疲倦或没有精力", note = "最近两周", options = options),
                SeedQuestion("q5", 5, "食欲不振或吃太多", note = "最近两周", options = options),
                SeedQuestion("q6", 6, "觉得自己很差劲、失败，或让家人失望", note = "最近两周", options = options),
                SeedQuestion("q7", 7, "做事时难以集中注意力", note = "最近两周", options = options),
                SeedQuestion("q8", 8, "动作/说话变慢，或坐立不安", note = "最近两周", options = options),
                SeedQuestion("q9", 9, "有不如死掉或伤害自己的想法", note = "最近两周", options = options),
                SeedQuestion(
                    questionKey = "q10",
                    orderNo = 10,
                    stem = "这些问题对您的工作、家务、学习或人际关系造成了多大影响？",
                    required = false,
                    scorable = false,
                    note = "功能损害附加题",
                    options = options
                )
            )
        )
    }

    private fun buildGad7(): SeedScale {
        val options = likert0To3Options()
        return SeedScale(
            code = "GAD7",
            name = "广泛性焦虑障碍量表（GAD-7）",
            description = "用于评估最近两周焦虑相关症状频率。",
            version = 2,
            method = ScaleScoringMethod.GAD7,
            ruleJson = "{}",
            configJson = """
                {
                  "timeWindow": "近两周",
                  "defaultOptionSet": "LIKERT_0_3",
                  "dimensions": [
                    {"key":"total","name":"总分","min":0,"max":21,"description":"7个核心题目总分"}
                  ]
                }
            """.trimIndent(),
            bands = listOf(
                SeedBand(null, BigDecimal("0"), BigDecimal("4"), "minimal", "无焦虑", "症状较轻，建议继续观察。"),
                SeedBand(null, BigDecimal("5"), BigDecimal("9"), "mild", "轻度焦虑", "存在轻度焦虑症状。"),
                SeedBand(null, BigDecimal("10"), BigDecimal("14"), "moderate", "中度焦虑", "建议结合专业评估。"),
                SeedBand(null, BigDecimal("15"), BigDecimal("21"), "severe", "重度焦虑", "建议尽快接受专业帮助。")
            ),
            questions = listOf(
                SeedQuestion("q1", 1, "感到紧张、焦虑或心神不定", note = "最近两周", options = options),
                SeedQuestion("q2", 2, "无法停止或控制担忧", note = "最近两周", options = options),
                SeedQuestion("q3", 3, "对各种事情担心过多", note = "最近两周", options = options),
                SeedQuestion("q4", 4, "很难放松下来", note = "最近两周", options = options),
                SeedQuestion("q5", 5, "坐立不安，难以安静", note = "最近两周", options = options),
                SeedQuestion("q6", 6, "容易烦躁或易怒", note = "最近两周", options = options),
                SeedQuestion("q7", 7, "总觉得会有可怕的事情发生", note = "最近两周", options = options)
            )
        )
    }

    private fun buildPsqi(): SeedScale {
        val freqOptions = listOf(
            SeedOption("opt_0", 0, "没有（最近一个月没发生过）", BigDecimal.ZERO),
            SeedOption("opt_1", 1, "偶尔（每周少于1次）", BigDecimal.ONE),
            SeedOption("opt_2", 2, "有时（每周1-2次）", BigDecimal("2")),
            SeedOption("opt_3", 3, "经常（每周3次或以上）", BigDecimal("3"))
        )
        val qualityOptions = listOf(
            SeedOption("opt_0", 0, "很好", BigDecimal.ZERO),
            SeedOption("opt_1", 1, "比较好", BigDecimal.ONE),
            SeedOption("opt_2", 2, "比较差", BigDecimal("2")),
            SeedOption("opt_3", 3, "很差", BigDecimal("3"))
        )
        val latencyOptions = listOf(
            SeedOption("opt_0", 0, "15分钟以内", BigDecimal.ZERO),
            SeedOption("opt_1", 1, "16-30分钟", BigDecimal.ONE),
            SeedOption("opt_2", 2, "31-60分钟", BigDecimal("2")),
            SeedOption("opt_3", 3, "超过60分钟", BigDecimal("3"))
        )
        return SeedScale(
            code = "PSQI",
            name = "匹兹堡睡眠质量指数量表（PSQI）",
            description = "用于评估最近一个月的睡眠质量及相关维度。",
            version = 1,
            method = ScaleScoringMethod.PSQI,
            ruleJson = "{}",
            configJson = """
                {
                  "timeWindow": "近一个月",
                  "dimensions": [
                    {"key":"C1","name":"主观睡眠质量","min":0,"max":3},
                    {"key":"C2","name":"入睡时间","min":0,"max":3},
                    {"key":"C3","name":"睡眠时间","min":0,"max":3},
                    {"key":"C4","name":"睡眠效率","min":0,"max":3},
                    {"key":"C5","name":"睡眠障碍","min":0,"max":3},
                    {"key":"C6","name":"催眠药物","min":0,"max":3},
                    {"key":"C7","name":"日间功能障碍","min":0,"max":3}
                  ]
                }
            """.trimIndent(),
            bands = listOf(
                SeedBand(null, BigDecimal("0"), BigDecimal("7"), "good", "睡眠质量良好", "睡眠质量总体良好。"),
                SeedBand(null, BigDecimal("8"), BigDecimal("21"), "sleep_issue", "存在睡眠问题", "建议结合临床进一步评估。")
            ),
            questions = listOf(
                SeedQuestion(
                    questionKey = "q1",
                    orderNo = 1,
                    stem = "您最近一个月，通常是晚上几点上床睡觉？",
                    type = ScaleQuestionType.TIME,
                    dimension = "C4",
                    scorable = false,
                    optionSetCode = null,
                    hint = "请使用 HH:mm 24小时制"
                ),
                SeedQuestion("q2", 2, "您上床后，一般需要多长时间才能睡着？", dimension = "C2", options = latencyOptions),
                SeedQuestion(
                    questionKey = "q3",
                    orderNo = 3,
                    stem = "您通常是早上几点起床？",
                    type = ScaleQuestionType.TIME,
                    dimension = "C4",
                    scorable = false,
                    optionSetCode = null,
                    hint = "请使用 HH:mm 24小时制"
                ),
                SeedQuestion(
                    questionKey = "q4",
                    orderNo = 4,
                    stem = "您每天晚上实际睡了多长时间？",
                    type = ScaleQuestionType.DURATION,
                    dimension = "C3",
                    scorable = false,
                    optionSetCode = null,
                    hint = "例如：6h、6.5h、390min"
                ),
                SeedQuestion("q5a", 5, "您有没有躺下后30分钟内睡不着的情况？", dimension = "C2", options = freqOptions),
                SeedQuestion("q5b", 6, "您有没有半夜醒来或者凌晨早醒的情况？", dimension = "C5", options = freqOptions),
                SeedQuestion("q5c", 7, "您有没有因为要上厕所而起床的情况？", dimension = "C5", options = freqOptions),
                SeedQuestion("q5d", 8, "您睡觉时有没有呼吸不顺畅、憋气的感觉？", dimension = "C5", options = freqOptions),
                SeedQuestion("q5e", 9, "您睡觉时有没有咳嗽或者打呼噜很响的情况？", dimension = "C5", options = freqOptions),
                SeedQuestion("q5f", 10, "您睡觉时有没有感觉太冷的情况？", dimension = "C5", options = freqOptions),
                SeedQuestion("q5g", 11, "您睡觉时有没有感觉太热的情况？", dimension = "C5", options = freqOptions),
                SeedQuestion("q5h", 12, "您有没有做噩梦的情况？", dimension = "C5", options = freqOptions),
                SeedQuestion("q5i", 13, "您有没有因为身体疼痛或不舒服而影响睡眠的情况？", dimension = "C5", options = freqOptions),
                SeedQuestion(
                    questionKey = "q5j_text",
                    orderNo = 14,
                    stem = "除了以上原因，还有没有其他原因影响您的睡眠？",
                    type = ScaleQuestionType.TEXT,
                    dimension = "C5",
                    required = false,
                    scorable = false,
                    optionSetCode = null
                ),
                SeedQuestion("q5j", 15, "其他原因的发生频率", dimension = "C5", options = freqOptions),
                SeedQuestion("q6", 16, "您最近一个月有没有吃过安眠药或其他帮助睡眠的药物？", dimension = "C6", options = freqOptions),
                SeedQuestion("q7", 17, "白天有没有因为睡眠不好而困倦、打瞌睡、没精神？", dimension = "C7", options = freqOptions),
                SeedQuestion(
                    questionKey = "q8",
                    orderNo = 18,
                    stem = "有没有因为睡眠不好而影响做事精力和积极性？",
                    dimension = "C7",
                    options = listOf(
                        SeedOption("opt_0", 0, "没有影响，精力很好", BigDecimal.ZERO),
                        SeedOption("opt_1", 1, "有一点影响", BigDecimal.ONE),
                        SeedOption("opt_2", 2, "影响比较明显", BigDecimal("2")),
                        SeedOption("opt_3", 3, "影响很大", BigDecimal("3"))
                    )
                ),
                SeedQuestion("q9", 19, "总的来说，您觉得最近一个月自己的睡眠质量怎么样？", dimension = "C1", options = qualityOptions),
                SeedQuestion(
                    questionKey = "q10a",
                    orderNo = 20,
                    stem = "同床家人/伴侣是否观察到您睡觉时打呼噜很响？",
                    dimension = null,
                    required = false,
                    scorable = false,
                    options = freqOptions
                ),
                SeedQuestion(
                    questionKey = "q10b",
                    orderNo = 21,
                    stem = "同床家人/伴侣是否观察到您睡觉时呼吸暂停？",
                    dimension = null,
                    required = false,
                    scorable = false,
                    options = freqOptions
                ),
                SeedQuestion(
                    questionKey = "q10c",
                    orderNo = 22,
                    stem = "同床家人/伴侣是否观察到您睡觉时腿部抽动？",
                    dimension = null,
                    required = false,
                    scorable = false,
                    options = freqOptions
                ),
                SeedQuestion(
                    questionKey = "q10d_text",
                    orderNo = 23,
                    stem = "同床家人/伴侣是否观察到其他异常（如梦游/梦话）？",
                    type = ScaleQuestionType.TEXT,
                    dimension = null,
                    required = false,
                    scorable = false,
                    optionSetCode = null
                )
            )
        )
    }

    private fun buildScl90(): SeedScale {
        val options = scl90LikertOptions()
        val stems = scl90Stems()
        val dimensionByIndex = mutableListOf<String>().apply {
            addAll(List(12) { "somatization" })
            addAll(List(10) { "obsessive_compulsive" })
            addAll(List(9) { "interpersonal_sensitivity" })
            addAll(List(13) { "depression" })
            addAll(List(10) { "anxiety" })
            addAll(List(6) { "hostility" })
            addAll(List(7) { "phobic_anxiety" })
            addAll(List(6) { "paranoid_ideation" })
            addAll(List(10) { "psychoticism" })
            addAll(List(7) { "additional" })
        }
        val questions = stems.mapIndexed { index, stem ->
            val order = index + 1
            SeedQuestion(
                questionKey = "q$order",
                orderNo = order,
                stem = stem,
                type = ScaleQuestionType.SINGLE_CHOICE,
                dimension = dimensionByIndex[index],
                options = options,
                optionSetCode = "LIKERT_1_5"
            )
        }
        return SeedScale(
            code = "SCL90",
            name = "症状自评量表（SCL-90）",
            description = "用于心理症状筛查，包含10个因子。",
            version = 1,
            method = ScaleScoringMethod.SCL90,
            ruleJson = "{}",
            configJson = """
                {
                  "timeWindow":"近一周",
                  "dimensions":[
                    {"key":"somatization","name":"躯体化","min":1,"max":5},
                    {"key":"obsessive_compulsive","name":"强迫症状","min":1,"max":5},
                    {"key":"interpersonal_sensitivity","name":"人际关系敏感","min":1,"max":5},
                    {"key":"depression","name":"抑郁","min":1,"max":5},
                    {"key":"anxiety","name":"焦虑","min":1,"max":5},
                    {"key":"hostility","name":"敌对","min":1,"max":5},
                    {"key":"phobic_anxiety","name":"恐怖","min":1,"max":5},
                    {"key":"paranoid_ideation","name":"偏执","min":1,"max":5},
                    {"key":"psychoticism","name":"精神病性","min":1,"max":5},
                    {"key":"additional","name":"其他","min":1,"max":5}
                  ]
                }
            """.trimIndent(),
            bands = emptyList(),
            questions = questions
        )
    }

    private fun buildEpq88(): SeedScale {
        val options = yesNoOptions()
        val stems = epqStems()
        val questions = stems.mapIndexed { index, stem ->
            val order = index + 1
            val (dimension, reverseScored) = when (order) {
                in 1..21 -> "E" to (order in 15..21)
                in 22..45 -> "N" to false
                in 46..65 -> "P" to false
                else -> "L" to false
            }
            SeedQuestion(
                questionKey = "q$order",
                orderNo = order,
                stem = stem,
                type = ScaleQuestionType.YES_NO,
                dimension = dimension,
                reverseScored = reverseScored,
                options = options,
                optionSetCode = "YES_NO_1_0"
            )
        }
        return SeedScale(
            code = "EPQ",
            name = "艾森克人格问卷（EPQ-88）",
            description = "用于评估 E/N/P/L 四个维度。",
            version = 1,
            method = ScaleScoringMethod.EPQ,
            ruleJson = """
                {
                  "norms": {
                    "E": {"male":{"mean":11.48,"sd":4.76},"female":{"mean":12.18,"sd":4.70}},
                    "N": {"male":{"mean":11.04,"sd":5.56},"female":{"mean":13.86,"sd":5.68}},
                    "P": {"male":{"mean":6.14,"sd":3.54},"female":{"mean":4.66,"sd":3.06}},
                    "L": {"male":{"mean":13.62,"sd":4.74},"female":{"mean":14.88,"sd":4.56}}
                  }
                }
            """.trimIndent(),
            configJson = """
                {
                  "dimensions": [
                    {"key":"E","name":"内外向","min":0,"max":21},
                    {"key":"N","name":"神经质","min":0,"max":24},
                    {"key":"P","name":"精神质","min":0,"max":20},
                    {"key":"L","name":"掩饰性","min":0,"max":23}
                  ]
                }
            """.trimIndent(),
            bands = emptyList(),
            questions = questions
        )
    }

    private fun likert0To3Options(): List<SeedOption> = listOf(
        SeedOption("opt_0", 0, "完全不会", BigDecimal.ZERO),
        SeedOption("opt_1", 1, "有几天", BigDecimal.ONE),
        SeedOption("opt_2", 2, "一半以上时间", BigDecimal("2")),
        SeedOption("opt_3", 3, "几乎每天", BigDecimal("3"))
    )

    private fun yesNoOptions(): List<SeedOption> = listOf(
        SeedOption("yes", 0, "是", BigDecimal.ONE),
        SeedOption("no", 1, "否", BigDecimal.ZERO)
    )

    private fun scl90LikertOptions(): List<SeedOption> = listOf(
        SeedOption("opt_1", 0, "没有", BigDecimal("1")),
        SeedOption("opt_2", 1, "很轻", BigDecimal("2")),
        SeedOption("opt_3", 2, "中等", BigDecimal("3")),
        SeedOption("opt_4", 3, "偏重", BigDecimal("4")),
        SeedOption("opt_5", 4, "严重", BigDecimal("5"))
    )

    private fun scl90Stems(): List<String> = listOf(
        "您有没有头痛的情况？",
        "您有没有头晕的感觉？",
        "您有没有心跳加快、心慌的情况？",
        "您有没有胸口疼痛的感觉？",
        "您有没有腰背酸痛的情况？",
        "您有没有肌肉酸痛的感觉？",
        "您有没有手脚发麻、刺痛的感觉？",
        "您有没有喉咙有梗塞感，好像有东西堵着？",
        "您有没有恶心或胃部不舒服的情况？",
        "您有没有呼吸困难的感觉？",
        "您有没有身体忽冷忽热的感觉？",
        "您有没有身体某些部位麻木的感觉？",
        "您脑子里是不是反复出现一些想法、念头或画面，赶也赶不走？",
        "您是不是觉得记性不好，容易忘事？",
        "您是不是担心自己衣着不整或仪表不正？",
        "您是不是觉得做事情很难完成？",
        "您是不是做事必须反复检查才放心？",
        "您是不是觉得很难下决定？",
        "您是不是害怕乘坐公共交通工具（如汽车、地铁、火车等）？",
        "您是不是觉得脑子变空了、变迟钝了？",
        "您是不是很难集中注意力？",
        "您的思维是不是感觉被控制了，不由自己？",
        "您是不是觉得别人对您不友好、不喜欢您？",
        "您是不是觉得自己不如别人？",
        "您和别人在一起时，是不是感觉不自在？",
        "您是不是容易感情用事、容易激动？",
        "您是不是觉得别人不理解您、不同情您？",
        "您是不是觉得别人对您有敌意、不怀好意？",
        "您在公共场合吃东西或说话时，是不是感到很不自在？",
        "您和异性在一起时，是不是感到害羞不自在？",
        "您是不是想找人倾诉，但又不敢？",
        "您是不是觉得做什么事都没意思、提不起劲？",
        "您是不是容易哭泣或想哭？",
        "您是不是觉得前途没有希望？",
        "您是不是觉得心情沉重、郁闷？",
        "您是不是对什么事都不感兴趣了？",
        "您是不是有想结束自己生命的念头？",
        "您是不是觉得被困住了、无法摆脱？",
        "您是不是过分担心、焦虑？",
        "您是不是觉得自己没有什么价值？",
        "您是不是觉得孤独寂寞？",
        "您是不是容易感到悲伤、心情低落？",
        "您是不是过分担忧事情？",
        "您是不是觉得活着没什么意思？",
        "您是不是感到紧张、心神不定？",
        "您是不是容易害怕、恐惧？",
        "您是不是一紧张就感到心慌、发抖？",
        "您是不是觉得神经过敏、容易受惊？",
        "您是不是感到坐立不安、静不下来？",
        "您是不是觉得有什么不好的事要发生？",
        "您是不是有恐怖的想法或念头？",
        "您是不是容易烦躁、发脾气？",
        "您是不是一个人时就感到害怕？",
        "您是不是突然感到害怕，没有原因？",
        "您是不是容易发脾气、想摔东西？",
        "您是不是容易和别人争论、吵架？",
        "您是不是控制不住想大喊大叫？",
        "您是不是容易冲动、控制不住自己？",
        "您是不是有想打人或伤害别人的冲动？",
        "您是不是有想摔坏、砸烂东西的冲动？",
        "您是不是害怕空旷的场地或街道？",
        "您是不是害怕出门？",
        "您是不是害怕晕倒？",
        "您是不是必须避开某些东西、地方或活动，因为它们让您害怕？",
        "您在人多的地方（如商场、电影院、公交车）是不是感到害怕？",
        "您是不是害怕在公共场合突然晕倒？",
        "您单独外出时是不是感到害怕？",
        "您是不是觉得别人应该为您的困难负责？",
        "您是不是觉得大多数人不可信任？",
        "您是不是觉得别人在背后议论您？",
        "您是不是觉得别人不理解您、对您不公平？",
        "您是不是不愿意让别人知道您的想法？",
        "您是不是觉得别人想占您的便宜、利用您？",
        "您是不是觉得别人能知道您的想法？",
        "您是不是觉得别人应该为您的想法负责？",
        "您是不是觉得有人在控制您的思想？",
        "您是不是听到别人听不到的声音？",
        "您是不是觉得别人能够了解您的内心？",
        "您是不是觉得别人对您的想法不理解、不同情？",
        "您是不是觉得有人想害您？",
        "您是不是看到别人看不到的东西？",
        "您是不是有别人没有的特殊能力或想法？",
        "您是不是觉得自己和别人很疏远、有距离感？",
        "您的胃口怎么样？是不是吃得很少或没胃口？",
        "您的睡眠怎么样？是不是睡不好、容易醒？",
        "您是不是有想结束生命的念头？",
        "您是不是睡眠不安、容易惊醒？",
        "您是不是觉得自己有罪、应该受惩罚？",
        "您是不是觉得脑子里乱糟糟的、思维混乱？",
        "您是不是觉得做什么事都没有精力、很累？"
    )

    private fun epqStems(): List<String> = listOf(
        "您喜欢热闹和活动多的场合吗？",
        "您是不是一个活泼好动的人？",
        "您在聚会上是不是经常是活跃分子？",
        "您喜欢经常和朋友在一起吗？",
        "您喜欢结交新朋友吗？",
        "您是不是喜欢开玩笑和讲笑话？",
        "您喜欢参加许多社交活动吗？",
        "您是不是一个健谈的人？",
        "您喜欢与人交往吗？",
        "您容易变得活跃和兴奋吗？",
        "您喜欢冒险吗？",
        "您做事是不是很快就决定？",
        "您是不是无忧无虑、逍遥自在的人？",
        "您喜欢参加快节奏的活动吗？",
        "您是不是一个比较安静、不爱说话的人？",
        "您喜欢独自一人做事吗？",
        "您是不是宁愿看书也不愿意见人？",
        "您喜欢独自消磨空闲时间吗？",
        "您是不是在做事之前要仔细考虑？",
        "您不喜欢刺激和兴奋的事吗？",
        "您是不是一个慢性子的人？",
        "您的情绪是不是时好时坏？",
        "您是不是经常感到孤独？",
        "您是不是一个神经过敏的人？",
        "您是不是经常感到厌烦？",
        "您是不是经常担心？",
        "您是不是容易激动？",
        "您的感情是不是容易受伤害？",
        "您是不是经常感到害怕或焦虑？",
        "您是不是容易紧张？",
        "您是不是经常感到烦恼？",
        "您是不是经常感到内疚？",
        "您睡眠是不是不好？",
        "您是不是经常感到疲倦和无精打采？",
        "您是不是经常为健康担心？",
        "您是不是一个敏感的人？",
        "您是不是容易感到不安？",
        "您是不是容易为小事烦恼？",
        "您是不是容易感到自卑？",
        "您是不是容易害羞和脸红？",
        "您是不是容易感到紧张不安？",
        "您是不是经常觉得自己很不幸？",
        "您是不是容易被激怒？",
        "您在受到批评后是不是很长时间感到不愉快？",
        "您是不是经常感到心烦意乱？",
        "您喜欢捉弄别人吗？",
        "您是不是喜欢冒险和刺激？",
        "您做事是不是不考虑后果？",
        "您是不是喜欢独来独往？",
        "您是不是不太关心别人的感受？",
        "您是不是容易冲动？",
        "您是不是喜欢看别人出丑？",
        "您是不是不太在乎别人对您的看法？",
        "您是不是喜欢与众不同？",
        "您是不是不喜欢遵守规则？",
        "您是不是容易与人发生冲突？",
        "您是不是做事不考虑别人的利益？",
        "您是不是喜欢危险的事情？",
        "您是不是不太同情别人的困难？",
        "您是不是容易厌倦？",
        "您是不是喜欢独特的想法？",
        "您是不是不太在乎传统习俗？",
        "您是不是容易感到无聊？",
        "您是不是喜欢挑战权威？",
        "您是不是不太关心别人的评价？",
        "您做过的事都是对的吗？",
        "您从来没有迟到过吗？",
        "您从来没有说过谎吗？",
        "您总是言行一致吗？",
        "您从来没有违背诺言吗？",
        "您从来没有占过别人的便宜吗？",
        "您总是愿意承认自己的错误吗？",
        "您总是有礼貌吗？",
        "您从来没有贪心过吗？",
        "您总是说实话吗？",
        "您从来没有说过别人的坏话吗？",
        "您总是愿意帮助别人吗？",
        "您从来没有嫉妒过别人吗？",
        "您总是按时完成工作吗？",
        "您从来没有生过气吗？",
        "您总是尊重他人吗？",
        "您从来没有自私过吗？",
        "您总是考虑别人的感受吗？",
        "您从来没有抱怨过吗？",
        "您总是遵守规则吗？",
        "您从来没有不高兴过吗？",
        "您总是很有耐心吗？",
        "您从来没有冲动过吗？"
    )
}

private data class SeedScale(
    val code: String,
    val name: String,
    val description: String,
    val version: Int,
    val method: ScaleScoringMethod,
    val ruleJson: String,
    val configJson: String? = null,
    val bands: List<SeedBand>,
    val questions: List<SeedQuestion>
)

private data class SeedQuestion(
    val questionKey: String,
    val orderNo: Int,
    val stem: String,
    val type: ScaleQuestionType = ScaleQuestionType.SINGLE_CHOICE,
    val dimension: String? = "total",
    val required: Boolean = true,
    val scorable: Boolean = true,
    val reverseScored: Boolean = false,
    val hint: String? = null,
    val note: String? = null,
    val optionSetCode: String? = "LIKERT_0_3",
    val metaJson: String? = null,
    val options: List<SeedOption> = emptyList()
)

private data class SeedOption(
    val optionKey: String,
    val orderNo: Int,
    val label: String,
    val scoreValue: BigDecimal?,
    val extJson: String? = null
)

private data class SeedBand(
    val dimension: String?,
    val minScore: BigDecimal,
    val maxScore: BigDecimal,
    val levelCode: String,
    val levelName: String,
    val interpretation: String
)
