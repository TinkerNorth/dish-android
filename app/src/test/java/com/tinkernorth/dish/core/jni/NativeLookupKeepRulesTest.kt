// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.jni

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// One member native code asks the JVM for by name: its class, its name and its descriptor.
internal data class NativeLookup(
    val className: String,
    val member: String,
    val descriptor: String,
)

// A release build minifies, and R8 removes or renames whatever no Kotlin code calls, which is every
// upcall native code makes. Each member the JNI resolves by name must have a keep rule in
// proguard-rules.pro, or the release build's bridge goes silent while every debug build works.
class NativeLookupKeepRulesTest {
    private val appDir: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(it, File(it, "app")) }
            .first { File(it, "proguard-rules.pro").exists() }

    private val nativeText: String =
        File(appDir, "src/main/cpp")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "cpp" || it.extension == "h") }
            .sortedBy { it.name }
            .joinToString("\n") { it.readText() }

    private val rules = keepRulesOf(File(appDir, "proguard-rules.pro").readText())

    @Test
    fun `every member native code resolves by name is kept under its own name and descriptor`() {
        val lookups = nativeLookupsOf(nativeText)
        val unkept = lookups.filterNot(rules::keeps)

        assertTrue("the scan found the bridges", lookups.size >= FEWEST_LOOKUPS)
        assertEquals(emptyList<NativeLookup>(), unkept)
    }

    @Test
    fun `native code looks members up only through the helpers this test reads`() {
        val rawLookups = RAW_LOOKUPS.associateWith { call -> Regex("""\b$call\(""").findAll(nativeText).count() }

        assertEquals(EXPECTED_RAW_LOOKUPS, rawLookups)
    }

    @Test
    fun `the scan reads a bridge's lookups split across lines and its class from its install export`() {
        val text =
            """
            JNIEXPORT void JNICALL Java_com_tinkernorth_dish_hotpath_input_FeedbackBridge_nativeInstall(
            m = resolveStaticMethod(
                env, g_cls, "FeedbackBridge", "dispatchPlayerLeds", "(III)V");
            """.trimIndent()

        val lookups = nativeLookupsOf(text)

        assertEquals(listOf(NativeLookup("com.tinkernorth.dish.hotpath.input.FeedbackBridge", "dispatchPlayerLeds", "(III)V")), lookups)
    }

    @Test
    fun `a rule keeps a method by its name and descriptor, and a wildcard keeps every member`() {
        val parsed =
            keepRulesOf(
                """
                -keep class a.B {
                    public static void go(int, java.lang.String, byte[]);
                    public static boolean write(int, int, short[]);
                }
                -keepclassmembers class a.C {
                    <fields>;
                }
                -keep class a.D { *; }
                """.trimIndent(),
            )

        assertTrue(parsed.keeps(NativeLookup("a.B", "go", "(ILjava/lang/String;[B)V")))
        assertTrue(parsed.keeps(NativeLookup("a.B", "write", "(II[S)Z")))
        assertTrue(!parsed.keeps(NativeLookup("a.B", "go", "(I)V")))
        assertTrue(parsed.keeps(NativeLookup("a.C", "x", "I")))
        assertTrue(!parsed.keeps(NativeLookup("a.C", "go", "()V")))
        assertTrue(parsed.keeps(NativeLookup("a.D", "anything", "()V")))
    }

    private companion object {
        const val FEWEST_LOOKUPS = 20
        val RAW_LOOKUPS = listOf("GetStaticMethodID", "GetMethodID", "GetFieldID", "GetStaticFieldID", "FindClass", "RegisterNatives")

        // resolveStaticMethod's one GetStaticMethodID, and readClaimField's and readClaimFlag's GetFieldID.
        val EXPECTED_RAW_LOOKUPS =
            mapOf(
                "GetStaticMethodID" to 1,
                "GetMethodID" to 0,
                "GetFieldID" to 2,
                "GetStaticFieldID" to 0,
                "FindClass" to 0,
                "RegisterNatives" to 0,
            )
    }
}

private const val CLAIM_CLASS = "com.tinkernorth.dish.core.jni.UsbInterfaceClaim"
private const val INT_FIELD = "I"
private const val BOOLEAN_FIELD = "Z"

private val INSTALL_EXPORT = Regex("""Java_(com_tinkernorth_dish(?:_[a-z0-9]+)*)_([A-Z][A-Za-z0-9]*)_nativeInstall\b""")
private val STATIC_METHOD = Regex("""resolveStaticMethod\(\s*env,\s*\w+,\s*"(\w+)",\s*"(\w+)",\s*"([^"]+)"\s*\)""")
private val CLAIM_INT = Regex("""readClaimField\(\s*env,\s*cls,\s*claim,\s*"(\w+)"""")
private val CLAIM_FLAG = Regex("""readClaimFlag\(\s*env,\s*cls,\s*claim,\s*"(\w+)"""")

internal fun nativeLookupsOf(text: String): List<NativeLookup> {
    val classes = INSTALL_EXPORT.findAll(text).associate(::bridgeClassOf)
    val methods =
        STATIC_METHOD
            .findAll(text)
            .map { it.destructured }
            .map { (owner, name, signature) -> NativeLookup(classes.getValue(owner), name, signature) }
    val ints = CLAIM_INT.findAll(text).map { NativeLookup(CLAIM_CLASS, it.groupValues[1], INT_FIELD) }
    val flags = CLAIM_FLAG.findAll(text).map { NativeLookup(CLAIM_CLASS, it.groupValues[1], BOOLEAN_FIELD) }
    return (methods + ints + flags).toList()
}

// "Java_com_tinkernorth_dish_hotpath_input_FeedbackBridge_nativeInstall" installs hotpath.input.FeedbackBridge.
private fun bridgeClassOf(export: MatchResult): Pair<String, String> {
    val (packagePath, simpleName) = export.destructured
    return simpleName to "${packagePath.replace('_', '.')}.$simpleName"
}

// What one -keep or -keepclassmembers rule keeps of one class.
internal data class ClassKeep(
    val everyMember: Boolean,
    val everyField: Boolean,
    val everyMethod: Boolean,
    val members: Set<Pair<String, String>>,
)

internal class KeepRules(
    private val byClass: Map<String, List<ClassKeep>>,
) {
    fun keeps(lookup: NativeLookup): Boolean = byClass[lookup.className].orEmpty().any { keeps(it, lookup) }

    private fun keeps(
        keep: ClassKeep,
        lookup: NativeLookup,
    ): Boolean {
        val isAField = !lookup.descriptor.startsWith("(")
        val byWildcard = keep.everyMember || (isAField && keep.everyField) || (!isAField && keep.everyMethod)
        return byWildcard || (lookup.member to lookup.descriptor) in keep.members
    }
}

private val KEEP_BLOCK = Regex("""-keep(?:classmembers)?(?:,\w+)*\s+class\s+([\w.$]+)\s*\{([^}]*)}""")
private val METHOD_LINE = Regex("""^(?:public\s+)?(?:static\s+)?([\w.\[\]]+)\s+(\w+)\(([^)]*)\);$""")
private val FIELD_LINE = Regex("""^(?:public\s+|private\s+)?(?:static\s+)?([\w.\[\]]+)\s+(\w+);$""")

internal fun keepRulesOf(text: String): KeepRules {
    val withoutComments = text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
    val keeps = KEEP_BLOCK.findAll(withoutComments).map { it.groupValues[1] to classKeepOf(it.groupValues[2]) }
    return KeepRules(keeps.groupBy({ it.first }, { it.second }))
}

private fun classKeepOf(body: String): ClassKeep {
    val lines = body.lines().map(String::trim).filter(String::isNotEmpty)
    return ClassKeep(
        everyMember = "*;" in lines,
        everyField = "<fields>;" in lines,
        everyMethod = "<methods>;" in lines,
        members = lines.mapNotNull(::memberOf).toSet(),
    )
}

private fun memberOf(line: String): Pair<String, String>? {
    val method = METHOD_LINE.matchEntire(line)
    if (method != null) {
        val (returns, name, params) = method.destructured
        val args =
            params
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString("") { descriptorOf(it) }
        return name to "($args)${descriptorOf(returns)}"
    }
    val field = FIELD_LINE.matchEntire(line) ?: return null
    val (type, name) = field.destructured
    return name to descriptorOf(type)
}

private val PRIMITIVES =
    mapOf(
        "void" to "V",
        "boolean" to "Z",
        "byte" to "B",
        "char" to "C",
        "short" to "S",
        "int" to "I",
        "long" to "J",
        "float" to "F",
        "double" to "D",
    )

private fun descriptorOf(type: String): String {
    val isAnArray = type.endsWith("[]")
    if (isAnArray) return "[" + descriptorOf(type.removeSuffix("[]"))
    return PRIMITIVES[type] ?: "L${type.replace('.', '/')};"
}
