package com.koneksiglobal.sapuranjau.game

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

// Entity + repository Spring Data JDBC (ADR-0020, SQL-first) untuk tabel yang dipakai orkestrasi run.
// Kolom yang TAK dipetakan (mis. created_at, email, phone_enc) sengaja dibiarkan ke DEFAULT DB —
// petakan hanya yang modul ini baca/tulis. Skema = `docs/08_DATA_SCHEMA.md` (V1..V16).

@Table("app_user")
data class AppUserRow(@Id val id: Long? = null, val firebaseUid: String, val status: String = "active")

interface AppUserRepo : CrudRepository<AppUserRow, Long> {
    fun findByFirebaseUid(firebaseUid: String): AppUserRow?
}

@Table("period")
data class PeriodRow(@Id val id: Long? = null, val name: String?, val status: String)

interface PeriodRepo : CrudRepository<PeriodRow, Long> {
    fun findFirstByStatus(status: String): PeriodRow?
}

@Table("level_config")
data class LevelConfigRow(
    @Id val id: Long? = null,
    val periodId: Long,
    val levelIndex: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val mineCount: Int,
    val baseScore: Int, // Base(L), ADR-0017
    val lifeCap: Int,   // capNyawa, ADR-0017
)

interface LevelConfigRepo : CrudRepository<LevelConfigRow, Long> {
    fun findByPeriodIdAndLevelIndex(periodId: Long, levelIndex: Int): LevelConfigRow?
    fun countByPeriodId(periodId: Long): Int
}

// Agregat pemain per periode = sumber leaderboard (§8). One-shot: `currentLevel` hanya maju (ADR-0024).
@Table("run")
data class RunRow(
    @Id val id: Long? = null,
    val userId: Long,
    val periodId: Long,
    val currentLevel: Int = 0,
    val totalScore: Long = 0,
    val livesUsed: Int = 0,
    val totalTimeMs: Long = 0,
    val totalMoves: Int = 0,
    val completedAllAt: Instant? = null,
    val scoreLocked: Boolean = false,
    val updatedAt: Instant = Instant.now(),
)

interface RunRepo : CrudRepository<RunRow, Long> {
    fun findByPeriodIdAndUserId(periodId: Long, userId: Long): RunRow?
}

// `moves`/`movesCount`/`activeTimeMs` = progres level BERJALAN (ADR-0036). Ditulis lewat JdbcClient
// bersyarat (optimistic lock) di GameService, bukan lewat save() — lihat GameService.persist().
@Table("board")
data class BoardRow(
    @Id val id: Long? = null,
    val runId: Long,
    val levelConfigId: Long,
    val seed: Long,
    val commitHash: String,
    val status: String = "active",
    val moves: ByteArray = ByteArray(0),
    val movesCount: Int = 0, // langkah TERSKOR (ADR-0018), bukan panjang log
    val activeTimeMs: Long = 0,
    val updatedAt: Instant = Instant.now(),
) {
    // data class + array: equals/hashCode by identity sudah cukup (row tak pernah dibandingkan).
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

interface BoardRepo : CrudRepository<BoardRow, Long> {
    fun findByRunIdAndLevelConfigId(runId: Long, levelConfigId: Long): BoardRow?
}

// Hasil level FINAL (one-shot: UNIQUE (run, level) — ADR-0024). `moves` = log final utk re-sim.
@Table("level_score")
data class LevelScoreRow(
    @Id val id: Long? = null,
    val runId: Long,
    val levelConfigId: Long,
    val moves: ByteArray,
    val movesCount: Int,
    val parMoves: Int,
    val activeTimeMs: Long,
    val livesUsed: Int,
    val score: Int,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

interface LevelScoreRepo : CrudRepository<LevelScoreRow, Long> {
    fun existsByRunIdAndLevelConfigId(runId: Long, levelConfigId: Long): Boolean
}
