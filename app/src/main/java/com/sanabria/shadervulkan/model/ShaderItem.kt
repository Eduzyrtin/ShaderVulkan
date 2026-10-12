package com.sanabria.shadervulkan.model

/**
 * Modelo de un item de shader cargado desde assets/shaders/ o carpetas externas.
 */
data class ShaderItem(
    val id: String,                    // ID único
    val name: String,                  // Nombre amigable para la UI
    val extension: String,            // ".slangp", etc.
    val path: String,                 // URI o path local
    val notes: String = "",           // Descripción breve
    var isActive: Boolean = false,    // ¿Está seleccionado?
    val isFolder: Boolean = false     // ¿Es una carpeta?
) {
    companion object {
        const val FOLDER_UP_ID = "folder_up"
        const val EXTERNAL_ROOT_ID = "external_root"

        // Hasta T09 los nombres llevaban un emoji delante ("📦 crt-royale"); siguen así en la caché de carpetas y en el nombre
        // del preset activo guardados antes. La fuente CRT no tiene esos glifos: se quitan al leerlos.
        private val LEGACY_PREFIXES = listOf("📦 ", "📂 ", "🔙 ", "🏠 ", "📁 ", "📄 ")

        fun cleanName(name: String): String =
            LEGACY_PREFIXES.firstOrNull { name.startsWith(it) }?.let { name.removePrefix(it) } ?: name
    }
}
