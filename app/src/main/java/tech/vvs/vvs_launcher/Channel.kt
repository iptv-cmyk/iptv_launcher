package tech.vvs.vvs_launcher

/**
 * Data class representing a TV channel entry.
 *
 * @param name Human‑readable name of the channel, as provided in the channel list.
 * @param uri Network URI of the stream (e.g. udp://225.0.0.1:9001).
 * @param number Optional channel number, if available in the list.
 */
data class Channel(
    val name: String,
    val uri: String,
    val number: Int? = null
)