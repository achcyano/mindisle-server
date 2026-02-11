package me.hztcm.mindisle.common

object ErrorCodes {
    const val INVALID_REQUEST = 40000
    const val INVALID_PHONE = 40001
    const val PASSWORD_TOO_SHORT = 40002
    const val INVALID_SMS_CODE = 40003
    const val LOGIN_TICKET_INVALID = 40004

    const val UNAUTHORIZED = 40100
    const val INVALID_CREDENTIALS = 40101

    const val REGISTER_REQUIRED = 40401
    const val PHONE_NOT_REGISTERED = 40402

    const val PHONE_ALREADY_REGISTERED = 40901

    const val SMS_TOO_FREQUENT = 42901
    const val SMS_DAILY_LIMIT = 42902
}
