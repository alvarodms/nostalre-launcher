package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerConfTest {
	/** "key: value" lines of a conf text, as rAthena reads them. */
	private fun parse( text: String ): Map<String, String> =
		text.lines().map { it.substringBefore( "//" ) }.filter { ':' in it }
			.associate { it.substringBefore( ':' ).trim() to it.substringAfter( ':' ).trim() }

	private val dropKeys = ServerSettings.DROP_KEYS
	private val cardKeys = ServerSettings.CARD_KEYS

	private val aiKeys = listOf(
		"population_engine_chat_enable", "population_engine_chat_reply_enable",
		"population_engine_vending_enable", "population_engine_daily_cycle",
	)

	private val defaults = ServerSettings.defaults

	private fun battle( values: ServerSettings.Values ) = parse( ServerConf.battleFile( values ) )

	@Test fun noSavedSettingsMeanShellsOnAtTwoHundredAndOfficialEverything() {
		val conf = battle( ServerSettings.read( emptyMap() ) )
		assertEquals( "1", conf["population_engine_enable"] )
		assertEquals( "200", conf["population_engine_max_count"] )
		assertEquals( "100", conf["population_engine_density_pct"] )
		assertEquals( "100", conf["mob_count_rate"] )
		assertEquals( "100", conf["mob_spawn_delay"] )
		assertEquals( "100", conf["base_exp_rate"] )
		assertEquals( "100", conf["quest_exp_rate"] )
		assertEquals( "0", conf["skip_teleport_lv1_menu"] )
	}

	@Test fun eachWorldPickGoesToItsOwnKey() {
		val conf = battle( defaults
			.with( ServerSettings.AI, false ).with( ServerSettings.MAX_AI, 500 ).with( ServerSettings.DENSITY, 50 )
			.with( ServerSettings.MONSTERS, 300 ).with( ServerSettings.RESPAWN, 10 ) )
		assertEquals( "0", conf["population_engine_enable"] )
		assertEquals( "500", conf["population_engine_max_count"] )
		assertEquals( "50", conf["population_engine_density_pct"] )
		assertEquals( "300", conf["mob_count_rate"] )
		assertEquals( "10", conf["mob_spawn_delay"] )
	}

	@Test fun respawnSpeedIsTheDelayDivided() {
		val delays = ServerSettings.RESPAWN_STEPS.map { battle( defaults.with( ServerSettings.RESPAWN, it ) )["mob_spawn_delay"] }
		assertEquals( listOf( "100", "50", "33", "20", "10" ), delays )
	}

	@Test fun worldStepsAsPlanned() {
		assertEquals( listOf( 50, 100, 200, 300, 500 ), ServerSettings.MAX_AI_STEPS )
		assertEquals( listOf( 50, 100, 150, 200, 300 ), ServerSettings.DENSITY_STEPS )
		assertEquals( listOf( 50, 100, 150, 200, 300 ), ServerSettings.MONSTER_STEPS )
		assertEquals( listOf( 1, 2, 3, 5, 10 ), ServerSettings.RESPAWN_STEPS )
	}

	/** The ranges in the server's battle config table (src/custom/battle_config_init.inc): out of range is ignored. */
	@Test fun everyWorldStepIsInTheServersRange() {
		assertTrue( ServerSettings.MAX_AI_STEPS.all { it in 1..30000 } )
		assertTrue( ServerSettings.DENSITY_STEPS.all { it in 10..300 } )
		assertTrue( ServerSettings.MONSTER_STEPS.all { it >= 0 } )
		assertTrue( ServerSettings.RESPAWN_STEPS.all { 100 / it >= 0 } )
	}

	/** Saved as the app saved them before the settings had tabs: the same stores and keys, so old picks carry over. */
	@Test fun picksSavedBeforeTheTabsReadBack() {
		val values = ServerSettings.read( mapOf(
			"rates" to mapOf( "base_exp" to 25, "job_exp" to 50, "drop" to 3, "card" to 1 ),
			"world" to mapOf( "ai" to false, "max_shells" to 50, "density" to 300, "monsters" to 150, "respawn" to 5 ),
			"helpers" to mapOf( "warper" to false, "healer" to true ),
		) )
		assertEquals( 25, values[ServerSettings.BASE_EXP] )
		assertEquals( 50, values[ServerSettings.JOB_EXP] )
		assertEquals( 3, values[ServerSettings.ITEM_DROPS] )
		assertEquals( 1, values[ServerSettings.CARD_DROPS] )
		assertEquals( false, values[ServerSettings.AI] )
		assertEquals( 50, values[ServerSettings.MAX_AI] )
		assertEquals( 300, values[ServerSettings.DENSITY] )
		assertEquals( 150, values[ServerSettings.MONSTERS] )
		assertEquals( 5, values[ServerSettings.RESPAWN] )
		assertEquals( 1, values[ServerSettings.QUEST_EXP] )
		assertEquals( false, values[ServerSettings.SKIP_TELEPORT_MENU] )
		assertEquals( listOf( true, false, true, true, true ), ServerSettings.NPCS.take( 5 ).map { values[it] } )
	}

	@Test fun aPickOffTheStepsReadsAsDefault() {
		val values = ServerSettings.read( mapOf(
			"rates" to mapOf( "base_exp" to 7 ),
			"world" to mapOf( "max_shells" to 7, "density" to 999, "monsters" to "x", "respawn" to 4 ),
		) )
		assertEquals( defaults, values )
	}

	@Test fun officialRatesWriteOneHundredEverywhere() {
		val conf = battle( defaults )
		for( key in listOf( "base_exp_rate", "job_exp_rate", "mvp_exp_rate", "quest_exp_rate" ) + dropKeys + cardKeys )
			assertEquals( key, "100", conf[key] )
	}

	@Test fun eachRateGoesToItsOwnKeys() {
		val conf = battle( defaults
			.with( ServerSettings.BASE_EXP, 10 ).with( ServerSettings.JOB_EXP, 3 ).with( ServerSettings.QUEST_EXP, 25 )
			.with( ServerSettings.ITEM_DROPS, 5 ).with( ServerSettings.CARD_DROPS, 1000 ) )
		assertEquals( "1000", conf["base_exp_rate"] )
		assertEquals( "1000", conf["mvp_exp_rate"] )
		assertEquals( "300", conf["job_exp_rate"] )
		assertEquals( "2500", conf["quest_exp_rate"] )
		for( key in dropKeys )
			assertEquals( key, "500", conf[key] )
		for( key in cardKeys )
			assertEquals( key, "100000", conf[key] )
	}

	@Test fun quickTeleportSkipsTheLevelOneMenu() {
		assertEquals( "1", battle( defaults.with( ServerSettings.SKIP_TELEPORT_MENU, true ) )["skip_teleport_lv1_menu"] )
	}

	/** Every key written is one the server's own battle confs set (a typo would be ignored by rAthena, silently). */
	@Test fun everyKeyIsARealSetting() {
		val battle = File( "../../server/conf/battle" )
		val known = listOf( "exp.conf", "drops.conf", "population_engine.conf", "monster.conf", "skill.conf", "player.conf" )
			.flatMap { parse( File( battle, it ).readText() ).keys }.toSet()
		val written = parse( ServerConf.battleFile( defaults ) ).keys
		for( key in written )
			assertTrue( key, key in known )
	}

	/** No two settings write the same key (the later would win, silently). */
	@Test fun noTwoSettingsWriteTheSameConfKey() {
		val lines = ServerSettings.all.flatMap { it.battleLines( defaults ) }.map { it.first }
		assertEquals( lines.distinct(), lines )
		assertEquals( lines.size, parse( ServerConf.battleFile( defaults ) ).size )
	}

	@Test fun multiLevelUpIsOffUntilTurnedOn() {
		assertEquals( "0", battle( defaults )["multi_level_up"] )
		assertEquals( "1", battle( defaults.with( ServerSettings.MULTI_LEVEL_UP, true ) )["multi_level_up"] )
	}

	@Test fun eachAiPickGoesToItsOwnKey() {
		assertEquals( listOf( "1", "1", "1", "0" ), aiKeys.map { battle( defaults )[it] } )
		val conf = battle( defaults
			.with( ServerSettings.AI_CHAT, false ).with( ServerSettings.AI_REPLIES, false )
			.with( ServerSettings.AI_SHOPS, false ).with( ServerSettings.AI_ROUTINE, true ) )
		assertEquals( listOf( "0", "0", "0", "1" ), aiKeys.map { conf[it] } )
	}

	/** The heal timer ticks every 500 ms (NATURAL_HEAL_INTERVAL), the least the server takes. */
	@Test fun regenSpeedDividesTheHealIntervals() {
		val official = battle( defaults )
		assertEquals( "6000", official["natural_healhp_interval"] )
		assertEquals( "8000", official["natural_healsp_interval"] )
		val twice = battle( defaults.with( ServerSettings.REGEN, 2 ) )
		assertEquals( "3000", twice["natural_healhp_interval"] )
		assertEquals( "4000", twice["natural_healsp_interval"] )
		for( step in ServerSettings.REGEN_STEPS ) {
			val conf = battle( defaults.with( ServerSettings.REGEN, step ) )
			assertTrue( conf["natural_healhp_interval"]!!.toInt() >= 500 )
			assertTrue( conf["natural_healsp_interval"]!!.toInt() >= 500 )
		}
	}

	@Test fun deathPenaltyGoesToBaseAndJob() {
		assertEquals( "100", battle( defaults )["death_penalty_base"] )
		val conf = battle( defaults.with( ServerSettings.DEATH_PENALTY, 0 ) )
		assertEquals( "0", conf["death_penalty_base"] )
		assertEquals( "0", conf["death_penalty_job"] )
		assertEquals( listOf( "Off", "0.25%", "0.5%", "1%", "2%" ), ServerSettings.DEATH_PENALTY_STEPS.map( ServerSettings.DEATH_PENALTY.format ) )
	}

	/** Each setting saves under its own key of its store: none overwrites another's pick. */
	@Test fun noTwoSettingsShareAKey() {
		val keys = ServerSettings.all.map { it.store to it.key }
		assertEquals( keys.distinct(), keys )
	}

	@Test fun rateStepsGoFromOneToAThousand() {
		assertEquals( listOf( 1, 2, 3, 5, 10, 25, 50, 100, 250, 500, 1000 ), ServerSettings.RATE_STEPS )
	}

	@Test fun everyStepDefaultIsOneOfItsSteps() {
		for( setting in ServerSettings.all.filterIsInstance<ServerSettings.Step>() )
			assertTrue( setting.key, setting.default in setting.steps )
	}

	private fun npcLines( values: ServerSettings.Values ) =
		ServerConf.npcFile( values ).lines().filter { it.isNotBlank() && !it.startsWith( "//" ) }

	@Test fun npcsAsTheyStartWriteNothing() {
		assertEquals( emptyList<String>(), npcLines( defaults ) )
	}

	@Test fun eachNpcOffBecomesADelnpcLine() {
		val ( jobMaster, _, healer ) = ServerSettings.NPCS
		val lines = npcLines( defaults.with( jobMaster, false ).with( healer, false ) )
		assertEquals( listOf( "delnpc: npc/custom/jobmaster.txt", "delnpc: npc/custom/healer.txt" ), lines )
	}

	@Test fun anNpcTheServerLeavesOutIsLoadedWhenTurnedOn() {
		val platinum = ServerSettings.NPCS.first { it.file == "platinum_skills" }
		assertEquals( false, defaults[platinum] )
		assertEquals( listOf( "npc: npc/custom/platinum_skills.txt" ), npcLines( defaults.with( platinum, true ) ) )
	}

	/** Each helper is one the server loads, and the off file comes after them (delnpc only drops a file already listed). */
	@Test fun theServerLoadsEveryHelperBeforeTheOffFile() {
		val conf = File( "../../server/npc/scripts_custom.conf" ).readLines().map { it.substringBefore( "//" ).trim() }
		val offAt = conf.indexOf( "import: npc/nostalre_off.conf" )
		assertTrue( "scripts_custom.conf imports npc/nostalre_off.conf", offAt >= 0 )
		for( npc in ServerSettings.NPCS ) {
			assertTrue( npc.file, File( "../../server/npc/custom/${npc.file}.txt" ).isFile )
			val at = conf.indexOf( "npc: npc/custom/${npc.file}.txt" )
			// on to start with: one the server loads; off: one it leaves out, else turning it on would load it twice
			if( npc.default )
				assertTrue( npc.file, at in 0 until offAt )
			else
				assertEquals( npc.file, -1, at )
		}
	}
}
