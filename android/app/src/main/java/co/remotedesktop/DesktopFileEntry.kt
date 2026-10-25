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

    fun comparator(foldersFirst: Boolean): Comparator<DesktopFileEntry> {
        val valueOrder = when (this) {
            NAME_ASC, NAME_DESC -> compareBy<DesktopFileEntry> { it.name.lowercase(Locale.ROOT) }
            // Folder sizes aren't calculated, so they rank below empty files.
            SIZE_ASC, SIZE_DESC -> compareBy { if (it.dir) -1L else it.size }
            MODIFIED_ASC, MODIFIED_DESC -> compareBy { it.mtime }
        }
        val ordered = if (descending) valueOrder.reversed() else valueOrder
        return (if (foldersFirst) compareByDescending<DesktopFileEntry> { it.dir }.then(ordered) else ordered)
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.name }
    }
}
