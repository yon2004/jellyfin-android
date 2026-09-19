package org.jellyfin.mobile.player.cast

import android.app.Activity
import org.jellyfin.mobile.bridge.JavascriptCallback
import org.json.JSONArray
import org.json.JSONException

interface IChromecast {
    fun initializePlugin(activity: Activity)

    @Throws(JSONException::class)
    fun execute(action: String, args: JSONArray, cbContext: JavascriptCallback): Boolean

    /**
     * Supplies the Jellyfin server base URL as soon as it is known, so the local stream
     * relay knows where to forward Chromecast requests when a Cast session starts.
     *
     * Declared here rather than on the proprietary Chromecast class so that code in the
     * main source set can call it without naming a flavour-specific type — referencing
     * the proprietary class from main breaks the libre build.
     *
     * No-op in the libre flavour, which has no Cast support.
     */
    fun setServerBaseUrl(baseUrl: String)

    fun destroy()
}
