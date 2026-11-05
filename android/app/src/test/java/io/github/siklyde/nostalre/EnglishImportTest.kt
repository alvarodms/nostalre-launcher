package io.github.siklyde.nostalre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnglishImportTest {
	@get:Rule val tmp = TemporaryFolder()

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

	/**
	 * The whole import against scripts/import-english.py's output for the same translation: ENGLISH_CHECKOUT, a
	 * ROenglishRE checkout at [EnglishText.COMMIT] with Translation/Compatibility on disk, and ENGLISH_EXPECTED, the
	 * game folder the script wrote into. Skipped without them.
	 */
	@Test fun matchesThePcScript() {
		val checkout = System.getenv( "ENGLISH_CHECKOUT" )?.let( ::File )
		val expected = System.getenv( "ENGLISH_EXPECTED" )?.let { File( it, "data" ) }
		assumeTrue( checkout != null && expected != null )
		val rathenaDb = File( System.getenv( "ENGLISH_RATHENA_DB" ) ?: "../../server/db/re" )
		val listing = File( checkout, "Translation" ).walkTopDown().filter { it.isFile }
			.map { it.relativeTo( checkout!! ).invariantSeparatorsPath }.toList()
		val target = tmp.newFolder( "data" )

		EnglishImport.build( listing, checkout!!, rathenaDb, target )

		// the app writes iteminfo_en.lub as Lua source, the script compiles it (or skips it without luac)
		val itemInfo = "luafiles514/lua files/datainfo/iteminfo_en.lub"
		fun files( dir: File ) = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo( dir ).invariantSeparatorsPath }
			.filter { it != itemInfo }.toSortedSet()
		assertEquals( files( expected!! ), files( target ) )
		val different = files( expected ).filter { !File( expected, it ).readBytes().contentEquals( File( target, it ).readBytes() ) }
		assertEquals( emptyList<String>(), different )
		assertTrue( File( target, itemInfo ).readBytes().contentEquals(
			File( checkout, "Translation/Renewal/SystemEN/LuaFiles514/itemInfo.lua" ).readBytes() ) )
	}
}
