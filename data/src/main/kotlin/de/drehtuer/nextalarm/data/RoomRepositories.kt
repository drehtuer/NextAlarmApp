package de.drehtuer.nextalarm.data

import de.drehtuer.nextalarm.data.db.AlarmDao
import de.drehtuer.nextalarm.data.db.PlannerDao
import de.drehtuer.nextalarm.data.db.SettingsDao
import de.drehtuer.nextalarm.data.db.toDomain
import de.drehtuer.nextalarm.data.db.toEntity
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmSnapshot
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.PlannerRecord
import de.drehtuer.nextalarm.domain.PlannerRepository
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SettingsRepository
import java.time.Instant

class RoomSettingsRepository(private val dao: SettingsDao) : SettingsRepository {
    override suspend fun load(): Settings = dao.load()?.toDomain() ?: Settings.DEFAULT

    override suspend fun save(settings: Settings) = dao.save(settings.toEntity())
}

class RoomPlannerRepository(private val dao: PlannerDao) : PlannerRepository {
    override suspend fun load(): PlannerRecord = dao.load()?.toDomain() ?: PlannerRecord.INITIAL

    override suspend fun save(record: PlannerRecord) = dao.save(record.toEntity())
}

class RoomAlarmRepository(private val dao: AlarmDao) : AlarmRepository {
    override suspend fun all(): List<AlarmInstance> = dao.all().map { it.toDomain() }

    override suspend fun find(id: Long): AlarmInstance? = dao.find(id)?.toDomain()

    override suspend fun create(key: AlarmKey, triggerAt: Instant, snapshot: AlarmSnapshot): AlarmInstance {
        val instance = AlarmInstance(
            id = 0,
            key = key,
            triggerAt = triggerAt,
            state = AlarmState.Scheduled,
            snoozeCount = 0,
            autoSnoozed = false,
            snapshot = snapshot,
        )
        return instance.copy(id = dao.insert(instance.toEntity()))
    }

    override suspend fun update(instance: AlarmInstance) = dao.update(instance.toEntity())
}
