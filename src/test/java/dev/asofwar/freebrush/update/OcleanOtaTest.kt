package dev.asofwar.freebrush.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcleanOtaTest {
    @Test
    fun parsesTheServersOffer() {
        // Shape of the answer for the user's brush (oclean-re/firmware/README.md).
        val offer = OcleanOta.parse(
            """{"state":true,"code":0,"msg":"Success","data":{"mac":"70:28:45:85:56:C7","isAppDown":false,"language":0,
            "newOTAVersion":"1.0.2.5","newVoiceVersion":"1.0.0.0","newImgVersion":"1.0.0.0",
            "otaFilePath":"https://hwmag.oclean.com/upload/OTA/OCLEANY3PB/1.0.2.5/OTA.bin"}}""",
        )
        assertEquals("1.0.2.5", offer.version)
        assertEquals("https://hwmag.oclean.com/upload/OTA/OCLEANY3PB/1.0.2.5/OTA.bin", offer.url)
    }

    @Test
    fun anEmptyPathMeansNothingNewer() {
        val offer = OcleanOta.parse("""{"state":true,"code":0,"data":{"newOTAVersion":"1.0.2.5","otaFilePath":""}}""")
        assertNull(offer.url)
    }

    @Test(expected = UpdateError::class)
    fun aRefusalIsAnError() {
        OcleanOta.parse("""{"state":false,"code":4007,"msg":"Version string portion was too short","data":null}""")
    }

    @Test
    fun versionsForTheServer() {
        assertEquals("1.0.2.5", OcleanOta.serverVersion("1.0.2.5"))
        assertEquals("1.0.2.5", OcleanOta.serverVersion("V1.0.2.5_release"))
        assertEquals("1.0.0.0", OcleanOta.serverVersion(null))
        assertEquals("1.0.0.0", OcleanOta.serverVersion("unknown"))
        assertTrue(OcleanOta.compareVersions("1.0.2.5", "1.0.2.0") > 0)
        assertEquals(0, OcleanOta.compareVersions("1.0.2", "1.0.2.0"))
        assertTrue(OcleanOta.compareVersions("1.0.10", "1.0.9") > 0)
    }

    @Test
    fun onlyOcleanHttpsFilesAreDownloaded() {
        assertTrue(OcleanOta.isOcleanFile("https://hwmag.oclean.com/upload/OTA/OCLEANY3PB/1.0.2.5/OTA.bin"))
        assertFalse(OcleanOta.isOcleanFile("http://hwmag.oclean.com/upload/OTA.bin"))
        assertFalse(OcleanOta.isOcleanFile("https://evil.example/oclean.com/OTA.bin"))
        assertFalse(OcleanOta.isOcleanFile("https://notoclean.com/OTA.bin"))
    }
}
