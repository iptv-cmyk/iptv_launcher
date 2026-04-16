package tech.vvs.vvs_launcher

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * ViewModel responsible for loading and exposing a list of TV channels.
 *
 * The channel list is downloaded from a user‑provided URL pointing to a `.u38` file.
 * The file is expected to contain one entry per line. A line must contain a
 * multicast URI (e.g. "udp://225.0.0.1:9001"). Any text before the URI is
 * considered the channel name. Lines starting with `#` or blank lines are ignored.
 * If no channel name is found, a generic name (e.g. "Channel 1") is assigned.
 */
class ChannelViewModel(application: Application) : AndroidViewModel(application) {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    /**
     * List of available channels. Observed by the UI to display the channel list.
     */
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _selectedChannel = MutableStateFlow<Channel?>(null)
    /**
     * Currently selected channel. When this changes the UI should instruct the player
     * to switch streams.
     */
    val selectedChannel: StateFlow<Channel?> = _selectedChannel.asStateFlow()

    /**
     * Downloads and parses a channel list from the specified URL. The parsing is
     * lenient and will try to extract a channel name and URI from each line.
     *
     * @param url The URL pointing to the `.u38` channel list file.
     */
    fun fetchChannelList(url: String) {
        android.util.Log.d("VVS_TV_LOG", "Fetching channel list from: $url")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = downloadFile(url)
                val parsed = parseU38(content)
                _channels.value = parsed
                // Auto‑select the first channel if available <- REMOVED per user request
                // _selectedChannel.value = parsed.firstOrNull()
            } catch (e: Exception) {
                android.util.Log.e("VVS_TV_LOG", "Error fetching channel list", e)
                // In case of error, leave the list empty. Errors should be displayed by the UI.
                _channels.value = emptyList()
                _selectedChannel.value = null
            }
        }
    }

    /**
     * Updates the currently selected channel.
     */
    fun selectChannel(channel: Channel) {
        _selectedChannel.value = channel
        Log.d("VVS_TV_LOG", "Selected channel: ${channel.name} (${channel.uri})")
    }

    fun clearSelection() {
        _selectedChannel.value = null
        Log.d("VVS_TV_LOG", "Cleared selected channel")
    }

    /**
     * Downloads the content of a remote file and returns it as a [String].
     */
    @Throws(Exception::class)
    private fun downloadFile(fileUrl: String): String {
        val url = URL(fileUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        connection.requestMethod = "GET"
        connection.doInput = true
        connection.connect()
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            val sb = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                sb.append(line).append('\n')
                line = reader.readLine()
            }
            return sb.toString()
        }
    }

    /**
     * Parses the contents of a `.u38` file into a list of [Channel] objects.
     */
    private fun parseU38(content: String): List<Channel> {
        val lines = content.lines()
        val list = mutableListOf<Channel>()
        var index = 1
        var pendingName: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Handle M3U style: lines starting with #EXTINF contain the name.
            // The name is taken from after the first comma.  We do not parse
            // attributes like tvg-id or group-title here because the channel
            // name we display should be the value after the comma.
            if (trimmed.startsWith("#")) {
                val tag = "#EXTINF"
                if (trimmed.startsWith(tag, ignoreCase = true)) {
                    val commaIndex = trimmed.indexOf(',')
                    if (commaIndex >= 0 && commaIndex < trimmed.length - 1) {
                        pendingName = trimmed.substring(commaIndex + 1).trim()
                    }
                }
                // Skip comment lines
                continue
            }
            // At this point, the line is not a comment.  Check if it contains a URI.
            // Look for known protocol prefixes and extract the URI and optional name.
            val protocols = listOf("udp://", "http://", "https://")
            var uriStart = -1
            var foundProto: String? = null
            for (proto in protocols) {
                val idx = trimmed.indexOf(proto, ignoreCase = true)
                if (idx >= 0 && (uriStart == -1 || idx < uriStart)) {
                    uriStart = idx
                    foundProto = proto
                }
            }
            if (uriStart != -1) {
                // Extract the URI portion from the start of the protocol to the end
                val uriPart = trimmed.substring(uriStart).trim()
                // The name (if any) is the portion before the protocol.  Trim common separators.
                val namePart = trimmed.substring(0, uriStart).trim().trim(',', ';', ':', '=')
                val name = when {
                    namePart.isNotEmpty() -> namePart
                    !pendingName.isNullOrEmpty() -> pendingName!!
                    else -> "Channel $index"
                }
                list.add(Channel(name, uriPart, index))
                index++
                // Clear pending name after consuming it
                pendingName = null
            }
        }
        return list
    }
}
