package io.github.siklyde.nostalre

import android.content.Context
import android.content.SharedPreferences

/**
 * The servers' settings the hub offers, each declared once: its name and what it does, its choices and default, where
 * the pick is saved (SharedPreferences [Setting.store] under [Setting.key], as the app has always saved them) and
 * what it writes into the servers' files ([ServerConf.battleFile], [ServerConf.helpersFile]). [categories] are the
 * settings screen's server tabs, in order: a new setting is one entry in one of them.
 */
object ServerSettings {
	sealed class Setting<T : Any>(
		val store: String,
		val key: String,
		val label: String,
		val help: String,
		val default: T,
	) {
		/** The saved pick, or [default] when there is none or it is not one of the choices. */
		abstract fun read( saved: Map<String, *> ): T

		protected abstract fun write( editor: SharedPreferences.Editor, value: T )

		/** "key: value" lines for conf/import/battle_conf.txt, rAthena's values. */
		protected open fun battle( value: T ): List<Pair<String, Int>> = emptyList()

		/** NPC scripts (files under npc/custom) to drop: a delnpc line each in npc/nostalre_off.conf. */
		protected open fun npcsOff( value: T ): List<String> = emptyList()

		fun save( editor: SharedPreferences.Editor, values: Values ) = write( editor, values[this] )

		fun battleLines( values: Values ) = battle( values[this] )

		fun npcsOffFor( values: Values ) = npcsOff( values[this] )
	}

	/** One of [steps], shown as [format] gives it. */
	class Step(
		store: String, key: String, label: String, help: String,
		val steps: List<Int>, default: Int, val format: ( Int ) -> String,
		private val conf: ( Int ) -> List<Pair<String, Int>>,
	) : Setting<Int>( store, key, label, help, default ) {
		override fun read( saved: Map<String, *> ) = ( saved[key] as? Int )?.takeIf { it in steps } ?: default

		override fun write( editor: SharedPreferences.Editor, value: Int ) {
			editor.putInt( key, value )
		}

		override fun battle( value: Int ) = conf( value )
	}

	/** On or off. */
	open class Toggle(
		store: String, key: String, label: String, help: String, default: Boolean,
		private val conf: ( Boolean ) -> List<Pair<String, Int>> = { emptyList() },
	) : Setting<Boolean>( store, key, label, help, default ) {
		override fun read( saved: Map<String, *> ) = saved[key] as? Boolean ?: default

		override fun write( editor: SharedPreferences.Editor, value: Boolean ) {
			editor.putBoolean( key, value )
		}

		override fun battle( value: Boolean ) = conf( value )
	}

	/** A custom NPC the server's npc/scripts_custom.conf loads, on until turned off: [file] under npc/custom. */
	class Npc( val file: String, label: String, help: String ) : Toggle( "helpers", file, label, help, true ) {
		override fun npcsOff( value: Boolean ) = if( value ) emptyList() else listOf( file )
	}

	/** A pick for every setting. */
	class Values internal constructor( private val picks: Map<Setting<*>, Any> ) {
		@Suppress( "UNCHECKED_CAST" )
		operator fun <T : Any> get( setting: Setting<T> ): T = picks.getValue( setting ) as T

		fun <T : Any> with( setting: Setting<T>, value: T ) = Values( picks + ( setting to value ) )

		/** [settings] back to their defaults. */
		fun reset( settings: List<Setting<*>> ) = Values( picks + settings.associateWith { it.default } )

		fun differs( other: Values, settings: List<Setting<*>> ) = settings.any { picks[it] != other.picks[it] }

		fun atDefaults( settings: List<Setting<*>> ) = settings.all { picks[it] == it.default }

		override fun equals( other: Any? ) = other is Values && other.picks == picks

		override fun hashCode() = picks.hashCode()
	}

	/** A tab of the settings screen: [about] shown when it opens. */
	class Category( val title: String, val about: String, val settings: List<Setting<*>> )

	val RATE_STEPS = listOf( 1, 2, 3, 5, 10, 25, 50, 100, 250, 500, 1000 )
	val MAX_AI_STEPS = listOf( 50, 100, 200, 300, 500 )
	val DENSITY_STEPS = listOf( 50, 100, 150, 200, 300 )
	val MONSTER_STEPS = listOf( 50, 100, 150, 200, 300 )
	val RESPAWN_STEPS = listOf( 1, 2, 3, 5, 10 )

	// conf/battle/drops.conf: every item rate but the cards', and the cards'
	val DROP_KEYS = listOf(
		"item_rate_common", "item_rate_common_boss", "item_rate_common_mvp",
		"item_rate_heal", "item_rate_heal_boss", "item_rate_heal_mvp",
		"item_rate_use", "item_rate_use_boss", "item_rate_use_mvp",
		"item_rate_equip", "item_rate_equip_boss", "item_rate_equip_mvp",
		"item_rate_mvp", "item_rate_adddrop", "item_group_rate", "item_rate_treasure",
	)
	val CARD_KEYS = listOf( "item_rate_card", "item_rate_card_boss", "item_rate_card_mvp" )

	private fun times( value: Int ) = "${value}x"
	private fun percent( value: Int ) = "$value%"
	private fun count( value: Int ) = "$value"
	private fun yesNo( on: Boolean ) = if( on ) 1 else 0

	// rAthena's rates are percents, 100 for 1x
	val BASE_EXP = Step( "rates", "base_exp", "Base EXP", "Base experience from monsters (MVPs too), times the official rate.",
		RATE_STEPS, 1, ::times ) { listOf( "base_exp_rate" to it * 100, "mvp_exp_rate" to it * 100 ) }
	val JOB_EXP = Step( "rates", "job_exp", "Job EXP", "Job experience from monsters, times the official rate.",
		RATE_STEPS, 1, ::times ) { listOf( "job_exp_rate" to it * 100 ) }
	val QUEST_EXP = Step( "rates", "quest_exp", "Quest EXP", "Base and job experience that NPCs give for quests, times the official rate.",
		RATE_STEPS, 1, ::times ) { listOf( "quest_exp_rate" to it * 100 ) }
	val ITEM_DROPS = Step( "rates", "drop", "Item drops", "How often monsters drop items (all but cards). Each drop tops out at 100%.",
		RATE_STEPS, 1, ::times ) { rate -> DROP_KEYS.map { it to rate * 100 } }
	val CARD_DROPS = Step( "rates", "card", "Card drops", "How often monsters drop their cards.",
		RATE_STEPS, 1, ::times ) { rate -> CARD_KEYS.map { it to rate * 100 } }

	val MONSTERS = Step( "world", "monsters", "Monsters", "How many monsters each map has, against the official count.",
		MONSTER_STEPS, 100, ::percent ) { listOf( "mob_count_rate" to it ) }
	// a respawn delay in percent: 2x as fast is half the delay
	val RESPAWN = Step( "world", "respawn", "Respawn speed", "How fast killed monsters come back, times the official speed. Not MVPs or plants.",
		RESPAWN_STEPS, 1, ::times ) { listOf( "mob_spawn_delay" to 100 / it ) }

	// the Population Engine's shells
	val AI = Toggle( "world", "ai", "AI players", "Other adventurers who hunt, chat and sell around you. Only on the map you are on.",
		true ) { listOf( "population_engine_enable" to yesNo( it ) ) }
	val MAX_AI = Step( "world", "max_shells", "Max AI players", "The most AI players at once, in the whole world. Fewer is lighter on the phone.",
		MAX_AI_STEPS, 200, ::count ) { listOf( "population_engine_max_count" to it ) }
	val DENSITY = Step( "world", "density", "How busy", "How many AI players a map gets, against its usual crowd.",
		DENSITY_STEPS, 100, ::percent ) { listOf( "population_engine_density_pct" to it ) }

	val SKIP_TELEPORT_MENU = Toggle( "skills", "skip_teleport_lv1_menu", "Quick Teleport Lv 1",
		"Teleport Lv 1 takes you somewhere random on the map at once, without asking Random or Cancel first.",
		false ) { listOf( "skip_teleport_lv1_menu" to yesNo( it ) ) }

	val NPCS = listOf(
		Npc( "jobmaster", "Job Master", "Job Master, in Prontera: changes your job once you reach the levels, no quest." ),
		Npc( "warper", "Warper", "Warper, in every town: takes you to towns, fields and dungeons." ),
		Npc( "healer", "Healer", "Healer, in every town: fills HP and SP for free." ),
		Npc( "stylist", "Stylist", "Stylist, in Prontera: hair style, hair colour and clothes colour." ),
		Npc( "resetnpc", "Reset Girl", "Reset Girl, in Prontera: gives back your stat or skill points, 5,000 zeny each." ),
	)

	val categories = listOf(
		Category( "Rates", "EXP and drops, as multiples of the official rates.",
			listOf( BASE_EXP, JOB_EXP, QUEST_EXP, ITEM_DROPS, CARD_DROPS ) ),
		Category( "Monsters", "How many monsters the maps have, and how fast they come back.", listOf( MONSTERS, RESPAWN ) ),
		Category( "AI Players", "Other adventurers in the world, run by the server.", listOf( AI, MAX_AI, DENSITY ) ),
		Category( "Skills", "Skills as the official game has them, or quicker to use.", listOf( SKIP_TELEPORT_MENU ) ),
		Category( "NPCs", "The helper NPCs in town, each on or off.", NPCS ),
	)

	val all: List<Setting<*>> = categories.flatMap { it.settings }

	/** Every setting's pick from what each [Setting.store] saved (as SharedPreferences.getAll gives it). */
	fun read( saved: Map<String, Map<String, *>> ) = Values( all.associateWith { it.read( saved[it.store].orEmpty() ) } )

	val defaults = read( emptyMap() )

	private fun prefs( context: Context, store: String ) = context.getSharedPreferences( store, Context.MODE_PRIVATE )

	fun load( context: Context ) = read( all.map { it.store }.distinct().associateWith { prefs( context, it ).all } )

	fun save( context: Context, values: Values ) = all.groupBy { it.store }.forEach { ( store, settings ) ->
		prefs( context, store ).edit().apply { settings.forEach { it.save( this, values ) } }.apply()
	}
}
