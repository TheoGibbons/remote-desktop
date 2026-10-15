package co.remotedesktop

import java.util.Locale

internal data class DesktopFileEntry(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

internal enum class DesktopFileSort(val label: Int, val summary: Int, private val descending: Boolean) {
    NAME_ASC(R.string.files_sort_name_asc, R.string.files_sort_name_up, false),
    NAME_DESC(R.string.files_sort_name_desc, R.string.files_sort_name_down, true),
    SIZE_DESC(R.string.files_sort_size_desc, R.string.files_sort_size_down, true),
    SIZE_ASC(R.string.files_sort_size_asc, R.string.files_sort_size_up, false),
    MODIFIED_DESC(R.string.files_sort_modified_desc, R.string.files_sort_modified_down, true),
    MODIFIED_ASC(R.string.files_sort_modified_asc, R.string.files_sort_modified_up, false);

    fun comparator(): Comparator<DesktopFileEntry> {
        val valueOrder = when (this) {
            NAME_ASC, NAME_DESC -> compareBy<DesktopFileEntry> { it.name.lowercase(Locale.ROOT) }
            SIZE_ASC, SIZE_DESC -> compareBy { it.size }
            MODIFIED_ASC, MODIFIED_DESC -> compareBy { it.mtime }
        }
        return compareByDescending<DesktopFileEntry> { it.dir }
            .then(if (descending) valueOrder.reversed() else valueOrder)
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.name }
    }
}
