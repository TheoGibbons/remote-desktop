package co.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The order devices are listed in: PCs, then phones; online first, then by name. */
class DeviceOrderTest {
    private data class Listed(val name: String, val device: String, val online: Boolean, val key: String)

    private fun sort(items: List<Listed>) =
        DeviceOrder.sort(items, { it.device }, { it.online }, { it.name }, { it.key })

    @Test
    fun pcsComeBeforePhonesThenOnlineBeforeOfflineThenByName() {
        // In the order they might have joined or been approved.
        val devices = listOf(
            Listed("Pixel", "android", online = true, key = "k1"),
            Listed("office", "windows", online = false, key = "k2"),
            Listed("S26", "android", online = false, key = "k3"),
            Listed("Laptop", "windows", online = true, key = "k4"),
            Listed("Desktop", "windows", online = true, key = "k5"),
            Listed("Tablet", "ipad", online = true, key = "k6"),
            Listed("galaxy", "android", online = true, key = "k7"),
            Listed("Attic", "windows", online = false, key = "k8"),
        )
        assertEquals(
            listOf("Desktop", "Laptop", "Attic", "office", "galaxy", "Pixel", "S26", "Tablet"),
            sort(devices).map { it.name },
        )
    }

    @Test
    fun devicesOfOneNameKeepTheirPlacesWhicheverJoinedFirst() {
        val a = Listed("Phone", "android", online = true, key = "aaa")
        val b = Listed("Phone", "android", online = true, key = "bbb")
        assertSame(a, sort(listOf(b, a)).first())
        assertSame(a, sort(listOf(a, b)).first())
    }
}
