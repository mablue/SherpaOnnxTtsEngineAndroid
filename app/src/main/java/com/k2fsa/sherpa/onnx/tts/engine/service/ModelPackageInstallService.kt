package com.k2fsa.sherpa.onnx.tts.engine.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.drake.net.component.Progress
import com.k2fsa.sherpa.onnx.tts.engine.NotificationConst
import com.k2fsa.sherpa.onnx.tts.engine.R
import com.k2fsa.sherpa.onnx.tts.engine.conf.AppConfig
import com.k2fsa.sherpa.onnx.tts.engine.synthesizer.ModelPackageInstaller
import com.k2fsa.sherpa.onnx.tts.engine.ui.MainActivity
import com.k2fsa.sherpa.onnx.tts.engine.utils.NotificationUtils
import com.k2fsa.sherpa.onnx.tts.engine.utils.NotificationUtils.notificationBuilder
import com.k2fsa.sherpa.onnx.tts.engine.utils.NotificationUtils.sendNotification
import com.k2fsa.sherpa.onnx.tts.engine.utils.ThrottleUtil
import com.k2fsa.sherpa.onnx.tts.engine.utils.fileName
import com.k2fsa.sherpa.onnx.tts.engine.utils.formatFileSize
import com.k2fsa.sherpa.onnx.tts.engine.utils.pendingIntentFlags
import com.k2fsa.sherpa.onnx.tts.engine.utils.startForegroundCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelPackageInstallService : Service() {
    companion object {
        const val TAG = "PackageInstallService"

        const val ACTION_NOTIFICATION_CANCEL =
            "com.k2fsa.sherpa.onnx.tts.engine.service.PackageInstallService.ACTION_NOTIFICATION_CANCEL"

        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_FILE_NAME = "file_name"
    }

    override fun onBind(intent: Intent): IBinder? = null

    private var mNotificationId = NotificationUtils.nextNotificationId()
    private val mNotificationReceiver by lazy { NotificationReceiver() }

    inner class NotificationReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_NOTIFICATION_CANCEL) {
                if (mNotificationId == intent.getIntExtra(
                        EXTRA_NOTIFICATION_ID,
                        NotificationUtils.UNSPECIFIED_ID
                    )
                ) {
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationUtils.createChannel(
                NotificationConst.MODEL_PACKAGE_INSTALLER_CHANNEL,
                getString(R.string.model_package_installer),
            )
        }

        ContextCompat.registerReceiver(
            this,
            mNotificationReceiver,
            IntentFilter(ACTION_NOTIFICATION_CANCEL),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        mNotificationId = NotificationUtils.UNSPECIFIED_ID
        stopForeground(true)
        mScope.cancel()
        unregisterReceiver(mNotificationReceiver)

        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun createNotification(
        summary: String,
        progress: Int,
        title: String,
        content: String
    ): Notification {
        Log.d(TAG, "createNotification: $progress, $title, $content")
        return notificationBuilder(NotificationConst.MODEL_PACKAGE_INSTALLER_CHANNEL).apply {
            setContentTitle(title)
            setContentText(content)
            setSmallIcon(R.mipmap.ic_launcher)
//            setVisibility(Notification.VISIBILITY_PUBLIC)
            style = Notification.BigTextStyle().setSummaryText(summary).setBigContentTitle(title)
                .bigText(content)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            setProgress(100, progress, progress == -1)

            val cancelPending = PendingIntent.getBroadcast(
                /* context = */ this@ModelPackageInstallService,
                /* requestCode = */ 0,
                /* intent = */ Intent(ACTION_NOTIFICATION_CANCEL).apply {
                    putExtra(EXTRA_NOTIFICATION_ID, mNotificationId)
                },
                /* flags = */ pendingIntentFlags
            )
            addAction(
                Notification.Action.Builder(
                    0,
                    getString(android.R.string.cancel),
                    cancelPending
                ).build()
            )
            setContentIntent(
                PendingIntent.getActivity(
                    this@ModelPackageInstallService,
                    0,
                    Intent(this@ModelPackageInstallService, MainActivity::class.java),
                    pendingIntentFlags
                )
            )
        }.build()
    }

    private val mScope = CoroutineScope(Dispatchers.IO)

    private val timeoutThrottle = ThrottleUtil(mScope, time = 1000L * 15) //15s
    private fun setTimeout() {
        timeoutThrottle.runAction {
            sendNotification(
                channelId = NotificationConst.MODEL_PACKAGE_INSTALLER_CHANNEL,
                title = getString(R.string.model_install_failed),
                content = getString(R.string.timed_out)
            )
            stopSelf()
        }
    }

    private var mLastUpdateNotification = 0L
    private fun updateNotification(summary: String, progress: Int, title: String, content: String) {
        setTimeout()
        if (mNotificationId != NotificationUtils.UNSPECIFIED_ID) {
            if (SystemClock.elapsedRealtime() - mLastUpdateNotification < 500) return

            Log.d(TAG, "startForegroundCompat: $progress, $title, $content")
            startForegroundCompat(
                mNotificationId,
                createNotification(summary, progress, title, content)
            )
            mLastUpdateNotification = SystemClock.elapsedRealtime()
        }
    }

    // [1MB / 25MB] 100 KB/s
    private fun Progress.toNotificationContent(): String =
        "[${currentSize()} / ${totalSize()}] \t ${speedSize()}/s"

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val mUri = intent.data?.toString() ?: run {
            Log.e(TAG, "onStartCommand: uri is null")
            stopSelf()
            return super.onStartCommand(intent, flags, startId)
        }
        val mFileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: kotlin.run {
            Log.d(TAG, "onStartCommand: fileName is null, from local file install")
            ""
        }
        Log.i(TAG, "onStartCommand: uri=$mUri, fileName=$mFileName")

        updateNotification(
            "",
            0,
            getString(if (mFileName.isEmpty()) R.string.model_package_installer else R.string.downloading),
            mFileName
        )

        mScope.launch {
            runCatching {
                execute(mUri, mFileName)
            }.onFailure {
                if (it is CancellationException) return@onFailure

                Log.e(TAG, "onStartCommand: execute failed", it)
                sendNotification(
                    channelId = NotificationConst.MODEL_PACKAGE_INSTALLER_CHANNEL,
                    title = getString(R.string.model_install_failed),
                    content = it.message ?: getString(R.string.error)
                )
            }
            stopSelf()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private suspend fun execute(uriString: String, fileName: String) {
        Log.d(TAG, "execute: $uriString, $fileName")

        fun updateUnzipProgress(file: String, total: Long, current: Long) {
            val name = file.substringAfterLast('/')
            val str =
                "${current.formatFileSize(this)} / ${total.formatFileSize(this)}"
            updateNotification(
                getString(R.string.unzipping),
                progress = ((current / total.toDouble()) * 100).toInt(),
                title = name,
                content = str,
            )
        }

        fun updateStartMoveFiles() {
            updateNotification(
                "",
                progress = -1,
                title = getString(R.string.moving_files),
                content = "..."
            )
        }

        val ok = if (fileName.isBlank()) {
            val uri = uriString.toUri()
            val file = DocumentFile.fromSingleUri(this, uri)
            val name = file?.name ?: throw IllegalArgumentException("file is null: uri=${uri}")
            val type = name.substringAfter(".")

            val ins = contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("openInputStream return null: uri=${uri}")
            Log.d(TAG, "execute: type=$type")
            ModelPackageInstaller.installPackage(
                type,
                ins,
                onUnzipProgress = ::updateUnzipProgress,
                onStartMoveFiles = ::updateStartMoveFiles
            )
        } else {
            val url = if (AppConfig.ghProxyUrl.value.isEmpty())
                uriString
            else
                "${AppConfig.ghProxyUrl.value.removeSuffix("/")}/$uriString"
            ModelPackageInstaller.installPackageFromUrl(
                url = url,
                fileName = fileName,
                onDownloadProgress = {
                    updateNotification(
                        summary = getString(R.string.downloading),
                        progress = it.progress(),
                        title = fileName,
                        content = it.toNotificationContent()
                    )
                },
                onUnzipProgress = ::updateUnzipProgress,
                onStartMoveFiles = ::updateStartMoveFiles
            )
        }

        sendNotification(
            channelId = NotificationConst.MODEL_PACKAGE_INSTALLER_CHANNEL,
            title = getString(if (ok) R.string.model_installed else R.string.model_install_failed),
            content = fileName.ifBlank {
                try {
                    uriString.toUri().fileName(this)
                } catch (e: Exception) {
                    Log.e(TAG, "execute: unable to get file name from uri=$uriString", e)
                    ""
                }
            }
        )
    }

}