package com.druk.lmplayground.inference

import android.os.RemoteException
import android.util.Log
import com.druk.llamacpp.ILlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.jni.NativeLlamaSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the per-token generation loop on a dedicated thread.
 *
 * The native `generate(callback)` returns one token at a time. We loop
 * inside this thread until the native side stops, the client cancels,
 * or the session is destroyed. The full accumulated response from each
 * native callback is diff'd against the previously sent length so we
 * stream only the *delta* across the AIDL boundary — keeping per-token
 * IPC cost bounded by token size, not response size.
 *
 * **Lifecycle / publish race avoidance.** The Thread is created and
 * started inside `init`, then immediately blocks on [publishedLatch].
 * The publisher (currently `LlamaService.startGeneration`) MUST call
 * [publish] only after CAS-installing this worker into the session's
 * worker slot. Without that gate, a `tearDownSession` racing between
 * CAS-publish and `start()` could observe a worker whose `Thread.join()`
 * returns true immediately (Java spec: never-started thread → join
 * returns at once), free the native session, and then we'd resume
 * `nativeSession.generate()` on freed memory. The latch makes that
 * impossible: the thread is alive (blocked on the latch) until we
 * release it, so `join()` correctly waits for an exit signal.
 */
internal class GenerationWorker(
    private val sessionId: Int,
    private val nativeSession: NativeLlamaSession,
    private val cb: ILlamaGenerationCallback,
    /**
     * Called from the worker thread right before run() returns. Receives
     * both the sessionId and the worker instance so the service-side
     * cleanup can `compareAndSet` against the atomic worker slot — a
     * late-firing exit callback for an already-replaced worker must NOT
     * clobber its successor.
     */
    private val onFinished: (sessionId: Int, worker: GenerationWorker) -> Unit,
) {
    @Volatile private var cancelled = false
    private val publishedLatch = CountDownLatch(1)

    /**
     * Pre-created and started in `init`. The body blocks on
     * [publishedLatch] until the publisher releases it via [publish],
     * so the Thread is observable by `Thread.join()` from the moment
     * this worker is CAS-installed.
     */
    private val thread: Thread = Thread({
        var statusCode = 0
        try {
            // Block until the publisher has installed us in the session's
            // worker slot. Without this gate, a tearDown landing between
            // CAS-publish and start() could free the native session
            // before we run.
            try {
                if (!publishedLatch.await(PUBLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "worker $sessionId publish timeout; bailing")
                    cancelled = true
                }
            } catch (_: InterruptedException) {
                cancelled = true
            }

            if (!cancelled) {
                var sentLength = 0
                val nativeCallback = object : LlamaGenerationCallback {
                    override fun onFullResponse(response: String) {
                        if (response.length > sentLength) {
                            val delta = response.substring(sentLength)
                            sentLength = response.length
                            try {
                                cb.onResponseDelta(delta)
                            } catch (e: RemoteException) {
                                cancelled = true
                            }
                        }
                    }
                }
                while (!cancelled) {
                    // NativeLlamaSession.generate() returns 0 to keep
                    // sampling and non-zero to stop. Map the native code
                    // onto the AIDL contract:
                    //   0 → keep going
                    //   1 → natural stop (EOS, max-tokens) ⇒ statusCode 0
                    //   2 → tool calls pending ⇒ propagate as 2 so the
                    //        proxy can drain them and resume
                    //   other → error/forbidden state ⇒ propagate as-is
                    val rc = nativeSession.generate(nativeCallback)
                    if (rc == 0) continue
                    statusCode = if (rc == 1) 0 else rc
                    break
                }
            }
            if (cancelled) statusCode = -1
        } catch (t: Throwable) {
            Log.e(TAG, "generation worker $sessionId crashed", t)
            statusCode = -2
        } finally {
            try {
                cb.onGenerationFinished(statusCode)
            } catch (_: RemoteException) {
                // client dead, fine
            }
            onFinished(sessionId, this@GenerationWorker)
        }
    }, "llama-gen-$sessionId").also { it.start() }

    /**
     * Release the worker thread to start its real work. The publisher
     * MUST call this immediately after CAS-installing this worker.
     * Idempotent — safe to call multiple times.
     */
    fun publish() {
        publishedLatch.countDown()
    }

    /**
     * Signal the worker to exit at its next opportunity. Also releases
     * the publish latch so a worker that was rejected at CAS time
     * (i.e., never published) exits promptly instead of sitting on the
     * latch until the timeout.
     */
    fun cancel() {
        cancelled = true
        // Also abort any in-flight native decode (the prompt-eval phase runs
        // inside a single generate() call and otherwise wouldn't see the
        // between-token cancel flag until it returns). Safe to call from this
        // thread while the worker thread is inside generate(): it only flips an
        // atomic the abort callback polls.
        try { nativeSession.requestAbort() } catch (_: Throwable) {}
        publishedLatch.countDown()
    }

    /**
     * True until the worker thread has actually returned from its run().
     * Used by [LlamaService] to refuse to destroy the underlying native
     * session while it might still be in use.
     */
    fun isAlive(): Boolean = thread.isAlive

    /**
     * Wait for the worker thread to exit. Returns `true` if it exited
     * within [timeoutMs], `false` if it's still running.
     *
     * The default budget is large because `nativeSession.generate()` can
     * block for several seconds while processing a long prompt batch
     * before it next checks the cancel flag. Returning `false` from here
     * means the caller MUST NOT destroy the native session — it's still
     * in use, and freeing it would be a use-after-free.
     */
    fun join(timeoutMs: Long = 30_000): Boolean {
        thread.join(timeoutMs)
        return !thread.isAlive
    }

    companion object {
        private const val TAG = "GenerationWorker"
        // How long the worker thread blocks on the publish latch before
        // giving up. The publisher calls publish() within microseconds
        // of CAS-installing the worker, so anything beyond ~1 s means
        // the publisher crashed and we should let the thread exit.
        private const val PUBLISH_TIMEOUT_MS: Long = 5_000
    }
}
