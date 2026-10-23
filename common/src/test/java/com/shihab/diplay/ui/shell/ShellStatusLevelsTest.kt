// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellStatusLevelsTest {
    @Test fun gsmAsuMapsOntoFourBars() {
        assertEquals(0, ShellStatusLevels.gsmBars(99))
        assertEquals(0, ShellStatusLevels.gsmBars(0))
        assertEquals(0, ShellStatusLevels.gsmBars(1))
        assertEquals(1, ShellStatusLevels.gsmBars(2))
        assertEquals(2, ShellStatusLevels.gsmBars(5))
        assertEquals(3, ShellStatusLevels.gsmBars(8))
        assertEquals(4, ShellStatusLevels.gsmBars(12))
        assertEquals(4, ShellStatusLevels.gsmBars(31))
    }

    @Test fun lteRsrpUsesTheSameStepsAsThePlatformThresholds() {
        assertEquals(4, ShellStatusLevels.lteRsrpBars(-85))
        assertEquals(3, ShellStatusLevels.lteRsrpBars(-95))
        assertEquals(2, ShellStatusLevels.lteRsrpBars(-105))
        assertEquals(1, ShellStatusLevels.lteRsrpBars(-115))
        assertEquals(0, ShellStatusLevels.lteRsrpBars(-116))
    }

    @Test fun lteReadingWinsOverAStaleGsmAsu() {
        assertEquals(4, ShellStatusLevels.cellBars(gsm = true, gsmAsu = 99, cdmaDbm = -1, lteRsrp = -80, lteAsu = 99))
        assertEquals(2, ShellStatusLevels.cellBars(gsm = true, gsmAsu = 2, cdmaDbm = -1, lteRsrp = Int.MIN_VALUE, lteAsu = 8))
        assertEquals(1, ShellStatusLevels.cellBars(gsm = true, gsmAsu = 3, cdmaDbm = -1, lteRsrp = 0, lteAsu = 99))
        assertEquals(3, ShellStatusLevels.cellBars(gsm = false, gsmAsu = 99, cdmaDbm = -80, lteRsrp = Int.MAX_VALUE, lteAsu = 0))
        assertEquals(0, ShellStatusLevels.cdmaBars(0))
        assertEquals(0, ShellStatusLevels.cdmaBars(-130))
    }

    @Test fun wifiRssiUsesTheKitKatHundredToFiftyFiveWindow() {
        assertEquals(0, ShellStatusLevels.wifiBars(-101))
        assertEquals(0, ShellStatusLevels.wifiBars(-100))
        assertEquals(2, ShellStatusLevels.wifiBars(-70))
        assertEquals(3, ShellStatusLevels.wifiBars(-55))
        assertEquals(3, ShellStatusLevels.wifiBars(-40))
    }

    @Test fun bluetoothConnectedBeatsEnabled() {
        assertEquals(ShellBluetooth.OFF, ShellStatusLevels.bluetooth(false, headsetConnected = true, a2dpConnected = false))
        assertEquals(ShellBluetooth.ON, ShellStatusLevels.bluetooth(true, headsetConnected = false, a2dpConnected = false))
        assertEquals(ShellBluetooth.CONNECTED, ShellStatusLevels.bluetooth(true, headsetConnected = false, a2dpConnected = true))
        assertEquals(ShellBluetooth.CONNECTED, ShellStatusLevels.bluetooth(true, headsetConnected = true, a2dpConnected = false))
    }
}
