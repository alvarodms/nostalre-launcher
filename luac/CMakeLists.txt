# luac 5.1, the Lua compiler: the app compiles the English translation's itemInfo.lua with it on the device (the
# game reads iteminfo compiled only), the tests compile it on the host. lua-5.1.5/ is Lua 5.1.5's src/ and COPYRIGHT,
# as lua.org ships them (lua-5.1.5.tar.gz, sha256 2640fc56a795f29d28ef15e13c34a47e223960b0240e8cb0a82d9b0738695333).
cmake_minimum_required( VERSION 3.13 )
project( luac C )

set( LUA lua-5.1.5/src )
# Lua's core and auxiliary library, then luac itself (src/Makefile's CORE_O, lauxlib.o and LUAC_O)
add_executable( luac
	${LUA}/lapi.c ${LUA}/lcode.c ${LUA}/ldebug.c ${LUA}/ldo.c ${LUA}/ldump.c ${LUA}/lfunc.c ${LUA}/lgc.c
	${LUA}/llex.c ${LUA}/lmem.c ${LUA}/lobject.c ${LUA}/lopcodes.c ${LUA}/lparser.c ${LUA}/lstate.c
	${LUA}/lstring.c ${LUA}/ltable.c ${LUA}/ltm.c ${LUA}/lundump.c ${LUA}/lvm.c ${LUA}/lzio.c
	${LUA}/lauxlib.c
	${LUA}/luac.c ${LUA}/print.c )
target_compile_definitions( luac PRIVATE LUA_USE_POSIX )
target_link_libraries( luac m )
