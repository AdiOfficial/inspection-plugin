package org.jetbrains.intellij

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import org.gradle.testkit.runner.TaskOutcome.*
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.jetbrains.intellij.InspectionTest.DiagnosticsStatus.SHOULD_BE_ABSENT
import org.jetbrains.intellij.InspectionTest.DiagnosticsStatus.SHOULD_PRESENT
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InspectionTest {
    @Rule
    @JvmField
    val testProjectDir = TemporaryFolder()

    private fun generateBuildFile(
            kotlinNeeded: Boolean,
            maxErrors: Int = -1,
            maxWarnings: Int = -1,
            showViolations: Boolean = true,
            xmlReport: Boolean = false,
            kotlinVersion: String = "1.1.3-2"
    ): String {
        val templateLines = File("testData/inspection/build.gradle.template").readLines()
        return StringBuilder().apply {
            for (line in templateLines) {
                val template = Regex("<.*>").find(line)
                if (template == null) {
                    appendln(line)
                    continue
                }
                when (template.value.drop(1).dropLast(1)) {
                    "kotlinGradleDependency" -> if (kotlinNeeded) {
                        appendln("        classpath \"org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion\"")
                    }
                    "kotlinPlugin" -> if (kotlinNeeded) {
                        appendln("    id 'org.jetbrains.kotlin.jvm' version '$kotlinVersion'")
                    }
                    "maxErrors" -> if (maxErrors > -1) {
                        appendln("    maxErrors = $maxErrors")
                    }
                    "maxWarnings" -> if (maxWarnings > -1) {
                        appendln("    maxWarnings = $maxWarnings")
                    }
                    "showViolations" -> if (!showViolations) {
                        appendln("    showViolations = false")
                    }
                    "xmlDestination" -> if (xmlReport) {
                        appendln("            destination \"build/report.xml\"")
                    }
                    "kotlin-stdlib" -> if (kotlinNeeded) {
                        appendln("    compile \"org.jetbrains.kotlin:kotlin-stdlib\"")
                    }
                    "kotlin-runtime" -> if (kotlinNeeded) {
                        appendln("    compile \"org.jetbrains.kotlin:kotlin-runtime\"")
                    }
                }
            }
            println(this)
        }.toString()
    }

    private fun assertInspectionBuild(
            expectedOutcome: TaskOutcome,
            expectedDiagnosticsStatus: DiagnosticsStatus,
            vararg expectedDiagnostics: String
    ) {
        val result = try {
            GradleRunner.create()
                    .withProjectDir(testProjectDir.root)
                    .withArguments("--info", "--stacktrace", "inspectionsMain")
                    .withPluginClasspath()
                    .apply {
                        withPluginClasspath(pluginClasspath)
                    }.build()
        } catch (failure: UnexpectedBuildFailure) {
            println("Exception caught in test: $failure")
            failure.buildResult
        }

        println(result.output)
        for (diagnostic in expectedDiagnostics) {
            when (expectedDiagnosticsStatus) {
                SHOULD_PRESENT -> assertTrue("$diagnostic is not found (but should)",
                        diagnostic in result.output)
                SHOULD_BE_ABSENT -> assertFalse("$diagnostic is found (but should not)", diagnostic in result.output)
            }
        }
        assertEquals(expectedOutcome, result.task(":inspectionsMain").outcome)
    }

    private fun writeFile(destination: File, content: String) {
        destination.bufferedWriter().use {
            it.write(content)
        }
    }

    private fun generateInspectionTags(
            tagName: String,
            inspections: List<String>
    ): String {
        return StringBuilder().apply {
            appendln("    <${tagName}s>")
            for (inspectionClass in inspections) {
                appendln("        <$tagName class = \"$inspectionClass\"/>")
            }
            appendln("    </${tagName}s>")
        }.toString()
    }

    private fun generateInspectionFile(
            errors: List<String> = emptyList(),
            warnings: List<String> = emptyList(),
            infos: List<String> = emptyList()
    ): String {
        return StringBuilder().apply {
            appendln("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>")
            appendln("<inspections>")
            appendln(generateInspectionTags("error", errors))
            appendln(generateInspectionTags("warning", warnings))
            appendln(generateInspectionTags("info", infos))
            appendln("</inspections>")
        }.toString()
    }

    private enum class DiagnosticsStatus {
        SHOULD_PRESENT,
        SHOULD_BE_ABSENT
    }

    private inner class InspectionTestConfiguration(
            val testFilePath: String,
            val maxErrors: Int = -1,
            val maxWarnings: Int = -1,
            val showViolations: Boolean = true,
            val xmlReport: Boolean = false,
            val kotlinVersion: String = "1.1.3-2",
            val errors: List<String> = emptyList(),
            val warnings: List<String> = emptyList(),
            val infos: List<String> = emptyList(),
            val expectedOutcome: TaskOutcome = SUCCESS,
            val expectedDiagnosticsStatus: DiagnosticsStatus = SHOULD_PRESENT,
            vararg val expectedDiagnostics: String
    ) {
        fun doTest() {
            val buildFile = testProjectDir.newFile("build.gradle")
            testProjectDir.newFolder("config", "inspections")
            val inspectionsFile = testProjectDir.newFile("config/inspections/inspections.xml")
            testProjectDir.newFolder("src", "main", "kotlin")
            testProjectDir.newFolder("src", "main", "java")
            testProjectDir.newFolder("build")

            val isKotlinFile = testFilePath.endsWith("kt")
            val buildFileContent = generateBuildFile(
                    isKotlinFile,
                    maxErrors,
                    maxWarnings,
                    showViolations,
                    xmlReport,
                    kotlinVersion
            )
            writeFile(buildFile, buildFileContent)
            val inspectionsFileContent = generateInspectionFile(errors, warnings, infos)
            writeFile(inspectionsFile, inspectionsFileContent)

            fun buildSourceFileName(): String {
                val sb = StringBuilder()
                sb.append("src/main/")
                if (isKotlinFile) {
                    sb.append("kotlin")
                }
                else {
                    sb.append("java")
                }
                sb.append("/")
                sb.append(testFilePath.replace("\\", "/").substringAfterLast('/'))
                return sb.toString()
            }
            val testFile = File(testFilePath)
            val sourceFile = testProjectDir.newFile(buildSourceFileName())
            testFile.copyTo(sourceFile, overwrite = true)
            assertInspectionBuild(
                    expectedOutcome,
                    expectedDiagnosticsStatus,
                    *expectedDiagnostics
            )
        }
    }

    @Test
    fun testHelloWorldTask() {
        val buildFileContent = "task helloWorld {" +
                               "    doLast {" +
                               "        println 'Hello world!'" +
                               "    }" +
                               "}"
        val buildFile = testProjectDir.newFile("build.gradle")
        writeFile(buildFile, buildFileContent)

        val result = GradleRunner.create()
                .withProjectDir(testProjectDir.root)
                .withArguments("helloWorld")
                .build()

        assertTrue(result.output.contains("Hello world!"))
        assertEquals(result.task(":helloWorld").outcome, SUCCESS)
    }

    private fun doTest(testFilePath: String) {
        val testFile = File(testFilePath)
        val lines = testFile.readLines()
        fun getDiagnosticList(diagnosticKind: String) =
                lines.filter { it.startsWith("// $diagnosticKind:") }.map { it.split(":")[1].trim() }

        val errors = getDiagnosticList("error")
        val warnings = getDiagnosticList("warning")
        val infos = getDiagnosticList("info")

        val testFileName = testFilePath.replace("\\", "/").substringAfterLast('/')
        val expectedDiagnostics = lines.filter { it.startsWith("// :") }.map { testFileName + it.drop(3) }

        fun getParameterValue(parameterName: String, defaultValue: String): String {
            val line = lines.singleOrNull { it.startsWith("// $parameterName =") } ?: return defaultValue
            return line.split("=")[1].trim()
        }

        val maxErrors = getParameterValue("maxErrors", "-1").toInt()
        val maxWarnings = getParameterValue("maxWarnings", "-1").toInt()
        val showViolations = getParameterValue("showViolations", "true").toBoolean()
        val xmlReport = getParameterValue("xmlReport", "false").toBoolean()
        val kotlinVersion = getParameterValue("kotlinVersion", "1.1.3-2")

        val expectedDiagnosticsStatus = if (lines.contains("// SHOULD_BE_ABSENT")) SHOULD_BE_ABSENT else SHOULD_PRESENT
        val expectedOutcome = if (lines.contains("// FAIL")) FAILED else SUCCESS

        InspectionTestConfiguration(
                testFilePath,
                maxErrors,
                maxWarnings,
                showViolations,
                xmlReport,
                kotlinVersion,
                errors,
                warnings,
                infos,
                expectedOutcome,
                expectedDiagnosticsStatus,
                *expectedDiagnostics.toTypedArray()
        ).doTest()
    }

    @Test
    fun testInspectionConfigurationJava() {
        doTest("testData/inspection/configurationJava/Main.java")
    }

    @Test
    fun testInspectionConfigurationKotlin() {
        doTest("testData/inspection/configurationKotlin/main.kt")
    }

    @Test
    fun testMaxErrors() {
        doTest("testData/inspection/maxErrors/foo.kt")
    }

    @Test
    fun testRedundantModality() {
        doTest("testData/inspection/redundantModality/My.kt")
    }

    @Test
    fun testConvertToStringTemplate() {
        doTest("testData/inspection/convertToStringTemplate/foo.kt")
    }

    @Test
    fun testDoNotShowViolations() {
        doTest("testData/inspection/doNotShowViolations/My.kt")
    }

    @Test
    fun testXMLOutput() {
        doTest("testData/inspection/xmlOutput/My.kt")

        val file = File(testProjectDir.root, "build/report.xml")
        val lines = file.readLines()
        val allLines = lines.joinToString(separator = " ")
        assertTrue("warning class=\"org.jetbrains.kotlin.idea.inspections.DataClassPrivateConstructorInspection\"" in allLines)
        assertTrue("My.kt:4:15: Private data class constructor is exposed via the generated 'copy' method" in allLines)
    }
}