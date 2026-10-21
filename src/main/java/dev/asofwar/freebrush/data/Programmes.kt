package dev.asofwar.freebrush.data

import android.content.Context
import dev.asofwar.freebrush.ble.BrushControls.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A brushing programme: numbered steps of a motor mode and level for some seconds.
 * [prompt] marks programmes whose steps are separated by the brush's step prompt (almost all of them).
 */
data class Programme(
    val num: Int,
    val name: String,
    val steps: List<Step>,
    val description: String = "",
    val prompt: Boolean = true,
) {
    val seconds: Int get() = steps.sumOf { it.seconds }
}

/** Motor modes as the X Pro Digital numbers them: one byte per step selects mode and level together. */
enum class Motor(val title: String, val first: Int, val levels: Int) {
    CLEAN("Чистка", 1, 32),
    WHITEN("Отбеливание", 33, 4),
    MASSAGE("Массаж", 37, 4),
    SENSITIVE("Деликатный", 41, 1),
    ;

    fun gear(level: Int): Int = first + level.coerceIn(1, levels) - 1

    companion object {
        fun of(gear: Int): Motor = entries.lastOrNull { gear >= it.first } ?: CLEAN
        fun level(gear: Int): Int = gear - of(gear).first + 1
    }
}

/**
 * Programmes of the Oclean X Pro Digital. Steps are Oclean's own, as published for this model in the official
 * app's online catalogue; the names are ours. Programmes 1 and 2 are the brush's built-in day and evening modes,
 * which the official app does not list.
 */
object Programmes {
    private fun p(num: Int, name: String, description: String, vararg steps: Pair<Int, Int>, prompt: Boolean = true) =
        Programme(num, name, steps.map { Step(it.first, it.second) }, description, prompt)

    val BUILT_IN = listOf(
        p(0, "Стандартная", "Четыре зоны по 30 секунд на мягкой мощности", 2 to 30, 2 to 30, 2 to 30, 2 to 30, prompt = false),
        p(1, "Эксклюзивный", "Дневной режим щётки: 4 минуты чистки на уровне 18", 18 to 240),
        p(2, "Закат", "Вечерний режим щётки: 3 минуты массажа", 40 to 180),
    )

    val CATALOGUE = listOf(
        p(72, "Сильная чистка", "Для пародонтоза", 16 to 30, 16 to 30, 16 to 30, 16 to 30, 24 to 30, 37 to 30),
        p(73, "Очень сильная чистка", "Для глубокой очистки", 24 to 30, 24 to 30, 24 to 30, 24 to 30, 32 to 30, 38 to 30),
        p(74, "После профчистки", "Мягко, для чувствительных после чистки у стоматолога зубов", 8 to 30, 8 to 30, 8 to 30, 8 to 30, 37 to 30),
        p(75, "Отбеливание", "Стандартное отбеливание", 34 to 30, 34 to 30, 16 to 30, 16 to 30, 24 to 30),
        p(76, "Сильное отбеливание", "Для любителей кофе и вина", 35 to 30, 35 to 30, 24 to 30, 24 to 30, 32 to 30),
        p(77, "Максимальное отбеливание", "Для глубокого отбеливания", 36 to 30, 36 to 30, 32 to 30, 32 to 30, 32 to 30),
        p(78, "Деликатная", "Для чувствительных зубов", 41 to 30, 41 to 30, 41 to 30, 41 to 30, 41 to 30, 37 to 30),
        p(79, "Нежный СПА", "Для чувствительных дёсен", 33 to 30, 33 to 30, 8 to 30, 8 to 30, 37 to 30, 37 to 30),
        p(80, "СПА", "Отбеливание, чистка и массаж дёсен", 34 to 30, 34 to 30, 16 to 30, 16 to 30, 38 to 30, 38 to 30),
        p(81, "Глубокий СПА", "Для чувствительных зубов и дёсен", 35 to 30, 35 to 30, 24 to 30, 24 to 30, 39 to 30, 39 to 30),
        p(82, "После сладкого", "Средняя мощность, затем глубже", 12 to 30, 12 to 30, 18 to 30, 18 to 30),
        p(83, "После еды", "Очень мягко: эмаль после кислой еды размягчена", 41 to 30, 41 to 30, 41 to 30, 41 to 30, 1 to 30),
        p(84, "Массаж дёсен", "Только массаж, без чистки", 38 to 30, 38 to 30, 38 to 30, 38 to 30),
        p(85, "Уход за дёснами", "Мягкая чистка и массаж", 8 to 30, 8 to 30, 8 to 30, 8 to 30, 8 to 30, 37 to 30, 37 to 20),
        p(86, "Отбеливание для начинающих", "Мягкая мощность и лёгкое отбеливание", 33 to 30, 33 to 30, 6 to 30, 6 to 30, 6 to 30),
        p(87, "Брекеты", "Мягко и дольше", 8 to 30, 8 to 30, 8 to 30, 8 to 30, 8 to 30, 8 to 30),
        p(88, "Быстрая", "80 секунд на высокой мощности", 30 to 20, 30 to 20, 30 to 20, 30 to 20),
        p(89, "В дорогу", "Отбеливание и стандартная чистка", 35 to 30, 35 to 30, 17 to 30, 17 to 30),
    )

    fun find(num: Int, custom: List<Programme>): Programme? =
        (BUILT_IN + CATALOGUE + custom).firstOrNull { it.num == num }
}

/** The user's own programmes, kept on the phone. Numbers start at 231 like the official app's custom ones. */
class CustomProgrammes(context: Context) {
    private val prefs = context.getSharedPreferences("programmes", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<Programme>> = _items

    private fun read(): List<Programme> {
        val json = prefs.getString("custom", null) ?: return emptyList()
        val arr = JSONArray(json)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val steps = o.getJSONArray("steps")
            Programme(
                num = o.getInt("num"),
                name = o.getString("name"),
                steps = List(steps.length()) { s -> steps.getJSONObject(s).let { Step(it.getInt("g"), it.getInt("s")) } },
            )
        }
    }

    private fun write(list: List<Programme>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("num", p.num)
                put("name", p.name)
                put("steps", JSONArray().apply { p.steps.forEach { put(JSONObject().put("g", it.gear).put("s", it.seconds)) } })
            })
        }
        prefs.edit().putString("custom", arr.toString()).apply()
        _items.value = list
    }

    /** A free programme number for a new programme, or null when all 25 custom numbers (231..255) are taken. */
    fun nextNum(): Int? = (231..255).firstOrNull { n -> _items.value.none { it.num == n } }

    fun save(p: Programme) {
        write(_items.value.filter { it.num != p.num } + p)
    }

    fun delete(num: Int) {
        write(_items.value.filter { it.num != num })
    }
}
