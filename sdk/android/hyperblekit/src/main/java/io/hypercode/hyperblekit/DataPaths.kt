package io.hypercode.hyperblekit

/**
 * Which radios carry a game's messages once the phones are connected; see
 * [HyperBleKitConfig.dataPaths]. Finding a game, joining it and every fallback always use
 * Bluetooth LE.
 */
public enum class DataPaths {
    /** Bluetooth LE only, exactly as in HyperBleKit 0.1. The default. */
    BLE_ONLY,

    /**
     * Bluetooth LE first; then, between a host and a client that both support Wi-Fi Aware, the
     * messages move to Wi-Fi Aware, and back to Bluetooth LE if that link breaks. Games behave
     * the same either way: the same messages, the same size limits, only faster. Phones without
     * Wi-Fi Aware, or with an app built with [BLE_ONLY], play in the same game over Bluetooth LE.
     *
     * Needs Android 10 or later, a phone with Wi-Fi Aware, the permissions in
     * [HyperBleKit.requiredPermissions] for this config, and `ACCESS_WIFI_STATE` and
     * `CHANGE_WIFI_STATE` in the app's manifest. Without them the phone plays over Bluetooth LE.
     */
    PREFER_WIFI_AWARE,
}
