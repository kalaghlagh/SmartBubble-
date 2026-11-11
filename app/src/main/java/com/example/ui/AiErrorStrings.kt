package com.example.ui

import androidx.annotation.StringRes
import com.example.R
import com.example.ai.providers.AiErrorKind

/** Localized message for each AI error category (shared by Chat errors and Settings statuses). */
@StringRes
fun aiErrorStringRes(kind: AiErrorKind): Int = when (kind) {
    AiErrorKind.NO_DEFAULT_PROVIDER -> R.string.ai_err_no_default_provider
    AiErrorKind.MISSING_API_KEY -> R.string.ai_err_missing_key
    AiErrorKind.CREDENTIAL_UNAVAILABLE -> R.string.ai_err_credential_unavailable
    AiErrorKind.INVALID_API_KEY -> R.string.ai_err_invalid_key
    AiErrorKind.PERMISSION_DENIED -> R.string.ai_err_permission
    AiErrorKind.MODEL_NOT_FOUND -> R.string.ai_err_model_not_found
    AiErrorKind.RATE_LIMITED -> R.string.ai_err_rate_limited
    AiErrorKind.QUOTA_OR_BILLING -> R.string.ai_err_quota
    AiErrorKind.BAD_REQUEST -> R.string.ai_err_bad_request
    AiErrorKind.REQUEST_TOO_LARGE -> R.string.ai_err_too_large
    AiErrorKind.PROVIDER_UNAVAILABLE -> R.string.ai_err_provider_unavailable
    AiErrorKind.NETWORK_UNAVAILABLE -> R.string.ai_err_network
    AiErrorKind.TIMEOUT -> R.string.ai_err_timeout
    AiErrorKind.EMPTY_RESPONSE -> R.string.ai_err_empty
    AiErrorKind.MALFORMED_RESPONSE -> R.string.ai_err_malformed
    AiErrorKind.TRUNCATED_RESPONSE -> R.string.ai_err_truncated
    AiErrorKind.CONTENT_BLOCKED -> R.string.ai_err_blocked
    AiErrorKind.INVALID_CONFIGURATION -> R.string.ai_err_configuration
    AiErrorKind.UNKNOWN -> R.string.ai_err_unknown
}
