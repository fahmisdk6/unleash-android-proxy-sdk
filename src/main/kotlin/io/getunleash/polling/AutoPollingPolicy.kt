package io.getunleash.polling

import io.getunleash.UnleashConfig
import io.getunleash.UnleashContext
import io.getunleash.cache.ToggleCache
import io.getunleash.data.Toggle
import org.slf4j.LoggerFactory
import java.util.Timer
import java9.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timer

class AutoPollingPolicy(
    override val unleashFetcher: UnleashFetcher,
    override val cache: ToggleCache,
    override val config: UnleashConfig,
    override var context: UnleashContext,
    val autoPollingConfig: AutoPollingMode,
) :
    RefreshPolicy(
        unleashFetcher = unleashFetcher,
        cache = cache,
        logger = LoggerFactory.getLogger("io.getunleash.polling.AutoPollingPolicy"),
        config = config,
        context = context
    ) {
    private val initialized = AtomicBoolean(false)
    private val initFuture = CompletableFuture<Unit>()
    private var timer: Timer? = null
    init {
        autoPollingConfig.togglesUpdatedListener.let { listeners.add(it) }
        autoPollingConfig.erroredListener.let { errorListeners.add(it) }
        if (autoPollingConfig.pollImmediate) {
            timer =
                timer(
                    name = "unleash_toggles_fetcher",
                    initialDelay = 0L,
                    daemon = true,
                    period = autoPollingConfig.pollRateDuration
                ) {
                    updateToggles()
                    if (!initialized.getAndSet(true)) {
                        initFuture.complete(null)
                    }
                }
        }
    }


    override fun getConfigurationAsync(): CompletableFuture<Map<String, Toggle>> {
        return if (this.initFuture.isDone) {
            CompletableFuture.completedFuture(super.readToggleCache())
        } else {
            this.initFuture.thenApplyAsync { super.readToggleCache() }
        }
    }

    override fun startPolling() {
        this.timer?.cancel()
        this.timer =  timer(
            name = "unleash_toggles_fetcher",
            initialDelay = 0L,
            daemon = true,
            period = autoPollingConfig.pollRateDuration
        ) {
            updateToggles()
            if (!initialized.getAndSet(true)) {
                initFuture.complete(null)
            }
        }
    }

    override fun isPolling(): Boolean {
        return this.timer != null
    }

    private fun updateToggles() {
        try {
            val response = super.fetcher().getTogglesAsync(context).get()
            val cached = super.readToggleCache()
            if (response.isFetched() && cached != response.toggles) {
                super.writeToggleCache(response.toggles)
                this.broadcastTogglesUpdated()
            } else if (response.isFailed()) {
                response?.error?.let(::broadcastTogglesErrored)
            }
        } catch (e: Exception) {
            this.broadcastTogglesErrored(e)
            logger.warn("Exception in AutoPollingCachePolicy", e)
        }
    }

    override fun close() {
        super.close()
        this.timer?.cancel()
        this.listeners.clear()
        this.timer = null
    }
}
