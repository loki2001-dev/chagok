package io.chagok.app.domain

/** Existing installations keep their recording choice; new ones opt in after choosing a vehicle. */
class FirstRunSetup(private val store: DiaryStore) {
    val required: Boolean get() = store.setting(COMPLETED) != "true" && store.setting(RECORDING) == null

    fun complete(powertrain: Powertrain) = store.transaction {
        val diary = DiaryService(store)
        val profile = diary.profile()
        store.saveProfile(profile.copy(powertrain = powertrain))
        store.saveSetting(COMPLETED, "true")
        store.saveSetting(RECORDING, "true")
    }

    companion object {
        const val COMPLETED = "onboarding.completed"
        const val RECORDING = "monitor.enabled"
    }
}
