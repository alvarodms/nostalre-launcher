package io.github.siklyde.nostalre

import android.content.Context
import android.content.SharedPreferences

/**
 * The servers' settings the hub offers, each declared once: its name and what it does, its choices and default, where
 * the pick is saved (SharedPreferences [Setting.store] under [Setting.key], as the app has always saved them) and
 * what it writes into the servers' files ([ServerConf.battleFile], [ServerConf.npcFile]). [categories] are the
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

		/** Lines for npc/nostalre_off.conf: delnpc for a script the server loads, npc for one it does not. */
		protected open fun npc( value: T ): List<String> = emptyList()

		fun save( editor: SharedPreferences.Editor, values: Values ) = write( editor, values[this] )

		fun battleLines( values: Values ) = battle( values[this] )

		fun npcLines( values: Values ) = npc( values[this] )
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

	/**
	 * A custom NPC, [file] under npc/custom. On by [default]: one npc/scripts_custom.conf loads, dropped when turned
	 * off; else one it leaves out, loaded when turned on.
	 */
	class Npc( val file: String, label: String, help: String, default: Boolean = true ) : Toggle( "helpers", file, label, help, default ) {
		override fun npc( value: Boolean ) = when {
			value == default -> emptyList()
			value -> listOf( "npc: npc/custom/$file.txt" )
			else -> listOf( "delnpc: npc/custom/$file.txt" )
		}
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
	val SHOP_COUNT_STEPS = listOf( 50, 100, 150, 200, 300 )
	/** Towns with shops, by tier: 0 Prontera, 1 the main towns, 2 the small towns too. */
	val SHOP_TOWN_STEPS = listOf( 0, 1, 2 )
	val SHOP_PRICE_STEPS = listOf( 50, 75, 100, 150, 200 )
	/** Minutes a shop stays put, on average; 0 never moves. */
	val SHOP_MOVE_STEPS = listOf( 0, 10, 30, 60 )
	// no 10x: the heal timer runs every 500 ms (NATURAL_HEAL_INTERVAL), so faster steps come out uneven
	val REGEN_STEPS = listOf( 1, 2, 3, 5 )
	/** Death penalty, rAthena's way: 100 is 1% of the EXP. */
	val DEATH_PENALTY_STEPS = listOf( 0, 25, 50, 100, 200 )
	/** Pickup area, cells around the item picked up: up to 14, the server's max (about the whole screen). */
	val PICKUP_AREA_STEPS = listOf( 0, 1, 2, 3, 5, 7, 14 )

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
	private fun square( value: Int ) = if( value == 0 ) "Off" else "${value * 2 + 1}×${value * 2 + 1}"
	private fun shopTowns( value: Int ) = listOf( "Prontera", "Main towns", "All towns" )[value]
	private fun shopMove( value: Int ) = if( value == 0 ) "Never" else if( value < 60 ) "~$value min" else "~${value / 60} h"
	private fun penalty( value: Int ) = if( value == 0 ) "Off" else "${( value / 100.0 ).toString().removeSuffix( ".0" )}%"

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
	val MULTI_LEVEL_UP = Toggle( "rates", "multi_level_up", "Multi level-up",
		"One kill can give several levels at once. Off, the official way: one level at most, the rest of the EXP lost.",
		false ) { listOf( "multi_level_up" to yesNo( it ) ) }

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
	val AI_CHAT = Toggle( "world", "ai_chat", "AI chat", "AI players say lines out loud now and then.",
		true ) { listOf( "population_engine_chat_enable" to yesNo( it ) ) }
	val AI_REPLIES = Toggle( "world", "ai_replies", "AI replies", "AI players whisper back when you say their name.",
		true ) { listOf( "population_engine_chat_reply_enable" to yesNo( it ) ) }
	val AI_SKILLS = Toggle( "world", "ai_skills", "Smart skills",
		"AI players pick the skill that suits the moment (the monster's element, a crowd, their SP) and fight their own way. Off: they cycle through their skills in turn.",
		true ) { listOf( "population_engine_scored_skills" to yesNo( it ) ) }
	val AI_PARTIES = Toggle( "world", "ai_parties", "AI parties",
		"On leveling maps some AI players gather as a party: a leader at a camp, a priest who heals and buffs, killers who hunt around it and come back, on some maps a Bard and Dancer singing. Off: every AI player hunts alone.",
		true ) { listOf( "population_engine_parties" to yesNo( it ) ) }
	val AI_ROUTINE = Toggle( "world", "ai_routine", "Daily routine",
		"AI players in town follow the phone's clock: out and about by day, chatting in the evening (18:00), sitting at night (22:00 to 6:00).",
		false ) { listOf( "population_engine_daily_cycle" to yesNo( it ) ) }

	// the AI players' shops (vendor shells): db/population_vendors.yml says where and what, these how much
	val AI_SHOPS = Toggle( "world", "ai_shops", "AI shops",
		"AI merchants set up shops in town, selling monster drops, potions and gear, that you can buy from.",
		true ) { listOf( "population_engine_vending_enable" to yesNo( it ) ) }
	val SHOPS_PER_TOWN = Step( "world", "shops_per_town", "Shops per town",
		"How many shops a town gets, against its usual count (Prontera has the most). Apart from How busy.",
		SHOP_COUNT_STEPS, 100, ::percent ) { listOf( "population_engine_vendor_pct" to it ) }
	val SHOP_TOWNS = Step( "world", "shop_towns", "Shops in",
		"Which towns have AI shops: Prontera alone, the main towns, or the small ones too.",
		SHOP_TOWN_STEPS, 1, ::shopTowns ) { listOf( "population_engine_vendor_towns" to it ) }
	val SHOP_PRICES = Step( "world", "shop_prices", "Shop prices",
		"What AI shops charge, against their usual prices: drops a bit under NPC prices, potions and gear at them.",
		SHOP_PRICE_STEPS, 100, ::percent ) { listOf( "population_engine_vendor_price_pct" to it ) }
	// a shift: half to one and a half times the step, so the stalls do not all move at once
	val SHOPS_MOVE = Step( "world", "shops_move", "Shops move",
		"How long a merchant keeps a spot before packing up, so the shops move around town over a long session.",
		SHOP_MOVE_STEPS, 30, ::shopMove ) {
		listOf( "population_engine_vendor_shift_min_ms" to it * 30_000, "population_engine_vendor_shift_max_ms" to it * 90_000 )
	}
	val SHOP_LOOT = Toggle( "world", "shop_loot", "Sell loot", "AI shops selling monster drops from fields and dungeons.",
		true ) { listOf( "population_engine_vendor_loot" to yesNo( it ) ) }
	val SHOP_SUPPLIES = Toggle( "world", "shop_supplies", "Sell supplies", "AI shops selling potions, buff items and arrows.",
		true ) { listOf( "population_engine_vendor_supplies" to yesNo( it ) ) }
	val SHOP_GEAR = Toggle( "world", "shop_gear", "Sell gear", "AI shops selling starter weapons and armour.",
		true ) { listOf( "population_engine_vendor_gear" to yesNo( it ) ) }
	val SHOP_CARDS = Toggle( "world", "shop_cards", "Cards in shops",
		"Dungeon loot shops also sell a card or two (never an MVP's), at a high price.",
		false ) { listOf( "population_engine_vendor_cards" to yesNo( it ) ) }

	// the heal timer's interval divided: 2x as fast is every half the time
	val REGEN = Step( "character", "regen", "HP/SP regen", "How fast HP and SP refill on their own, times the official speed.",
		REGEN_STEPS, 1, ::times ) { listOf( "natural_healhp_interval" to 6000 / it, "natural_healsp_interval" to 8000 / it ) }
	val DEATH_PENALTY = Step( "character", "death_penalty", "Death penalty",
		"The base and job EXP of your level you lose when you die. Official: 1%.",
		DEATH_PENALTY_STEPS, 100, ::penalty ) { listOf( "death_penalty_base" to it, "death_penalty_job" to it ) }

	val AUTOLOOT = Toggle( "loot", "autoloot", "Autoloot",
		"What monsters drop goes straight into your bag. Off: drops fall to the ground for you to pick up. Not for kills by a homunculus or mercenary alone.",
		true ) { listOf( "item_auto_get" to yesNo( it ) ) }
	// the server's pickup_area (nostalRE's own): cells each way from the item picked up
	val PICKUP_AREA = Step( "loot", "pickup_area", "Pickup area",
		"Picking up one item also picks up every other item in a square around it, as long as you can carry them. 29×29 is about the whole screen.",
		PICKUP_AREA_STEPS, 0, ::square ) { listOf( "pickup_area" to it ) }

	val SKIP_TELEPORT_MENU = Toggle( "skills", "skip_teleport_lv1_menu", "Quick Teleport Lv 1",
		"Teleport Lv 1 takes you somewhere random on the map at once, without asking Random or Cancel first.",
		false ) { listOf( "skip_teleport_lv1_menu" to yesNo( it ) ) }

	val NPCS = listOf(
		Npc( "jobmaster", "Job Master", "Job Master, in Prontera: changes your job once you reach the levels, no quest." ),
		Npc( "warper", "Warper", "Warper, in every town: takes you to towns, fields and dungeons." ),
		Npc( "healer", "Healer", "Healer, in every town: fills HP and SP for free." ),
		Npc( "stylist", "Stylist", "Stylist, in Prontera: hair style, hair colour and clothes colour." ),
		Npc( "resetnpc", "Reset Girl", "Reset Girl, in Prontera: gives back your stat or skill points, 5,000 zeny each." ),
		Npc( "population/arena", "Arena Manager", "Arena Manager, in Morroc: 1v1, 3v3 or 5v5 fights against AI players on a PvP map." ),
		Npc( "platinum_skills", "Platinum Skills", "Platinum Skill NPC, in Prontera: your job's quest skills at once, no quests.", false ),
		Npc( "breeder", "Mount Rental", "Universal Rental NPC, in Prontera: your job's mount, falcon, warg, cart or Mado Gear, free.", false ),
		Npc( "itemmall", "Shop Street", "A street of shops in north Prontera selling weapons, armour and more for zeny.", false ),
		Npc( "card_remover", "Card Remover", "Wise Old Woman, in a Prontera house: takes cards out of your equipment for zeny. It can fail and break the item.", false ),
		Npc( "item_signer", "Item Signer", "Perchik, in a Prontera house: writes your name on a rare item, for 3 Gift Boxes and zeny by its refine.", false ),
		Npc( "booster", "Booster Event",
			"Booster Registration, in north Prontera: a Booster Call that summons an assistant with daily quests for Booster Coins, and a Booster Equipment NPC that trades coins and tickets for gear.",
			false ),
	)

	val categories = listOf(
		Category( "Rates", "EXP and drops, as multiples of the official rates.",
			listOf( BASE_EXP, JOB_EXP, QUEST_EXP, ITEM_DROPS, CARD_DROPS, MULTI_LEVEL_UP ) ),
		Category( "Monsters", "How many monsters the maps have, and how fast they come back.", listOf( MONSTERS, RESPAWN ) ),
		Category( "AI Players", "Other adventurers in the world, run by the server.",
			listOf( AI, MAX_AI, DENSITY, AI_SKILLS, AI_PARTIES, AI_ROUTINE, AI_CHAT, AI_REPLIES ) ),
		Category( "AI Shops", "Shops the AI merchants run in town: where, how many, what they sell and for how much.",
			listOf( AI_SHOPS, SHOPS_PER_TOWN, SHOP_TOWNS, SHOP_PRICES, SHOPS_MOVE, SHOP_LOOT, SHOP_SUPPLIES, SHOP_GEAR, SHOP_CARDS ) ),
		Category( "Character", "How your character recovers, and what dying costs.", listOf( REGEN, DEATH_PENALTY ) ),
		Category( "Loot", "How drops reach your bag: straight away, or picked up from the ground.", listOf( AUTOLOOT, PICKUP_AREA ) ),
		Category( "Skills", "Skills as the official game has them, or quicker to use.", listOf( SKIP_TELEPORT_MENU ) ),
		Category( "NPCs", "The custom NPCs, each on or off: the first ones are on to start with.", NPCS ),
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
