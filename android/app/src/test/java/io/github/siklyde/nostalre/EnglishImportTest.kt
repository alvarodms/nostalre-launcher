package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnglishImportTest {
	@get:Rule val tmp = TemporaryFolder()

	/** The host's luac 5.1 (scripts/build-luac.sh host), or ENGLISH_LUAC; the tests that compile skip without it. */
	private val luac = File( System.getenv( "ENGLISH_LUAC" ) ?: "../../out/host/bin/luac" )

	@Test fun luaTablesReadAsTheScriptReadsThem() {
		val lua = """
			-- a comment = { not = "read" }
			SKILL_INFO_LIST = {
				[SKID.NV_BASIC] = { "NV_BASIC"; SkillName = "Basic \"Skill\"\n", MaxLv = 9, SpAmount = { 1, -2 }, bSeperateLv = true },
				[501] = { identifiedDescriptionName = {}, other = false },
				["quoted"] = 3,
			}
		""".trimIndent()
		val table = EnglishImport.luaTable( lua, "SKILL_INFO_LIST" ) as Map<*, *>
		assertEquals( listOf( "NV_BASIC", 501L, "quoted" ), table.keys.toList() )
		val basic = table["NV_BASIC"] as Map<*, *>
		assertEquals( "Basic \"Skill\"\n", basic["SkillName"] )
		assertEquals( listOf( 1L, -2L ), basic["SpAmount"] )
		assertEquals( true, basic["bSeperateLv"] )
		assertEquals( emptyList<Any>(), ( table[501L] as Map<*, *> )["identifiedDescriptionName"] )
	}

	@Test fun copiesTakeTextTexturesAndTheWorldMapFromEachFolderInOrder() {
		val listing = listOf(
			"Translation/Renewal/data/msgstringtable.txt",
			"Translation/Renewal/data/sub/notkept.txt",
			"Translation/Renewal/data/texture/b.bmp",
			"Translation/Renewal/data/texture/a.bmp",
			"Translation/Renewal/data/texture/model.rsm",
			"Translation/Renewal/data/luafiles514/lua files/worldviewdata/worldviewdata_language.lub",
			"Translation/Renewal/data/luafiles514/lua files/datainfo/other.lub",
			"Translation/Compatibility/2017-12-13/data/texture/old.bmp",
			"Translation/Compatibility/2017-12-13/Renewal/data/texture/a.bmp",
			"Translation/Compatibility/2023-01-01/data/texture/later.bmp",
		)
		assertEquals( listOf(
			"Translation/Renewal/data/luafiles514/lua files/worldviewdata/worldviewdata_language.lub",
			"Translation/Renewal/data/msgstringtable.txt",
			"Translation/Renewal/data/texture/a.bmp",
			"Translation/Renewal/data/texture/b.bmp",
			"Translation/Compatibility/2017-12-13/Renewal/data/texture/a.bmp",
		), EnglishImport.copies( listing ).map { it.first } )
	}

	@Test fun anItemTableThatDoesNotCompileWritesNothing() {
		assumeTrue( luac.canExecute() )
		val source = tmp.newFolder( "source" )
		File( source, "Translation/Renewal/SystemEN/LuaFiles514/itemInfo.lua" ).apply {
			parentFile.mkdirs()
			writeText( "tbl = {" )
		}
		val target = File( tmp.root, "data" )
		try {
			EnglishImport.build( listOf( "Translation/Renewal/data/msgstringtable.txt" ), source, tmp.newFolder( "db" ), target, luac )
			fail( "built from a table that does not compile" )
		} catch( e: java.io.IOException ) {
			assertTrue( e.message, e.message!!.startsWith( "luac could not compile itemInfo.lua" ) )
		}
		assertFalse( target.exists() )
	}

	@Test fun aTranslationTheTablesCannotBeMadeFromWritesNothing() {
		val source = tmp.newFolder( "source" )
		fun file( path: String, text: String ) = File( source, path ).apply {
			parentFile.mkdirs()
			writeText( text )
		}
		file( "Translation/Renewal/data/msgstringtable.txt", "Hello#\n" )
		file( "Translation/Pre-Renewal/data/lua files/skillinfoz/skilldescript.lub", "SKILL_DESCRIPT = {" )
		file( "Translation/Renewal/data/luafiles514/lua files/skillinfoz/skillinfolist.lub", "SKILL_INFO_LIST = {}" )
		val target = File( tmp.root, "data" )
		val listing = source.walkTopDown().filter { it.isFile }.map { it.relativeTo( source ).invariantSeparatorsPath }.toList()
		try {
			EnglishImport.build( listing, source, tmp.newFolder( "db" ), target, luac )
			fail( "built from a table that does not close" )
		} catch( e: IllegalArgumentException ) {
			assertEquals( "table not closed", e.message )
		}
		assertFalse( target.exists() )
	}

	/**
	 * The whole import against scripts/import-english.py's output for the same translation, both with the host's
	 * luac 5.1: ENGLISH_CHECKOUT, a ROenglishRE checkout (any commit) with Translation/Compatibility on
	 * disk, and ENGLISH_EXPECTED, the game folder the script wrote into (luac5.1 on its PATH). Skipped without them.
	 */
	@Test fun matchesThePcScript() {
		val checkout = System.getenv( "ENGLISH_CHECKOUT" )?.let( ::File )
		val expected = System.getenv( "ENGLISH_EXPECTED" )?.let { File( it, "data" ) }
		assumeTrue( checkout != null && expected != null && luac.canExecute() )
		val rathenaDb = File( System.getenv( "ENGLISH_RATHENA_DB" ) ?: "../../server/db/re" )
		val listing = File( checkout, "Translation" ).walkTopDown().filter { it.isFile }
			.map { it.relativeTo( checkout!! ).invariantSeparatorsPath }.toList()
		val target = tmp.newFolder( "data" )

		EnglishImport.build( listing, checkout!!, rathenaDb, target, luac )

		fun files( dir: File ) = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo( dir ).invariantSeparatorsPath }.toSortedSet()
		assertTrue( "the script wrote no iteminfo_en.lub: no luac5.1 on its PATH",
			"luafiles514/lua files/datainfo/iteminfo_en.lub" in files( expected!! ) )
		assertEquals( files( expected ), files( target ) )
		val different = files( expected ).filter { !File( expected, it ).readBytes().contentEquals( File( target, it ).readBytes() ) }
		assertEquals( emptyList<String>(), different )
	}
}
