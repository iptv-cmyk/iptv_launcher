package tech.vvs.vvs_launcher

data class EpgItem(
    val startMillis: Long,
    val endMillis: Long,
    val title: String
) {
    val durationMinutes: Int get() = ((endMillis - startMillis) / 60000L).toInt()
}
