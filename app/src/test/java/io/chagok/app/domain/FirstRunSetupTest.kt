package io.chagok.app.domain

import org.junit.Assert.*
import org.junit.Test

class FirstRunSetupTest {
    @Test fun openingSetupDoesNotChoosePowertrainOrEnableRecording() {
        val store = MemoryStore()
        assertTrue(FirstRunSetup(store).required)
        assertNull(store.profile())
        assertNull(store.setting(FirstRunSetup.RECORDING))
    }

    @Test fun explicitStartPersistsChoiceAcrossRecreationAndSelectsCorrectDetector() {
        val store = MemoryStore()
        FirstRunSetup(store).complete(Powertrain.EV)
        assertFalse(FirstRunSetup(store).required)
        assertEquals("true", store.setting(FirstRunSetup.RECORDING))
        assertEquals(listOf(EnergyKind.SOC), DiaryService(store).energyKinds())
        store.saveSetting(FirstRunSetup.RECORDING, "false")
        assertFalse(FirstRunSetup(store).required)
        assertEquals("false", store.setting(FirstRunSetup.RECORDING))
    }

    @Test fun existingUsersKeepTheirRecordingChoiceAndVehicle() {
        for (enabled in listOf("true", "false")) {
            val store = MemoryStore()
            val vehicle = VehicleProfile("existing", "우리 전기차", Powertrain.EV, "기존 차종", "vin")
            store.saveProfile(vehicle)
            store.saveSetting(FirstRunSetup.RECORDING, enabled)
            assertFalse(FirstRunSetup(store).required)
            assertEquals(vehicle, store.profile())
            assertEquals(enabled, store.setting(FirstRunSetup.RECORDING))
        }
    }

    @Test fun setupPreservesExistingIdentityAndDoesNotEnableOptionalFeatures() {
        val store = MemoryStore()
        val vehicle = VehicleProfile("existing", "우리 차", Powertrain.ICE, "차종", "vin")
        store.saveProfile(vehicle)
        FirstRunSetup(store).complete(Powertrain.HYBRID)
        assertEquals(vehicle.copy(powertrain = Powertrain.HYBRID), store.profile())
        assertNull(store.setting(PlaceVisitTracker.ENABLED))
        assertTrue(store.plans(vehicle.id).isEmpty())
    }
}
