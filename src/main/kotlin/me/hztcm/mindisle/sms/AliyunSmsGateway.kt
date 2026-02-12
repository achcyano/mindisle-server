package me.hztcm.mindisle.sms

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.config.SmsProviderConfig
import me.hztcm.mindisle.model.SmsPurpose
import me.hztcm.mindisle.util.generateSecureToken
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CompletionStage
import kotlin.math.max

class AliyunSmsGateway(
    private val config: SmsProviderConfig
) : SmsGateway {
    private data class SdkContext(
        val mode: SdkMode,
        val client: Any,
        val requestPackage: String
    )

    private enum class SdkMode {
        ASYNC_V2,
        TEA_CLASSIC
    }

    private val accessKeyId = requireValue(config.aliyunAccessKeyId, "ALIBABA_CLOUD_ACCESS_KEY_ID")
    private val accessKeySecret = requireValue(config.aliyunAccessKeySecret, "ALIBABA_CLOUD_ACCESS_KEY_SECRET")
    private val signName = requireValue(config.aliyunSignName, "ALIYUN_SMS_SIGN_NAME")
    private val templateCode = requireValue(config.aliyunTemplateCode, "ALIYUN_SMS_TEMPLATE_CODE")
    private val sdk = createSdkContext()
    private val runtimeOptionsClass: Class<*>? = classOrNull("com.aliyun.teautil.models.RuntimeOptions")

    override fun sendSmsCode(
        phone: String,
        purpose: SmsPurpose,
        ttlSeconds: Long,
        intervalSeconds: Long
    ): SmsSendResult {
        val outId = buildOutId(phone, purpose)
        val ttlMinutes = max(1L, ttlSeconds / 60L)
        val templateParam = """{"code":"##code##","min":"$ttlMinutes"}"""
        val request = createRequest(
            simpleClassName = "SendSmsVerifyCodeRequest",
            requiredProperties = setOf("phoneNumber", "countryCode", "signName", "templateCode", "templateParam", "outId"),
            values = mapOf(
                "phoneNumber" to phone,
                "countryCode" to config.countryCode,
                "signName" to signName,
                "templateCode" to templateCode,
                "templateParam" to templateParam,
                "outId" to outId,
                "codeType" to config.codeType,
                "codeLength" to config.codeLength,
                "validTime" to ttlSeconds,
                "interval" to intervalSeconds,
                "duplicatePolicy" to config.duplicatePolicy
            )
        )

        val response = runCatching {
            invokeClient(
                client = sdk.client,
                methodNames = listOf("sendSmsVerifyCode"),
                request = request
            )
        }.getOrElse { throwable ->
            throw providerError("Aliyun send sms request failed", throwable)
        }
        val body = requireBody(response)
        ensureApiOk(body, "Aliyun send sms failed")
        val model = child(body, "getModel")
        val modelOutId = valueAsString(model, listOf("getOutId", "outId"))
        val bizId = valueAsString(model, listOf("getBizId", "bizId"))
        return SmsSendResult(outId = modelOutId ?: outId, bizId = bizId)
    }

    override fun verifySmsCode(
        phone: String,
        purpose: SmsPurpose,
        code: String
    ): Boolean {
        val request = createRequest(
            simpleClassName = "CheckSmsVerifyCodeRequest",
            requiredProperties = setOf("phoneNumber", "countryCode", "verifyCode"),
            values = mapOf(
                "phoneNumber" to phone,
                "countryCode" to config.countryCode,
                "verifyCode" to code,
                "caseAuthPolicy" to config.caseAuthPolicy
            )
        )

        val response = runCatching {
            invokeClient(
                client = sdk.client,
                methodNames = listOf("checkSmsVerifyCode", "verifySmsCode"),
                request = request
            )
        }.getOrElse { throwable ->
            throw providerError("Aliyun verify sms request failed", throwable)
        }
        val body = requireBody(response)
        ensureApiOk(body, "Aliyun verify sms failed")
        val model = child(body, "getModel")
        val verifyResult = valueAsString(model, listOf("getVerifyResult", "verifyResult"))
        return verifyResult == "PASS"
    }

    private fun buildOutId(phone: String, purpose: SmsPurpose): String {
        return "mi-${purpose.name.lowercase()}-${phone.takeLast(4)}-${generateSecureToken(8)}"
    }

    private fun createSdkContext(): SdkContext {
        val asyncClientClass = classOrNull("com.aliyun.sdk.service.dypnsapi20170525.AsyncClient")
        if (asyncClientClass != null) {
            val client = createAsyncV2Client(asyncClientClass)
            return SdkContext(
                mode = SdkMode.ASYNC_V2,
                client = client,
                requestPackage = "com.aliyun.sdk.service.dypnsapi20170525.models"
            )
        }

        val classicClientClass = classOrNull("com.aliyun.dypnsapi20170525.Client")
        if (classicClientClass != null) {
            val client = createTeaClassicClient(classicClientClass)
            return SdkContext(
                mode = SdkMode.TEA_CLASSIC,
                client = client,
                requestPackage = "com.aliyun.dypnsapi20170525.models"
            )
        }
        throw providerError("Aliyun SDK class not found for both AsyncClient v2 and Tea classic client")
    }

    private fun createTeaClassicClient(clientClass: Class<*>): Any {
        val configClass = classOrNull("com.aliyun.teaopenapi.models.Config")
            ?: throw providerError("Aliyun SDK class not found: com.aliyun.teaopenapi.models.Config")
        val configObject = configClass.getDeclaredConstructor().newInstance()
        setRequiredProperty(configObject, "endpoint", config.aliyunEndpoint)
        setRequiredProperty(configObject, "accessKeyId", accessKeyId)
        setRequiredProperty(configObject, "accessKeySecret", accessKeySecret)

        val constructor = clientClass.constructors.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0].name == configClass.name
        } ?: throw providerError("Aliyun SDK client constructor not found")
        return constructor.newInstance(configObject)
    }

    private fun createAsyncV2Client(asyncClientClass: Class<*>): Any {
        val credentialClass = classOrNull("com.aliyun.auth.credentials.Credential")
            ?: throw providerError("Aliyun SDK class not found: com.aliyun.auth.credentials.Credential")
        val credentialBuilder = invokeStaticNoArg(credentialClass, "builder")
            ?: throw providerError("Aliyun credential builder not found")
        setRequiredProperty(credentialBuilder, "accessKeyId", accessKeyId)
        setRequiredProperty(credentialBuilder, "accessKeySecret", accessKeySecret)
        val credential = invokeNoArg(credentialBuilder, listOf("build"))
            ?: throw providerError("Aliyun credential build failed")

        val providerClass = classOrNull("com.aliyun.auth.credentials.provider.StaticCredentialProvider")
            ?: throw providerError("Aliyun SDK class not found: com.aliyun.auth.credentials.provider.StaticCredentialProvider")
        val provider = invokeStaticWithOneArg(providerClass, "create", credential)
            ?: throw providerError("Aliyun static credential provider create failed")

        val builder = invokeStaticNoArg(asyncClientClass, "builder")
            ?: throw providerError("Aliyun AsyncClient builder not found")
        setRequiredProperty(builder, "region", config.aliyunRegion)
        setRequiredProperty(builder, "credentialsProvider", provider)

        val overrideClass = classOrNull("darabonba.core.client.ClientOverrideConfiguration")
        if (overrideClass != null) {
            val overrideConfig = invokeStaticNoArg(overrideClass, "create")
            if (overrideConfig != null && setOptionalProperty(overrideConfig, "endpointOverride", config.aliyunEndpoint)) {
                setOptionalProperty(builder, "overrideConfiguration", overrideConfig)
            }
        } else {
            setOptionalProperty(builder, "endpointOverride", config.aliyunEndpoint)
            setOptionalProperty(builder, "endpoint", config.aliyunEndpoint)
        }

        return invokeNoArg(builder, listOf("build"))
            ?: throw providerError("Aliyun AsyncClient build failed")
    }

    private fun createRequest(
        simpleClassName: String,
        requiredProperties: Set<String>,
        values: Map<String, Any?>
    ): Any {
        val className = "${sdk.requestPackage}.$simpleClassName"
        val requestClass = classOrNull(className)
            ?: throw providerError("Aliyun SDK class not found: $className")
        val builderFactory = requestClass.methods.firstOrNull {
            it.name == "builder" && it.parameterCount == 0 && Modifier.isStatic(it.modifiers)
        }
        if (builderFactory != null) {
            val builder = builderFactory.invoke(null)
            values.forEach { (name, value) ->
                val applied = setOptionalProperty(builder, name, value)
                if (name in requiredProperties && !applied) {
                    throw providerError("Aliyun SDK request field bind failed: $name")
                }
            }
            return invokeNoArg(builder, listOf("build"))
                ?: throw providerError("Aliyun SDK request build failed: $simpleClassName")
        }
        val request = requestClass.getDeclaredConstructor().newInstance()
        values.forEach { (name, value) ->
            val applied = setOptionalProperty(request, name, value)
            if (name in requiredProperties && !applied) {
                throw providerError("Aliyun SDK request field bind failed: $name")
            }
        }
        return request
    }

    private fun classOrNull(className: String): Class<*>? {
        return runCatching { Class.forName(className) }.getOrNull()
    }

    private fun invokeClient(client: Any, methodNames: List<String>, request: Any): Any {
        val runtime = runtimeOptionsClass?.getDeclaredConstructor()?.newInstance()
        for (name in methodNames) {
            val oneArgMethod = client.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 1
            }
            if (oneArgMethod != null) {
                return unwrapCompletion(oneArgMethod.invoke(client, request))
            }

            val withOptionsMethod = client.javaClass.methods.firstOrNull {
                it.name == "${name}WithOptions" && it.parameterCount == 2
            }
            if (withOptionsMethod != null && runtime != null) {
                return unwrapCompletion(withOptionsMethod.invoke(client, request, runtime))
            }
        }
        throw IllegalStateException("Aliyun SDK method not found: $methodNames")
    }

    private fun unwrapCompletion(result: Any): Any {
        if (result is CompletionStage<*>) {
            return result.toCompletableFuture().join()
                ?: throw providerError("Aliyun SDK completion returned null result")
        }
        return result
    }

    private fun requireBody(response: Any): Any {
        return child(response, "getBody")
            ?: throw providerError("Aliyun SDK response body is empty")
    }

    private fun ensureApiOk(body: Any, errorPrefix: String) {
        val model = child(body, "getModel")
        val code = valueAsString(body, listOf("getCode", "code"))
            ?: valueAsString(model, listOf("getCode", "code"))
        val message = valueAsString(body, listOf("getMessage", "message"))
            ?: valueAsString(model, listOf("getMessage", "message"))
        val success = valueAsBoolean(body, listOf("getSuccess", "success"))
            ?: valueAsBoolean(model, listOf("getSuccess", "success"))
        val requestId = valueAsString(body, listOf("getRequestId", "requestId"))
            ?: valueAsString(model, listOf("getRequestId", "requestId"))
        val accessDeniedDetail = valueAsString(body, listOf("getAccessDeniedDetail", "accessDeniedDetail"))
        if (code != "OK" || success != true) {
            val details = mutableListOf(
                "code=$code",
                "message=$message",
                "success=$success"
            )
            requestId?.let { details.add("requestId=$it") }
            accessDeniedDetail?.takeIf { it.isNotBlank() }?.let { details.add("accessDeniedDetail=$it") }
            throw providerError("$errorPrefix: ${details.joinToString(", ")}")
        }
    }

    private fun valueAsString(target: Any?, getters: List<String>): String? {
        val value = valueByGetters(target, getters) ?: return null
        return value as? String ?: value.toString()
    }

    private fun valueAsBoolean(target: Any?, getters: List<String>): Boolean? {
        val value = valueByGetters(target, getters) ?: return null
        return value as? Boolean
    }

    private fun valueByGetters(target: Any?, getters: List<String>): Any? {
        if (target == null) return null
        for (getter in getters) {
            val method = target.javaClass.methods.firstOrNull {
                it.name == getter && it.parameterCount == 0
            } ?: continue
            return method.invoke(target)
        }
        return null
    }

    private fun child(target: Any?, getter: String): Any? {
        if (target == null) return null
        val candidates = listOf(getter, getter.removePrefix("get").replaceFirstChar { it.lowercase() })
        for (name in candidates) {
            val method = target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: continue
            return method.invoke(target)
        }
        return null
    }

    private fun setRequiredProperty(target: Any, property: String, value: Any?) {
        if (value == null || !setProperty(target, property, value)) {
            throw providerError("Aliyun SDK setter not found for property: $property")
        }
    }

    private fun setOptionalProperty(target: Any, property: String, value: Any?): Boolean {
        if (value == null) return false
        return setProperty(target, property, value)
    }

    private fun setProperty(target: Any, property: String, value: Any): Boolean {
        val upper = property.replaceFirstChar { it.uppercase() }
        val methodNames = listOf("set$upper", property)
        val methods = target.javaClass.methods
            .filter { it.name in methodNames && it.parameterCount == 1 }
            .sortedByDescending { methodScore(it) }
        for (method in methods) {
            val converted = convertValue(value, method.parameterTypes[0]) ?: continue
            method.invoke(target, converted)
            return true
        }
        return false
    }

    private fun methodScore(method: Method): Int {
        val type = method.parameterTypes[0]
        return when {
            type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> 3
            type == java.lang.Integer.TYPE || type == java.lang.Integer::class.java -> 2
            type == String::class.java -> 1
            else -> 0
        }
    }

    private fun invokeStaticNoArg(type: Class<*>, name: String): Any? {
        val method = type.methods.firstOrNull {
            it.name == name && it.parameterCount == 0 && Modifier.isStatic(it.modifiers)
        } ?: return null
        return method.invoke(null)
    }

    private fun invokeStaticWithOneArg(type: Class<*>, name: String, value: Any): Any? {
        val methods = type.methods.filter {
            it.name == name && it.parameterCount == 1 && Modifier.isStatic(it.modifiers)
        }.sortedByDescending { methodScore(it) }
        for (method in methods) {
            val converted = convertValue(value, method.parameterTypes[0]) ?: continue
            return method.invoke(null, converted)
        }
        return null
    }

    private fun invokeNoArg(target: Any, names: List<String>): Any? {
        for (name in names) {
            val method = target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: continue
            return method.invoke(target)
        }
        return null
    }

    private fun convertValue(value: Any, targetType: Class<*>): Any? {
        return when {
            targetType.isAssignableFrom(value.javaClass) -> value
            targetType == java.lang.Long.TYPE || targetType == java.lang.Long::class.java ->
                (value as? Number)?.toLong()
            targetType == java.lang.Integer.TYPE || targetType == java.lang.Integer::class.java ->
                (value as? Number)?.toInt()
            targetType == java.lang.Boolean.TYPE || targetType == java.lang.Boolean::class.java ->
                value as? Boolean
            targetType == String::class.java -> value.toString()
            else -> null
        }
    }

    private fun providerError(message: String, cause: Throwable? = null): AppException {
        return AppException(
            code = ErrorCodes.SMS_PROVIDER_ERROR,
            message = message + (cause?.message?.let { ": $it" } ?: ""),
            status = HttpStatusCode.InternalServerError
        )
    }

    private fun requireValue(value: String?, key: String): String {
        if (value.isNullOrBlank()) {
            throw providerError("Missing required sms provider config: $key")
        }
        return value
    }
}
