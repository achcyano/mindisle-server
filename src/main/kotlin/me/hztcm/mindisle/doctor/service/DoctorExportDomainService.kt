package me.hztcm.mindisle.doctor.service

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorPatientBindingStatus
import me.hztcm.mindisle.db.DoctorPatientBindingsTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.db.ScaleOptionsTable
import me.hztcm.mindisle.db.ScaleQuestionsTable
import me.hztcm.mindisle.db.ScaleSessionStatus
import me.hztcm.mindisle.db.ScalesTable
import me.hztcm.mindisle.db.UserDiseaseHistoriesTable
import me.hztcm.mindisle.db.UserMedicalHistoriesTable
import me.hztcm.mindisle.db.UserMedicationsTable
import me.hztcm.mindisle.db.UserProfilesTable
import me.hztcm.mindisle.db.UserScaleAnswerRecordsTable
import me.hztcm.mindisle.db.UserScaleSessionsTable
import me.hztcm.mindisle.db.UserWeightLogsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.Gender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val CSV_NEWLINE = "\r\n"
private val CSV_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val SCALE_CODE_WITH_DIGITS_REGEX = Regex("^([A-Z]+)(\\d+)$")
private val EXPORT_FILENAME_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

private val PATIENTS_CSV_HEADERS = listOf(
    "患者ID",
    "手机号",
    "姓名",
    "性别",
    "出生日期",
    "既往史",
    "是否使用中药",
    "身高(cm)",
    "腰围(cm)",
    "疾病史"
)

private val WEIGHT_LOGS_CSV_HEADERS = listOf(
    "患者ID",
    "记录日期",
    "体重(kg)",
    "来源"
)

private val MEDICATIONS_CSV_HEADERS = listOf(
    "患者ID",
    "用药ID",
    "药品名称",
    "服药时间",
    "开始日期",
    "结束日期",
    "每次用量",
    "用量单位",
    "单片规格用量",
    "单片规格单位",
    "是否已删除",
    "删除时间",
    "创建时间",
    "更新时间"
)

private val SCALE_ANSWERS_CSV_HEADERS = listOf(
    "患者ID",
    "会话ID",
    "量表编码",
    "量表名称",
    "题目标识",
    "原始答案",
    "作答日期"
)

data class DoctorPatientsExportResult(
    val fileName: String,
    val zipBytes: ByteArray
)

private data class DoctorExportScope(
    val generatedAt: LocalDateTime,
    val orderedPatientIds: List<Long>,
    val patientRefs: List<EntityID<Long>>
)

internal class DoctorExportDomainService(private val deps: DoctorServiceDeps) {
    suspend fun exportDoctorPatients(doctorId: Long): DoctorPatientsExportResult {
        val (generatedAt, zipBytes) = DatabaseFactory.dbQuery { buildExportZipBytes(doctorId) }
        val fileName = buildExportZipFileName(doctorId, generatedAt)
        return DoctorPatientsExportResult(
            fileName = fileName,
            zipBytes = zipBytes
        )
    }

    private fun org.jetbrains.exposed.sql.Transaction.buildExportZipBytes(doctorId: Long): Pair<LocalDateTime, ByteArray> {
        val scope = loadExportScope(doctorId)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writePatientsCsv(zip, scope)
            writeWeightLogsCsv(zip, scope.patientRefs)
            writeMedicationsCsv(zip, scope.patientRefs)
            writeScaleAnswersCsv(zip, scope.patientRefs)
        }
        return scope.generatedAt to output.toByteArray()
    }

    private fun org.jetbrains.exposed.sql.Transaction.loadExportScope(doctorId: Long): DoctorExportScope {
        val doctorRef = EntityID(doctorId, DoctorsTable)
        requireDoctor(doctorRef)

        val now = utcNow()
        val activeBindings = DoctorPatientBindingsTable.selectAll().where {
            (DoctorPatientBindingsTable.doctorId eq doctorRef) and
                (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE) and
                DoctorPatientBindingsTable.unboundAt.isNull()
        }.orderBy(DoctorPatientBindingsTable.updatedAt, SortOrder.DESC).toList()

        val patientIds = linkedSetOf<Long>()
        activeBindings.forEach { row ->
            patientIds += row[DoctorPatientBindingsTable.patientUserId].value
        }
        val orderedPatientIds = patientIds.sorted()
        val patientRefs = orderedPatientIds.map { EntityID(it, UsersTable) }
        return DoctorExportScope(
            generatedAt = now,
            orderedPatientIds = orderedPatientIds,
            patientRefs = patientRefs
        )
    }

    private fun org.jetbrains.exposed.sql.Transaction.writePatientsCsv(
        zip: ZipOutputStream,
        scope: DoctorExportScope
    ) {
        zip.writeCsvEntry("patients.csv", PATIENTS_CSV_HEADERS) { writer ->
            val usersById = if (scope.patientRefs.isEmpty()) {
                emptyMap()
            } else {
                UsersTable.selectAll().where {
                    UsersTable.id inList scope.patientRefs
                }.associateBy { it[UsersTable.id].value }
            }

            val profilesByUserId = if (scope.patientRefs.isEmpty()) {
                emptyMap()
            } else {
                UserProfilesTable.selectAll().where {
                    UserProfilesTable.userId inList scope.patientRefs
                }.associateBy { it[UserProfilesTable.userId].value }
            }

            val medicalHistoriesByUserId = if (scope.patientRefs.isEmpty()) {
                emptyMap()
            } else {
                UserMedicalHistoriesTable.selectAll().where {
                    UserMedicalHistoriesTable.userId inList scope.patientRefs
                }.orderBy(UserMedicalHistoriesTable.createdAt, SortOrder.ASC)
                    .toList()
                    .groupBy { it[UserMedicalHistoriesTable.userId].value }
                    .mapValues { (_, rows) ->
                        rows.mapNotNull { row ->
                            row[UserMedicalHistoriesTable.item].trim().takeIf { it.isNotEmpty() }
                        }
                    }
            }

            val diseaseHistoriesByUserId = if (scope.patientRefs.isEmpty()) {
                emptyMap()
            } else {
                UserDiseaseHistoriesTable.selectAll().where {
                    UserDiseaseHistoriesTable.userId inList scope.patientRefs
                }.orderBy(UserDiseaseHistoriesTable.createdAt, SortOrder.ASC)
                    .toList()
                    .groupBy { it[UserDiseaseHistoriesTable.userId].value }
                    .mapValues { (_, rows) ->
                        rows.mapNotNull { row ->
                            row[UserDiseaseHistoriesTable.item].trim().takeIf { it.isNotEmpty() }
                        }
                    }
            }

            scope.orderedPatientIds.forEach { patientId ->
                val userRow = usersById[patientId] ?: return@forEach
                val profileRow = profilesByUserId[patientId]
                val medicalHistory = medicalHistoriesByUserId[patientId].orEmpty().joinToString("；")
                val diseaseHistory = diseaseHistoriesByUserId[patientId].orEmpty().joinToString("；")
                writer.writeRow(
                    listOf(
                        patientId.toString(),
                        userRow[UsersTable.phone],
                        profileRow?.get(UserProfilesTable.fullName).orEmpty(),
                        formatGender(profileRow?.get(UserProfilesTable.gender)),
                        profileRow?.get(UserProfilesTable.birthDate)?.toString().orEmpty(),
                        medicalHistory,
                        formatYesNo(profileRow?.get(UserProfilesTable.usesTcm)),
                        formatDecimal(profileRow?.get(UserProfilesTable.heightCm)),
                        formatDecimal(profileRow?.get(UserProfilesTable.waistCm)),
                        diseaseHistory
                    )
                )
            }
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.writeWeightLogsCsv(
        zip: ZipOutputStream,
        patientRefs: List<EntityID<Long>>
    ) {
        zip.writeCsvEntry("weight_logs.csv", WEIGHT_LOGS_CSV_HEADERS) { writer ->
            if (patientRefs.isEmpty()) {
                return@writeCsvEntry
            }

            UserWeightLogsTable.selectAll().where {
                UserWeightLogsTable.userId inList patientRefs
            }.orderBy(UserWeightLogsTable.userId, SortOrder.ASC)
                .orderBy(UserWeightLogsTable.recordedAt, SortOrder.ASC)
                .forEach { row ->
                    writer.writeRow(
                        listOf(
                            row[UserWeightLogsTable.userId].value.toString(),
                            row[UserWeightLogsTable.recordedAt].toIsoOffsetPlus8(),
                            formatDecimal(row[UserWeightLogsTable.weightKg]),
                            row[UserWeightLogsTable.sourceType]
                        )
                    )
                }
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.writeMedicationsCsv(
        zip: ZipOutputStream,
        patientRefs: List<EntityID<Long>>
    ) {
        zip.writeCsvEntry("medications.csv", MEDICATIONS_CSV_HEADERS) { writer ->
            if (patientRefs.isEmpty()) {
                return@writeCsvEntry
            }

            UserMedicationsTable.selectAll().where {
                UserMedicationsTable.userId inList patientRefs
            }.orderBy(UserMedicationsTable.userId, SortOrder.ASC)
                .orderBy(UserMedicationsTable.createdAt, SortOrder.ASC)
                .orderBy(UserMedicationsTable.id, SortOrder.ASC)
                .forEach { row ->
                    writer.writeRow(
                        listOf(
                            row[UserMedicationsTable.userId].value.toString(),
                            row[UserMedicationsTable.id].value.toString(),
                            row[UserMedicationsTable.drugName],
                            parseDoseTimesForExport(row[UserMedicationsTable.doseTimesJson]),
                            row[UserMedicationsTable.recordedDateLocal].toString(),
                            row[UserMedicationsTable.endDateLocal].toString(),
                            formatDecimal(row[UserMedicationsTable.doseAmount]),
                            row[UserMedicationsTable.doseUnit].name,
                            formatDecimal(row[UserMedicationsTable.tabletStrengthAmount]),
                            row[UserMedicationsTable.tabletStrengthUnit]?.name.orEmpty(),
                            formatYesNo(row[UserMedicationsTable.deletedAt] != null),
                            row[UserMedicationsTable.deletedAt]?.toIsoInstant().orEmpty(),
                            row[UserMedicationsTable.createdAt].toIsoInstant(),
                            row[UserMedicationsTable.updatedAt].toIsoInstant()
                        )
                    )
                }
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.writeScaleAnswersCsv(
        zip: ZipOutputStream,
        patientRefs: List<EntityID<Long>>
    ) {
        zip.writeCsvEntry("scale_answers.csv", SCALE_ANSWERS_CSV_HEADERS) { writer ->
            if (patientRefs.isEmpty()) {
                return@writeCsvEntry
            }

            val sessions = UserScaleSessionsTable.selectAll().where {
                (UserScaleSessionsTable.userId inList patientRefs) and
                    (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED)
            }.orderBy(UserScaleSessionsTable.userId, SortOrder.ASC)
                .orderBy(UserScaleSessionsTable.submittedAt, SortOrder.ASC)
                .orderBy(UserScaleSessionsTable.id, SortOrder.ASC)
                .toList()
            if (sessions.isEmpty()) {
                return@writeCsvEntry
            }

            val sessionRefs = sessions.map { it[UserScaleSessionsTable.id] }
            val answerRows = UserScaleAnswerRecordsTable.selectAll().where {
                UserScaleAnswerRecordsTable.sessionId inList sessionRefs
            }.toList()
            if (answerRows.isEmpty()) {
                return@writeCsvEntry
            }

            val latestAnswerBySessionQuestion = mutableMapOf<Pair<Long, Long>, ResultRow>()
            answerRows.forEach { row ->
                val key = row[UserScaleAnswerRecordsTable.sessionId].value to row[UserScaleAnswerRecordsTable.questionId].value
                val current = latestAnswerBySessionQuestion[key]
                if (current == null || isAnswerRecordLater(row, current)) {
                    latestAnswerBySessionQuestion[key] = row
                }
            }
            if (latestAnswerBySessionQuestion.isEmpty()) {
                return@writeCsvEntry
            }

            val questionRefs = latestAnswerBySessionQuestion.values.map { it[UserScaleAnswerRecordsTable.questionId] }.distinct()
            val questionById = if (questionRefs.isEmpty()) {
                emptyMap()
            } else {
                ScaleQuestionsTable.selectAll().where {
                    ScaleQuestionsTable.id inList questionRefs
                }.associateBy { it[ScaleQuestionsTable.id].value }
            }
            val optionRowsByQuestionId = if (questionRefs.isEmpty()) {
                emptyMap()
            } else {
                ScaleOptionsTable.selectAll().where {
                    ScaleOptionsTable.questionId inList questionRefs
                }.orderBy(ScaleOptionsTable.orderNo, SortOrder.ASC)
                    .toList()
                    .groupBy { it[ScaleOptionsTable.questionId].value }
            }
            val optionLabelByQuestionIdAndOptionId = optionRowsByQuestionId.mapValues { (_, rows) ->
                rows.associate { row ->
                    row[ScaleOptionsTable.id].value to row[ScaleOptionsTable.label]
                }
            }
            val optionLabelByQuestionIdAndOptionKey = optionRowsByQuestionId.mapValues { (_, rows) ->
                rows.associate { row ->
                    row[ScaleOptionsTable.optionKey] to row[ScaleOptionsTable.label]
                }
            }

            val scaleRefs = sessions.map { it[UserScaleSessionsTable.scaleId] }.distinct()
            val scaleById = if (scaleRefs.isEmpty()) {
                emptyMap()
            } else {
                ScalesTable.selectAll().where {
                    ScalesTable.id inList scaleRefs
                }.associateBy { it[ScalesTable.id].value }
            }

            val latestAnswersBySessionId = latestAnswerBySessionQuestion.values.groupBy { it[UserScaleAnswerRecordsTable.sessionId].value }
            sessions.forEach { session ->
                val sessionId = session[UserScaleSessionsTable.id].value
                val scaleRow = scaleById[session[UserScaleSessionsTable.scaleId].value]
                val rawScaleCode = scaleRow?.get(ScalesTable.code) ?: "SCALE-${session[UserScaleSessionsTable.scaleId].value}"
                val normalizedScaleCode = normalizeScaleCodeForExport(rawScaleCode)
                val sessionSubmittedAt = session[UserScaleSessionsTable.submittedAt]
                latestAnswersBySessionId[sessionId].orEmpty()
                    .sortedWith(
                        compareBy<ResultRow> {
                            questionById[it[UserScaleAnswerRecordsTable.questionId].value]?.get(ScaleQuestionsTable.orderNo)
                                ?: Int.MAX_VALUE
                        }.thenBy { it[UserScaleAnswerRecordsTable.questionId].value }
                    )
                    .forEach { answer ->
                        val questionId = answer[UserScaleAnswerRecordsTable.questionId].value
                        val questionOrderNo = questionById[questionId]?.get(ScaleQuestionsTable.orderNo)
                        val questionIdentifier = if (questionOrderNo != null) {
                            "$normalizedScaleCode-$questionOrderNo"
                        } else {
                            "$normalizedScaleCode-Q$questionId"
                        }
                        val answerDate = (sessionSubmittedAt ?: answer[UserScaleAnswerRecordsTable.answeredAt]).toIsoOffsetPlus8()
                        val enrichedRawAnswerJson = enrichRawAnswerJsonForExport(
                            rawAnswerJson = answer[UserScaleAnswerRecordsTable.rawAnswerJson],
                            optionLabelById = optionLabelByQuestionIdAndOptionId[questionId].orEmpty(),
                            optionLabelByKey = optionLabelByQuestionIdAndOptionKey[questionId].orEmpty(),
                            json = deps.json
                        )
                        writer.writeRow(
                            listOf(
                                session[UserScaleSessionsTable.userId].value.toString(),
                                sessionId.toString(),
                                rawScaleCode,
                                scaleRow?.get(ScalesTable.name).orEmpty(),
                                questionIdentifier,
                                enrichedRawAnswerJson,
                                answerDate
                            )
                        )
                    }
            }
        }
    }

    private fun isAnswerRecordLater(candidate: ResultRow, current: ResultRow): Boolean {
        val candidateAnsweredAt = candidate[UserScaleAnswerRecordsTable.answeredAt]
        val currentAnsweredAt = current[UserScaleAnswerRecordsTable.answeredAt]
        if (candidateAnsweredAt != currentAnsweredAt) {
            return candidateAnsweredAt.isAfter(currentAnsweredAt)
        }
        return candidate[UserScaleAnswerRecordsTable.id].value > current[UserScaleAnswerRecordsTable.id].value
    }

    private fun parseDoseTimesForExport(raw: String): String {
        val parsed = runCatching { deps.json.decodeFromString<List<String>>(raw) }.getOrNull()
            ?: return raw
        return parsed.mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() } }.joinToString("|")
    }
}

private class ZipCsvEntryWriter(
    private val zip: ZipOutputStream,
    private val columnCount: Int
) {
    fun writeRow(row: List<String>) {
        val normalized = if (row.size < columnCount) {
            row + List(columnCount - row.size) { "" }
        } else {
            row.take(columnCount)
        }
        val line = normalized.joinToString(",") { escapeCsvCell(it) } + CSV_NEWLINE
        zip.write(line.toByteArray(StandardCharsets.UTF_8))
    }
}

private inline fun ZipOutputStream.writeCsvEntry(
    name: String,
    headers: List<String>,
    block: (ZipCsvEntryWriter) -> Unit
) {
    putNextEntry(ZipEntry(name))
    try {
        write(CSV_BOM)
        val writer = ZipCsvEntryWriter(this, headers.size)
        writer.writeRow(headers)
        block(writer)
    } finally {
        closeEntry()
    }
}

internal fun normalizeScaleCodeForExport(scaleCode: String): String {
    val normalized = scaleCode.trim().uppercase().replace('_', '-')
    if (normalized.isEmpty()) {
        return normalized
    }
    val compact = normalized.replace("-", "")
    val match = SCALE_CODE_WITH_DIGITS_REGEX.matchEntire(compact)
    return if (match == null) {
        normalized
    } else {
        "${match.groupValues[1]}-${match.groupValues[2]}"
    }
}

internal fun enrichRawAnswerJsonForExport(
    rawAnswerJson: String,
    optionLabelById: Map<Long, String>,
    optionLabelByKey: Map<String, String>,
    json: Json = Json
): String {
    val rawElement = runCatching { json.parseToJsonElement(rawAnswerJson) }.getOrNull() ?: return rawAnswerJson
    val enriched = enrichRawAnswerElement(rawElement, optionLabelById, optionLabelByKey)
    return json.encodeToString(JsonElement.serializer(), enriched)
}

internal fun buildCsvBytes(headers: List<String>, rows: List<List<String>>): ByteArray {
    val builder = StringBuilder()
    builder.append(headers.joinToString(",") { escapeCsvCell(it) }).append(CSV_NEWLINE)
    rows.forEach { row ->
        val normalized = if (row.size < headers.size) {
            row + List(headers.size - row.size) { "" }
        } else {
            row.take(headers.size)
        }
        builder.append(normalized.joinToString(",") { escapeCsvCell(it) }).append(CSV_NEWLINE)
    }
    val csvBytes = builder.toString().toByteArray(StandardCharsets.UTF_8)
    return prependUtf8Bom(csvBytes)
}

private fun enrichRawAnswerElement(
    element: JsonElement,
    optionLabelById: Map<Long, String>,
    optionLabelByKey: Map<String, String>
): JsonElement {
    return when (element) {
        is JsonObject -> {
            val mapped = linkedMapOf<String, JsonElement>()
            element.forEach { (key, value) ->
                when (key) {
                    "optionId" -> {
                        mapped[key] = value
                        val optionId = (value as? JsonPrimitive)?.longOrNull
                        val label = optionId?.let { optionLabelById[it] }
                        if (label != null) {
                            mapped["optionLabel"] = JsonPrimitive(label)
                        }
                    }

                    "optionIds" -> {
                        mapped[key] = value
                        val ids = (value as? JsonArray)?.mapNotNull { item ->
                            (item as? JsonPrimitive)?.longOrNull
                        }.orEmpty()
                        val labels = ids.mapNotNull { optionLabelById[it] }
                        if (labels.isNotEmpty()) {
                            mapped["optionLabels"] = buildJsonArray {
                                labels.forEach { label -> add(JsonPrimitive(label)) }
                            }
                        }
                    }

                    "optionKey" -> {
                        mapped[key] = value
                        val optionKey = (value as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
                        val label = optionKey?.let { optionLabelByKey[it] }
                        if (label != null) {
                            mapped["optionLabel"] = JsonPrimitive(label)
                        }
                    }

                    "optionKeys" -> {
                        mapped[key] = value
                        val keys = (value as? JsonArray)?.mapNotNull { item ->
                            (item as? JsonPrimitive)?.content?.trim()?.takeIf { keyText -> keyText.isNotEmpty() }
                        }.orEmpty()
                        val labels = keys.mapNotNull { optionLabelByKey[it] }
                        if (labels.isNotEmpty()) {
                            mapped["optionLabels"] = buildJsonArray {
                                labels.forEach { label -> add(JsonPrimitive(label)) }
                            }
                        }
                    }

                    else -> {
                        mapped[key] = enrichRawAnswerElement(value, optionLabelById, optionLabelByKey)
                    }
                }
            }
            JsonObject(mapped)
        }

        is JsonArray -> JsonArray(element.map { item ->
            enrichRawAnswerElement(item, optionLabelById, optionLabelByKey)
        })

        else -> element
    }
}

internal fun escapeCsvCell(value: String?): String {
    val raw = value ?: ""
    val escaped = raw.replace("\"", "\"\"")
    val requiresQuotes = escaped.contains(',') || escaped.contains('"') || escaped.contains('\n') || escaped.contains('\r')
    return if (requiresQuotes) "\"$escaped\"" else escaped
}

private fun prependUtf8Bom(content: ByteArray): ByteArray {
    val output = ByteArrayOutputStream(content.size + CSV_BOM.size)
    output.write(CSV_BOM)
    output.write(content)
    return output.toByteArray()
}

private fun buildZipBytes(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun buildExportZipFileName(doctorId: Long, generatedAt: LocalDateTime): String {
    val timestamp = generatedAt.atOffset(ZoneOffset.UTC)
        .withOffsetSameInstant(ZoneOffset.ofHours(8))
        .format(EXPORT_FILENAME_TIME_FORMATTER)
    return "doctor-$doctorId-patients-export-$timestamp.zip"
}

private fun formatGender(gender: Gender?): String = when (gender ?: Gender.UNKNOWN) {
    Gender.UNKNOWN -> "未知"
    Gender.MALE -> "男"
    Gender.FEMALE -> "女"
    Gender.OTHER -> "其他"
}

private fun formatYesNo(value: Boolean?): String = when (value) {
    true -> "是"
    false -> "否"
    null -> ""
}

private fun formatDecimal(value: BigDecimal?): String {
    return value?.stripTrailingZeros()?.toPlainString().orEmpty()
}
