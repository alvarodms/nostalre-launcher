package io.github.siklyde.nostalre

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * scripts/import-english.py on the device: ROenglishRE's English text written into the game folder's data/, from the
 * translation's own files ([files] says which, [build] writes them), byte for byte what the script writes, its
 * luac5.1 the one the APK carries (luac/, built by scripts/build-luac.sh).
 *
 * Plain JVM, so a unit test can hold it against the script. Text is handled as Latin-1 strings, a character per byte,
 * as the script handles bytes: the translation's Korean is CP949, never decoded.
 */
object EnglishImport {
	private const val RENEWAL = "Translation/Renewal/"
	private const val PRE_RENEWAL = "Translation/Pre-Renewal/"
	private const val ITEM_INFO = "SystemEN/LuaFiles514/itemInfo.lua"
	private const val RENEWAL_SKILLS = "${RENEWAL}data/luafiles514/lua files/skillinfoz/"
	private const val PRE_SKILL_DESCRIPTIONS = "${PRE_RENEWAL}data/lua files/skillinfoz/skilldescript.lub"
	private const val PRE_SKILL_NAMES = "${PRE_RENEWAL}data/skillnametable.txt"
	private const val ITEM_INFO_EN = "luafiles514/lua files/datainfo/iteminfo_en.lub"
	private const val LUAC_TIMEOUT_S = 120L

	private val KEEP_DIRS = setOf( "texture", "book" )
	private val KEEP_EXT = setOf( ".txt", ".xml", ".bmp", ".tga", ".jpg", ".png", ".spr", ".act" )
	// ClientGenerator.bat's dated folders for a client of 2022-04-06, in its order (it skips 2020-01-22)
	private val COMPATIBILITY = listOf( "2017-06-14", "2017-12-13", "2018-06-20", "2019-06-05", "2020-09-02",
		"2021-10-28", "2022-03-30", "2022-04-06" )
	private val LATIN1 = Charsets.ISO_8859_1
	private val CP949: Charset = Charset.forName( "MS949" )

	/** The translation's files [build] reads, out of [listing] (every file of the checkout, by path). */
	fun files( listing: Collection<String> ): List<String> {
		val extras = listOf( RENEWAL + ITEM_INFO, PRE_RENEWAL + ITEM_INFO, PRE_SKILL_DESCRIPTIONS,
			RENEWAL_SKILLS + "skillinfolist.lub", RENEWAL_SKILLS + "skilldescript.lub", PRE_SKILL_NAMES )
		val present = listing.toSet()
		return ( copies( listing ).map { it.first } + extras.filter { it in present } ).distinct()
	}

	/**
	 * The English text into [target] (the game folder's data/): the translation's files, read from [source] (a
	 * folder holding at least [files] of [listing] by path), then the tables made from them and from rAthena's db
	 * ([rathenaDb], the servers' db/re), and the item table compiled with [luac] (Lua 5.1's). The compiling goes
	 * first: when it fails, nothing is written.
	 */
	fun build( listing: Collection<String>, source: File, rathenaDb: File, target: File, luac: File ) {
		val itemInfo = compileItemInfo( source, luac )
		try {
			target.mkdirs()
			val root = target.canonicalFile
			for( ( path, relative ) in copies( listing ) ) {
				val dest = File( root, relative )
				require( dest.canonicalPath.startsWith( root.path + File.separator ) ) { "refusing path outside $target: $relative" }
				dest.parentFile?.mkdirs()
				File( source, path ).copyTo( dest, overwrite = true )
			}
			writeItemTables( source, rathenaDb, root )
			writeRathenaTables( rathenaDb, root )
			if( itemInfo != null ) {
				val dest = File( root, ITEM_INFO_EN )
				dest.parentFile?.mkdirs()
				itemInfo.copyTo( dest, overwrite = true )
			}
			writeSkillTables( source, rathenaDb, root )
		} finally {
			itemInfo?.delete()
		}
	}

	/**
	 * Each data folder's files the client takes, as (path in the translation, path under data/), in the order they are
	 * written (a later one replaces an earlier): Translation/Renewal, then each dated compatibility folder up to the
	 * client's date, its Renewal/ when it has one, as ClientGenerator.bat stages a 2022-04-06 Renewal client.
	 */
	fun copies( listing: Collection<String> ): List<Pair<String, String>> {
		val folders = listOf( "${RENEWAL}data/" ) + COMPATIBILITY.map { date ->
			val dated = "Translation/Compatibility/$date/"
			if( listing.any { it.startsWith( "${dated}Renewal/" ) } ) "${dated}Renewal/data/" else "${dated}data/"
		}
		return folders.flatMap { folder ->
			listing.filter { it.startsWith( folder ) }
				.map { it to it.removePrefix( folder ) }
				.filter { ( _, relative ) -> kept( relative ) }
				.sortedWith { a, b -> byParts.compare( a.second, b.second ) }
		}
	}

	/** Top-level text tables, the world map labels (roBrowser loads them through its Lua engine), textures and books. */
	private fun kept( relative: String ): Boolean {
		val parts = relative.split( '/' )
		val suffix = suffix( parts.last() ).lowercase()
		val topLevelText = parts.size == 1 && suffix in setOf( ".txt", ".xml" )
		val worldMap = parts.take( 3 ).map { it.lowercase() } == listOf( "luafiles514", "lua files", "worldviewdata" )
		return topLevelText || worldMap || ( parts[0] in KEEP_DIRS && suffix in KEEP_EXT )
	}

	/** A name's extension as Python's Path.suffix has it: none for ".name" or "name.". */
	private fun suffix( name: String ): String {
		val dot = name.lastIndexOf( '.' )
		return if( dot > 0 && dot < name.length - 1 ) name.substring( dot ) else ""
	}

	/** Paths compared part by part, as Python sorts them. */
	private val byParts = Comparator<String> { a, b ->
		val x = a.split( '/' )
		val y = b.split( '/' )
		for( i in 0 until minOf( x.size, y.size ) ) {
			val c = x[i].compareTo( y[i] )
			if( c != 0 )
				return@Comparator c
		}
		x.size - y.size
	}

	// --- item tables

	/** The item name and description tables (data/idnum2itemdisplaynametable.txt and the like) from the English itemInfo.lua files. */
	private fun writeItemTables( source: File, rathenaDb: File, data: File ) {
		val items = LinkedHashMap<Any, Any>()
		for( mode in listOf( RENEWAL, PRE_RENEWAL ) ) { // the server is pre-renewal: its texts win
			val file = File( source, mode + ITEM_INFO )
			if( file.exists() )
				( luaTable( file.readText( LATIN1 ), "tbl" ) as? Map<*, *> )?.forEach { ( key, value ) -> items[key!!] = value!! }
		}
		if( items.isEmpty() )
			return
		// the server knows items newer than the translation (Renewal ids above 65535): their rAthena names
		for( ( id, name ) in rathenaItemNames( rathenaDb ) )
			if( id !in items )
				items[id] = mapOf( "identifiedDisplayName" to name, "unidentifiedDisplayName" to name )

		fun line( text: Any? ) = string( text ).replace( '#', '_' )
		fun lines( item: Map<*, *>, key: String ) =
			"\n" + ( item[key] as? List<*> ).orEmpty().joinToString( "\n" ) { line( it ) } + "\n#"
		val tables = mapOf<String, ( Map<*, *> ) -> String>(
			"idnum2itemdisplaynametable.txt" to { line( it["identifiedDisplayName"] ?: "" ) + "#" },
			"num2itemdisplaynametable.txt" to { line( it["unidentifiedDisplayName"] ?: "" ) + "#" },
			"idnum2itemdesctable.txt" to { lines( it, "identifiedDescriptionName" ) },
			"num2itemdesctable.txt" to { lines( it, "unidentifiedDescriptionName" ) },
		)
		val ids = items.keys.filterIsInstance<Long>().sorted()
		for( ( name, format ) in tables )
			write( File( data, name ), buildString {
				for( id in ids )
					append( id ).append( '#' ).append( format( items[id] as? Map<*, *> ?: emptyMap<Any, Any>() ) ).append( '\n' )
			} )
	}

	/**
	 * The translation's Renewal itemInfo.lua compiled, for data/…/iteminfo_en.lub (the items kRO's own iteminfo lacks;
	 * the game reads iteminfo compiled only), as a temporary file; null without it.
	 */
	private fun compileItemInfo( source: File, luac: File ): File? {
		val file = File( source, RENEWAL + ITEM_INFO )
		if( !file.exists() )
			return null
		val compiled = File.createTempFile( "iteminfo_en", ".lub" )
		val log = File.createTempFile( "luac", ".log" )
		compiled.delete()
		try {
			runLuac( luac, file, compiled, log )
		} finally {
			log.delete()
		}
		return compiled
	}

	private fun runLuac( luac: File, file: File, compiled: File, log: File ) {
		val process = ProcessBuilder( luac.path, "-s", "-o", compiled.path, file.path )
			.redirectErrorStream( true )
			.redirectOutput( log )
			.start()
		if( !process.waitFor( LUAC_TIMEOUT_S, TimeUnit.SECONDS ) ) {
			process.destroyForcibly()
			throw IOException( "luac did not finish" )
		}
		if( process.exitValue() != 0 || !compiled.isFile )
			throw IOException( "luac could not compile itemInfo.lua: ${log.readText().trim()}" )
	}

	private fun itemDbFiles( rathenaDb: File ): List<File> =
		rathenaDb.listFiles { file -> file.name.startsWith( "item_db_" ) && file.name.endsWith( ".yml" ) }.orEmpty().sortedBy { it.name }

	private fun yamlPattern( regex: String ) = Pattern.compile( regex, Pattern.MULTILINE or Pattern.UNIX_LINES )

	private val ITEM_NAME = yamlPattern( """^  - Id: (\d+)[^\n]*\n(?:    .*\n)*?    Name: (.+)$""" )

	/** Id -> English name of every item in rAthena's Renewal item db, as CP949 like the translation's. */
	private fun rathenaItemNames( rathenaDb: File ): Map<Long, String> {
		val names = LinkedHashMap<Long, String>()
		for( file in itemDbFiles( rathenaDb ) ) {
			val matcher = ITEM_NAME.matcher( file.readText() )
			while( matcher.find() ) {
				val name = matcher.group( 2 ).split( "#" )[0].trim().trim( '"' )
				names[matcher.group( 1 ).toLong()] = cp949( name )
			}
		}
		return names
	}

	private val CARD = yamlPattern( """^    Type: Card$""" )
	private val ARMOR = yamlPattern( """^    Type: Armor$""" )
	private val VIEW = yamlPattern( """^    View: (\d+)$""" )
	private val LEFT_HAND = yamlPattern( """^      Left_Hand: true$""" )
	private val HEAD = yamlPattern( """^      (Costume_)?Head_(Top|Mid|Low): true$""" )

	/**
	 * data/shieldviewtable.txt and headgearviewtable.txt: the view of every shield and headgear in rAthena's Renewal
	 * item db (id#view#); data/rathenacardtable.txt: its cards (id#). The game takes them for the items its iteminfo
	 * lacks or gives no ClassNum: a shield's look, a headgear's icon, a card's icon.
	 */
	private fun writeRathenaTables( rathenaDb: File, data: File ) {
		val shields = TreeMap<Long, Long>()
		val headgear = TreeMap<Long, Long>()
		val cards = mutableListOf<Long>()
		for( file in itemDbFiles( rathenaDb ) )
			for( entry in file.readText().split( "\n  - Id: " ).drop( 1 ) ) {
				val id = entry.substringBefore( '\n' ).trim().split( Regex( "\\s+" ) )[0].toLong()
				if( CARD.matcher( entry ).find() )
					cards += id
				val view = VIEW.matcher( entry )
				if( !view.find() || !ARMOR.matcher( entry ).find() )
					continue
				if( LEFT_HAND.matcher( entry ).find() )
					shields[id] = view.group( 1 ).toLong()
				else if( HEAD.matcher( entry ).find() )
					headgear[id] = view.group( 1 ).toLong()
			}
		for( ( name, views ) in listOf( "shieldviewtable.txt" to shields, "headgearviewtable.txt" to headgear ) )
			write( File( data, name ), views.entries.joinToString( "" ) { ( id, view ) -> "$id#$view#\n" } )
		write( File( data, "rathenacardtable.txt" ), cards.sorted().joinToString( "" ) { "$it#\n" } )
	}

	// --- skill tables

	/**
	 * The skill tables the game reads, from the English skillinfoz lubs (Renewal over Pre-Renewal):
	 * - data/skillnametable.txt (code#Name#): the Renewal SkillName, else the Pre-Renewal table's, else rAthena's
	 *   description (the server's own sub-skills have no name in kRO's tables);
	 * - data/skilldesctable_renewal.txt (code#lines#): the Renewal descriptions, else the Pre-Renewal ones;
	 * - data/leveluseskillspamount.txt (code#, SP per level#, @): the skills kRO lets pick a level, with the SP
	 *   rAthena charges at each.
	 * data/skilldesctable.txt stays the Pre-Renewal descriptions: roBrowser reads it.
	 */
	private fun writeSkillTables( source: File, rathenaDb: File, data: File ) {
		val preDescriptions = File( source, PRE_SKILL_DESCRIPTIONS )
		val renewal = File( source, RENEWAL_SKILLS )
		if( !preDescriptions.exists() || !File( renewal, "skillinfolist.lub" ).exists() )
			return

		fun writeDescriptions( name: String, skills: Map<*, *> ) = write( File( data, name ), buildString {
			for( ( code, lines ) in skills )
				append( code ).append( "#\n" )
					.append( ( lines as? List<*> ).orEmpty().joinToString( "\n" ) { string( it ).replace( '#', '_' ) } )
					.append( "\n#\n" )
		} )

		val classic = luaTable( preDescriptions.readText( LATIN1 ), "SKILL_DESCRIPT" ) as? Map<*, *> ?: emptyMap<Any, Any>()
		writeDescriptions( "skilldesctable.txt", classic )
		val renewalDescriptions = luaTable( File( renewal, "skilldescript.lub" ).readText( LATIN1 ), "SKILL_DESCRIPT" ) as? Map<*, *>
		writeDescriptions( "skilldesctable_renewal.txt", LinkedHashMap<Any?, Any?>( classic ).apply { putAll( renewalDescriptions.orEmpty() ) } )

		val info = luaTable( File( renewal, "skillinfolist.lub" ).readText( LATIN1 ), "SKILL_INFO_LIST" ) as? Map<*, *> ?: emptyMap<Any, Any>()
		val server = rathenaSkills( rathenaDb )
		val names = LinkedHashMap<String, String>()
		server.forEach { ( code, skill ) -> names[code] = skill.first }
		val oldNames = File( source, PRE_SKILL_NAMES )
		if( oldNames.exists() )
			for( line in oldNames.readText( LATIN1 ).lines() ) {
				val parts = line.split( '#' )
				if( parts.size >= 2 && strip( parts[0] ).isNotEmpty() && strip( parts[1] ).isNotEmpty() )
					names[strip( parts[0] )] = strip( parts[1] ).replace( '_', ' ' )
			}
		for( ( code, entry ) in info ) {
			val name = ( entry as? Map<*, *> )?.get( "SkillName" )
			if( truthy( name ) )
				names[code.toString()] = string( name )
		}
		write( File( data, "skillnametable.txt" ), buildString {
			for( code in names.keys.sorted() )
				append( code ).append( '#' ).append( names.getValue( code ).replace( '#', '_' ).replace( ' ', '_' ) ).append( "#\n" )
		} )

		write( File( data, "leveluseskillspamount.txt" ), buildString {
			for( ( code, entry ) in info ) {
				if( entry !is Map<*, *> || !truthy( entry["bSeperateLv"] ) )
					continue
				val sp = server[code.toString()]?.second ?: ( entry["SpAmount"] as? List<*> ).orEmpty()
				append( code ).append( "#\n" )
				sp.forEach { append( it ).append( "#\n" ) }
				append( "@\n" )
			}
		} )
	}

	private val SKILL_NAME = yamlPattern( """^    Name: (.+)$""" )
	private val SKILL_DESCRIPTION = yamlPattern( """^    Description: (.+)$""" )
	private val MAX_LEVEL = yamlPattern( """^    MaxLevel: (\d+)$""" )
	private val REQUIRES = yamlPattern( """^    Requires:\n((?:(?:      .*)?\n)*)""" )
	private val SP_COST = yamlPattern( """^      SpCost: (-?\d+)$""" )
	private val SP_COSTS = yamlPattern( """^      SpCost:\n((?:        .*\n)*)""" )
	private val COST_FIELD = yamlPattern( """(Level|Amount): (-?\d+)""" )

	/** Aegis name -> (English name, as CP949, SP per level) of every skill in rAthena's Renewal skill db. */
	private fun rathenaSkills( rathenaDb: File ): Map<String, Pair<String, List<Long>>> {
		val skills = LinkedHashMap<String, Pair<String, List<Long>>>()
		for( split in File( rathenaDb, "skill_db.yml" ).readText().split( "\n  - Id: " ).drop( 1 ) ) {
			val entry = split + "\n" // its last line ends where the next entry starts
			fun field( pattern: Pattern ) = pattern.matcher( entry ).let { if( it.find() ) it.group( 1 ) else null }
			val name = field( SKILL_NAME )?.let( ::yamlScalar ) ?: continue
			val description = field( SKILL_DESCRIPTION )?.let( ::yamlScalar ) ?: name
			val levels = 1..( field( MAX_LEVEL )?.toInt() ?: 1 )
			val requires = field( REQUIRES ).orEmpty()
			val flat = SP_COST.matcher( requires ).let { if( it.find() ) it.group( 1 ).toLong() else null }
			val perLevel = mutableMapOf<Long, Long>()
			SP_COSTS.matcher( requires ).takeIf { flat == null && it.find() }?.group( 1 )
				?.split( Regex( "(?m)^        - " ) )?.forEach { item ->
					val fields = COST_FIELD.matcher( item ).let { m -> buildMap { while( m.find() ) put( m.group( 1 ), m.group( 2 ).toLong() ) } }
					val level = fields["Level"] ?: return@forEach
					perLevel[level] = fields.getValue( "Amount" )
				}
			val sp = levels.map { level -> if( perLevel.isNotEmpty() ) perLevel[level.toLong()] ?: 0 else flat ?: 0 }
			skills[name] = cp949( description ) to sp
		}
		return skills
	}

	/** A plain or quoted YAML scalar's text. */
	private fun yamlScalar( raw: String ): String {
		val value = raw.trim()
		return when {
			value.length >= 2 && value.startsWith( '"' ) && value.endsWith( '"' ) -> value.substring( 1, value.length - 1 ).replace( "\\\"", "\"" )
			value.length >= 2 && value.startsWith( '\'' ) && value.endsWith( '\'' ) -> value.substring( 1, value.length - 1 ).replace( "''", "'" )
			else -> value.substringBefore( " #" ).trim()
		}
	}

	// --- Lua tables

	/**
	 * The top-level Lua table `name = {...}` of [text] as maps and lists (a table with keys is a map, else a list):
	 * strings, and names (`SKID.NV_BASIC` reads as "NV_BASIC"), are Strings; numbers Longs; true and false Booleans.
	 */
	fun luaTable( text: String, name: String ): Any = LuaReader( text ).table( name )

	private class LuaReader( private val text: String ) {
		private var kinds = ByteArray( 1 shl 16 )
		private var starts = IntArray( 1 shl 16 )
		private var ends = IntArray( 1 shl 16 )
		private var count = 0
		private var pos = 0

		init {
			tokenize()
		}

		fun table( name: String ): Any {
			while( !( kinds[pos] == NAME && word( pos ) == name && punct( pos + 1 ) == '=' ) )
				pos++
			pos += 3 // name = {
			return table()
		}

		/** The script's tokens: whitespace and -- comments skipped, then a string, a number, a name or one character. */
		private fun tokenize() {
			var i = 0
			val n = text.length
			while( i < n ) {
				val c = text[i]
				when {
					c == ' ' || c in '\t'..'\r' -> i++
					c == '-' && i + 1 < n && text[i + 1] == '-' -> {
						while( i < n && text[i] != '\n' ) i++
					}
					c == '"' && closingQuote( i ) >= 0 -> {
						val end = closingQuote( i )
						add( STRING, i + 1, end )
						i = end + 1
					}
					c.isAsciiDigit() || c == '-' && i + 1 < n && text[i + 1].isAsciiDigit() -> {
						val start = i
						i++
						while( i < n && text[i].isAsciiDigit() ) i++
						add( NUMBER, start, i )
					}
					c.isAsciiLetter() || c == '_' -> {
						val start = i
						while( i < n && ( text[i].isAsciiLetter() || text[i].isAsciiDigit() || text[i] == '_' ) ) i++
						add( NAME, start, i )
					}
					else -> {
						add( PUNCT, i, i + 1 )
						i++
					}
				}
			}
		}

		/** Where the string opened at [open] ends, or -1 when it never does (the quote is then a character). */
		private fun closingQuote( open: Int ): Int {
			var i = open + 1
			while( i < text.length ) {
				when( text[i] ) {
					'"' -> return i
					'\\' -> if( i + 1 < text.length ) i += 2 else return -1
					else -> i++
				}
			}
			return -1
		}

		private fun add( kind: Byte, start: Int, end: Int ) {
			if( count == kinds.size ) {
				kinds = kinds.copyOf( count * 2 )
				starts = starts.copyOf( count * 2 )
				ends = ends.copyOf( count * 2 )
			}
			kinds[count] = kind
			starts[count] = start
			ends[count] = end
			count++
		}

		private fun word( i: Int ) = text.substring( starts[i], ends[i] )

		private fun punct( i: Int ): Char? = if( i < count && kinds[i] == PUNCT ) text[starts[i]] else null

		private fun value(): Any {
			val i = pos++
			return when( kinds[i] ) {
				STRING -> unescape( word( i ) )
				NUMBER -> word( i ).toLong()
				NAME -> {
					val word = word( i )
					if( word == "true" || word == "false" )
						return word == "true"
					var name = word
					while( punct( pos ) == '.' && pos + 1 < count && kinds[pos + 1] == NAME ) { // SKID.NV_BASIC reads as NV_BASIC
						name = word( pos + 1 )
						pos += 2
					}
					name
				}
				else -> if( text[starts[i]] == '{' ) table() else throw IllegalArgumentException( "unexpected '${word( i )}' at ${starts[i]}" )
			}
		}

		private fun table(): Any {
			val result = LinkedHashMap<Any, Any>()
			val items = mutableListOf<Any>()
			while( punct( pos ) != '}' ) {
				require( pos < count ) { "table not closed" }
				if( punct( pos ) == '[' ) { // [key] = value, key may be a constant like SKID.NV_BASIC
					pos++
					var last = -1
					while( punct( pos ) != ']' ) {
						last = pos
						pos++
					}
					require( last >= 0 ) { "empty key at ${starts[pos]}" }
					pos += 2 // ] =
					result[if( kinds[last] == NUMBER ) word( last ).toLong() else word( last )] = value()
				} else if( kinds[pos] == NAME && punct( pos + 1 ) == '=' ) {
					val key = word( pos )
					pos += 2
					result[key] = value()
				} else {
					items += value()
				}
				if( punct( pos ) == ',' || punct( pos ) == ';' ) // Lua accepts both separators
					pos++
			}
			pos++
			return if( result.isNotEmpty() ) result else items
		}

		/** \n and \t, any other escaped character as itself (an escaped line break stays as it is). */
		private fun unescape( raw: String ): String {
			if( '\\' !in raw )
				return raw
			return buildString( raw.length ) {
				var i = 0
				while( i < raw.length ) {
					val c = raw[i]
					if( c == '\\' && i + 1 < raw.length && raw[i + 1] != '\n' ) {
						append( when( val escaped = raw[i + 1] ) { 'n' -> '\n'; 't' -> '\t'; else -> escaped } )
						i += 2
					} else {
						append( c )
						i++
					}
				}
			}
		}

		private fun Char.isAsciiDigit() = this in '0'..'9'
		private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

		private companion object {
			const val STRING: Byte = 1
			const val NUMBER: Byte = 2
			const val NAME: Byte = 3
			const val PUNCT: Byte = 4
		}
	}

	// --- helpers

	/** A value as the text the script writes of it. */
	private fun string( value: Any? ): String = when( value ) {
		null -> ""
		is Boolean -> if( value ) "True" else "False"
		else -> value.toString()
	}

	private fun truthy( value: Any? ): Boolean = when( value ) {
		null -> false
		is Boolean -> value
		is Long -> value != 0L
		is String -> value.isNotEmpty()
		is Collection<*> -> value.isNotEmpty()
		is Map<*, *> -> value.isNotEmpty()
		else -> true
	}

	/** Python's bytes.strip(): ASCII whitespace only. */
	private fun strip( text: String ) = text.trim { it == ' ' || it in '\t'..'\r' }

	/** [text] as CP949 bytes (unmappable characters as ?), spelled as Latin-1. */
	private fun cp949( text: String ) = String( text.toByteArray( CP949 ), LATIN1 )

	private fun write( file: File, text: String ) = file.writeBytes( text.toByteArray( LATIN1 ) )
}
