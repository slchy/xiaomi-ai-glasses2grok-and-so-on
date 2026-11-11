package com.miaiglasses.core.config

/**
 * Firmware-bound protocol facts. These are not host tuning knobs.
 * Numeric values match the constants that previously lived next to each codec.
 */
public object GlassesProtocol {
    public object L1 {
        public const val MAGIC: Int = 0xA5
        public const val HEADER_SIZE: Int = 8
        public const val MAX_PAYLOAD: Int = 0xFFFF
        public const val TYPE_NAK: Int = 0
        public const val TYPE_ACK: Int = 1
        public const val TYPE_CMD: Int = 2
        public const val TYPE_DATA: Int = 3
    }

    public object Crc {
        public const val REFLECTED_POLY: Int = com.miaiglasses.core.Crc16Arc.REFLECTED_POLY
        public const val POLY: Int = com.miaiglasses.core.Crc16Arc.POLY
        public const val INIT: Int = com.miaiglasses.core.Crc16Arc.INIT
        public const val XOR_OUT: Int = com.miaiglasses.core.Crc16Arc.XOR_OUT
        public const val MASK: Int = com.miaiglasses.core.Crc16Arc.MASK
    }

    public object Bluetooth {
        public const val SPP_UUID: String = "00001101-0000-1000-8000-00805F9B34FB"
    }

    public object Crypto {
        public const val TOKEN_SIZE: Int = 16
        public const val HKDF_INFO: String = "miwear-auth"
        public const val HKDF_LENGTH: Int = 64
        public const val DEVICE_KEY_OFFSET: Int = 0
        public const val APP_KEY_OFFSET: Int = 16
        public const val KEY_LENGTH: Int = 16
        public const val DEVICE_IV_OFFSET: Int = 32
        public const val APP_IV_OFFSET: Int = 36
        public const val IV_LENGTH: Int = 4
        public const val CCM_TAG_LENGTH: Int = 4
        public const val CCM_NONCE_ZERO_PAD: Int = 8
        public const val RANDOM_SIZE: Int = 16
    }

    public object Handshake {
        public const val ACCOUNT_TYPE: Int = 1
        public const val ID_APP_VERIFY: Int = 26
        public const val ID_DEVICE_VERIFY: Int = 26
        public const val ID_APP_CONFIRM: Int = 27
        public const val ID_DEVICE_CONFIRM: Int = 27
        public const val FIELD_APP_VERIFY: Int = 30
        public const val FIELD_DEVICE_VERIFY: Int = 31
        public const val FIELD_APP_CONFIRM: Int = 32
        public const val COMPANION_DEVICE_TYPE: Int = 0
        public const val COMPANION_SYSTEM_VERSION: Float = 31.0f
        public const val COMPANION_FIELD_DEVICE_TYPE: Int = 1
        public const val COMPANION_FIELD_SYSTEM_VERSION: Int = 2
        public const val COMPANION_FIELD_MODEL: Int = 3
        public const val COMPANION_FIELD_LOCALE: Int = 5
    }

    public object Sar {
        public const val CMD_L1START_REQ: Int = 1
        public const val CMD_L1START_RSP: Int = 2
        public const val CMD_L1STOP_REQ: Int = 3
        public const val CMD_L1STOP_RSP: Int = 4
        public const val CONFIG_TYPE_VERSION: Int = 1
        public const val CONFIG_TYPE_MPS: Int = 2
        public const val CONFIG_TYPE_TX_WIN: Int = 3
        public const val CONFIG_TYPE_SEND_TIMEOUT: Int = 4
        public const val IMMEDIATE_ACK_PB: Int = 1
        public const val IMMEDIATE_ACK_LYRA: Int = 8
        public const val NO_ACK_NETWORK: Int = 7

        public fun cumAckThreshold(remoteWindow: Int): Int = (remoteWindow * 2) / 3
    }

    public object L2 {
        public const val HEADER_SIZE: Int = 2
        public const val CH_PB: Int = 1
        public const val CH_MASS: Int = 2
        public const val CH_MASS_VOICE: Int = 3
        public const val CH_FILE_SENSOR: Int = 4
        public const val CH_FILE_FITNESS: Int = 5
        public const val CH_OTA: Int = 6
        public const val CH_NETWORK: Int = 7
        public const val CH_LYRA: Int = 8
        public const val CH_RESEARCH: Int = 9
        public const val OP_WRITE: Int = 1
        public const val OP_WRITE_ENC: Int = 2
        public const val OP_READ: Int = 3
    }

    public object Wear {
        public const val ACCOUNT: Int = 1
        public const val SYSTEM: Int = 2
        public const val FACTORY: Int = 13
        public const val VOICE: Int = 14
        public const val MEDIA: Int = 18
        public const val PAYSDK: Int = 20
        public const val INTERCONNECTION: Int = 23
    }

    public object Mass {
        public const val HEADER_SIZE: Int = 12
        public const val CMD_START: Int = 1
        public const val CMD_DATA: Int = 2
        public const val CMD_END: Int = 3
        public const val FIRST_VOICE: Int = 1
        public const val FOLLOW_VOICE: Int = 2
        public const val FIRST_IMAGE: Int = 3
        public const val FOLLOW_IMAGE: Int = 4
        public const val IMAGE_OCR: Int = 5
        public const val WAKEUP_FIRST: Int = 6
        public const val WAKEUP_FOLLOW: Int = 7
        public const val SMALL_PICTURE: Int = 8
        public const val LARGE_PICTURE: Int = 9
        public const val XIAO_AI_TRACK: Int = 10
        public const val IMAGE_HD_FOLLOW: Int = 11
        public const val MSBC_FRAME_BYTES: Int = 57
        public const val MSBC_FRAME_MS: Double = 7.5
        public const val TYPICAL_DIALOG_PACKET_BYTES: Int = 582
    }

    public object Voice {
        public const val TYPE: Int = 14
        public const val ID_WAKE: Int = 10
        public const val ID_MULTIMODAL: Int = 4
        public const val ID_SYNC_STATUS: Int = 5
        public const val ID_STATUS: Int = 0
        public const val ID_ERROR: Int = 6
        public const val ERROR_TAKEN_OFF: Int = 4
    }

    public object System {
        public const val TYPE: Int = 2
        public const val ID_DEVICE_STATUS: Int = 1
        public const val ID_DEVICE_INFO: Int = 2
        public const val ID_SET_SYSTEM_TIME: Int = 3
        public const val ID_SYSTEM_SETTING: Int = 14
        public const val ID_SYSTEM_SETTING_WRITE: Int = 15
        public const val ID_ALERT_STATUS: Int = 91
        public const val ID_WEAR_STATUS: Int = 93
        public const val ID_BATTERY: Int = 97
        public const val FIELD_WEAR_STATUS: Int = 60
        public const val FIELD_ALERT_STATUS: Int = 58
    }

    public object Wifi {
        public const val ID_ENABLE_AP: Int = 88
        public const val ID_DISABLE_AP: Int = 89
        public const val FIELD_RESULT: Int = 56
        public const val FIELD_WIFI_AP: Int = 57
    }

    public object Record {
        public const val TYPE: Int = 18
        public const val ID_REQUEST: Int = 20
        public const val ID_STATUS: Int = 21
        public const val ID_REPORT: Int = 22
    }
}
