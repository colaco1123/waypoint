package com.shay.waypoint.sync

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

object Auth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/spreadsheets")

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    /** Token without UI, or null if the user must sign in interactively. */
    suspend fun silentToken(ctx: Context): String? {
        val r = Identity.getAuthorizationClient(ctx).authorize(request()).await()
        return if (r.hasResolution()) null else r.accessToken
    }
}
