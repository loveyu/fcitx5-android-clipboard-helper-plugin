package org.fcitx.fcitx5.android.plugin.clipboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.fcitx.fcitx5.android.common.ipc.IFcitxRemoteService

private const val FCITX_APP_ID = "org.fcitx.fcitx5.android"

fun Context.bindFcitxRemoteService(
    onConnected: (IFcitxRemoteService) -> Unit
): FcitxRemoteConnection {
    val connection = FcitxRemoteConnection(onConnected)
    bindService(
        Intent("$FCITX_APP_ID.IPC").apply { setPackage(FCITX_APP_ID) },
        connection,
        Context.BIND_AUTO_CREATE
    )
    return connection
}

class FcitxRemoteConnection(
    private val onConnected: (IFcitxRemoteService) -> Unit
) : ServiceConnection {

    var remoteService: IFcitxRemoteService? = null
        private set

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        IFcitxRemoteService.Stub.asInterface(service).also {
            remoteService = it
            onConnected(it)
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        remoteService = null
    }
}
