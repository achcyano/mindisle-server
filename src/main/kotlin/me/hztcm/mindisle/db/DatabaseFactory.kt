package me.hztcm.mindisle.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import me.hztcm.mindisle.config.DbConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    private lateinit var database: Database

    fun init(config: DbConfig) {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            driverClassName = config.driver
            maximumPoolSize = config.maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        val dataSource = HikariDataSource(hikari)
        database = Database.connect(dataSource)
        transaction(database) {
            SchemaUtils.create(
                UsersTable,
                UserProfilesTable,
                UserFamilyHistoriesTable,
                UserMedicalHistoriesTable,
                UserMedicationHistoriesTable,
                SmsVerificationCodesTable,
                UserSessionsTable,
                LoginTicketsTable
            )
        }
    }

    suspend fun <T> dbQuery(block: Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database, statement = block)
}
