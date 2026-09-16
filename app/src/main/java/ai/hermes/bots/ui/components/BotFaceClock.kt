package ai.hermes.bots.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one shared 15 fps face clock (UI-SPEC.md §3.2): a process-wide object owning a SINGLE
 * animation loop. While at least one face is observing, it emits a quantized monotonic tick
 * every 66 ms (1000/15 ≈ 66 ms, the spec's quantization budget) on Dispatchers.Main; with
 * zero observers the loop stops, so off-screen faces cost nothing and no face ever runs a
 * per-row ticker. Faces collect [tick] (via [rememberTick]) and pose from it ([isBlinking]).
 *
 * AUTHORIZED GLOBAL-STATE EXCEPTION: the app convention forbids global mutable state outside
 * DataStore-backed repos; this object is the explicitly authorized render-only exception.
 * It holds only an observer count, one loop Job, and the tick flow — no user data, no
 * configuration, nothing but pixels. Main-thread confined (composition enter/leave is the
 * only acquire/release path); [isBlinking] is pure and Android-free for unit testing
 * (see BotFaceClockTest).
 */
object BotFaceClock {

  /** One tick per 15 fps frame: 1000 ms / 15 ≈ 66 ms. */
  const val INTERVAL_MS: Long = 66L

  // §3.2 blink windows: working `t % 1.45s > 1.26s`; idle `t % 3.2s > 3.02s`.
  private const val WORKING_PERIOD_MS: Long = 1450L
  private const val WORKING_ONSET_MS: Long = 1260L
  private const val IDLE_PERIOD_MS: Long = 3200L
  private const val IDLE_ONSET_MS: Long = 3020L

  private val _tick = MutableStateFlow(System.nanoTime() / 1_000_000L)

  /** Quantized monotonic milliseconds; advances every [INTERVAL_MS] while observers > 0. */
  val tick: StateFlow<Long> = _tick

  private var observers = 0
  private var loop: Job? = null
  private val scope: CoroutineScope by lazy {
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  }

  /**
   * True when the face should render its eyes closed at monotonic time [tMs]. Pure, deterministic,
   * and side-effect free (no clock reads, no Android imports): the same tick always yields the
   * same answer. Working faces blink on the short 1.45 s window, idle faces on the lazy 3.2 s
   * one (~0.4 s blend feel per the spec).
   */
  fun isBlinking(tMs: Long, working: Boolean): Boolean =
    if (working) tMs % WORKING_PERIOD_MS > WORKING_ONSET_MS
    else tMs % IDLE_PERIOD_MS > IDLE_ONSET_MS

  /** One more face is composed: start the loop if this is the first observer. */
  fun acquire() {
    observers += 1
    if (loop == null) {
      loop = scope.launch {
        while (isActive) {
          delay(INTERVAL_MS)
          _tick.value = System.nanoTime() / 1_000_000L
        }
      }
    }
  }

  /** One face left composition: stop the loop when the last observer is gone. */
  fun release() {
    observers = (observers - 1).coerceAtLeast(0)
    if (observers == 0) {
      loop?.cancel()
      loop = null
    }
  }

  /**
   * The composable face-clock hook: collects [tick] as compose state and owns one clock
   * reference for this face's lifetime — acquires on enter, releases on leave, so the loop
   * runs only while at least one face is composed.
   */
  @Composable
  fun rememberTick(): State<Long> {
    val state = tick.collectAsState()
    DisposableEffect(Unit) {
      acquire()
      onDispose { release() }
    }
    return state
  }
}
