package org.abimon.eternalJukebox.handlers.api

import com.auth0.jwt.JWT
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.Response
import io.vertx.ext.web.Cookie
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import org.abimon.eternalJukebox.*
import org.abimon.eternalJukebox.objects.*
import org.abimon.visi.io.ByteArrayDataSource
import org.abimon.visi.security.sha512Hash

object ProfileAPI : IAPI {
    override val mountPath: String = "/profile"
    override val name: String = "Profile"

    val googleClient: String?
        get() = EternalJukebox.config.googleClient
    val googleSecret: String?
        get() = EternalJukebox.config.googleSecret
    val redirectURI: String
        get() = "${EternalJukebox.config.baseDomain}/api/profile/google_callback"

    val oauthDocument: GoogleOAuthDiscoveryDocumentResponse = run {
        val (_, response) = Fuel.get("https://accounts.google.com/.well-known/openid-configuration").response()

        return@run EternalJukebox.jsonMapper.tryReadValue(response.data, GoogleOAuthDiscoveryDocumentResponse::class) ?: error("Google is offline; this is not good")
    }

    override fun setup(router: Router) {
        router.route().handler(this::checkInvalidAuth)
        router.get("/google").handler(this::googleProfile)
        router.get("/me").handler(this::profile)

        router.put("/stars/:id").handler(this::addStar)
        router.delete("/stars/:id").handler(this::deleteStar)

        router.get("/google_callback").handler(this::googleCallback)
    }

    fun checkInvalidAuth(context: RoutingContext) {
        val cookie = context.getCookie(ConstantValues.AUTH_COOKIE_NAME) ?: return context.next()
        if (EternalJukebox.database.provideAccountForEternalAuth(cookie.value, context.clientInfo) == null)
            context.removeCookie(ConstantValues.AUTH_COOKIE_NAME)

        context.next()
    }

    fun googleProfile(context: RoutingContext) {
        val clientInfo = context.clientInfo
        val account = clientInfo.authToken?.let { auth -> EternalJukebox.database.provideAccountForEternalAuth(auth, clientInfo) } ?: return context.fail(401)

        val (_, response) = Fuel.get(oauthDocument.userinfo_endpoint).oauthRequest(account)
        val person = EternalJukebox.jsonMapper.tryReadValue(response.data, GooglePerson::class) ?: return context.fail(403)

        context.endWithStatusCode(200) { this["displayName"] = person.name }
    }

    fun profile(context: RoutingContext) {
        val clientInfo = context.clientInfo
        val account = clientInfo.authToken?.let { auth -> EternalJukebox.database.provideAccountForEternalAuth(auth, clientInfo) } ?: return context.fail(401)
        if(!EternalJukebox.storage.shouldStore(EnumStorageType.PROFILE)) {
            return context.endWithStatusCode(501) {
                this["error"] = "Configured storage method does not support storing profiles"
                this["client_uid"] = context.clientInfo.userUID
            }
        }

        if(EternalJukebox.storage.isStored("${account.eternalID}.json", EnumStorageType.PROFILE))
            EternalJukebox.storage.provide("${account.eternalID}.json", EnumStorageType.PROFILE, context, clientInfo)
        else
            context.response().putHeader("Content-Type", "application/json").putHeader("X-Client-UID", clientInfo.userUID).end("{}")
    }

    fun stars(context: RoutingContext) {
        val clientInfo = context.clientInfo
        val account = clientInfo.authToken?.let { auth -> EternalJukebox.database.provideAccountForEternalAuth(auth, clientInfo) } ?: return context.fail(401)
        val profile = profileForID(account.eternalID, clientInfo)
        context.response().putHeader("Content-Type", "application/json").putHeader("X-Client-UID", clientInfo.userUID).end(EternalJukebox.jsonMapper.writeValueAsString(profile?.stars ?: emptySet<String>()))
    }

    fun addStar(context: RoutingContext) {
        val clientInfo = context.clientInfo
        val account = clientInfo.authToken?.let { auth -> EternalJukebox.database.provideAccountForEternalAuth(auth, clientInfo) } ?: return context.fail(401)
        if(!EternalJukebox.storage.shouldStore(EnumStorageType.PROFILE)) {
            return context.endWithStatusCode(501) {
                this["error"] = "Configured storage method does not support storing profiles"
                this["client_uid"] = context.clientInfo.userUID
            }
        }

        val profile: EternalUserProfile = profileForID(account.eternalID, clientInfo) ?: EternalUserProfile(HashSet())
        profile.stars.add(context.pathParam("id"))

        EternalJukebox.storage.store(
                "${account.eternalID}.json",
                EnumStorageType.PROFILE,
                ByteArrayDataSource(EternalJukebox.jsonMapper.writeValueAsBytes(profile)),
                "application/json",
                clientInfo
        )

        context.response().setStatusCode(204).end()
    }

    fun deleteStar(context: RoutingContext) {
        val clientInfo = context.clientInfo
        val account = clientInfo.authToken?.let { auth -> EternalJukebox.database.provideAccountForEternalAuth(auth, clientInfo) } ?: return context.fail(401)
        if(!EternalJukebox.storage.shouldStore(EnumStorageType.PROFILE)) {
            return context.endWithStatusCode(501) {
                    this["error"] = "Configured storage method does not support storing profiles"
                    this["client_uid"] = context.clientInfo.userUID
            }
        }

        val profile: EternalUserProfile = profileForID(account.eternalID, clientInfo) ?: EternalUserProfile(HashSet())
        profile.stars.remove(context.pathParam("id"))

        EternalJukebox.storage.store(
                "${account.eternalID}.json",
                EnumStorageType.PROFILE,
                ByteArrayDataSource(EternalJukebox.jsonMapper.writeValueAsBytes(profile)),
                "application/json",
                clientInfo
        )

        context.response().setStatusCode(204).end()
    }

    fun googleCallback(context: RoutingContext) {
        val params = context.request().params()
        val code = params["code"] ?: return context.fail(400)
        val clientInfo = context.clientInfo
        val path = params["state"]?.let { state -> EternalJukebox.database.retrieveOAuthState(state, clientInfo) }
                ?: return context.fail(401)

        val (_, response) = Fuel.post("https://www.googleapis.com/oauth2/v4/token", listOf(
                "code" to code,
                "client_id" to googleClient,
                "client_secret" to googleSecret,
                "redirect_uri" to redirectURI,
                "grant_type" to "authorization_code"
        )).response()

        if (response.statusCode in 200..299) {
            val token = EternalJukebox.jsonMapper.tryReadValue(response.data, GoogleTokenOAuthResponse::class)
                    ?: return context.endWithStatusCode(500) {
                        this["error"] = "Invalid Google response"

                        log("[${clientInfo.userUID}] Invalid Google Response ${String(response.data)}")
                    }

            val jwtToken = JWT.decode(token.id_token)
            val account = EternalJukebox.database.provideAccountForGoogleID(jwtToken.subject, clientInfo)
                    ?: JukeboxAccount(
                            EternalJukebox.snowstorm.get(),
                            jwtToken.subject,
                            token.access_token,
                            token.refresh_token,
                            ByteArray(8192).apply { EternalJukebox.secureRandom.nextBytes(this) }.sha512Hash()
                    )

            account.googleAccessToken = token.access_token
            account.googleRefreshToken = token.refresh_token

            EternalJukebox.database.storeAccount(account, clientInfo)

            context.addCookie(Cookie.cookie(ConstantValues.AUTH_COOKIE_NAME, account.eternalAccessToken).setPath("/"))
            context.response().redirect(path)
        } else {
            context.endWithStatusCode(400) {
                this["error"] = "Invalid code"

                log("[${clientInfo.userUID}] Invalid Code: ${String(response.data)}")
            }
        }
    }

    fun profileForID(eternalID: String, clientInfo: ClientInfo?): EternalUserProfile? {
        if(EternalJukebox.storage.isStored("$eternalID.json", EnumStorageType.PROFILE))
            return EternalJukebox.storage.provide("$eternalID.json", EnumStorageType.PROFILE, clientInfo)?.use { stream -> EternalJukebox.jsonMapper.tryReadValue(stream, EternalUserProfile::class) }
        return null
    }

    fun Request.oauthRequest(account: JukeboxAccount): Pair<Request, Response> {
        val (requestA, responseA) = header("Authorization" to "Bearer ${account.googleAccessToken}").response()

        if(responseA.statusCode == 401) {
            log("Refreshing ${account.eternalID}/${account.googleID}'s account")

            val (_, responseRefresh) = Fuel.post("https://www.googleapis.com/oauth2/v4/token", listOf(
                    "refresh_token" to account.googleRefreshToken,
                    "client_id" to googleClient,
                    "client_secret" to googleSecret,
                    "grant_type" to "refresh_token"
            )).response()

            if(responseRefresh.statusCode in 200..299) {
                val token = EternalJukebox.jsonMapper.tryReadValue(responseRefresh.data, GoogleTokenOAuthResponse::class) ?: return requestA to responseA
                account.googleAccessToken = token.access_token

                EternalJukebox.database.storeAccount(account, null)

                val (requestB, responseB) = header("Authorization" to "Bearer ${account.googleAccessToken}").response()
                return requestB to responseB
            }
        }

        return requestA to responseA
    }
}