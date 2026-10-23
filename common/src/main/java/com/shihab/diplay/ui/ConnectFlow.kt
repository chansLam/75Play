package com.shihab.diplay.ui

import com.shihab.diplay.host.R

/**
 * 无线连接在主页上的五步。顺序跟实际上报一致：准备、拉起无线、找到手机、握手、出画面。
 * 认不出的句子返回 -1，界面保持上一步，避免一句杂讯把进度打回起点。
 */
object ConnectFlow {
    const val COUNT = 5
    const val PREPARE = 0
    const val NETWORK = 1
    const val PHONE = 2
    const val LINK = 3
    const val SCREEN = 4

    fun titleRes(index: Int): Int = when (index) {
        PREPARE -> R.string.connect_flow_prepare
        NETWORK -> R.string.connect_flow_network
        PHONE -> R.string.connect_flow_phone
        LINK -> R.string.connect_flow_link
        else -> R.string.connect_flow_screen
    }

    fun indexOf(label: String, stringOf: (Int) -> String): Int {
        val text = label.trim()
        if (text.isEmpty()) return -1
        fun exact(id: Int) = text == stringOf(id)
        fun prefix(id: Int): Boolean {
            val head = stringOf(id).substringBefore('%').trim()
            return head.length >= 2 && text.startsWith(head)
        }
        if (exact(R.string.wireless_carplay_active) ||
            exact(R.string.opening_carplay) ||
            exact(R.string.carplay_service_already_running) ||
            exact(R.string.carplay_session_already_running)
        ) return SCREEN
        if (exact(R.string.pairing_with_iphone) ||
            exact(R.string.connecting_iap2_control) ||
            exact(R.string.wireless_carplay_control_running) ||
            exact(R.string.carplay_control_running) ||
            exact(R.string.selecting_carplay_configuration) ||
            exact(R.string.opening_usb_data_paths)
        ) return LINK
        if (exact(R.string.waiting_for_paired_iphone) ||
            exact(R.string.discovering_iphone) ||
            exact(R.string.waiting_for_iphone_over_usb) ||
            exact(R.string.connect_your_iphone_with_a_usb_cable) ||
            exact(R.string.looking_for_your_paired_iphone) ||
            exact(R.string.connecting_bluetooth) ||
            exact(R.string.reconnecting_to_your_iphone) ||
            exact(R.string.starting_airplay_service) ||
            exact(R.string.status_attaching_ncm)
        ) return PHONE
        if (exact(R.string.starting_wireless_hotspot) ||
            exact(R.string.existing_wifi_attaching) ||
            prefix(R.string.status_hotspot_ready) ||
            prefix(R.string.existing_wifi_ready)
        ) return NETWORK
        if (exact(R.string.preparing_mfi_authentication) ||
            exact(R.string.waiting_for_mfi_coprocessor) ||
            exact(R.string.requesting_mfi_usb_permission) ||
            exact(R.string.mfi_authentication_ready) ||
            exact(R.string.getting_carplay_ready) ||
            exact(R.string.connecting_to_your_iphone)
        ) return PREPARE
        return -1
    }
}
