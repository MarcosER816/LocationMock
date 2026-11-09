package com.mockroute.app.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mockroute.app.MainActivity
import com.mockroute.app.R
import com.mockroute.app.sim.SimError
import com.mockroute.app.sim.SimStatus
import com.mockroute.app.sim.SimulationRepository
import com.mockroute.app.sim.SimulationState
import com.mockroute.app.sim.TrajectorySpec
import com.mockroute.core.Units
import com.mockroute.core.trajectory.KinematicState
import com.mockroute.core.trajectory.Trajectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service (type "location") that plays a [Trajectory] into the mock providers at 1 Hz.
 * Only ever started from a visible UI action via [start]. Pause keeps re-sending the current point
 * (at rest) so consumers don't see a stale fix; Stop removes all test providers.
 */
class MockLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var injector: MockInjector? = null

    private var spec: TrajectorySpec? = null
    private var trajectory: Trajectory? = null
    /** Simulation time accumulated before the current run segment (seconds). */
    private var accumulatedSeconds = 0.0
    /** elapsedRealtime when the current run segment started; null while paused. */
    private var runningSinceMs: Long? = null
    private var lastState: KinematicState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val newSpec = TrajectorySpec.readFrom(intent)
                if (newSpec == null) {
                    stopSimulation()
                } else if (goForeground(newSpec)) {
                    startSimulation(newSpec, intent.getBooleanExtra(EXTRA_USE_GMS, true))
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopSimulation()
            else -> stopSimulation() // null intent = system restart; never resume on our own
        }
        return START_NOT_STICKY
    }

    /** Returns false (and stops) if Android refuses the foreground start, e.g. missing location permission. */
    private fun goForeground(spec: TrajectorySpec): Boolean {
        createChannel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(spec, null, SimStatus.Running), type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground refused", e)
            fail(SimError.Other("Android refused to start the location service (${e.javaClass.simpleName})"))
            false
        }
    }

    private fun startSimulation(newSpec: TrajectorySpec, useGms: Boolean) {
        tickJob?.cancel()
        spec = newSpec
        trajectory = newSpec.build()
        accumulatedSeconds = 0.0
        runningSinceMs = SystemClock.elapsedRealtime()
        tickJob = scope.launch {
            if (injector == null) {
                val inj = MockInjector(this@MockLocationService, useGms)
                try {
                    inj.start()
                    injector = inj
                } catch (e: SecurityException) {
                    Log.w(TAG, "Not selected as mock location app", e)
                    fail(SimError.NotMockApp)
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Could not register test providers", e)
                    fail(SimError.Other(e.message ?: e.javaClass.simpleName))
                    return@launch
                }
            }
            tickLoop()
        }
    }

    private suspend fun CoroutineScope.tickLoop() {
        while (isActive) {
            val traj = trajectory ?: return
            val t = simTimeSeconds()
            val state = if (runningSinceMs == null) {
                (lastState ?: traj.stateAt(t)).copy(speedMps = 0.0)
            } else {
                traj.stateAt(t)
            }
            try {
                injector?.push(state)
            } catch (e: SecurityException) {
                // Mock app selection revoked mid-run.
                fail(SimError.NotMockApp)
                return
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "push failed", e)
            }
            lastState = state
            val status = if (runningSinceMs == null) SimStatus.Paused else SimStatus.Running
            SimulationRepository.update {
                SimulationState(
                    status = status,
                    spec = spec,
                    current = state,
                    elapsedSeconds = t,
                    finished = traj.isFinished(t),
                )
            }
            notify(buildNotification(spec, state, status, traj.isFinished(t)))
            // Align to whole seconds of wall-clock to keep a steady 1 Hz.
            delay(TICK_MS - (SystemClock.elapsedRealtime() % TICK_MS))
        }
    }

    private fun simTimeSeconds(): Double {
        val since = runningSinceMs ?: return accumulatedSeconds
        return accumulatedSeconds + (SystemClock.elapsedRealtime() - since) / 1000.0
    }

    private fun pause() {
        if (trajectory == null) return stopSimulation() // stale notification action, nothing running
        if (runningSinceMs == null) return
        accumulatedSeconds = simTimeSeconds()
        runningSinceMs = null
        SimulationRepository.update { it.copy(status = SimStatus.Paused) }
        notify(buildNotification(spec, lastState, SimStatus.Paused))
    }

    private fun resume() {
        if (trajectory == null) return stopSimulation()
        if (runningSinceMs != null) return
        runningSinceMs = SystemClock.elapsedRealtime()
        SimulationRepository.update { it.copy(status = SimStatus.Running) }
        notify(buildNotification(spec, lastState, SimStatus.Running))
    }

    private fun fail(error: SimError) {
        teardown()
        SimulationRepository.reportError(error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSimulation() {
        teardown()
        SimulationRepository.update { SimulationState(error = it.error) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        tickJob?.cancel()
        tickJob = null
        injector?.stop()
        injector = null
        trajectory = null
        spec = null
        lastState = null
        runningSinceMs = null
    }

    /** Swiped away from Recents: stop cleanly so the phone isn't left with a frozen mock location. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed — stopping simulation")
        stopSimulation()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        teardown()
        StartupCleaner.clean(this, reason = "service destroyed")
        SimulationRepository.update { SimulationState(error = it.error) }
        scope.cancel()
        super.onDestroy()
    }

    // ---- notification ----------------------------------------------------------------------

    private fun notify(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    private fun buildNotification(
        spec: TrajectorySpec?,
        state: KinematicState?,
        status: SimStatus,
        finished: Boolean = false,
    ): Notification {
        val title = when {
            status == SimStatus.Paused -> "Paused"
            finished && spec is TrajectorySpec.StraightLine -> "Arrived"
            else -> spec?.label ?: "Simulating"
        }
        val speedKmh = Units.msToKmh(state?.speedMps ?: 0.0)
        val text = if (state != null) {
            String.format(Locale.US, "%.1f km/h · %.5f, %.5f", speedKmh, state.lat, state.lon)
        } else {
            "Starting…"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MockRoute · $title")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (status == SimStatus.Paused) {
            b.addAction(0, getString(R.string.notif_resume), actionIntent(ACTION_RESUME, 2))
        } else {
            b.addAction(0, getString(R.string.notif_pause), actionIntent(ACTION_PAUSE, 1))
        }
        b.addAction(0, getString(R.string.notif_stop), actionIntent(ACTION_STOP, 3))
        return b.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, MockLocationService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "simulation"
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 1000L
        private const val ACTION_START = "com.mockroute.app.START"
        private const val ACTION_PAUSE = "com.mockroute.app.PAUSE"
        private const val ACTION_RESUME = "com.mockroute.app.RESUME"
        private const val ACTION_STOP = "com.mockroute.app.STOP"
        private const val EXTRA_USE_GMS = "use_gms"

        /** Call only from a visible UI action (foreground-service-from-background rules). */
        fun start(context: Context, spec: TrajectorySpec, useGms: Boolean) {
            val intent = spec.writeTo(Intent(context, MockLocationService::class.java).setAction(ACTION_START))
                .putExtra(EXTRA_USE_GMS, useGms)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) if not started from a visible UI.
                SimulationRepository.reportError(SimError.Other("Start the simulation from the app screen"))
            }
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) {
            if (SimulationRepository.state.value.isActive) send(context, ACTION_STOP)
            else StartupCleaner.clean(context, reason = "stop while idle")
        }

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, MockLocationService::class.java).setAction(action))
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_desc) }
            nm.createNotificationChannel(channel)
        }
    }
}
