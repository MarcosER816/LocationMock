package com.mockroute.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * English ↔ Spanish for the app's fixed labels. English text is the key: [tr] returns the Spanish text when the
 * app language is Spanish and the label is in the dictionary; anything else (including labels with numbers in
 * them) stays in English. Notification, tile and shortcut texts use Android string resources (values-es).
 */
object I18n {
    var spanish by mutableStateOf(false)
        private set

    /** [language]: "system", "en" or "es". */
    fun apply(language: String) {
        spanish = when (language) {
            "es" -> true
            "en" -> false
            else -> Locale.getDefault().language == "es"
        }
    }

    fun tr(en: String): String = if (spanish) ES[en] ?: en else en

    private val ES: Map<String, String> = mapOf(
        // Toolbar and map
        "Map" to "Mapa", "Route" to "Ruta", "Trace" to "Trazar", "Draw" to "Dibujar", "Area" to "Zona",
        "Be here" to "Estar aquí", "Walk here" to "Caminar aquí", "Selected point" to "Punto elegido", "Close" to "Cerrar",
        "Pause" to "Pausa", "Resume" to "Seguir", "Stop" to "Parar", "Skip" to "Siguiente", "OK" to "Vale",
        "Hide" to "Ocultar", "Open" to "Abrir", "Done" to "Listo", "Cancel" to "Cancelar", "Save" to "Guardar",
        "Start" to "Empezar", "Retry" to "Reintentar", "Later" to "Luego", "Delete" to "Borrar", "Edit" to "Editar",
        "Back" to "Atrás", "Automatic" to "Automático", "Remove" to "Quitar", "+ Add" to "+ Añadir",
        // Route sheet
        "Walk" to "A pie", "Car" to "Coche", "Bus" to "Autobús",
        "Undo" to "Deshacer", "Clear" to "Borrar todo", "Start at my location" to "Empezar en mi ubicación",
        "Free draw (no snapping)" to "Dibujo libre (sin ajustar a calles)", "Move A" to "Mover A",
        "A = my location" to "A = mi ubicación", "Clear B" to "Quitar B", "Speed & time" to "Velocidad y tiempo",
        "Shuffle" to "Variar", "Shuffle all" to "Variar todo", "Keep duration" to "Mantener duración",
        "Generating plan…" to "Preparando…", "Generating…" to "Preparando…", "Fix speed & time to get a plan." to "Ajusta velocidad y tiempo para poder empezar.",
        "Re-fetch" to "Actualizar", "Traffic: TomTom (historic)" to "Tráfico: TomTom (histórico)",
        // Speed & time screen
        "Speed" to "Velocidad", "Time" to "Tiempo", "Feasible ✓" to "Posible ✓", "Not feasible" to "No es posible",
        "Acceleration a_max" to "Aceleración máxima", "Slider scale:" to "Escala:", "Mean" to "Media", "Mode" to "Moda",
        "Fix it (lowering one raises the other):" to "Arréglalo (bajar uno sube el otro):",
        // Area
        "Building" to "Edificio", "Streets" to "Calles", "Shape" to "Forma", "Start point" to "Inicio", "End point" to "Final",
        "Favourite spots" to "Sitios favoritos", "Close shape" to "Cerrar forma", "Reopen" to "Reabrir",
        "How you move around" to "Cómo te mueves", "Natural sway" to "Vaivén natural", "Go spot to spot" to "De sitio en sitio",
        "Stroll around" to "Pasear", "Drift around" to "Deambular", "3D preview" to "Vista 3D", "Update streets" to "Actualizar calles",
        "Height" to "Altura", "Auto-rotate" to "Girar solo", "Where you'll wander (3D)" to "Por dónde te moverás (3D)",
        // Trip
        "Start point A" to "Punto de inicio A", "Use my location" to "Usar mi ubicación", "Move on map" to "Mover en el mapa",
        "+ Route" to "+ Ruta", "+ Building" to "+ Edificio", "+ Streets" to "+ Calles", "+ Pause" to "+ Pausa",
        "+ Route at start" to "+ Ruta al inicio", "+ Pause at start" to "+ Pausa al inicio",
        "Prepare trip" to "Preparar viaje", "Start trip" to "Empezar viaje", "Show on map" to "Ver en el mapa",
        "Start from here" to "Empezar desde aquí", "Reset to automatic" to "Volver a automático",
        "Files" to "Archivos", "Save as…" to "Guardar como…", "New" to "Nuevo", "Export JSON" to "Exportar JSON",
        "Import" to "Importar", "Export GPX" to "Exportar GPX", "Saved timelines" to "Viajes guardados",
        "Snap to edge" to "Ajustar al borde", "Add connector route" to "Añadir ruta de enlace",
        "No steps yet. Add a route, a building, some streets or a pause below." to "Aún no hay pasos. Añade abajo una ruta, un edificio, unas calles o una pausa.",
        "Changed since planning — Plan all again." to "Ha cambiado: prepara el viaje otra vez.",
        // Settings
        "Settings" to "Ajustes", "Check setup" to "Revisar configuración", "GPS noise" to "Ruido GPS", "Routing" to "Rutas",
        "Traffic (Car/Bus)" to "Tráfico (coche/autobús)", "Last real location" to "Última ubicación real", "About" to "Acerca de",
        "Debug overlay" to "Capa de depuración", "Test routing" to "Probar rutas", "Language" to "Idioma", "Units" to "Unidades",
        "Map style" to "Estilo del mapa", "Dark" to "Oscuro", "Light" to "Claro", "System" to "Sistema",
        "Metric (km/h, m)" to "Métrico (km/h, m)", "Imperial (mph, ft)" to "Imperial (mph, ft)",
        "Background test" to "Prueba en segundo plano", "Run 10-minute test" to "Probar 10 minutos",
        "Xiaomi / HyperOS helper" to "Ayuda Xiaomi / HyperOS", "Open Autostart" to "Abrir Inicio automático", "Open app info" to "Abrir info de la app",
        "Off" to "Desactivado", "Light " to "Suave", "Realistic" to "Realista", "Heavy" to "Fuerte",
        "Google Play services mock layer" to "Capa de Google Play services",
        // Wizard / onboarding
        "Set up MockRoute" to "Configurar MockRoute", "Skip for now" to "Saltar por ahora", "Continue to map" to "Ir al mapa",
        "All set — open the map" to "Todo listo: abrir el mapa", "I understand" to "Entendido", "Before you simulate" to "Antes de simular",
        "Next" to "Siguiente", "Get started" to "Empezar",
        "Pretend to be somewhere else" to "Finge estar en otro sitio",
        "MockRoute makes your phone report a location you choose: a point, a route along real streets, a walk inside a building or around a neighbourhood." to
            "MockRoute hace que el móvil informe de la ubicación que tú elijas: un punto, una ruta por calles reales, un paseo dentro de un edificio o por un barrio.",
        "It moves like a person" to "Se mueve como una persona",
        "Speeds change with traffic and the time of day, walkers wait at crossings, and the GPS signal jitters like the real thing." to
            "La velocidad cambia con el tráfico y la hora, los peatones esperan en los cruces y la señal GPS tiembla como la real.",
        "Chain a whole day" to "Encadena todo un día",
        "Build a trip from steps (route, building, streets, pause). Each step starts where the last one ended. Other apps can detect mock locations: use it for testing and privacy only." to
            "Crea un viaje por pasos (ruta, edificio, calles, pausa). Cada paso empieza donde acabó el anterior. Otras apps pueden detectar ubicaciones simuladas: úsalo solo para pruebas y privacidad.",
        "Offline: showing saved map and routes only" to "Sin conexión: solo mapa y rutas guardados",
        "was interrupted at" to "se interrumpió al",
        "Resume trip" to "Continuar viaje", "Dismiss" to "Descartar",
    )
}
